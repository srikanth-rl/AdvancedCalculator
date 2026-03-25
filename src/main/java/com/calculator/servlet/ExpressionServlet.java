package com.calculator.servlet;

import com.calculator.core.MathEngine;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Evaluates a full infix arithmetic expression, e.g. "5+3*2-1/4+8%"
 * Supports: + - * / and % (treated as /100 for the operand just before it)
 *
 * Uses shunting-yard algorithm for parsing.
 * Each applyOp() routes through MathEngine for algorithm-optimal computation:
 *   - multiply: schoolbook / Karatsuba / Toom-Cook 3 based on digit length (O(1) detect)
 *   - add/sub: BigInteger path for integers (avoids BigDecimal overhead)
 *   - divide: adaptive-precision BigDecimal
 */
@WebServlet("/evaluate")
public class ExpressionServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Access-Control-Allow-Origin", "*");

        PrintWriter out = response.getWriter();
        String expression = request.getParameter("expression");

        if (expression == null || expression.isBlank()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.print("{\"success\":false,\"error\":\"Empty expression\"}");
            return;
        }

        try {
            // Replace % with /100 so "50%" becomes "50/100"
            String normalized = expression.trim().replace("%", "/100");
            BigDecimal result = evaluate(normalized);

            out.print("{\"success\":true,\"result\":\"" + escapeJson(MathEngine.formatDecimal(result)) + "\"}");

        } catch (ArithmeticException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.print("{\"success\":false,\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.print("{\"success\":false,\"error\":\"Invalid expression\"}");
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

    // ── Shunting-yard parser ──────────────────────────────────────────────────

    private BigDecimal evaluate(String expr) {
        Deque<BigDecimal> values = new ArrayDeque<>();
        Deque<Character>  ops    = new ArrayDeque<>();

        int i = 0, len = expr.length();

        while (i < len) {
            char c = expr.charAt(i);

            if (c == ' ') { i++; continue; }

            // Number token (integer or decimal)
            if (Character.isDigit(c) || c == '.') {
                StringBuilder sb = new StringBuilder();
                while (i < len && (Character.isDigit(expr.charAt(i)) || expr.charAt(i) == '.')) {
                    sb.append(expr.charAt(i++));
                }
                values.push(new BigDecimal(sb.toString()));
                continue;
            }

            if (c == '(') { ops.push(c); i++; continue; }

            if (c == ')') {
                while (!ops.isEmpty() && ops.peek() != '(')
                    values.push(applyOp(ops.pop(), values.pop(), values.pop()));
                if (!ops.isEmpty()) ops.pop();
                i++;
                continue;
            }

            if (c == '+' || c == '-' || c == '*' || c == '/') {
                // Unary minus
                if (c == '-' && (i == 0 || isOperator(expr.charAt(i - 1)) || expr.charAt(i - 1) == '(')) {
                    StringBuilder sb = new StringBuilder("-");
                    i++;
                    while (i < len && (Character.isDigit(expr.charAt(i)) || expr.charAt(i) == '.'))
                        sb.append(expr.charAt(i++));
                    values.push(new BigDecimal(sb.toString()));
                    continue;
                }

                while (!ops.isEmpty() && ops.peek() != '(' && precedence(ops.peek()) >= precedence(c))
                    values.push(applyOp(ops.pop(), values.pop(), values.pop()));
                ops.push(c);
                i++;
                continue;
            }

            i++; // unknown char — skip
        }

        while (!ops.isEmpty())
            values.push(applyOp(ops.pop(), values.pop(), values.pop()));

        if (values.isEmpty()) throw new ArithmeticException("Invalid expression");
        return values.pop();
    }

    /**
     * Route each binary operation through MathEngine for optimal algorithm selection.
     * For * and integer operands: Karatsuba / Toom-Cook via MathEngine.fastMultiply().
     * For + and - with integer operands: BigInteger.add/subtract (avoids decimal overhead).
     */
    private BigDecimal applyOp(char op, BigDecimal b, BigDecimal a) {
        return switch (op) {
            case '+' -> {
                // Integer fast path
                if (isWholeNumber(a) && isWholeNumber(b)) {
                    BigInteger ai = a.toBigIntegerExact();
                    BigInteger bi = b.toBigIntegerExact();
                    yield new BigDecimal(ai.add(bi));
                }
                yield a.add(b);
            }
            case '-' -> {
                if (isWholeNumber(a) && isWholeNumber(b)) {
                    BigInteger ai = a.toBigIntegerExact();
                    BigInteger bi = b.toBigIntegerExact();
                    yield new BigDecimal(ai.subtract(bi));
                }
                yield a.subtract(b);
            }
            case '*' -> {
                // Integer fast path: route through MathEngine (Karatsuba / Toom-Cook)
                if (isWholeNumber(a) && isWholeNumber(b)) {
                    BigInteger ai = a.toBigIntegerExact();
                    BigInteger bi = b.toBigIntegerExact();
                    yield new BigDecimal(MathEngine.fastMultiply(ai, bi));
                }
                yield a.multiply(b);
            }
            case '/' -> {
                if (b.compareTo(BigDecimal.ZERO) == 0) throw new ArithmeticException("Division by zero");
                int prec = Math.max(30, a.precision() + b.precision() + 10);
                yield a.divide(b, new MathContext(prec, RoundingMode.HALF_UP));
            }
            default -> throw new ArithmeticException("Unknown operator: " + op);
        };
    }

    private boolean isWholeNumber(BigDecimal d) {
        try {
            d.toBigIntegerExact();
            return true;
        } catch (ArithmeticException e) {
            return false;
        }
    }

    private int precedence(char op) {
        return (op == '*' || op == '/') ? 2 : (op == '+' || op == '-') ? 1 : 0;
    }

    private boolean isOperator(char c) {
        return c == '+' || c == '-' || c == '*' || c == '/';
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
