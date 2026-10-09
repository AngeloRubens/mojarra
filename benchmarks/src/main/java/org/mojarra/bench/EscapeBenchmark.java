package org.mojarra.bench;

import java.io.IOException;
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

import com.sun.faces.io.FastStringWriter;
import com.sun.faces.util.HtmlUtils;

/**
 * Current {@link HtmlUtils} escaping vs {@link ProposedHtmlUtils}, writing into the same StringBuilder-backed writer
 * that {@code WriteBehindStateWriter} buffers the rendered view into.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class EscapeBenchmark {

    @Param({ "id", "css", "amp", "multiline", "italian", "cjk", "long" })
    public String input;

    private String text;
    private final char[] buf = new char[128];
    private final FastStringWriter out = new FastStringWriter(4096);

    @Setup
    public void setup() {
        text = switch (input) {
            case "id" -> "mainForm:customerTable:12:inputName";
            case "css" -> "ui-inputfield ui-widget ui-state-default ui-corner-all";
            case "amp" -> "Terms & Conditions apply, see <below>";
            case "multiline" -> "Line one of the description\nLine two of the description\nLine three\n";
            case "italian" -> "Perché è già più facile così: la città e l'università hanno già aperto.";
            case "cjk" -> "日本語のテキストはすべての文字が非ASCIIです。こんにちは世界";
            case "long" -> "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ".repeat(8);
            default -> throw new IllegalArgumentException(input);
        };
        try {
            check(textCurrent(), "text", () -> textProposed());
            check(attributeCurrent(), "attribute", () -> attributeProposed());
            check(textCurrent(), "textNoCopy", () -> textProposedNoCopy());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private interface Run {
        int run() throws IOException;
    }

    private void check(int ignored, String kind, Run proposed) throws IOException {
        String expected = out.toString();
        proposed.run();
        if (!expected.equals(out.toString())) {
            throw new IllegalStateException(kind + " output differs for " + input + ": [" + expected + "] vs [" + out + "]");
        }
    }

    @Benchmark
    public int textCurrent() throws IOException {
        out.reset();
        HtmlUtils.writeText(out, false, false, text, buf, false);
        return out.getBuffer().length();
    }

    @Benchmark
    public int textProposed() throws IOException {
        out.reset();
        ProposedHtmlUtils.writeText(out, text, buf);
        return out.getBuffer().length();
    }

    @Benchmark
    public int textProposedNoCopy() throws IOException {
        out.reset();
        ProposedHtmlUtils.writeTextNoCopy(out, text);
        return out.getBuffer().length();
    }

    @Benchmark
    public int attributeCurrent() throws IOException {
        out.reset();
        HtmlUtils.writeAttribute(out, false, false, text, buf, false, false);
        return out.getBuffer().length();
    }

    @Benchmark
    public int attributeProposed() throws IOException {
        out.reset();
        ProposedHtmlUtils.writeAttribute(out, text, buf);
        return out.getBuffer().length();
    }
}
