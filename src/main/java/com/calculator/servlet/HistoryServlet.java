package com.calculator.servlet;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;

@WebServlet("/history")
public class HistoryServlet extends HttpServlet implements HttpSessionListener {

    private static final ConcurrentHashMap<String, List<String[]>> GLOBAL_HISTORY = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicBoolean>  SESSION_LOCKS  = new ConcurrentHashMap<>();

    private static final int MAX_HISTORY_RESULT = 5_000;
    private static final int MAX_ENTRIES        = 100;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        setCors(response);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String sessionId = request.getSession().getId();
        List<String[]> history = GLOBAL_HISTORY.computeIfAbsent(
                sessionId, k -> Collections.synchronizedList(new ArrayList<>()));

        response.setStatus(HttpServletResponse.SC_OK);
        response.getWriter().print(toJson(history));
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        setCors(response);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String sessionId = request.getSession().getId();
        String action    = request.getParameter("action");

        AtomicBoolean lock = SESSION_LOCKS.computeIfAbsent(sessionId, k -> new AtomicBoolean(false));
        if (!lock.compareAndSet(false, true)) {
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            response.getWriter().print("{\"success\":false,\"error\":\"Server busy\"}");
            return;
        }

        try {
            if ("clear".equals(action)) {
                GLOBAL_HISTORY.remove(sessionId);
                response.setStatus(HttpServletResponse.SC_OK);
                response.getWriter().print("{\"success\":true,\"message\":\"History cleared\"}");
                return;
            }

            StringBuilder sb = new StringBuilder();
            String line;
            try (BufferedReader r = request.getReader()) {
                while ((line = r.readLine()) != null) sb.append(line);
            }

            String jsonBody = sb.toString();
            if (jsonBody == null || jsonBody.trim().isEmpty()) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.getWriter().print("{\"success\":false,\"error\":\"Empty request body\"}");
                return;
            }

            String expr = unescapeJson(extractJsonValue(jsonBody, "expression"));
            String res  = unescapeJson(extractJsonValue(jsonBody, "result"));
            String dLen = unescapeJson(extractJsonValue(jsonBody, "digitsLength"));

            if (expr == null || res == null || expr.trim().isEmpty() || res.trim().isEmpty() || expr.equals(res)) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.getWriter().print("{\"success\":false,\"error\":\"Invalid history entry\"}");
                return;
            }

            if (res.length() > MAX_HISTORY_RESULT) {    
                res = res.substring(0, 1_000) + "\u2026 [remaining digits hidden \u2014 use desktop to copy full result] \u2026" + res.substring(res.length() - 1_000);
            }

            List<String[]> history = GLOBAL_HISTORY.computeIfAbsent(
                    sessionId, k -> Collections.synchronizedList(new ArrayList<>()));

            if (history.size() >= MAX_ENTRIES) {
                history.remove(0); 
            }
            history.add(new String[]{
                expr,
                res,
                dLen != null ? dLen : ""
            });

            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().print("{\"success\":true,\"message\":\"History saved\"}");

        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().print("{\"success\":false,\"error\":\"Server error: " + escapeJson(e.getMessage()) + "\"}");
        } finally {
            lock.set(false);
        }
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent se) {
        String id = se.getSession().getId();
        GLOBAL_HISTORY.remove(id);
        SESSION_LOCKS.remove(id);
    }

    private String toJson(List<String[]> h) {
        StringBuilder sb = new StringBuilder("{\"success\":true,\"history\":[");
        synchronized (h) {
            for (int i = 0; i < h.size(); i++) {
                String[] e = h.get(i);
                sb.append(String.format(
                    "{\"expression\":\"%s\",\"result\":\"%s\",\"digitsLength\":\"%s\"}",
                    escapeJson(e[0]),
                    escapeJson(e[1]),
                    escapeJson(e[2])
                ));
                if (i < h.size() - 1) sb.append(",");
            }
        }
        return sb.append("]}").toString();
    }

    private void setCors(HttpServletResponse r) {
        r.setHeader("Access-Control-Allow-Origin",  "*");
        r.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        r.setHeader("Access-Control-Allow-Headers", "Content-Type");
    }

    private String extractJsonValue(String j, String k) {
        String p = "\"" + k + "\":\"";
        int s = j.indexOf(p);
        if (s == -1) return null;
        s += p.length();
        int e = s;
        while (e < j.length() && (j.charAt(e) != '"' || (e > 0 && j.charAt(e - 1) == '\\'))) e++;
        return j.substring(s, e);
    }

    private String unescapeJson(String s) {
        if (s == null) return null;
        return s.replace("\\\\", "\u0000")
                .replace("\\\"", "\"")
                .replace("\\n",  "\n")
                .replace("\u0000", "\\");
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}