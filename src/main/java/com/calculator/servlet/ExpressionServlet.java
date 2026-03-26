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
 * Evaluates a full infix arithmetic expression.
 * Supports: + - * / % (as /100) and ** (power, right-associative).
 *
 * ** can appear with or without spaces: "2**10", "2 ** 10", "2** 10" all work.
 *
 * Operator precedence:
 *   ** (power)  → 3  (right-associative)
 *   * /         → 2
 *   + -         → 1
 *
 * Parsing strategy:
 *   Pre-process step normalises "**" into a single sentinel char ('^')
 *   so the shunting-yard loop stays single-char clean.
 */
@WebServlet("/evaluate")
public class ExpressionServlet extends HttpServlet {

    /** Internal sentinel for the power operator after pre-processing. */
    private static final char POW_SENTINEL = '^';

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
            // 1. Collapse "**" (with optional surrounding spaces) → POW_SENTINEL
            //    e.g. "2 ** 10" → "2^10", "5**3" → "5^3"
            String normalized = expression.trim()
                    .replaceAll("\\s*\\*\\*\\s*", String.valueOf(POW_SENTINEL))
                    // Replace bare % with /100
                    .replace("%", "/100");

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

            // ── Number token ──────────────────────────────────────────────
            if (Character.isDigit(c) || c == '.') {
                StringBuilder sb = new StringBuilder();
                while (i < len && (Character.isDigit(expr.charAt(i)) || expr.charAt(i) == '.'))
                    sb.append(expr.charAt(i++));
                values.push(new BigDecimal(sb.toString()));
                continue;
            }

            // ── Left paren ────────────────────────────────────────────────
            if (c == '(') { ops.push(c); i++; continue; }

            // ── Right paren ───────────────────────────────────────────────
            if (c == ')') {
                while (!ops.isEmpty() && ops.peek() != '(')
                    applyTop(ops, values);
                if (!ops.isEmpty()) ops.pop(); // pop '('
                i++;
                continue;
            }

            // ── Operators ────────────────────────────────────────────────
            if (c == '+' || c == '-' || c == '*' || c == '/' || c == POW_SENTINEL) {

                // Unary minus
                if (c == '-' && (i == 0
                        || isOperatorChar(expr.charAt(i - 1))
                        || expr.charAt(i - 1) == '(')) {
                    StringBuilder sb = new StringBuilder("-");
                    i++;
                    while (i < len && (Character.isDigit(expr.charAt(i)) || expr.charAt(i) == '.'))
                        sb.append(expr.charAt(i++));
                    values.push(new BigDecimal(sb.toString()));
                    continue;
                }

                // Pop operators with higher (or equal for left-assoc) precedence.
                // ** is right-associative: only pop if strictly greater precedence.
                while (!ops.isEmpty() && ops.peek() != '(') {
                    int topPrec = precedence(ops.peek());
                    int curPrec = precedence(c);
                    boolean shouldPop = (c == POW_SENTINEL)
                            ? topPrec > curPrec          // right-associative
                            : topPrec >= curPrec;        // left-associative
                    if (!shouldPop) break;
                    applyTop(ops, values);
                }
                ops.push(c);
                i++;
                continue;
            }

            i++; // unknown char — skip
        }

        while (!ops.isEmpty())
            applyTop(ops, values);

        if (values.isEmpty()) throw new ArithmeticException("Invalid expression");
        return values.pop();
    }

    private void applyTop(Deque<Character> ops, Deque<BigDecimal> values) {
        char op = ops.pop();
        BigDecimal b = values.pop();
        BigDecimal a = values.pop();
        values.push(applyOp(op, a, b));
    }

    /**
     * Routes each binary operation through MathEngine.
     * For ** : delegates to MathEngine.power() for fast binary exponentiation.
     * For *  with integer operands: Karatsuba / Toom-Cook via fastMultiply().
     */
    private BigDecimal applyOp(char op, BigDecimal a, BigDecimal b) {
        return switch (op) {
            case '+' -> {
                if (isWholeNumber(a) && isWholeNumber(b))
                    yield new BigDecimal(a.toBigIntegerExact().add(b.toBigIntegerExact()));
                yield a.add(b);
            }
            case '-' -> {
                if (isWholeNumber(a) && isWholeNumber(b))
                    yield new BigDecimal(a.toBigIntegerExact().subtract(b.toBigIntegerExact()));
                yield a.subtract(b);
            }
            case '*' -> {
                if (isWholeNumber(a) && isWholeNumber(b))
                    yield new BigDecimal(MathEngine.fastMultiply(
                            a.toBigIntegerExact(), b.toBigIntegerExact()));
                yield a.multiply(b);
            }
            case '/' -> {
                if (b.compareTo(BigDecimal.ZERO) == 0)
                    throw new ArithmeticException("Division by zero");
                int prec = Math.max(30, a.precision() + b.precision() + 10);
                yield a.divide(b, new MathContext(prec, RoundingMode.HALF_UP));
            }
            case POW_SENTINEL -> {
                // Delegate to MathEngine.power() for fast binary-exp + Karatsuba
                String result = MathEngine.power(a.toPlainString(), b.toPlainString());
                yield new BigDecimal(result);
            }
            default -> throw new ArithmeticException("Unknown operator: " + op);
        };
    }

    private boolean isWholeNumber(BigDecimal d) {
        try { d.toBigIntegerExact(); return true; }
        catch (ArithmeticException e) { return false; }
    }

    private int precedence(char op) {
        if (op == POW_SENTINEL) return 3;
        return (op == '*' || op == '/') ? 2 : (op == '+' || op == '-') ? 1 : 0;
    }

    private boolean isOperatorChar(char c) {
        return c == '+' || c == '-' || c == '*' || c == '/' || c == POW_SENTINEL;
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