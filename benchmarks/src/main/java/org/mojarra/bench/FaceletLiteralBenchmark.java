package org.mojarra.bench;

import java.io.IOException;
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

import com.sun.faces.config.WebConfiguration;
import com.sun.faces.io.FastStringWriter;
import com.sun.faces.renderkit.html_basic.HtmlResponseWriter;

/**
 * Static template markup in a Facelet ({@code <div class="card" id="main"><span class="title">Titolo</span>...}) is
 * compiled into StartElement/LiteralAttribute/LiteralText/EndElement instructions that call the real
 * {@link HtmlResponseWriter} on every request, re-escaping the same constant strings each time. Compares that with
 * writing a fragment pre-rendered once at compile time (shared by all requests, flyweight), as chars and as UTF-8 bytes.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class FaceletLiteralBenchmark {

    private static final String[] ITEMS = { "Spedizione gratuita", "Reso entro 30 giorni", "Assistenza 24/7" };

    private final FastStringWriter out = new FastStringWriter(8192);
    private final Utf8BufferWriter bytesOut = new Utf8BufferWriter(8192);
    private HtmlResponseWriter writer;

    private String preRendered;
    private byte[] preEncoded;

    @Setup
    public void setup() throws IOException {
        writer = new HtmlResponseWriter(out, "text/html", "UTF-8", false, false, WebConfiguration.DisableUnicodeEscaping.Auto, false);
        // The pre-rendered fragment is produced by the real writer itself, so it is identical by construction.
        out.reset();
        instructions();
        writer.flush();
        preRendered = out.toString();
        preEncoded = Utf8BufferWriter.encode(preRendered);
    }

    /** What the compiled Facelet instructions do today for one static card block. */
    private void instructions() throws IOException {
        HtmlResponseWriter w = writer;
        w.startElement("div", null);
        w.writeAttribute("class", "card shadow-sm", null);
        w.writeAttribute("id", "main", null);
        w.startElement("span", null);
        w.writeAttribute("class", "title", null);
        w.writeText("Riepilogo dell'ordine", null);
        w.endElement("span");
        w.startElement("p", null);
        w.writeAttribute("class", "description text-muted", null);
        w.writeText("Controlla i dati prima di procedere al pagamento: potrai modificarli in seguito.", null);
        w.endElement("p");
        w.startElement("ul", null);
        w.writeAttribute("class", "list-unstyled", null);
        for (String item : ITEMS) {
            w.startElement("li", null);
            w.writeText(item, null);
            w.endElement("li");
        }
        w.endElement("ul");
        w.endElement("div");
    }

    @Benchmark
    public int currentInstructions() throws IOException {
        out.reset();
        instructions();
        return out.getBuffer().length();
    }

    @Benchmark
    public int preRenderedChars() throws IOException {
        out.reset();
        writer.write(preRendered);
        return out.getBuffer().length();
    }

    @Benchmark
    public int preEncodedBytes() {
        bytesOut.reset();
        bytesOut.writeBytes(preEncoded);
        return bytesOut.size();
    }
}
