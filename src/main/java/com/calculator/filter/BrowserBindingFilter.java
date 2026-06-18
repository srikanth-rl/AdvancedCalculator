package com.calculator.filter;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;

import com.calculator.servlet.CsrfServlet;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Session hijack prevention — four layers in order:
 *
 *  1. Sec-Fetch-Site: same-origin    — blocks Postman / curl / automated scripts.
 *  2. X-Browser-Secret               — per-session secret in client localStorage.
 *  3. ECDSA proof-of-origin signature — every request is signed with a
 *     non-extractable P-256 private key stored in browser IndexedDB.  The raw
 *     key bytes are NEVER accessible — not from JavaScript, not from DevTools,
 *     not by copying the IndexedDB file to another browser.  Copying
 *     JSESSIONID + _bsec to another browser cannot forge a valid signature.
 *  4. User-Agent / Accept-Language fingerprint.
 */
public class BrowserBindingFilter implements Filter {

    private ServletContext servletCtx;

    public static final String ATTR_UA       = "bb.ua";
    public static final String ATTR_LANG     = "bb.lang";
    public static final String ATTR_PUBKEY   = "bb.pubkey";
    public static final String HEADER_SECRET = "X-Browser-Secret";
    public static final String HEADER_SIG_TS = "X-Sig-Ts";
    public static final String HEADER_SIG    = "X-Sig";
    private static final long MAX_SKEW_MS = 30_000;

    @Override public void init(FilterConfig fc) throws ServletException { servletCtx = fc.getServletContext(); }
    @Override public void destroy() {}

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  req  = (HttpServletRequest)  request;
        HttpServletResponse resp = (HttpServletResponse) response;
        String method = req.getMethod();

        // ───  Always pass CORS preflight and forceReset pings ───
        if ("OPTIONS".equalsIgnoreCase(method)) {
            chain.doFilter(request, response); return;
        }
        if ("POST".equalsIgnoreCase(method)
                && "true".equalsIgnoreCase(req.getParameter("forceReset"))
                && "ping".equalsIgnoreCase(req.getParameter("action"))) {
            chain.doFilter(request, response); return;
        }

        // ─── Sec-Fetch-Site: same-origin ───
        String secFetchSite = req.getHeader("Sec-Fetch-Site");
        if (!"same-origin".equalsIgnoreCase(secFetchSite)) {
            unauthorized(resp, "You are not authorized to make this request.");
            return;
        }

        HttpSession session = req.getSession(false);
        if (session == null) {
            // No session yet — /csrf will create one and bind all attributes.
            chain.doFilter(request, response); return;
        }

        // ─── X-Browser-Secret ───
        String boundSecret   = (String) session.getAttribute(CsrfServlet.SESSION_BROWSER_SECRET);
        String requestSecret = nvl(req.getHeader(HEADER_SECRET));
        if (boundSecret != null && !boundSecret.equals(requestSecret)) {
            invalidateAndReject(session, resp, "SESSION_INVALID"); return;
        }

        // ─── ECDSA proof-of-origin signature ───
        String pubKeyJwk = (String) session.getAttribute(ATTR_PUBKEY);
        if (pubKeyJwk != null) {
            String sigTsStr = req.getHeader(HEADER_SIG_TS);
            String sigB64   = req.getHeader(HEADER_SIG);
            if (sigTsStr == null || sigB64 == null) {
                // Public key is bound but no signature sent — different browser.
                invalidateAndReject(session, resp, "SESSION_INVALID"); return;
            }
            long ts;
            try { ts = Long.parseLong(sigTsStr); }
            catch (NumberFormatException e) {
                invalidateAndReject(session, resp, "SESSION_INVALID"); return;
            }
            if (Math.abs(System.currentTimeMillis() - ts) > MAX_SKEW_MS) {
                // Timestamp too old — possible replay; don’t invalidate session.
                unauthorized(resp, "SESSION_REPLAY"); return;
            }
            try {
                byte[] msgBytes = sigTsStr.getBytes(StandardCharsets.UTF_8);
                byte[] sigBytes = Base64.getDecoder().decode(sigB64);
                if (!verifyEcdsaSignature(pubKeyJwk, msgBytes, sigBytes)) {
                    invalidateAndReject(session, resp, "SESSION_INVALID"); return;
                }
            } catch (Exception e) {
                invalidateAndReject(session, resp, "SESSION_INVALID"); return;
            }
        }

        // ─── UA / Accept-Language fingerprint ────
        String currentUa   = nvl(req.getHeader("User-Agent"));
        String currentLang = nvl(req.getHeader("Accept-Language"));
        String boundUa     = (String) session.getAttribute(ATTR_UA);
        String boundLang   = (String) session.getAttribute(ATTR_LANG);
        if (boundUa == null) {
            session.setAttribute(ATTR_UA,   currentUa);
            session.setAttribute(ATTR_LANG, currentLang);
            chain.doFilter(request, response); return;
        }
        if (!boundUa.equals(currentUa) || !boundLang.equals(currentLang)) {
            invalidateAndReject(session, resp, "SESSION_HIJACKED"); return;
        }

        chain.doFilter(request, response);
    }

    // ─── ECDSA P-256 helpers ───
    private boolean verifyEcdsaSignature(String pubKeyJwk, byte[] message, byte[] sigP1363)
            throws Exception {
        PublicKey pubKey = parseEcP256Jwk(pubKeyJwk);
        byte[] derSig    = p1363ToDer(sigP1363);
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(pubKey);
        verifier.update(message);
        return verifier.verify(derSig);
    }

    private static PublicKey parseEcP256Jwk(String jwk) throws Exception {
        byte[] xb = Base64.getUrlDecoder().decode(extractJwkField(jwk, "x"));
        byte[] yb = Base64.getUrlDecoder().decode(extractJwkField(jwk, "y"));
        ECPoint point = new ECPoint(new BigInteger(1, xb), new BigInteger(1, yb));
        AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec ecSpec = params.getParameterSpec(ECParameterSpec.class);
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, ecSpec));
    }

    private static String extractJwkField(String json, String field) {
        String key = '"' + field + '"';
        int i = json.indexOf(key);
        if (i < 0) throw new IllegalArgumentException("JWK missing field: " + field);
        i = json.indexOf('"', json.indexOf(':', i) + 1) + 1;
        return json.substring(i, json.indexOf('"', i));
    }

    private static byte[] p1363ToDer(byte[] rs) {
        int half = rs.length / 2;
        byte[] r = asn1Int(Arrays.copyOfRange(rs, 0,    half));
        byte[] s = asn1Int(Arrays.copyOfRange(rs, half, rs.length));
        int seqLen = r.length + s.length;         
        byte[] der = new byte[2 + seqLen];
        der[0] = 0x30;
        der[1] = (byte) seqLen;
        System.arraycopy(r, 0, der, 2           , r.length);
        System.arraycopy(s, 0, der, 2 + r.length, s.length);
        return der;
    }

    private static byte[] asn1Int(byte[] val) {
        int start = 0;
        while (start < val.length - 1 && val[start] == 0) start++;
        val = Arrays.copyOfRange(val, start, val.length);
        if ((val[0] & 0x80) != 0) {          
            byte[] padded = new byte[val.length + 1];
            System.arraycopy(val, 0, padded, 1, val.length);
            val = padded;
        }
        byte[] tlv = new byte[2 + val.length];
        tlv[0] = 0x02;
        tlv[1] = (byte) val.length;
        System.arraycopy(val, 0, tlv, 2, val.length);
        return tlv;
    }

    // ─── Helpers ───

    private static String nvl(String v) { return v != null ? v : ""; }

    private void invalidateAndReject(HttpSession session, HttpServletResponse resp, String msg)
            throws IOException {
        try { session.invalidate(); } catch (Exception ignored) {}
        unauthorized(resp, msg);
    }

    private void unauthorized(HttpServletResponse resp, String msg) throws IOException {
        resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        resp.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        try (InputStream img = servletCtx.getResourceAsStream("/static/unauthorized.jpg")) {
            if (img != null) {
                resp.setContentType("image/jpeg");
                img.transferTo(resp.getOutputStream());
            } else {
                resp.setContentType("text/plain;charset=UTF-8");
                resp.getWriter().print("401 Unauthorized");
            }
        }
    }
}
