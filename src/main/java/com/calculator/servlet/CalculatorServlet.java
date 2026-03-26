package com.calculator.servlet;

import com.calculator.core.MathEngine;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

@WebServlet("/calculate")
public class CalculatorServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Access-Control-Allow-Origin", "*");

        PrintWriter out = response.getWriter();

        String action = request.getParameter("action");
        String num1   = request.getParameter("num1");
        String num2   = request.getParameter("num2");

        if (action == null || action.isBlank()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.print("{\"success\":false,\"error\":\"Missing action\"}");
            return;
        }

        try {
            String result = switch (action) {
                case "add"      -> MathEngine.add(num1, num2);
                case "subtract" -> MathEngine.subtract(num1, num2);
                case "multiply" -> MathEngine.multiply(num1, num2);
                case "divide"   -> MathEngine.divide(num1, num2);
                case "mod"      -> MathEngine.mod(num1, num2);
                case "power"    -> MathEngine.power(num1, num2);
                case "factorial"-> MathEngine.factorial(num1);
                case "prime"    -> MathEngine.checkPrime(num1);
                case "percent"  -> MathEngine.percent(num1);
                default -> throw new IllegalArgumentException("Unknown action: " + action);
            };
            out.print("{\"success\":true,\"result\":\"" + escapeJson(result) + "\"}");

        } catch (ArithmeticException | IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.print("{\"success\":false,\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.print("{\"success\":false,\"error\":\"Internal server error\"}");
        }
    }

    @Override
    protected void doOptions(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type");
        response.setStatus(HttpServletResponse.SC_OK);
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