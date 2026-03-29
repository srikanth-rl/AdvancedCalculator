package com.calculator.servlet;

import java.io.IOException;
import java.io.PrintWriter;
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

@WebServlet("/calculate")
public class CalculatorServlet extends HttpServlet {

    private static final ExecutorService COMPUTE_POOL =
        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());

    private static final ConcurrentHashMap<String, AtomicBoolean> SESSION_LOCKS =
        new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Future<?>> ACTIVE_COMPUTATIONS =
        new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> ACTIVE_CACHE_KEYS =
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

            String pendingKey = ACTIVE_CACHE_KEYS.remove(sessionId);
            if (pendingKey != null) MathEngine.cacheEvict(pendingKey);

            Future<?> active = ACTIVE_COMPUTATIONS.remove(sessionId);
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
            final String n1  = request.getParameter("num1");
            final String n2  = request.getParameter("num2");
            final String act = action;

            final CancellationToken tok = new CancellationToken();
            MathEngine.setSessionToken(sessionId, tok);

            final String cacheKey = buildCacheKey(act, n1, n2);
            if (cacheKey != null) ACTIVE_CACHE_KEYS.put(sessionId, cacheKey);

            Future<String> future = COMPUTE_POOL.submit(() -> switch (act) {
                case "add"       -> MathEngine.add(n1, n2, tok);
                case "subtract"  -> MathEngine.subtract(n1, n2, tok);
                case "multiply"  -> MathEngine.multiply(n1, n2, tok);
                case "divide"    -> MathEngine.divide(n1, n2, tok);
                case "mod"       -> MathEngine.mod(n1, n2, tok);
                case "power"     -> MathEngine.power(n1, n2, tok);
                case "gcd"       -> MathEngine.gcd(n1, n2, tok);
                case "lcm"       -> MathEngine.lcm(n1, n2, tok);
                case "factorial" -> MathEngine.factorial(n1, tok);
                case "prime"     -> MathEngine.checkPrime(n1, tok);
                default          -> "0|1";
            });

            ACTIVE_COMPUTATIONS.put(sessionId, future);

            try {
                String raw  = future.get(2, TimeUnit.MINUTES);
                String res  = raw;
                int    dLen = 0;
                if (raw.contains("|")) {
                    String[] p = raw.split("\\|", 2);
                    res  = p[0];
                    dLen = Integer.parseInt(p[1]);
                }
                out.print("{\"success\":true,\"result\":\"" + res + "\",\"digitLength\":" + dLen + "}");

            } catch (java.util.concurrent.CancellationException e) {
                tok.cancel();
                if (cacheKey != null) MathEngine.cacheEvict(cacheKey);
                out.print("{\"success\":false,\"error\":\"Session conflict — this calculation was stopped because the same page was opened in another tab. Please use only one tab, or refresh and try again.\"}");

            } catch (TimeoutException e) {
                future.cancel(true);
                tok.cancel();
                if (cacheKey != null) MathEngine.cacheEvict(cacheKey);
                out.print("{\"success\":false,\"error\":\"Calculation timed out (2-minute limit). Your input is too large — please reduce it and try again.\"}");

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                tok.cancel();
                if (cacheKey != null) MathEngine.cacheEvict(cacheKey);

            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof CancellationException) {
                    if (cacheKey != null) MathEngine.cacheEvict(cacheKey);
                    out.print("{\"success\":false,\"error\":\"Calculation was stopped.\"}");
                } else {
                    String msg = (cause != null && cause.getMessage() != null)
                        ? cause.getMessage() : "Computation error.";
                    out.print("{\"success\":false,\"error\":\"" + escapeJson(msg) + "\"}");
                }

            } finally {
                ACTIVE_COMPUTATIONS.remove(sessionId);
                ACTIVE_CACHE_KEYS.remove(sessionId);
                MathEngine.clearSessionToken(sessionId);
            }

        } finally {
            lock.set(false);
        }
    }

    private static String buildCacheKey(String action, String n1, String n2) {
        if (action == null || n1 == null) return null;
        String a = n1.trim();
        String b = (n2 != null) ? n2.trim() : "";
        return switch (action) {
            case "add"       -> "add:" + a + "," + b;
            case "subtract"  -> "sub:" + a + "," + b;
            case "multiply"  -> "mul:" + a + "," + b;
            case "divide"    -> "div:" + a + "," + b;
            case "mod"       -> "mod:" + a + "," + b;
            case "power"     -> "pow:" + a + "," + b;
            case "gcd"       -> "gcd:" + a + "," + b;
            case "lcm"       -> "lcm:" + a + "," + b;
            case "factorial" -> "fact:" + a;
            case "prime"     -> "prime:" + a;
            default          -> null;
        };
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}