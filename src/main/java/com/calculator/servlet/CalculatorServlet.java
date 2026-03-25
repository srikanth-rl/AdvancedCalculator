package com.calculator.servlet;

import com.calculator.core.MathEngine;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Thin routing servlet — validates input, dispatches to MathEngine, returns
 * JSON.
 *
 * All heavy computation lives in MathEngine with adaptive algorithm selection:
 * - digit length detected in O(1) via BigInteger.bitLength()
 * - multiplication: schoolbook → Karatsuba → Toom-Cook 3, all with ForkJoin
 * parallelism
 * - factorial: split-recursive divide-and-conquer with ForkJoin (unlimited
 * size)
 * - prime: deterministic Miller-Rabin (≤25 digits), probabilistic beyond
 */
@WebServlet("/calculate")
public class CalculatorServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        setCors(response);

        PrintWriter out = response.getWriter();

        String action = request.getParameter("action");
        String num1Str = request.getParameter("num1");
        String num2Str = request.getParameter("num2");

        if (action == null || action.isBlank()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.print("{\"success\":false,\"error\":\"Missing action\"}");
            return;
        }

        try {
            String result = switch (action) {
                case "add" -> MathEngine.add(require(num1Str), require(num2Str));
                case "subtract" -> MathEngine.subtract(require(num1Str), require(num2Str));
                case "multiply" -> MathEngine.multiply(require(num1Str), require(num2Str));
                case "divide" -> MathEngine.divide(require(num1Str), require(num2Str));
                case "mod" -> MathEngine.mod(require(num1Str), require(num2Str));
                case "factorial" -> MathEngine.factorial(require(num1Str));
                case "prime" -> MathEngine.checkPrime(require(num1Str));
                case "percent" -> MathEngine.percent(require(num1Str));
                default -> throw new IllegalArgumentException("Unknown action: " + action);
            };

            out.print("{\"success\":true,\"result\":\"" + escapeJson(result) + "\"}");

        } catch (ArithmeticException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.print("{\"success\":false,\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        } catch (NumberFormatException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.print("{\"success\":false,\"error\":\"Invalid number format\"}");
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.print("{\"success\":false,\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    @Override
    protected void doOptions(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        setCors(response);
        response.setStatus(HttpServletResponse.SC_OK);
    }

    private String require(String val) {
        if (val == null || val.isBlank())
            throw new IllegalArgumentException("Missing required parameter");
        return val.trim();
    }

    private void setCors(HttpServletResponse response) {
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type");
    }

    private String escapeJson(String s) {
        if (s == null)
            return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}