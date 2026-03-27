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
    private static final ConcurrentHashMap<String, AtomicBoolean> SESSION_LOCKS = new ConcurrentHashMap<>();

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        setCors(response);
        response.setContentType("application/json");
        String sessionId = request.getSession().getId();
        List<String[]> history = GLOBAL_HISTORY.computeIfAbsent(sessionId, k -> Collections.synchronizedList(new ArrayList<>()));
        response.getWriter().print(toJson(history));
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        setCors(response);
        String sessionId = request.getSession().getId();
        AtomicBoolean lock = SESSION_LOCKS.computeIfAbsent(sessionId, k -> new AtomicBoolean(false));
        if (!lock.compareAndSet(false, true)) {
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            return;
        }
        try {
            if ("clear".equals(request.getParameter("action"))) {
                GLOBAL_HISTORY.remove(sessionId);
                response.getWriter().print("{\"success\":true}");
                return;
            }
            StringBuilder sb = new StringBuilder();
            String line;
            try (BufferedReader r = request.getReader()) {
                while ((line = r.readLine()) != null) {
                    sb.append(line);
                }
            }
            String jsonBody = sb.toString();
            String expr = unescapeJson(extractJsonValue(jsonBody, "expression"));
            String res = unescapeJson(extractJsonValue(jsonBody, "result"));
            String dLen = unescapeJson(extractJsonValue(jsonBody, "digitsLength"));
            if (expr != null && res != null && !expr.equals(res)) {
                List<String[]> history = GLOBAL_HISTORY.computeIfAbsent(sessionId, k -> Collections.synchronizedList(new ArrayList<>()));
                history.add(new String[]{expr, res, dLen});
                response.getWriter().print("{\"success\":true}");
            }
        } finally {
            lock.set(false);
        }
    }

    private String toJson(List<String[]> h) {
        StringBuilder sb = new StringBuilder("{\"success\":true,\"history\":[");
        synchronized (h) {
            for (int i = 0; i < h.size(); i++) {
                String[] e = h.get(i);
                sb.append(String.format(
                    "{\"expression\":\"%s\",\"result\":\"%s\",\"digitsLength\":\"%s\"}",
                    escapeJson(e[0]), escapeJson(e[1]), escapeJson(e[2])
                ));
                if (i < h.size() - 1) {
                    sb.append(",");
                }
            }
        }
        return sb.append("]}").toString();
    }

    private void setCors(HttpServletResponse r) {
        r.setHeader("Access-Control-Allow-Origin", "*");
        r.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        r.setHeader("Access-Control-Allow-Headers", "Content-Type");
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent se) {
        String id = se.getSession().getId();
        GLOBAL_HISTORY.remove(id);
        SESSION_LOCKS.remove(id);
    }

    private String extractJsonValue(String j, String k) {
        String p = "\"" + k + "\":\"";
        int s = j.indexOf(p);
        if (s == -1) {
            return null;
        }
        s += p.length();
        int e = s;
        while (e < j.length() && (j.charAt(e) != '\"' || j.charAt(e - 1) == '\\')) {
            e++;
        }
        return j.substring(s, e);
    }

    private String unescapeJson(String s) {
        return s == null ? null : s.replace("\\\\", "\\").replace("\\\"", "\"").replace("\\n", "\n");
    }

    private String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}