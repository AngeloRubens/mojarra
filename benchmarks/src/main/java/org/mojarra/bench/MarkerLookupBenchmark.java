package org.mojarra.bench;

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
 * {@code UIComponentBase.markerGet} runs ahead of every {@code getAttributes().get(name)}: six {@code String.equals}
 * against the framework marker keys before the real property lookup. Compares the current chain with a string
 * {@code switch} and with a length gate (every marker key is longer than any standard component property name).
 * Each op looks up 10 typical property names (all misses, the hot case) and 2 marker keys (hits).
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class MarkerLookupBenchmark {

    // Same values as ComponentSupport / FacetHandler / RIConstants.
    static final String MARK_CREATED = "com.sun.faces.facelets.MARK_ID";
    static final String KEY = "facelets.FACET_NAME";
    static final String REMOVED_CHILDREN = "com.sun.faces.facelets.REMOVED_CHILDREN";
    static final String DYNAMIC_COMPONENT = "com.sun.faces.DynamicComponent";
    static final String MARK_DELETED = "com.sun.faces.facelets.MARK_DELETED";
    static final String MARK_CHILDREN_MODIFIED = "com.sun.faces.facelets.MARK_CHILDREN_MODIFIED";

    static final int MIN_MARKER_LENGTH = Math.min(Math.min(Math.min(MARK_CREATED.length(), KEY.length()), Math.min(REMOVED_CHILDREN.length(),
            DYNAMIC_COMPONENT.length())), Math.min(MARK_DELETED.length(), MARK_CHILDREN_MODIFIED.length()));

    static final Object NOT_MARKER = new Object();

    private final String markCreated = "j_id12";
    private final String facetName = null;
    private final Object removedChildren = null;
    private final Object dynamicComponent = null;
    private final boolean markDeleted = false;
    private final boolean markChildrenModified = false;

    private String[] keys;

    @Setup
    public void setup() {
        // new String(...) so identity checks cannot short-circuit, as with keys coming from EL or user code.
        String[] names = { "styleClass", "style", "title", "onclick", "value", "disabled", "readonly", "size", "maxlength", "label",
                MARK_CREATED, KEY };
        keys = new String[names.length];
        for (int i = 0; i < names.length; i++) {
            keys[i] = new String(names[i]);
        }
        for (String k : keys) {
            if (current(k) != switched(k) || current(k) != lengthGated(k)) {
                throw new IllegalStateException(k);
            }
        }
    }

    Object current(Object key) {
        if (MARK_CREATED.equals(key)) {
            return markCreated;
        }
        if (KEY.equals(key)) {
            return facetName;
        }
        if (REMOVED_CHILDREN.equals(key)) {
            return removedChildren;
        }
        if (DYNAMIC_COMPONENT.equals(key)) {
            return dynamicComponent;
        }
        if (MARK_DELETED.equals(key)) {
            return markDeleted ? Boolean.TRUE : null;
        }
        if (MARK_CHILDREN_MODIFIED.equals(key)) {
            return markChildrenModified ? Boolean.TRUE : null;
        }
        return NOT_MARKER;
    }

    Object switched(Object key) {
        if (!(key instanceof String s)) {
            return NOT_MARKER;
        }
        return switch (s) {
            case MARK_CREATED -> markCreated;
            case KEY -> facetName;
            case REMOVED_CHILDREN -> removedChildren;
            case DYNAMIC_COMPONENT -> dynamicComponent;
            case MARK_DELETED -> markDeleted ? Boolean.TRUE : null;
            case MARK_CHILDREN_MODIFIED -> markChildrenModified ? Boolean.TRUE : null;
            default -> NOT_MARKER;
        };
    }

    Object lengthGated(Object key) {
        if (!(key instanceof String s) || s.length() < MIN_MARKER_LENGTH) {
            return NOT_MARKER;
        }
        return current(key);
    }

    @Benchmark
    public void currentEqualsChain(Blackhole bh) {
        for (String k : keys) {
            bh.consume(current(k));
        }
    }

    @Benchmark
    public void stringSwitch(Blackhole bh) {
        for (String k : keys) {
            bh.consume(switched(k));
        }
    }

    @Benchmark
    public void lengthGate(Blackhole bh) {
        for (String k : keys) {
            bh.consume(lengthGated(k));
        }
    }
}
