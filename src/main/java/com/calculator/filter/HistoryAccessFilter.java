package com.calculator.filter;

import java.io.IOException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebFilter(urlPatterns = {"/history*", "/calculate*", "/evaluate*"})
public class HistoryAccessFilter implements Filter {

    private static final String REQUIRED_HEADER       = "X-Calculator-Client";
    private static final String REQUIRED_HEADER_VALUE = "true";

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {}

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  req  = (HttpServletRequest)  request;
        HttpServletResponse resp = (HttpServletResponse) response;

        // Never allow direct GET on compute endpoints.
        if ("GET".equalsIgnoreCase(req.getMethod()) && isComputeEndpoint(req)) {
            writeForbidden(resp);
            return;
        }

        // Always allow preflight OPTIONS through (CORS handshake)
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String headerVal = req.getHeader(REQUIRED_HEADER);
        boolean isForceResetPing = "POST".equalsIgnoreCase(req.getMethod())
            && "true".equalsIgnoreCase(req.getParameter("forceReset"))
            && "ping".equalsIgnoreCase(req.getParameter("action"));
        boolean isSameOriginRequest = isSameOrigin(req);

        if (REQUIRED_HEADER_VALUE.equalsIgnoreCase(headerVal) || isForceResetPing || isSameOriginRequest) {
            // Legitimate app request — pass through
            chain.doFilter(request, response);
        } else {
            // Direct browser tab access / curl without header — block it
            writeForbidden(resp);
        }
    }

    private boolean isComputeEndpoint(HttpServletRequest req) {
        String servletPath = req.getServletPath();
        return "/calculate".equals(servletPath) || "/evaluate".equals(servletPath);
    }

    private void writeForbidden(HttpServletResponse resp) throws IOException {
        resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
        resp.setContentType("text/html;charset=UTF-8");
        resp.getWriter().print(
            "<!DOCTYPE html><html><head><title>403 Forbidden</title>" +
            "<link rel=\"icon\" href=\"data:,\" />" +
            "</head>" +
            "<body><h2>403 Forbidden</h2>" +
            "<p>This endpoint is not accessible directly.</p>" +
            "</body></html>"
        );
    }

    private boolean isSameOrigin(HttpServletRequest req) {
        String origin = req.getHeader("Origin");
        String referer = req.getHeader("Referer");

        String expectedOrigin = req.getScheme() + "://" + req.getServerName()
            + ((req.getServerPort() == 80 || req.getServerPort() == 443) ? "" : ":" + req.getServerPort());

        if (origin != null && !origin.isBlank()) {
            return expectedOrigin.equalsIgnoreCase(origin.trim());
        }

        if (referer != null && !referer.isBlank()) {
            return referer.toLowerCase().startsWith(expectedOrigin.toLowerCase());
        }

        return false;
    }

    @Override
    public void destroy() {}
}