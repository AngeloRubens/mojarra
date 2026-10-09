package org.mojarra.bench;

import java.util.Arrays;
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

import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Finding the first character that needs HTML escaping: scalar lookup-table scan vs a Vector API (SIMD) scan.
 * Requires {@code --add-modules jdk.incubator.vector}, which is exactly why it is not directly usable in Mojarra
 * (a Jakarta EE library cannot require an incubator module); this benchmark only quantifies what SIMD would buy.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = "--add-modules=jdk.incubator.vector")
public class SimdScanBenchmark {

    private static final VectorSpecies<Short> SPECIES = ShortVector.SPECIES_PREFERRED;
    private static final boolean[] SAFE = new boolean[128];

    static {
        for (int c = 0x20; c < 0x80; c++) {
            SAFE[c] = true;
        }
        SAFE['<'] = SAFE['>'] = SAFE['&'] = false;
    }

    @Param({ "16", "64", "512", "4096" })
    public int length;

    private char[] text;

    @Setup
    public void setup() {
        text = new char[length];
        String src = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ";
        for (int i = 0; i < length; i++) {
            text[i] = src.charAt(i % src.length());
        }
        if (scalar() != vector() || scalar() != length) {
            throw new IllegalStateException();
        }
        char[] probe = Arrays.copyOf(text, length);
        probe[length - 1] = '&';
        char[] saved = text;
        text = probe;
        if (scalar() != vector()) {
            throw new IllegalStateException("mismatch");
        }
        text = saved;
    }

    @Benchmark
    public int scalar() {
        char[] t = text;
        for (int i = 0; i < t.length; i++) {
            char c = t[i];
            if (c < 128 && !SAFE[c]) {
                return i;
            }
        }
        return t.length;
    }

    @Benchmark
    public int vector() {
        char[] t = text;
        int i = 0;
        int bound = SPECIES.loopBound(t.length);
        for (; i < bound; i += SPECIES.length()) {
            ShortVector v = ShortVector.fromCharArray(SPECIES, t, i);
            VectorMask<Short> m = v.compare(VectorOperators.UNSIGNED_LT, (short) 0x20)
                    .or(v.compare(VectorOperators.EQ, (short) '<'))
                    .or(v.compare(VectorOperators.EQ, (short) '>'))
                    .or(v.compare(VectorOperators.EQ, (short) '&'));
            if (m.anyTrue()) {
                return i + m.firstTrue();
            }
        }
        for (; i < t.length; i++) {
            char c = t[i];
            if (c < 128 && !SAFE[c]) {
                return i;
            }
        }
        return t.length;
    }
}
