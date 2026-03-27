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

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

@WebServlet("/calculate")
public class CalculatorServlet extends HttpServlet {

    private static final ExecutorService COMPUTE_POOL = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
    private static final ConcurrentHashMap<String, AtomicBoolean> SESSION_LOCKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Future<?>> ACTIVE_COMPUTATIONS = new ConcurrentHashMap<>();

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

            Future<?> active = ACTIVE_COMPUTATIONS.get(sessionId);
            if (active != null) {
                active.cancel(true);
                ACTIVE_COMPUTATIONS.remove(sessionId);
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
            final String n1 = request.getParameter("num1");
            final String n2 = request.getParameter("num2");
            final String act = action;

            Future<String> future = COMPUTE_POOL.submit(() -> switch (act) {
                case "add"       -> MathEngine.add(n1, n2);
                case "subtract"  -> MathEngine.subtract(n1, n2);
                case "multiply"  -> MathEngine.multiply(n1, n2);
                case "divide"    -> MathEngine.divide(n1, n2);
                case "mod"       -> MathEngine.mod(n1, n2);
                case "power"     -> MathEngine.power(n1, n2);
                case "factorial" -> MathEngine.factorial(n1);
                case "prime"     -> MathEngine.checkPrime(n1);
                default          -> "0|1";
            });

            ACTIVE_COMPUTATIONS.put(sessionId, future);

            try {
                String raw = future.get(2, TimeUnit.MINUTES);
                String res = raw;
                int dLen = 0;
                if (raw.contains("|")) {
                    String[] p = raw.split("\\|");
                    res = p[0];
                    dLen = Integer.parseInt(p[1]);
                }
                out.print("{\"success\":true,\"result\":\"" + res + "\",\"digitLength\":" + dLen + "}");
            } catch (TimeoutException e) {
                future.cancel(true);
                out.print("{\"success\":false,\"error\":\"Timeout\"}");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
            } catch (ExecutionException e) {
                out.print("{\"success\":false,\"error\":\"Error\"}");
            } finally {
                ACTIVE_COMPUTATIONS.remove(sessionId);
            }
        } finally {
            lock.set(false);
        }
    }
}