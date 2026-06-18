package com.calculator.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.Base64;

import com.calculator.filter.BrowserBindingFilter;
import com.calculator.filter.CsrfProtectionFilter;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

@WebServlet("/csrf")
public class CsrfServlet extends HttpServlet {

    private static final String REQUIRED_HEADER        = "X-Calculator-Client";
    private static final String REQUIRED_HEADER_VALUE  = "true";
    public  static final String SESSION_BROWSER_SECRET = "browser.secret";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {

        if (!REQUIRED_HEADER_VALUE.equalsIgnoreCase(req.getHeader(REQUIRED_HEADER))) {
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            resp.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
            try (InputStream img = getServletContext().getResourceAsStream("/static/unauthorized.jpg")) {
                if (img != null) {
                    resp.setContentType("image/jpeg");
                    img.transferTo(resp.getOutputStream());
                } else {
                    resp.setContentType("text/plain;charset=UTF-8");
                    resp.getWriter().print("403 Forbidden");
                }
            }
            return;
        }

        // ── Stolen-session detection ───
        HttpSession existing = req.getSession(false);
        if (existing != null) {
            String boundSecret    = (String) existing.getAttribute(SESSION_BROWSER_SECRET);
            String incomingSecret = req.getHeader(BrowserBindingFilter.HEADER_SECRET);

            boolean secretPresent = (boundSecret != null && !boundSecret.isEmpty());
            boolean secretMatches = secretPresent
                    && incomingSecret != null
                    && boundSecret.equals(incomingSecret);

            if (secretPresent && !secretMatches) {
                try { existing.invalidate(); } catch (Exception ignored) {}

                resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                resp.setContentType("application/json;charset=UTF-8");
                resp.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
                resp.setHeader("Pragma", "no-cache");
                resp.getWriter().print(
                    "{\"success\":false,\"error\":\"SESSION_COMPROMISED\"," +
                    "\"message\":\"Your session was invalidated for security reasons. A new session will be created.\"}"
                );
                return;
            }
        }

        // ── Get or create session ───
        HttpSession session = req.getSession(true);

        // Bind browser fingerprint on first visit
        if (session.getAttribute(BrowserBindingFilter.ATTR_UA) == null) {
            String ua   = req.getHeader("User-Agent");
            String lang = req.getHeader("Accept-Language");
            session.setAttribute(BrowserBindingFilter.ATTR_UA,   ua   != null ? ua   : "");
            session.setAttribute(BrowserBindingFilter.ATTR_LANG, lang != null ? lang : "");
        }

        // Issue (or reuse) CSRF token
        String token = (String) session.getAttribute(CsrfProtectionFilter.SESSION_KEY);
        if (token == null) {
            token = CsrfProtectionFilter.generateToken();
            session.setAttribute(CsrfProtectionFilter.SESSION_KEY, token);
        }

        // Issue (or reuse) browser secret.
        // The JS side stores this in localStorage so ALL tabs in the same browser
        // share it. This means:
        //   • Opening a new tab reuses the existing secret → same session, no interruption.
        //   • A different browser never has the secret → stolen JSESSIONID is detected.
        String browserSecret = (String) session.getAttribute(SESSION_BROWSER_SECRET);
        if (browserSecret == null) {
            byte[] b = new byte[32];
            RANDOM.nextBytes(b);
            browserSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
            session.setAttribute(SESSION_BROWSER_SECRET, browserSecret);
        }

        // Bind ECDSA public key on first session creation.
        String publicKey = req.getHeader("X-Public-Key");
        if (publicKey != null
                && publicKey.length() < 1024
                && publicKey.startsWith("{")
                && publicKey.contains("\"crv\":\"P-256\"")
                && session.getAttribute(BrowserBindingFilter.ATTR_PUBKEY) == null) {
            session.setAttribute(BrowserBindingFilter.ATTR_PUBKEY, publicKey);
        }

        resp.setContentType("application/json;charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        resp.setHeader("Pragma", "no-cache");
        resp.getWriter().print(
            "{\"csrfToken\":\"" + token + "\",\"browserSecret\":\"" + browserSecret + "\"}"
        );
    }
}