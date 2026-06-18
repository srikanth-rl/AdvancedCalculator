package com.calculator.filter;

import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.Base64;

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

public class CsrfProtectionFilter implements Filter {

    private ServletContext servletCtx;

    public static final String SESSION_KEY = "csrfToken";
    public static final String HEADER_NAME = "X-CSRF-Token";
    private static final SecureRandom RANDOM = new SecureRandom();

    public static String generateToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    @Override public void init(FilterConfig fc) throws ServletException { servletCtx = fc.getServletContext(); }
    @Override public void destroy() {}

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  req  = (HttpServletRequest)  request;
        HttpServletResponse resp = (HttpServletResponse) response;
        String method = req.getMethod();

        if ("GET".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method)) {
            chain.doFilter(request, response); return;
        }

        // sendBeacon reset-pings cannot carry custom headers
        if ("POST".equalsIgnoreCase(method)
                && "true".equalsIgnoreCase(req.getParameter("forceReset"))
                && "ping".equalsIgnoreCase(req.getParameter("action"))) {
            chain.doFilter(request, response); return;
        }

        HttpSession session    = req.getSession(false);
        String      sessionTok = (session != null) ? (String) session.getAttribute(SESSION_KEY) : null;
        String      requestTok = req.getHeader(HEADER_NAME);

        if (sessionTok == null || requestTok == null || !sessionTok.equals(requestTok)) {
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            resp.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
            try (InputStream img = servletCtx.getResourceAsStream("/static/unauthorized.jpg")) {
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

        chain.doFilter(request, response);
    }
}
