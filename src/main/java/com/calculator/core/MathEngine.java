package com.calculator.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;

public final class MathEngine {

    // ── Shared pool ──────────────────────────────────────────────────────────
    private static final ForkJoinPool POOL = new ForkJoinPool(Runtime.getRuntime().availableProcessors());

    // ── Thresholds ───────────────────────────────────────────────────────────
    private static final int KARATSUBA_THRESHOLD = 70;
    private static final int TOOM_COOK_THRESHOLD = 10_000;
    private static final int PARALLEL_MIN_DIGITS = 500;
    private static final int FACTORIAL_LEAF = 128;
    private static final int FACTORIAL_ITER_LIMIT = 20;

    private static final double LOG10_2 = Math.log10(2.0); 

    // ── Division precision ───────────────────────────────────────────────────
    private static final int DIV_BASE_PRECISION = 30;

    private MathEngine() {
    }

    // =========================================================================
    // PUBLIC API
    // =========================================================================

    public static String add(String aStr, String bStr) {
        aStr = aStr.trim();
        bStr = bStr.trim();
        if (isInteger(aStr) && isInteger(bStr))
            return new BigInteger(aStr).add(new BigInteger(bStr)).toString();
        return formatDecimal(new BigDecimal(aStr).add(new BigDecimal(bStr)));
    }

    public static String subtract(String aStr, String bStr) {
        aStr = aStr.trim();
        bStr = bStr.trim();
        if (isInteger(aStr) && isInteger(bStr))
            return new BigInteger(aStr).subtract(new BigInteger(bStr)).toString();
        return formatDecimal(new BigDecimal(aStr).subtract(new BigDecimal(bStr)));
    }

    public static String multiply(String aStr, String bStr) {
        aStr = aStr.trim();
        bStr = bStr.trim();
        if (isInteger(aStr) && isInteger(bStr))
            return fastMultiply(new BigInteger(aStr), new BigInteger(bStr)).toString();
        return formatDecimal(new BigDecimal(aStr).multiply(new BigDecimal(bStr)));
    }

    public static String divide(String aStr, String bStr) {
        aStr = aStr.trim();
        bStr = bStr.trim();
        BigDecimal a = new BigDecimal(aStr), b = new BigDecimal(bStr);
        if (b.compareTo(BigDecimal.ZERO) == 0)
            throw new ArithmeticException("Division by zero");
        int precision = Math.max(DIV_BASE_PRECISION, a.precision() + b.precision() + 10);
        return formatDecimal(a.divide(b, new MathContext(precision, RoundingMode.HALF_UP)));
    }

    public static String mod(String aStr, String bStr) {
        aStr = aStr.trim();
        bStr = bStr.trim();
        BigInteger a = new BigInteger(aStr), b = new BigInteger(bStr);
        if (b.signum() == 0)
            throw new ArithmeticException("Modulo by zero");
        return a + " mod " + b + " = " + a.mod(b.abs());
    }

    public static String power(String baseStr, String expStr) {
        baseStr = baseStr.trim();
        expStr  = expStr.trim();

        if (isInteger(baseStr) && isInteger(expStr)) {
            BigInteger base = new BigInteger(baseStr);
            BigInteger exp  = new BigInteger(expStr);
            if (exp.signum() < 0)
                throw new ArithmeticException("Negative exponent not supported for integer power. Use decimal base.");
            if (exp.compareTo(BigInteger.valueOf(10_000_000L)) > 0)
                throw new ArithmeticException("Exponent too large (max 10,000,000).");
            BigInteger result = fastPow(base, exp.longValueExact());
            return result.toString();
        }

        BigDecimal base = new BigDecimal(baseStr);
        BigInteger exp;
        try {
            exp = new BigInteger(expStr);
        } catch (NumberFormatException e) {
            double result = Math.pow(base.doubleValue(), Double.parseDouble(expStr));
            return formatDecimal(BigDecimal.valueOf(result));
        }
        if (exp.signum() < 0) {
            BigDecimal pos = base.pow(exp.negate().intValueExact(),
                    new MathContext(50, RoundingMode.HALF_UP));
            return formatDecimal(BigDecimal.ONE.divide(pos, new MathContext(50, RoundingMode.HALF_UP)));
        }
        return formatDecimal(base.pow(exp.intValueExact(), new MathContext(50, RoundingMode.HALF_UP)));
    }

    private static BigInteger fastPow(BigInteger base, long exp) {
        if (exp == 0) return BigInteger.ONE;
        if (exp == 1) return base;
        BigInteger result = BigInteger.ONE;
        BigInteger b = base;
        long e = exp;
        while (e > 0) {
            if ((e & 1L) == 1L)
                result = fastMultiply(result, b);
            b = fastMultiply(b, b);
            e >>= 1;
        }
        return result;
    }

    // ── Factorial ─────────────────────────────────────────────────────────────
    public static String factorial(String numStr) {
        numStr = numStr.trim();
        BigInteger nBig;
        try {
            nBig = new BigInteger(numStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid input. Enter a non-negative integer.");
        }
        
        if (nBig.signum() < 0)
            throw new IllegalArgumentException("Factorial not defined for negative numbers.");

        long n = nBig.longValueExact();
        if (n == 0 || n == 1) return n + "! = 1|digits:1";

        // 1. Calculate the result using the Parallel Task
        BigInteger result = POOL.invoke(new FactorialTask(1, n));
        
        // 2. Convert to String separately (This is the "Exact" part)
        String rs = result.toString();
        
        // 3. Use StringBuilder to avoid the concatenation overflow bug
        StringBuilder sb = new StringBuilder();
        sb.append(n).append("! = ").append(rs).append("|digits:").append(rs.length());
        
        return sb.toString();
    }
    // ── Prime ─────────────────────────────────────────────────────────────────
    public static String checkPrime(String numStr) {
        String cleaned = numStr.trim().replace(",", "");
        BigInteger n;
        try {
            n = new BigInteger(cleaned);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid input. Enter a valid number.");
        }
        if (cleaned.length() > 15_000)
            throw new IllegalArgumentException("Input too large. Max 15,000 digits.");
        if (n.compareTo(BigInteger.ONE) <= 0)
            throw new IllegalArgumentException("Enter a positive number greater than 1.");
            
        boolean prime = isPrime(n);
        return n + (prime ? " is a prime number." : " is not a prime number.");
    }

    private static boolean isPrime(BigInteger n) {
        if (n.compareTo(BigInteger.TWO) < 0) return false;
        if (n.equals(BigInteger.TWO) || n.equals(BigInteger.valueOf(3))) return true;
        if (!n.testBit(0)) return false;

        // GCD Pre-filter
        if (!n.gcd(BigInteger.valueOf(30030)).equals(BigInteger.ONE)) return false;

        int[] smallPrimes = {17, 19, 23, 29, 31, 37, 41, 43, 47, 53, 59, 61, 67, 71, 73, 79, 83, 89, 97, 101, 103, 107, 109, 113};
        for (int p : smallPrimes) {
            if (n.mod(BigInteger.valueOf(p)).signum() == 0) return n.equals(BigInteger.valueOf(p));
        }

        int d = digitLength(n);
        if (d <= 25) return millerRabin(n, WITNESSES_DET);
        else if (d > 7500) return n.isProbablePrime(5); // High-Efficiency Path
        else return n.isProbablePrime(10); // Safe/Balanced Path
    }

    // ── Percent ───────────────────────────────────────────────────────────────
    public static String percent(String numStr) {
        numStr = numStr.trim();
        BigDecimal a = new BigDecimal(numStr);
        return formatDecimal(a.divide(new BigDecimal("100"),
                new MathContext(Math.max(30, a.precision() + 5), RoundingMode.HALF_UP)));
    }

    // =========================================================================
    // MULTIPLICATION ENGINE
    // =========================================================================

    public static BigInteger fastMultiply(BigInteger a, BigInteger b) {
        boolean negative = (a.signum() < 0) ^ (b.signum() < 0);
        BigInteger absA = a.abs(), absB = b.abs();

        if (absA.equals(absB)) {
            BigInteger result = unsignedSquare(absA);
            return negative ? result.negate() : result;
        }

        BigInteger result = unsignedMultiply(absA, absB);
        return negative ? result.negate() : result;
    }

    private static BigInteger unsignedSquare(BigInteger a) {
        int d = digitLength(a);
        if (d < KARATSUBA_THRESHOLD) return a.multiply(a);
        if (d < TOOM_COOK_THRESHOLD) return karatsubaSquare(a);
        return toomCook3Parallel(a, a);
    }

    private static BigInteger unsignedMultiply(BigInteger a, BigInteger b) {
        if (a.signum() == 0 || b.signum() == 0) return BigInteger.ZERO;
        if (a.equals(BigInteger.ONE)) return b;
        if (b.equals(BigInteger.ONE)) return a;

        int dA = digitLength(a), dB = digitLength(b);
        int maxD = Math.max(dA, dB);

        if (maxD < KARATSUBA_THRESHOLD) return a.multiply(b);
        if (maxD < TOOM_COOK_THRESHOLD) return karatsubaParallel(a, b);
        return toomCook3Parallel(a, b);
    }

    private static BigInteger karatsubaSquare(BigInteger x) {
        int d = digitLength(x);
        if (d < KARATSUBA_THRESHOLD) return x.multiply(x);
        int splitBits = x.bitLength() / 2;
        if (splitBits == 0) return x.multiply(x);
        BigInteger[] s = splitAtBit(x, splitBits);
        BigInteger x0 = s[0], x1 = s[1];
        if (x1.signum() == 0) return x0.multiply(x0);

        BigInteger sq0 = unsignedSquare(x0);
        BigInteger sq1 = unsignedSquare(x1);
        BigInteger cross = unsignedSquare(x0.add(x1)).subtract(sq0).subtract(sq1);
        return sq1.shiftLeft(2 * splitBits).add(cross.shiftLeft(splitBits)).add(sq0);
    }

    private static BigInteger karatsubaParallel(BigInteger a, BigInteger b) {
        int n = Math.max(digitLength(a), digitLength(b));
        if (n < KARATSUBA_THRESHOLD) return a.multiply(b);

        int maxBits = Math.max(a.bitLength(), b.bitLength());
        int splitBits = maxBits / 2;

        if (splitBits <= 0 || splitBits >= a.bitLength() || splitBits >= b.bitLength()) {
            int minBits = Math.min(a.bitLength(), b.bitLength());
            splitBits = Math.max(1, minBits / 2);
            if (splitBits >= minBits) return a.multiply(b);
        }

        BigInteger[] sa = splitAtBit(a, splitBits);
        BigInteger[] sb = splitAtBit(b, splitBits);
        BigInteger a0 = sa[0], a1 = sa[1], b0 = sb[0], b1 = sb[1];

        if (a1.signum() == 0) return unsignedMultiply(a0, b);
        if (b1.signum() == 0) return unsignedMultiply(a, b0);

        BigInteger z0, z1, z2;
        if (n > PARALLEL_MIN_DIGITS) {
            KaratsubaTask t0 = new KaratsubaTask(a0, b0);
            KaratsubaTask t2 = new KaratsubaTask(a1, b1);
            t0.fork();
            t2.fork();
            z1 = unsignedMultiply(a0.add(a1), b0.add(b1));
            z0 = t0.join();
            z2 = t2.join();
        } else {
            z0 = unsignedMultiply(a0, b0);
            z2 = unsignedMultiply(a1, b1);
            z1 = unsignedMultiply(a0.add(a1), b0.add(b1));
        }

        BigInteger mid = z1.subtract(z2).subtract(z0);
        return z2.shiftLeft(2 * splitBits).add(mid.shiftLeft(splitBits)).add(z0);
    }

    private static BigInteger toomCook3Parallel(BigInteger a, BigInteger b) {
        int n = Math.max(digitLength(a), digitLength(b));
        if (n < TOOM_COOK_THRESHOLD) return karatsubaParallel(a, b);

        int maxBits = Math.max(a.bitLength(), b.bitLength());
        int s = (maxBits + 2) / 3;
        if (s == 0) return a.multiply(b);

        BigInteger[] sa = splitInto3(a, s);
        BigInteger[] sb = splitInto3(b, s);
        BigInteger a0 = sa[0], a1 = sa[1], a2 = sa[2];
        BigInteger b0 = sb[0], b1 = sb[1], b2 = sb[2];

        BigInteger ev_0 = a0, ev_1 = a2.add(a1).add(a0), ev_m1 = a2.subtract(a1).add(a0);
        BigInteger ev_2 = a2.multiply(BigInteger.valueOf(4)).add(a1.multiply(BigInteger.TWO)).add(a0);
        BigInteger ev_inf = a2;

        BigInteger fu_0 = b0, fu_1 = b2.add(b1).add(b0), fu_m1 = b2.subtract(b1).add(b0);
        BigInteger fu_2 = b2.multiply(BigInteger.valueOf(4)).add(b1.multiply(BigInteger.TWO)).add(b0);
        BigInteger fu_inf = b2;

        BigInteger[] w = new BigInteger[5];
        if (n > PARALLEL_MIN_DIGITS * 3) {
            ToomTask t0 = new ToomTask(ev_0, fu_0, n / 3);
            ToomTask t1 = new ToomTask(ev_1, fu_1, n / 3);
            ToomTask tm1 = new ToomTask(ev_m1, fu_m1, n / 3);
            ToomTask t2 = new ToomTask(ev_2, fu_2, n / 3);
            t0.fork(); t1.fork(); tm1.fork(); t2.fork();
            w[4] = fastMultiply(ev_inf, fu_inf);
            w[3] = t2.join(); w[2] = tm1.join(); w[1] = t1.join(); w[0] = t0.join();
        } else {
            w[0] = fastMultiply(ev_0, fu_0); w[1] = fastMultiply(ev_1, fu_1);
            w[2] = fastMultiply(ev_m1, fu_m1); w[3] = fastMultiply(ev_2, fu_2);
            w[4] = fastMultiply(ev_inf, fu_inf);
        }

        BigInteger r0 = w[0], r4 = w[4];
        BigInteger r3 = w[3].subtract(w[1]).divide(BigInteger.valueOf(3));
        BigInteger r1 = w[1].subtract(w[2]).divide(BigInteger.TWO);
        BigInteger r2 = w[2].subtract(w[0]);
        r3 = r2.subtract(r3).divide(BigInteger.TWO).add(r4.shiftLeft(1));
        r2 = r2.add(r1).subtract(r4);
        r1 = r1.subtract(r3);

        return r4.shiftLeft(4 * s).add(r3.shiftLeft(3 * s)).add(r2.shiftLeft(2 * s)).add(r1.shiftLeft(s)).add(r0);
    }

    private static final long[] WITNESSES_DET = { 2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37 };

    private static boolean millerRabin(BigInteger n, long[] witnesses) {
        BigInteger nMinus1 = n.subtract(BigInteger.ONE);
        int r = nMinus1.getLowestSetBit();
        BigInteger d = nMinus1.shiftRight(r);
        for (long wLong : witnesses) {
            BigInteger a = BigInteger.valueOf(wLong);
            if (a.compareTo(n) >= 0) continue;
            BigInteger x = a.modPow(d, n);
            if (x.equals(BigInteger.ONE) || x.equals(nMinus1)) continue;
            boolean composite = true;
            for (int i = 0; i < r - 1; i++) {
                x = x.multiply(x).mod(n);
                if (x.equals(nMinus1)) { composite = false; break; }
            }
            if (composite) return false;
        }
        return true;
    }

    // ── Fixed Parallel Factorial Task ─────────────────────────────────────────
    private static final class FactorialTask extends RecursiveTask<BigInteger> {
        private final long lo, hi;
        FactorialTask(long lo, long hi) { this.lo = lo; this.hi = hi; }
        @Override
        protected BigInteger compute() {
            long range = hi - lo;
            if (range <= FACTORIAL_LEAF) {
                BigInteger prod = BigInteger.valueOf(lo == 0 ? 1 : lo);
                for (long i = lo + 1; i <= hi; i++) prod = prod.multiply(BigInteger.valueOf(i));
                return prod;
            }
            long mid = lo + (range / 2);
            FactorialTask left = new FactorialTask(lo, mid);
            FactorialTask right = new FactorialTask(mid + 1, hi);
            left.fork();
            BigInteger rightRes = right.compute();
            return fastMultiply(left.join(), rightRes);
        }
    }

    private static final class KaratsubaTask extends RecursiveTask<BigInteger> {
        private final BigInteger a, b;
        KaratsubaTask(BigInteger a, BigInteger b) { this.a = a; this.b = b; }
        @Override
        protected BigInteger compute() { return karatsubaParallel(a, b); }
    }

    private static final class ToomTask extends RecursiveTask<BigInteger> {
        private final BigInteger a, b;
        private final int sizeHint;
        ToomTask(BigInteger a, BigInteger b, int sizeHint) { this.a = a; this.b = b; this.sizeHint = sizeHint; }
        @Override
        protected BigInteger compute() {
            return sizeHint >= TOOM_COOK_THRESHOLD ? toomCook3Parallel(a, b) : karatsubaParallel(a, b);
        }
    }

    public static int digitLength(BigInteger n) {
        if (n.signum() == 0) return 1;
        return (int) (n.abs().bitLength() * LOG10_2) + 1;
    }

    private static BigInteger[] splitAtBit(BigInteger n, int bits) {
        BigInteger mask = BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE);
        return new BigInteger[] { n.and(mask), n.shiftRight(bits) };
    }

    private static BigInteger[] splitInto3(BigInteger n, int bitsPerPart) {
        BigInteger mask = BigInteger.ONE.shiftLeft(bitsPerPart).subtract(BigInteger.ONE);
        BigInteger p0 = n.and(mask);
        BigInteger rest = n.shiftRight(bitsPerPart);
        return new BigInteger[] { p0, rest.and(mask), rest.shiftRight(bitsPerPart) };
    }

    static boolean isInteger(String s) { return !s.contains(".") && !s.contains("e") && !s.contains("E"); }

    public static String formatDecimal(BigDecimal d) {
        BigDecimal stripped = d.stripTrailingZeros();
        if (stripped.scale() <= 0) {
            try { return stripped.toBigIntegerExact().toString(); }
            catch (ArithmeticException ignored) {}
        }
        return stripped.toPlainString();
    }
}