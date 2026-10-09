package org.mojarra.bench;

import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

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

import jakarta.faces.component.html.HtmlInputText;

/**
 * Cost of reading property-backed component attributes, as renderers do through {@code getAttributes().get(name)},
 * comparing the current cached-{@link Method} path against MethodHandle and LambdaMetafactory accessors.
 * Each op reads 10 different properties so the call sites are as polymorphic as in a renderer loop.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class AttributeAccessBenchmark {

    private static final String[] NAMES = { "styleClass", "style", "title", "onclick", "size", "maxlength", "dir", "lang", "alt",
            "accesskey" };

    private HtmlInputText component;
    private Map<String, Object> attributes;
    private final Map<String, Method> methods = new HashMap<>();
    private final Map<String, MethodHandle> handles = new HashMap<>();
    private final Map<String, Function<Object, Object>> functions = new HashMap<>();

    @Setup
    @SuppressWarnings("unchecked")
    public void setup() throws Throwable {
        component = new HtmlInputText();
        component.setStyleClass("ui-inputfield");
        component.setStyle("width:100%");
        component.setTitle("Name");
        component.setOnclick("doSomething()");
        component.setSize(20);
        component.setMaxlength(40);
        component.setDir("ltr");
        component.setLang("it");
        component.setAlt("alt");
        component.setAccesskey("n");
        attributes = component.getAttributes();

        MethodHandles.Lookup lookup = MethodHandles.lookup();
        for (PropertyDescriptor pd : Introspector.getBeanInfo(HtmlInputText.class).getPropertyDescriptors()) {
            Method read = pd.getReadMethod();
            if (read == null) {
                continue;
            }
            read.setAccessible(true);
            methods.put(pd.getName(), read);
            MethodHandle mh = lookup.unreflect(read);
            handles.put(pd.getName(), mh.asType(MethodType.methodType(Object.class, Object.class)));
            CallSite site = LambdaMetafactory.metafactory(lookup, "apply", MethodType.methodType(Function.class),
                    MethodType.methodType(Object.class, Object.class), mh, mh.type().wrap().changeReturnType(Object.class));
            functions.put(pd.getName(), (Function<Object, Object>) site.getTarget().invokeExact());
        }
        for (String name : NAMES) {
            if (!String.valueOf(attributes.get(name)).equals(String.valueOf(functions.get(name).apply(component)))) {
                throw new IllegalStateException(name);
            }
        }
    }

    @Benchmark
    public void currentAttributesMap(Blackhole bh) {
        for (String name : NAMES) {
            bh.consume(attributes.get(name));
        }
    }

    @Benchmark
    public void cachedMethodInvoke(Blackhole bh) throws Exception {
        for (String name : NAMES) {
            bh.consume(methods.get(name).invoke(component));
        }
    }

    @Benchmark
    public void cachedMethodHandle(Blackhole bh) throws Throwable {
        for (String name : NAMES) {
            bh.consume((Object) handles.get(name).invokeExact((Object) component));
        }
    }

    @Benchmark
    public void lambdaMetafactory(Blackhole bh) {
        for (String name : NAMES) {
            bh.consume(functions.get(name).apply(component));
        }
    }

    @Benchmark
    public void directGetters(Blackhole bh) {
        HtmlInputText c = component;
        bh.consume(c.getStyleClass());
        bh.consume(c.getStyle());
        bh.consume(c.getTitle());
        bh.consume(c.getOnclick());
        bh.consume(c.getSize());
        bh.consume(c.getMaxlength());
        bh.consume(c.getDir());
        bh.consume(c.getLang());
        bh.consume(c.getAlt());
        bh.consume(c.getAccesskey());
    }
}
