package com.calculator.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MathEngine {

    // ─── Cancellation token ───────────────────────────────────────────────────

    public static final class CancellationToken {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        public void cancel()         { cancelled.set(true); }
        public boolean isCancelled() { return cancelled.get(); }
        public void checkCancelled() {
            if (cancelled.get()) throw new CancellationException("Computation cancelled by user.");
        }
    }

    public static final class CancellationException extends RuntimeException {
        public CancellationException(String msg) { super(msg); }
    }

    private static final Map<String, CancellationToken> SESSION_TOKENS =
        Collections.synchronizedMap(new LinkedHashMap<>());

    public static void setSessionToken(String sessionId, CancellationToken tok) {
        SESSION_TOKENS.put(sessionId, tok);
    }

    public static void cancelSession(String sessionId) {
        CancellationToken tok = SESSION_TOKENS.remove(sessionId);
        if (tok != null) tok.cancel();
    }

    public static void clearSessionToken(String sessionId) {
        SESSION_TOKENS.remove(sessionId);
    }

    // ─── LRU cache ────────────────────────────────────────────────────────────

    private static final int  MAX_CACHE_ENTRIES = 10_000;
    private static final long MAX_CACHE_BYTES   = 2056L * 1024 * 1024; // ~2 GB
    private static volatile long estimatedCacheBytes = 0;

    private static final Map<String, String> CACHE = Collections.synchronizedMap(
        new LinkedHashMap<String, String>(MAX_CACHE_ENTRIES, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                if (size() > MAX_CACHE_ENTRIES || estimatedCacheBytes > MAX_CACHE_BYTES) {
                    estimatedCacheBytes -= estimateBytes(eldest.getKey(), eldest.getValue());
                    return true;
                }
                return false;
            }
        }
    );

    private static long estimateBytes(String key, String value) {
        return (long)(key.length() + value.length()) * 2L + 80L;
    }

    private static String cachePut(String key, String value) {
        estimatedCacheBytes += estimateBytes(key, value);
        CACHE.put(key, value);
        return value;
    }

    public static void cacheEvict(String key) {
        String removed = CACHE.remove(key);
        if (removed != null) estimatedCacheBytes -= estimateBytes(key, removed);
    }

    private static void memoryGuard(String operation) {
        Runtime rt   = Runtime.getRuntime();
        long    free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
        if (free < 100L * 1024 * 1024) {
            CACHE.clear();
            estimatedCacheBytes = 0;
            rt.gc();
            free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
            if (free < 100L * 1024 * 1024) {
                throw new IllegalStateException(
                    "Server is low on memory. Please try smaller inputs" + operation +
                    " or wait a moment and retry.");
            }
        }
    }

    // ─── Computation pool ─────────────────────────────────────────────────────
    private static final ForkJoinPool POOL =
        new ForkJoinPool(Runtime.getRuntime().availableProcessors());

    // ─── Thresholds ───────────────────────────────────────────────────────────
    private static final int    KARATSUBA_THRESHOLD = 100;
    private static final double LOG10_2             = Math.log10(2.0);
    private static final int    DIV_BASE_PRECISION  = 30;
    
    private static final int    FACTORIAL_LEAF      = 1024;
    private static final long MAX_FACTORIAL    = 3_000_000L;
    private static final int  MAX_PRIME_DIGITS = 7_000;

    private MathEngine() {}

    // ─── Public API ───────────────────────────────────────────────────────────

    public static String add(String aStr, String bStr) { return add(aStr, bStr, null); }
    public static String add(String aStr, String bStr, CancellationToken tok) {
        String key    = "add:" + aStr.trim() + "," + bStr.trim();
        String cached = CACHE.get(key); if (cached != null) return cached;
        memoryGuard("addition");
        if (tok != null) tok.checkCancelled();
        String res = isInteger(aStr) && isInteger(bStr)
            ? new BigInteger(aStr.trim()).add(new BigInteger(bStr.trim())).toString()
            : formatDecimal(new BigDecimal(aStr.trim()).add(new BigDecimal(bStr.trim())));
        try {
            if (tok != null) tok.checkCancelled();
            return cachePut(key, formatResultWithLength(res));
        } catch (RuntimeException e) { cacheEvict(key); throw e; }
    }

    public static String subtract(String aStr, String bStr) { return subtract(aStr, bStr, null); }
    public static String subtract(String aStr, String bStr, CancellationToken tok) {
        String key    = "sub:" + aStr.trim() + "," + bStr.trim();
        String cached = CACHE.get(key); if (cached != null) return cached;
        memoryGuard("subtraction");
        if (tok != null) tok.checkCancelled();
        String res = isInteger(aStr) && isInteger(bStr)
            ? new BigInteger(aStr.trim()).subtract(new BigInteger(bStr.trim())).toString()
            : formatDecimal(new BigDecimal(aStr.trim()).subtract(new BigDecimal(bStr.trim())));
        try {
            if (tok != null) tok.checkCancelled();
            return cachePut(key, formatResultWithLength(res));
        } catch (RuntimeException e) { cacheEvict(key); throw e; }
    }

    public static String multiply(String aStr, String bStr) { return multiply(aStr, bStr, null); }
    public static String multiply(String aStr, String bStr, CancellationToken tok) {
        String key    = "mul:" + aStr.trim() + "," + bStr.trim();
        String cached = CACHE.get(key); if (cached != null) return cached;
        memoryGuard("multiplication");
        if (tok != null) tok.checkCancelled();
        String res = isInteger(aStr) && isInteger(bStr)
            ? fastMultiply(new BigInteger(aStr.trim()), new BigInteger(bStr.trim())).toString()
            : formatDecimal(new BigDecimal(aStr.trim()).multiply(new BigDecimal(bStr.trim())));
        try {
            if (tok != null) tok.checkCancelled();
            return cachePut(key, formatResultWithLength(res));
        } catch (RuntimeException e) { cacheEvict(key); throw e; }
    }

    public static String divide(String aStr, String bStr) { return divide(aStr, bStr, null); }
    public static String divide(String aStr, String bStr, CancellationToken tok) {
        String key    = "div:" + aStr.trim() + "," + bStr.trim();
        String cached = CACHE.get(key); if (cached != null) return cached;
        memoryGuard("division");
        if (tok != null) tok.checkCancelled();
        BigDecimal a = new BigDecimal(aStr.trim());
        BigDecimal b = new BigDecimal(bStr.trim());
        if (b.compareTo(BigDecimal.ZERO) == 0)
            throw new ArithmeticException("Division by zero is undefined.");
        int precision = Math.max(DIV_BASE_PRECISION, a.precision() + b.precision() + 10);
        String res = formatDecimal(a.divide(b, new MathContext(precision, RoundingMode.HALF_UP)));
        try {
            if (tok != null) tok.checkCancelled();
            return cachePut(key, formatResultWithLength(res));
        } catch (RuntimeException e) { cacheEvict(key); throw e; }
    }

    public static String mod(String aStr, String bStr) { return mod(aStr, bStr, null); }
    public static String mod(String aStr, String bStr, CancellationToken tok) {
        String key    = "mod:" + aStr.trim() + "," + bStr.trim();
        String cached = CACHE.get(key); if (cached != null) return cached;
        memoryGuard("modulo");
        if (tok != null) tok.checkCancelled();
        BigInteger a = new BigInteger(aStr.trim());
        BigInteger b = new BigInteger(bStr.trim());
        if (b.signum() == 0) throw new ArithmeticException("Modulo by zero is undefined.");
        String res = a + " mod " + b + " = " + a.mod(b.abs());
        try {
            if (tok != null) tok.checkCancelled();
            return cachePut(key, res);
        } catch (RuntimeException e) { cacheEvict(key); throw e; }
    }

    public static String power(String baseStr, String expStr) { return power(baseStr, expStr, null); }
    public static String power(String baseStr, String expStr, CancellationToken tok) {
        String key    = "pow:" + baseStr.trim() + "," + expStr.trim();
        String cached = CACHE.get(key); if (cached != null) return cached;
        memoryGuard("power");
        if (tok != null) tok.checkCancelled();
        String res;
        if (isInteger(baseStr) && isInteger(expStr)) {
            BigInteger base = new BigInteger(baseStr.trim());
            BigInteger exp  = new BigInteger(expStr.trim());
            if (exp.signum() < 0)
                throw new ArithmeticException(
                    "Negative integer exponent is not supported. Use decimal base for fractional powers.");
            res = fastPow(base, exp).toString();
        } else {
            int intExp;
            try { intExp = new BigDecimal(expStr.trim()).intValueExact(); }
            catch (ArithmeticException ex) {
                throw new ArithmeticException(
                    "For decimal base, the exponent must be a whole number that fits in 32 bits.");
            }
            res = formatDecimal(new BigDecimal(baseStr.trim())
                    .pow(intExp, new MathContext(50, RoundingMode.HALF_UP)));
        }
        try {
            if (tok != null) tok.checkCancelled();
            return cachePut(key, formatResultWithLength(res));
        } catch (RuntimeException e) { cacheEvict(key); throw e; }
    }

    public static String factorial(String numStr) { return factorial(numStr, null); }
    public static String factorial(String numStr, CancellationToken tok) {
        String key    = "fact:" + numStr.trim();
        String cached = CACHE.get(key); if (cached != null) return cached;

        long n;
        try { 
            n = new BigDecimal(numStr.trim()).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Input must be a number that fits in 64 bits.");
        }
        if (n < 0) throw new IllegalArgumentException("Factorial is not defined for negative numbers.");
        if (n > MAX_FACTORIAL) throw new IllegalArgumentException(
            "Input is too large. Maximum supported factorial is 3,000,000 (3 M)! " +
            "Please reduce input and try again.");

        memoryGuard("factorial");
        if (tok != null) tok.checkCancelled();

        BigInteger resBig;
        try {
            resBig = (n <= 1) ? BigInteger.ONE : POOL.invoke(new FactorialTask(1, n, tok));
        } catch (RuntimeException e) {
            cacheEvict(key);
            throw e;
        }

        try {
            if (tok != null) tok.checkCancelled();
            String rs = resBig.toString();
            return cachePut(key, rs + "|" + rs.length());
        } catch (RuntimeException e) { cacheEvict(key); throw e; }
    }

    public static String gcd(String aStr, String bStr) { return gcd(aStr, bStr, null); }
    public static String gcd(String aStr, String bStr, CancellationToken tok) {
        String key    = "gcd:" + aStr.trim() + "," + bStr.trim();
        String cached = CACHE.get(key); if (cached != null) return cached;
        memoryGuard("GCD");
        if (tok != null) tok.checkCancelled();
        BigInteger a = new BigInteger(aStr.trim()).abs();
        BigInteger b = new BigInteger(bStr.trim()).abs();
        if (a.signum() == 0) return cachePut(key, b + "|" + b.toString().length());
        if (b.signum() == 0) return cachePut(key, a + "|" + a.toString().length());
        BigInteger result = a.gcd(b);
        try {
            if (tok != null) tok.checkCancelled();
            String res = result.toString();
            return cachePut(key, res + "|" + res.length());
        } catch (RuntimeException e) { cacheEvict(key); throw e; }
    }

    public static String lcm(String aStr, String bStr) { return lcm(aStr, bStr, null); }
    public static String lcm(String aStr, String bStr, CancellationToken tok) {
        String key    = "lcm:" + aStr.trim() + "," + bStr.trim();
        String cached = CACHE.get(key); if (cached != null) return cached;
        memoryGuard("LCM");
        if (tok != null) tok.checkCancelled();
        BigInteger a = new BigInteger(aStr.trim()).abs();
        BigInteger b = new BigInteger(bStr.trim()).abs();
        if (a.signum() == 0 || b.signum() == 0) return cachePut(key, "0|1");
        BigInteger gcdVal = a.gcd(b);
        BigInteger result = a.divide(gcdVal).multiply(b);
        try {
            if (tok != null) tok.checkCancelled();
            String res = result.toString();
            return cachePut(key, res + "|" + res.length());
        } catch (RuntimeException e) { cacheEvict(key); throw e; }
    }

    public static String checkPrime(String numStr) { return checkPrime(numStr, null); }
    public static String checkPrime(String numStr, CancellationToken tok) {
        String cleaned = numStr.trim().replace(",", "");
        if (cleaned.length() > MAX_PRIME_DIGITS) throw new IllegalArgumentException(
            "Input is too large. Prime check supports up to 7,000 digits " +
            "(your input: " + cleaned.length() + " digits). Please reduce and try again.");
        String key    = "prime:" + cleaned;
        String cached = CACHE.get(key); if (cached != null) return cached;
        if (tok != null) tok.checkCancelled();
        BigInteger n  = new BigInteger(cleaned);
        boolean prime = isPrime(n);
        try {
            if (tok != null) tok.checkCancelled();
            return cachePut(key, (prime ? "A" : "Not a") + " Prime Number.");
        } catch (RuntimeException e) { cacheEvict(key); throw e; }
    }

    // ─── Utilities ────────────────────────────────────────────────────────────

    public static int digitLength(String s) {
        return s.replace(".", "").replace("-", "").length();
    }

    public static String formatDecimal(BigDecimal d) {
        BigDecimal s = d.stripTrailingZeros();
        return s.scale() <= 0 ? s.toBigIntegerExact().toString() : s.toPlainString();
    }

    private static String formatResultWithLength(String result) {
        return result + "|" + digitLength(result);
    }

    public static BigInteger fastMultiply(BigInteger a, BigInteger b) {
        int bitLen = Math.max(a.bitLength(), b.bitLength());
        if ((int)(bitLen * LOG10_2) < KARATSUBA_THRESHOLD) {
            return a.multiply(b);
        }
        return karatsubaParallel(a, b);
    }

    private static BigInteger fastPow(BigInteger base, BigInteger exp) {
        BigInteger res = BigInteger.ONE;
        BigInteger b   = base;
        while (exp.signum() > 0) {
            if (exp.testBit(0)) res = fastMultiply(res, b);
            b   = fastMultiply(b, b);
            exp = exp.shiftRight(1);
            if (Thread.interrupted()) throw new RuntimeException("Computation interrupted");
        }
        return res;
    }

    private static final int[] SMALL_PRIMES = {
        3,5,7,11,13,17,19,23,29,31,37,41,43,47,53,59,61,67,71,73,
        79,83,89,97,101,103,107,109,113,127,131,137,139,149,151,157,
        163,167,173,179,181,191,193,197,199,211,223,227,229,233,239,
        241,251,257,263,269,271,277,281,283,293,307,311,313,317,331,
        337,347,349,353,359,367,373,379,383,389,397,401,409,419,421,
        431,433,439,443,449,457,461,463,467,479,487,491,499
    };

    private static boolean isPrime(BigInteger n) {
        if (n.compareTo(BigInteger.TWO) < 0) return false;
        if (n.equals(BigInteger.TWO) || n.equals(BigInteger.valueOf(3))) return true;
        if (!n.testBit(0)) return false; // Even numbers greater than 2 are not prime
        for (int p : SMALL_PRIMES) {
            BigInteger bp = BigInteger.valueOf(p);
            if (n.equals(bp)) return true;
            if (n.mod(bp).signum() == 0) return false;
        }
        return n.isProbablePrime(8);
    }

    // ─── Parallel Factorial ───────────────────────────────────────────────────

    private static final class FactorialTask extends RecursiveTask<BigInteger> {
        private final long lo, hi;
        private final CancellationToken tok;

        FactorialTask(long lo, long hi, CancellationToken tok) {
            this.lo = lo; this.hi = hi; this.tok = tok;
        }

        @Override
        protected BigInteger compute() {
            if (tok != null) tok.checkCancelled();

            if ((hi - lo) <= FACTORIAL_LEAF) {
                BigInteger prod = BigInteger.valueOf(lo);
                for (long i = lo + 1; i <= hi; i++) {
                    if (tok != null && (i & 31) == 0) tok.checkCancelled();
                    prod = prod.multiply(BigInteger.valueOf(i));
                }
                return prod;
            }
            long mid = (lo + hi) >>> 1;
            FactorialTask left = new FactorialTask(lo, mid, tok);
            left.fork();
            BigInteger right = new FactorialTask(mid + 1, hi, tok).compute();
            if (tok != null) tok.checkCancelled();
            return fastMultiply(right, left.join());
        }
    }

    // ─── Karatsuba ────────────────────────────────────────────────────────────

    private static BigInteger karatsubaParallel(BigInteger a, BigInteger b) {
        int n = Math.max(a.bitLength(), b.bitLength());
        if (n <= KARATSUBA_THRESHOLD * 3) return a.multiply(b);
        int half = (n / 2) + (n % 2);
        BigInteger[] sa = split(a, half);
        BigInteger[] sb = split(b, half);
        BigInteger a0 = sa[0], a1 = sa[1];
        BigInteger b0 = sb[0], b1 = sb[1];
        BigInteger z0 = a0.multiply(b0);
        BigInteger z2 = a1.multiply(b1);
        BigInteger z1 = a0.add(a1).multiply(b0.add(b1));
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
        String t = s.trim();
        return !t.contains(".") && !t.toLowerCase().contains("e");
    }
}