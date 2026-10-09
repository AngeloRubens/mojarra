package org.mojarra.bench;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * SIMD-style search for the first character needing HTML escaping ({@code < 0x20}, {@code <}, {@code >}, {@code &})
 * using only standard Java 17 APIs: no {@code sun.misc.Unsafe}, no incubator Vector API.
 * <ul>
 * <li>{@code scalarTable}: per-char lookup table with early exit (the proposed HtmlUtils loop).</li>
 * <li>{@code autoVectorChars}: branch-free OR-reduction over fixed-size blocks of a {@code char[]}, shaped so that C2's
 * SuperWord pass auto-vectorizes it; early exit happens per block only.</li>
 * <li>{@code swarBytes}: SWAR over a UTF-8 {@code byte[]} (the byte-level pipeline), 8 bytes per step through a
 * {@link MethodHandles#byteArrayViewVarHandle} long view.</li>
 * </ul>
 * Compare with {@link SimdScanBenchmark} for the Vector API reference numbers.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class SwarScanBenchmark {

    private static final VarHandle LONGS = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private static final long ONES = 0x0101010101010101L;
    private static final long HIGHS = 0x8080808080808080L;
    private static final long LT = ONES * '<';
    private static final long GT = ONES * '>';
    private static final long AMP = ONES * '&';
    private static final long SPACE = ONES * 0x20;

    private static final int BLOCK = 64;

    private static final boolean[] SAFE = new boolean[128];

    static {
        for (int c = 0x20; c < 0x80; c++) {
            SAFE[c] = true;
        }
        SAFE['<'] = SAFE['>'] = SAFE['&'] = false;
    }

    @Param({ "16", "64", "512", "4096" })
    public int length;

    private char[] chars;
    private byte[] bytes;
    private String string;

    @Setup
    public void setup() {
        String src = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ";
        chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = src.charAt(i % src.length());
        }
        bytes = new String(chars).getBytes(StandardCharsets.UTF_8);
        string = new String(chars);
        verify();
    }

    /** All three scans must agree on clean input and on a hit placed at every position, for every kind of hit. */
    private void verify() {
        char[] savedChars = chars;
        byte[] savedBytes = bytes;
        check("clean");
        for (char hit : new char[] { '<', '>', '&', '\n', 0x01 }) {
            for (int pos = 0; pos < length; pos++) {
                chars = savedChars.clone();
                chars[pos] = hit;
                bytes = new String(chars).getBytes(StandardCharsets.UTF_8);
                check("hit " + (int) hit + " at " + pos);
            }
        }
        chars = savedChars;
        bytes = savedBytes;
    }

    private void check(String what) {
        int expected = scalarTable();
        if (autoVectorChars() != expected || swarBytes() != expected || swarBytes16() != expected
                || (autoVectorFull() < 0) != (expected < length)) {
            throw new IllegalStateException(what + ": scalar=" + expected + " autoVector=" + autoVectorChars() + " swar=" + swarBytes());
        }
    }

    @Benchmark
    public int scalarTable() {
        char[] t = chars;
        for (int i = 0; i < t.length; i++) {
            char c = t[i];
            if (c < 128 && !SAFE[c]) {
                return i;
            }
        }
        return t.length;
    }

    @Benchmark
    public int autoVectorChars() {
        char[] t = chars;
        int n = t.length;
        int i = 0;
        for (; i + BLOCK <= n; i += BLOCK) {
            int acc = 0;
            for (int j = i; j < i + BLOCK; j++) {
                int c = t[j];
                // Sign bit set iff c < 0x20, c == '<', c == '>' or c == '&' (c is in [0, 0xFFFF]).
                acc |= (c - 0x20) | ((c ^ '<') - 1) | ((c ^ '>') - 1) | ((c ^ '&') - 1);
            }
            if (acc < 0) {
                break;
            }
        }
        for (; i < n; i++) {
            char c = t[i];
            if (c < 128 && !SAFE[c]) {
                return i;
            }
        }
        return n;
    }

    @Benchmark
    public int swarBytes() {
        byte[] b = bytes;
        int n = b.length;
        int i = 0;
        for (; i + 8 <= n; i += 8) {
            long x = (long) LONGS.get(b, i);
            long notX = ~x & HIGHS;
            // Per byte: high bit set where the byte is < 0x20 (ASCII only, UTF-8 continuation bytes excluded by notX)
            // or equals one of the three specials. Borrows only propagate upwards, so the lowest flag is exact.
            long mask = ((x - SPACE) & notX)
                    | (hasZero(x ^ LT)) | (hasZero(x ^ GT)) | (hasZero(x ^ AMP));
            if (mask != 0) {
                return i + (Long.numberOfTrailingZeros(mask) >>> 3);
            }
        }
        for (; i < n; i++) {
            int c = b[i];
            if (c >= 0 && !SAFE[c]) {
                return i;
            }
        }
        return n;
    }

    /**
     * Unrolled SWAR: two longs (16 bytes) per iteration, one combined test; the exact position is resolved only on a
     * hit.
     */
    @Benchmark
    public int swarBytes16() {
        byte[] b = bytes;
        int n = b.length;
        int i = 0;
        for (; i + 16 <= n; i += 16) {
            long m0 = flags((long) LONGS.get(b, i));
            long m1 = flags((long) LONGS.get(b, i + 8));
            if ((m0 | m1) != 0) {
                return m0 != 0 ? i + (Long.numberOfTrailingZeros(m0) >>> 3) : i + 8 + (Long.numberOfTrailingZeros(m1) >>> 3);
            }
        }
        for (; i + 8 <= n; i += 8) {
            long m = flags((long) LONGS.get(b, i));
            if (m != 0) {
                return i + (Long.numberOfTrailingZeros(m) >>> 3);
            }
        }
        for (; i < n; i++) {
            int c = b[i];
            if (c >= 0 && !SAFE[c]) {
                return i;
            }
        }
        return n;
    }

    private static long flags(long x) {
        return ((x - SPACE) & ~x & HIGHS) | hasZero(x ^ LT) | hasZero(x ^ GT) | hasZero(x ^ AMP);
    }

    /**
     * Whole-array branch-free reduction with no early exit at all: the most auto-vectorization-friendly shape. Only
     * answers "is there anything to escape" (negative = yes), which is what the common clean-string fast path needs.
     */
    @Benchmark
    public int autoVectorFull() {
        char[] t = chars;
        int acc = 0;
        for (int j = 0; j < t.length; j++) {
            int c = t[j];
            acc |= (c - 0x20) | ((c ^ '<') - 1) | ((c ^ '>') - 1) | ((c ^ '&') - 1);
        }
        return acc;
    }

    /**
     * JDK intrinsics: {@code String.indexOf(char)} is replaced by HotSpot with a hand-written SIMD stub (SSE4.2/AVX2/
     * AVX-512) on LATIN1 strings. PARTIAL check: covers '<', '>' and '&' only, not control characters, so it measures
     * the ceiling of an intrinsic-based fast path rather than a drop-in replacement.
     */
    @Benchmark
    public boolean intrinsicIndexOfPartial() {
        String s = string;
        return s.indexOf('<') >= 0 || s.indexOf('>') >= 0 || s.indexOf('&') >= 0;
    }

    private static long hasZero(long v) {
        return (v - ONES) & ~v & HIGHS;
    }
}
