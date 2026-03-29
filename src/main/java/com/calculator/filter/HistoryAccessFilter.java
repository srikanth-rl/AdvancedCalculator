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

@WebFilter("/history")
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

        // Always allow preflight OPTIONS through (CORS handshake)
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String headerVal = req.getHeader(REQUIRED_HEADER);

        if (REQUIRED_HEADER_VALUE.equalsIgnoreCase(headerVal)) {
            // Legitimate app request — pass through
            chain.doFilter(request, response);
        } else {
            // Direct browser tab access / curl without header — block it
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            resp.setContentType("text/html;charset=UTF-8");
            resp.getWriter().print(
                "<!DOCTYPE html><html><head><title>403 Forbidden</title></head>" +
                "<body><h2>403 Forbidden</h2>" +
                "<p>This endpoint is not accessible directly.</p>" +
                "</body></html>"
            );
        }
    }

    @Override
    public void destroy() {}
}