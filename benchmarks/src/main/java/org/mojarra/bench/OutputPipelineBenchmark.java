package org.mojarra.bench;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
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

import com.sun.faces.io.FastStringWriter;

/**
 * Renders a 200-row data table (row markup + an escaped text cell + an input with 4 attributes) to UTF-8 bytes,
 * comparing the current char pipeline (StringBuilder buffer, then charset encoding by the container) against a
 * byte-level buffer with and without pre-encoded markup constants.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class OutputPipelineBenchmark {

    private static final int ROWS = 200;

    @Param({ "ascii", "italian" })
    public String content;

    private String[] texts;
    private String[] ids;
    private String[] names;

    private final char[] escapeBuf = new char[128];
    private final FastStringWriter stringWriter = new FastStringWriter(64 * 1024);
    private final ByteSink sink = new ByteSink(64 * 1024);
    private final Utf8BufferWriter utf8 = new Utf8BufferWriter(64 * 1024);

    // Flyweight: shared pre-encoded fragments (per compiled Facelet / per renderer in a real implementation).
    private static final byte[] TR_OPEN = Utf8BufferWriter.encode("<tr class=\"row\"><td id=\"");
    private static final byte[] TD_MID = Utf8BufferWriter.encode("\">");
    private static final byte[] TD_CLOSE_OPEN = Utf8BufferWriter.encode("</td><td><input type=\"text\" name=\"");
    private static final byte[] VALUE_ATTR = Utf8BufferWriter.encode("\" value=\"");
    private static final byte[] CLASS_ATTR = Utf8BufferWriter.encode("\" class=\"ui-inputfield ui-widget\" maxlength=\"40");
    private static final byte[] ROW_CLOSE = Utf8BufferWriter.encode("\"/></td></tr>\n");

    @Setup
    public void setup() throws IOException {
        texts = new String[ROWS];
        ids = new String[ROWS];
        names = new String[ROWS];
        for (int i = 0; i < ROWS; i++) {
            texts[i] = "ascii".equals(content) ? "Customer number " + i + " - Rossi & Sons" : "Cliente n° " + i + " — Società Così & Figli, città di Forlì";
            ids[i] = "form:table:" + i + ":name";
            names[i] = "form:table:" + i + ":input";
        }
        byte[] a = charPipelineToBytes();
        byte[] b = Arrays.copyOf(bytesOf(byteConstants()), utf8.size());
        byte[] c = Arrays.copyOf(bytesOf(byteStrings()), utf8.size());
        if (!Arrays.equals(a, b) || !Arrays.equals(a, c)) {
            throw new IllegalStateException("outputs differ");
        }
    }

    private byte[] bytesOf(int ignored) throws IOException {
        ByteSink s = new ByteSink(utf8.size());
        utf8.writeTo(s);
        return s.toByteArray();
    }

    private byte[] charPipelineToBytes() throws IOException {
        renderChars(stringWriter);
        return stringWriter.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void renderChars(FastStringWriter w) throws IOException {
        w.reset();
        renderWithStrings(w);
    }

    private void renderWithStrings(Writer w) throws IOException {
        for (int i = 0; i < ROWS; i++) {
            w.write("<tr class=\"row\"><td id=\"");
            ProposedHtmlUtils.writeAttribute(w, ids[i], escapeBuf);
            w.write("\">");
            ProposedHtmlUtils.writeText(w, texts[i], escapeBuf);
            w.write("</td><td><input type=\"text\" name=\"");
            ProposedHtmlUtils.writeAttribute(w, names[i], escapeBuf);
            w.write("\" value=\"");
            ProposedHtmlUtils.writeAttribute(w, texts[i], escapeBuf);
            w.write("\" class=\"ui-inputfield ui-widget\" maxlength=\"40");
            w.write("\"/></td></tr>\n");
        }
    }

    /** Current shape: StringBuilder buffer, then the whole buffer is String-ified and encoded. */
    @Benchmark
    public int charStringBuilderGetBytes() throws IOException {
        renderChars(stringWriter);
        return stringWriter.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    /** Current shape, encoding through a CharsetEncoder-backed writer as a servlet container does. */
    @Benchmark
    public int charStringBuilderContainerEncoder() throws IOException {
        renderChars(stringWriter);
        sink.reset();
        Writer container = new OutputStreamWriter(sink, StandardCharsets.UTF_8);
        StringBuilder sb = stringWriter.getBuffer();
        container.append(sb);
        container.flush();
        return sink.size();
    }

    /** Byte-level buffer, markup constants still passed as String. */
    @Benchmark
    public int byteStrings() throws IOException {
        utf8.reset();
        renderWithStrings(utf8);
        return utf8.size();
    }

    /** Byte-level buffer with pre-encoded (flyweight) markup constants. */
    @Benchmark
    public int byteConstants() throws IOException {
        Utf8BufferWriter w = utf8;
        w.reset();
        for (int i = 0; i < ROWS; i++) {
            w.writeBytes(TR_OPEN);
            ProposedHtmlUtils.writeAttribute(w, ids[i], escapeBuf);
            w.writeBytes(TD_MID);
            ProposedHtmlUtils.writeText(w, texts[i], escapeBuf);
            w.writeBytes(TD_CLOSE_OPEN);
            ProposedHtmlUtils.writeAttribute(w, names[i], escapeBuf);
            w.writeBytes(VALUE_ATTR);
            ProposedHtmlUtils.writeAttribute(w, texts[i], escapeBuf);
            w.writeBytes(CLASS_ATTR);
            w.writeBytes(ROW_CLOSE);
        }
        return w.size();
    }

    static final class ByteSink extends OutputStream {
        private byte[] buf;
        private int count;

        ByteSink(int capacity) {
            buf = new byte[capacity];
        }

        @Override
        public void write(int b) {
            if (count == buf.length) {
                buf = Arrays.copyOf(buf, buf.length << 1);
            }
            buf[count++] = (byte) b;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            if (count + len > buf.length) {
                buf = Arrays.copyOf(buf, Math.max(buf.length << 1, count + len));
            }
            System.arraycopy(b, off, buf, count, len);
            count += len;
        }

        int size() {
            return count;
        }

        void reset() {
            count = 0;
        }

        byte[] toByteArray() {
            return Arrays.copyOf(buf, count);
        }
    }
}
