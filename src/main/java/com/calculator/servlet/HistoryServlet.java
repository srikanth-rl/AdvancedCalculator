package com.calculator.servlet;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

@WebServlet("/history")
public class HistoryServlet extends HttpServlet {

    private static final String SESSION_HISTORY_KEY = "calc_history";

    // ─── GET: retrieve history ───

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        setCors(response);

        HttpSession session = request.getSession(true);
        List<String[]> history = getHistory(session);

        PrintWriter out = response.getWriter();
        out.print(toJson(history));
    }

    // ─── POST: add an entry ───

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        setCors(response);

        String action = request.getParameter("action");

        if ("clear".equals(action)) {
            HttpSession session = request.getSession(true);
            session.removeAttribute(SESSION_HISTORY_KEY);
            response.getWriter().print("{\"success\":true,\"history\":[]}");
            return;
        }

        String expression = request.getParameter("expression");
        String result     = request.getParameter("result");

        if (expression == null || result == null) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().print("{\"success\":false,\"error\":\"Missing expression or result\"}");
            return;
        }

        HttpSession session = request.getSession(true);
        List<String[]> history = getHistory(session);
        history.add(new String[]{ expression, result });
        session.setAttribute(SESSION_HISTORY_KEY, history);

        response.getWriter().print("{\"success\":true}");
    }

    @Override
    protected void doOptions(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        setCors(response);
        response.setStatus(HttpServletResponse.SC_OK);
    }

    // ─── Helpers ───

    @SuppressWarnings("unchecked")
    private List<String[]> getHistory(HttpSession session) {
        Object attr = session.getAttribute(SESSION_HISTORY_KEY);
        if (attr instanceof List) {
            return (List<String[]>) attr;
        }
        List<String[]> fresh = new ArrayList<>();
        session.setAttribute(SESSION_HISTORY_KEY, fresh);
        return fresh;
    }

    private String toJson(List<String[]> history) {
        StringBuilder sb = new StringBuilder("{\"success\":true,\"history\":[");
        for (int i = 0; i < history.size(); i++) {
            String[] entry = history.get(i);
            sb.append("{\"expression\":\"").append(escapeJson(entry[0]))
              .append("\",\"result\":\"").append(escapeJson(entry[1])).append("\"}");
            if (i < history.size() - 1) sb.append(",");
        }
        sb.append("]}");
        return sb.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private void setCors(HttpServletResponse response) {
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type");
    }
}
