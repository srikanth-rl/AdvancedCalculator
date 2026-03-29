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
import com.calculator.core.MathEngine.CancellationToken;
import com.calculator.core.MathEngine.CancellationException;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

@WebServlet("/evaluate")
public class ExpressionServlet extends HttpServlet {

    private static final char POW_SENTINEL = '^';

    private static final ExecutorService COMPUTE_POOL =
        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());

    private static final ConcurrentHashMap<String, AtomicBoolean> SESSION_LOCKS =
        new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Future<?>> ACTIVE_EVALUATIONS =
        new ConcurrentHashMap<>();

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        PrintWriter out = response.getWriter();

        HttpSession session = request.getSession(true);
        String sessionId    = session.getId();
        String action       = request.getParameter("action");

        if ("true".equals(request.getParameter("forceReset"))) {
            MathEngine.cancelSession(sessionId);

            Future<?> active = ACTIVE_EVALUATIONS.remove(sessionId);
            if (active != null) active.cancel(true);

            AtomicBoolean lock = SESSION_LOCKS.get(sessionId);
            if (lock != null) lock.set(false);

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
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                out.print("{\"success\":false,\"error\":\"Empty expression\"}");
                return;
            }

            final String normalized = expr.trim()
                .replaceAll("\\s*\\*\\*\\s*", String.valueOf(POW_SENTINEL))
                .replace("%", "/100");

            final CancellationToken tok = new CancellationToken();
            MathEngine.setSessionToken(sessionId, tok);

            Future<String> future = COMPUTE_POOL.submit(() -> {
                BigDecimal result = evaluate(normalized, tok);
                tok.checkCancelled();
                String formatted = MathEngine.formatDecimal(result);
                return formatted + "|" + MathEngine.digitLength(formatted);
            });

            ACTIVE_EVALUATIONS.put(sessionId, future);

            try {
                String raw   = future.get(2, TimeUnit.MINUTES);
                String[] p   = raw.split("\\|", 2);
                out.print("{\"success\":true,\"result\":\"" + p[0] + "\",\"digitLength\":" + p[1] + "}");

            } catch (java.util.concurrent.CancellationException e) {
                tok.cancel();
                out.print("{\"success\":false,\"error\":\"Session conflict — this calculation was stopped because the same page was opened in another tab. Please use only one tab, or refresh and try again.\"}");

            } catch (TimeoutException e) {
                future.cancel(true);
                tok.cancel();
                out.print("{\"success\":false,\"error\":\"Calculation timed out (max 2-minutes limit). Your input is too large — please reduce it and try again.\"}");

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                tok.cancel();

            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof CancellationException) {
                    out.print("{\"success\":false,\"error\":\"Calculation was stopped.\"}");
                } else {
                    String msg = (cause != null && cause.getMessage() != null)
                        ? cause.getMessage() : "Invalid expression.";
                    out.print("{\"success\":false,\"error\":\"" + escapeJson(msg) + "\"}");
                }

            } finally {
                ACTIVE_EVALUATIONS.remove(sessionId);
                MathEngine.clearSessionToken(sessionId);
            }

        } finally {
            lock.set(false);
        }
    }

    private BigDecimal evaluate(String expr, CancellationToken tok) {
        Deque<BigDecimal> values = new ArrayDeque<>();
        Deque<Character>  ops    = new ArrayDeque<>();
        int i = 0;

        while (i < expr.length()) {
            if (tok != null) tok.checkCancelled();
            char c = expr.charAt(i);

            if (c == ' ') { i++; continue; }

            if (Character.isDigit(c) || c == '.') {
                StringBuilder sb = new StringBuilder();
                while (i < expr.length() && (Character.isDigit(expr.charAt(i)) || expr.charAt(i) == '.'))
                    sb.append(expr.charAt(i++));
                // Handle scientific notation: e.g. 1e9, 1.5e+10, 2E-3
                if (i < expr.length() && (expr.charAt(i) == 'e' || expr.charAt(i) == 'E')) {
                    sb.append(expr.charAt(i++));
                    if (i < expr.length() && (expr.charAt(i) == '+' || expr.charAt(i) == '-'))
                        sb.append(expr.charAt(i++));
                    while (i < expr.length() && Character.isDigit(expr.charAt(i)))
                        sb.append(expr.charAt(i++));
                }
                values.push(new BigDecimal(sb.toString()));
                continue;
            }

            if (c == '(') { ops.push(c); i++; continue; }

            if (c == ')') {
                while (!ops.isEmpty() && ops.peek() != '(') applyTop(ops, values, tok);
                if (!ops.isEmpty()) ops.pop();
                i++; continue;
            }

            // AFTER:
            if (isOp(c)) {
                while (!ops.isEmpty() && ops.peek() != '(' && shouldApply(c, ops.peek()))
                    applyTop(ops, values, tok);
                ops.push(c); i++; continue;
            }
            throw new ArithmeticException("Invalid character '" + c + "' in expression — only digits, operators, parentheses, and \"e\" notation are allowed.");
        }

        while (!ops.isEmpty()) applyTop(ops, values, tok);

        if (values.isEmpty()) throw new ArithmeticException("Invalid expression");
        return values.pop();
    }

    private boolean shouldApply(char current, char top) {
        return (current == POW_SENTINEL) ? prec(top) > prec(current) : prec(top) >= prec(current);
    }

    private void applyTop(Deque<Character> ops, Deque<BigDecimal> values, CancellationToken tok) {
        if (values.size() < 2) throw new ArithmeticException("Invalid expression");
        char op = ops.pop();
        BigDecimal b = values.pop();
        BigDecimal a = values.pop();

        String resRaw = switch (op) {
            case '+'          -> MathEngine.add(a.toPlainString(),      b.toPlainString(), tok);
            case '-'          -> MathEngine.subtract(a.toPlainString(), b.toPlainString(), tok);
            case '*'          -> MathEngine.multiply(a.toPlainString(), b.toPlainString(), tok);
            case '/'          -> MathEngine.divide(a.toPlainString(),   b.toPlainString(), tok);
            case POW_SENTINEL -> MathEngine.power(a.toPlainString(),    b.toPlainString(), tok);
            default           -> "0|1";
        };
        values.push(new BigDecimal(resRaw.split("\\|")[0]));
    }

    private int prec(char op) {
        return (op == POW_SENTINEL) ? 3 : (op == '*' || op == '/') ? 2 : 1;
    }

    private boolean isOp(char c) {
        return c == '+' || c == '-' || c == '*' || c == '/' || c == POW_SENTINEL;
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}