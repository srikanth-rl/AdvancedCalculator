package com.calculator.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;

public final class MathEngine {

    private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<>();

    private static final ForkJoinPool POOL = new ForkJoinPool(Runtime.getRuntime().availableProcessors());
    private static final int KARATSUBA_THRESHOLD = 70;
    private static final int TOOM_COOK_THRESHOLD = 10_000;
    private static final int PARALLEL_MIN_DIGITS = 500;
    private static final int FACTORIAL_LEAF = 128;
    private static final double LOG10_2 = Math.log10(2.0);
    private static final int DIV_BASE_PRECISION = 30;

    private MathEngine() {}

    // =========================================================================
    // PUBLIC API
    // =========================================================================

    public static String add(String aStr, String bStr) {
        String key = "add:" + aStr.trim() + "," + bStr.trim();
        return CACHE.computeIfAbsent(key, k -> {
            String res;
            if (isInteger(aStr) && isInteger(bStr)) {
                res = new BigInteger(aStr.trim()).add(new BigInteger(bStr.trim())).toString();
            } else {
                res = formatDecimal(new BigDecimal(aStr.trim()).add(new BigDecimal(bStr.trim())));
            }
            return formatResultWithLength(res);
        });
    }

    public static String subtract(String aStr, String bStr) {
        String key = "sub:" + aStr.trim() + "," + bStr.trim();
        return CACHE.computeIfAbsent(key, k -> {
            String res;
            if (isInteger(aStr) && isInteger(bStr)) {
                res = new BigInteger(aStr.trim()).subtract(new BigInteger(bStr.trim())).toString();
            } else {
                res = formatDecimal(new BigDecimal(aStr.trim()).subtract(new BigDecimal(bStr.trim())));
            }
            return formatResultWithLength(res);
        });
    }

    public static String multiply(String aStr, String bStr) {
        String key = "mul:" + aStr.trim() + "," + bStr.trim();
        return CACHE.computeIfAbsent(key, k -> {
            String res;
            if (isInteger(aStr) && isInteger(bStr)) {
                res = fastMultiply(new BigInteger(aStr.trim()), new BigInteger(bStr.trim())).toString();
            } else {
                res = formatDecimal(new BigDecimal(aStr.trim()).multiply(new BigDecimal(bStr.trim())));
            }
            return formatResultWithLength(res);
        });
    }

    public static String divide(String aStr, String bStr) {
        String key = "div:" + aStr.trim() + "," + bStr.trim();
        return CACHE.computeIfAbsent(key, k -> {
            BigDecimal a = new BigDecimal(aStr.trim());
            BigDecimal b = new BigDecimal(bStr.trim());
            if (b.compareTo(BigDecimal.ZERO) == 0) {
                throw new ArithmeticException("Division by zero");
            }
            int precision = Math.max(DIV_BASE_PRECISION, a.precision() + b.precision() + 10);
            return formatResultWithLength(formatDecimal(a.divide(b, new MathContext(precision, RoundingMode.HALF_UP))));
        });
    }

    public static String mod(String aStr, String bStr) {
        String key = "mod:" + aStr.trim() + "," + bStr.trim();
        return CACHE.computeIfAbsent(key, k -> {
            BigInteger a = new BigInteger(aStr.trim());
            BigInteger b = new BigInteger(bStr.trim());
            if (b.signum() == 0) {
                throw new ArithmeticException("Modulo by zero");
            }
            return a + " mod " + b + " = " + a.mod(b.abs());
        });
    }

    public static String power(String baseStr, String expStr) {
        String key = "pow:" + baseStr.trim() + "," + expStr.trim();
        return CACHE.computeIfAbsent(key, k -> {
            String res;
            if (isInteger(baseStr) && isInteger(expStr)) {
                BigInteger base = new BigInteger(baseStr.trim());
                BigInteger exp = new BigInteger(expStr.trim());
                if (exp.signum() < 0) {
                    throw new ArithmeticException("Negative integer exponent not supported.");
                }
                res = fastPow(base, exp.longValueExact()).toString();
            } else {
                res = formatDecimal(new BigDecimal(baseStr.trim()).pow(Integer.parseInt(expStr.trim()), new MathContext(50, RoundingMode.HALF_UP)));
            }
            return formatResultWithLength(res);
        });
    }

    public static String factorial(String numStr) {
        String key = "fact:" + numStr.trim();
        return CACHE.computeIfAbsent(key, k -> {
            long n = Long.parseLong(numStr.trim());
            if (n < 0) {
                throw new IllegalArgumentException("Negative Factorial.");
            }
            BigInteger resBig = (n <= 1) ? BigInteger.ONE : POOL.invoke(new FactorialTask(1, n));
            String rs = resBig.toString();
            return rs + "|" + rs.length();
        });
    }

    public static String checkPrime(String numStr) {
        String cleaned = numStr.trim().replace(",", "");
        String key = "prime:" + cleaned;
        return CACHE.computeIfAbsent(key, k -> {
            BigInteger n = new BigInteger(cleaned);
            boolean prime = isPrime(n);
            return (prime ? "A" : "Not a") + " Prime Number.";
        });
    }

    public static String percent(String numStr) {
        return formatResultWithLength(formatDecimal(
            new BigDecimal(numStr.trim()).divide(BigDecimal.valueOf(100), new MathContext(30, RoundingMode.HALF_UP))
        ));
    }

    // =========================================================================
    // INTERNAL HELPERS
    // =========================================================================

    public static int digitLength(String s) {
        return s.replace(".", "").replace("-", "").length();
    }

    private static String formatResultWithLength(String result) {
        return result + "|" + digitLength(result);
    }

    public static BigInteger fastMultiply(BigInteger a, BigInteger b) {
        int bitLen = Math.max(a.bitLength(), b.bitLength());
        if ((int) (bitLen * LOG10_2) < KARATSUBA_THRESHOLD) {
            return a.multiply(b);
        }
        return karatsubaParallel(a, b);
    }

    private static BigInteger fastPow(BigInteger base, long exp) {
        BigInteger res = BigInteger.ONE;
        BigInteger b = base;
        while (exp > 0) {
            if ((exp & 1) == 1) {
                res = fastMultiply(res, b);
            }
            b = fastMultiply(b, b);
            exp >>= 1;
        }
        return res;
    }

    private static boolean isPrime(BigInteger n) {
        if (n.compareTo(BigInteger.TWO) < 0) {
            return false;
        }
        if (n.equals(BigInteger.TWO) || n.equals(BigInteger.valueOf(3))) {
            return true;
        }
        if (!n.testBit(0)) {
            return false;
        }
        return n.isProbablePrime(8);
    }

    private static final class FactorialTask extends RecursiveTask<BigInteger> {

        private final long lo, hi;

        FactorialTask(long lo, long hi) {
            this.lo = lo;
            this.hi = hi;
        }

        @Override
        protected BigInteger compute() {
            if ((hi - lo) <= FACTORIAL_LEAF) {
                BigInteger prod = BigInteger.valueOf(lo);
                for (long i = lo + 1; i <= hi; i++) {
                    prod = prod.multiply(BigInteger.valueOf(i));
                }
                return prod;
            }
            long mid = (lo + hi) >>> 1;
            FactorialTask left = new FactorialTask(lo, mid);
            left.fork();
            return fastMultiply(new FactorialTask(mid + 1, hi).compute(), left.join());
        }
    }

    private static BigInteger karatsubaParallel(BigInteger a, BigInteger b) {
        int n = Math.max(a.bitLength(), b.bitLength());
        if (n <= KARATSUBA_THRESHOLD * 3) {
            return a.multiply(b);
        }
        int half = (n / 2) + (n % 2);
        BigInteger[] sa = split(a, half);
        BigInteger[] sb = split(b, half);
        BigInteger a0 = sa[0], a1 = sa[1];
        BigInteger b0 = sb[0], b1 = sb[1];
        BigInteger z0 = a0.multiply(b0);
        BigInteger z2 = a1.multiply(b1);
        BigInteger z1 = (a0.add(a1)).multiply(b0.add(b1));
        return z2.shiftLeft(half * 2)
                 .add(z1.subtract(z2).subtract(z0).shiftLeft(half))
                 .add(z0);
    }

    private static BigInteger[] split(BigInteger n, int bits) {
        return new BigInteger[]{
            n.and(BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE)),
            n.shiftRight(bits)
        };
    }

    static boolean isInteger(String s) {
        return !s.contains(".")
            && !s.toLowerCase().contains("e")
            && !s.toLowerCase().contains("E");
    }

    public static String formatDecimal(BigDecimal d) {
        BigDecimal s = d.stripTrailingZeros();
        return s.scale() <= 0 ? s.toBigIntegerExact().toString() : s.toPlainString();
    }
}