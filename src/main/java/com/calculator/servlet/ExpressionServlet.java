package com.calculator.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.calculator.core.MathEngine;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

@WebServlet("/evaluate")
public class ExpressionServlet extends HttpServlet {

    private static final char POW_SENTINEL = '^';
    private static final ExecutorService COMPUTE_POOL = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
    private static final ConcurrentHashMap<String, AtomicBoolean> SESSION_LOCKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Future<?>> ACTIVE_EVALUATIONS = new ConcurrentHashMap<>();

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        PrintWriter out = response.getWriter();

        HttpSession session = request.getSession(true);
        String sessionId = session.getId();
        String action = request.getParameter("action");

        if ("true".equals(request.getParameter("forceReset"))) {
            AtomicBoolean lock = SESSION_LOCKS.get(sessionId);
            if (lock != null) {
                lock.set(false);
            }

            Future<?> active = ACTIVE_EVALUATIONS.get(sessionId);
            if (active != null) {
                active.cancel(true);
                ACTIVE_EVALUATIONS.remove(sessionId);
            }

            if ("ping".equals(action)) {
                out.print("{\"success\":true,\"message\":\"Unlocked\"}");
                return;
            }
        }

        AtomicBoolean lock = SESSION_LOCKS.computeIfAbsent(sessionId, k -> new AtomicBoolean(false));
        if (!lock.compareAndSet(false, true)) {
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            out.print("{\"success\":false,\"error\":\"Busy\"}");
            return;
        }

        try {
            String expr = request.getParameter("expression");
            if (expr == null || expr.isBlank()) {
                return;
            }

            final String normalized = expr.trim()
                    .replaceAll("\\s*\\*\\*\\s*", String.valueOf(POW_SENTINEL))
                    .replace("%", "/100");

            Future<String> future = COMPUTE_POOL.submit(() -> {
                BigDecimal result = evaluate(normalized);
                String formatted = MathEngine.formatDecimal(result);
                return formatted + "|" + MathEngine.digitLength(formatted);
            });

            ACTIVE_EVALUATIONS.put(sessionId, future);

            try {
                String raw = future.get(2, TimeUnit.MINUTES);
                String[] p = raw.split("\\|");
                out.print("{\"success\":true,\"result\":\"" + p[0] + "\",\"digitLength\":" + p[1] + "}");
            } catch (TimeoutException e) {
                future.cancel(true);
                out.print("{\"success\":false,\"error\":\"Timeout\"}");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
            } catch (ExecutionException e) {
                out.print("{\"success\":false,\"error\":\"Invalid Expression\"}");
            } finally {
                ACTIVE_EVALUATIONS.remove(sessionId);
            }
        } finally {
            lock.set(false);
        }
    }

    private BigDecimal evaluate(String expr) {
        Deque<BigDecimal> values = new ArrayDeque<>();
        Deque<Character> ops = new ArrayDeque<>();
        int i = 0;
        while (i < expr.length()) {
            char c = expr.charAt(i);
            if (c == ' ') {
                i++;
                continue;
            }
            if (Character.isDigit(c) || c == '.') {
                StringBuilder sb = new StringBuilder();
                while (i < expr.length() && (Character.isDigit(expr.charAt(i)) || expr.charAt(i) == '.')) {
                    sb.append(expr.charAt(i++));
                }
                values.push(new BigDecimal(sb.toString()));
                continue;
            }
            if (c == '(') {
                ops.push(c);
                i++;
                continue;
            }
            if (c == ')') {
                while (!ops.isEmpty() && ops.peek() != '(') {
                    applyTop(ops, values);
                }
                if (!ops.isEmpty()) {
                    ops.pop();
                }
                i++;
                continue;
            }
            if (isOp(c)) {
                while (!ops.isEmpty() && ops.peek() != '(' && (c == POW_SENTINEL ? prec(ops.peek()) > prec(c) : prec(ops.peek()) >= prec(c))) {
                    applyTop(ops, values);
                }
                ops.push(c);
                i++;
                continue;
            }
            i++;
        }
        while (!ops.isEmpty()) {
            applyTop(ops, values);
        }
        return values.pop();
    }

    private void applyTop(Deque<Character> ops, Deque<BigDecimal> values) {
        char op = ops.pop();
        BigDecimal b = values.pop();
        BigDecimal a = values.pop();
        String resRaw = switch (op) {
            case '+'         -> MathEngine.add(a.toPlainString(), b.toPlainString());
            case '-'         -> MathEngine.subtract(a.toPlainString(), b.toPlainString());
            case '*'         -> MathEngine.multiply(a.toPlainString(), b.toPlainString());
            case '/'         -> MathEngine.divide(a.toPlainString(), b.toPlainString());
            case POW_SENTINEL -> MathEngine.power(a.toPlainString(), b.toPlainString());
            default          -> "0|1";
        };
        values.push(new BigDecimal(resRaw.split("\\|")[0]));
    }

    private int prec(char op) {
        return (op == POW_SENTINEL) ? 3 : (op == '*' || op == '/') ? 2 : 1;
    }

    private boolean isOp(char c) {
        return c == '+' || c == '-' || c == '*' || c == '/' || c == POW_SENTINEL;
    }
}