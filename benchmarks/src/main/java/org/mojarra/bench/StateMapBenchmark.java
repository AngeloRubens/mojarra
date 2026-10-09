package org.mojarra.bench;

import java.io.Serializable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * {@code ComponentStateHelper} keeps each component's properties in a {@code HashMap<Serializable, Object>} keyed by
 * {@code PropertyKeys} enum constants (plus a second one, the delta map, after the initial state is marked). A typical
 * component holds only a handful of entries. Compares it with a compact array map that compares keys by identity
 * (enum constants are singletons; non-enum keys fall back to equals): no Node objects, no hashing, two small arrays.
 * Run with {@code -prof gc} to see the per-component allocation ({@code gc.alloc.rate.norm}).
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class StateMapBenchmark {

    /** Shape of a generated component's PropertyKeys enum (HtmlInputText has 30). */
    enum PropertyKeys {
        accesskey, alt, autocomplete, dir, disabled, label, lang, maxlength, onblur, onchange, onclick, ondblclick, onfocus, onkeydown,
        onkeypress, onkeyup, onmousedown, onmousemove, onmouseout, onmouseover, onmouseup, onselect, readonly, role, size, style,
        styleClass, tabindex, title, type
    }

    private static final PropertyKeys[] SET = { PropertyKeys.styleClass, PropertyKeys.label, PropertyKeys.maxlength, PropertyKeys.size,
            PropertyKeys.onchange };
    private static final PropertyKeys[] READ = { PropertyKeys.styleClass, PropertyKeys.label, PropertyKeys.maxlength, PropertyKeys.size,
            PropertyKeys.onchange, PropertyKeys.style, PropertyKeys.title, PropertyKeys.disabled, PropertyKeys.readonly,
            PropertyKeys.dir };
    private static final Object[] VALUES = { "ui-inputfield", "Nome", 40, 20, "validate(this)" };

    private Map<Serializable, Object> hashMap;
    private CompactStateMap compact;

    @Setup
    public void setup() {
        hashMap = buildHashMap();
        compact = buildCompact();
        for (PropertyKeys k : READ) {
            if (hashMap.get(k) != compact.get(k)) {
                throw new IllegalStateException(k.name());
            }
        }
    }

    private static Map<Serializable, Object> buildHashMap() {
        Map<Serializable, Object> map = new HashMap<>(8); // as ComponentStateHelper.defaultMap()
        for (int i = 0; i < SET.length; i++) {
            map.put(SET[i], VALUES[i]);
        }
        return map;
    }

    private static CompactStateMap buildCompact() {
        CompactStateMap map = new CompactStateMap();
        for (int i = 0; i < SET.length; i++) {
            map.put(SET[i], VALUES[i]);
        }
        return map;
    }

    @Benchmark
    public Object createHashMap() {
        return buildHashMap();
    }

    @Benchmark
    public Object createCompact() {
        return buildCompact();
    }

    @Benchmark
    public void readHashMap(Blackhole bh) {
        Map<Serializable, Object> map = hashMap;
        for (PropertyKeys k : READ) {
            bh.consume(map.get(k));
        }
    }

    @Benchmark
    public void readCompact(Blackhole bh) {
        CompactStateMap map = compact;
        for (PropertyKeys k : READ) {
            bh.consume(map.get(k));
        }
    }

    /** Prototype: insertion-ordered parallel arrays, identity first, equals only for non-enum keys. */
    static final class CompactStateMap {
        private Object[] keys = new Object[4];
        private Object[] values = new Object[4];
        private int size;

        Object get(Object key) {
            Object[] k = keys;
            for (int i = 0, n = size; i < n; i++) {
                if (k[i] == key) {
                    return values[i];
                }
            }
            if (!(key instanceof Enum)) {
                for (int i = 0, n = size; i < n; i++) {
                    if (key.equals(k[i])) {
                        return values[i];
                    }
                }
            }
            return null;
        }

        Object put(Object key, Object value) {
            for (int i = 0; i < size; i++) {
                if (keys[i] == key || !(key instanceof Enum) && key.equals(keys[i])) {
                    Object old = values[i];
                    values[i] = value;
                    return old;
                }
            }
            if (size == keys.length) {
                keys = Arrays.copyOf(keys, size << 1);
                values = Arrays.copyOf(values, size << 1);
            }
            keys[size] = key;
            values[size++] = value;
            return null;
        }
    }
}
