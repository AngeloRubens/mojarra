import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;

/**
 * Summarizes a JFR recording from the test/perf bench as Markdown, attributing samples to Mojarra code. Streaming
 * replacement of jfr_mojarra.py (which converts the whole recording to JSON and runs out of memory on long runs).
 *
 * <p>
 * For every CPU sample (jdk.ExecutionSample) and allocation sample (jdk.ObjectAllocationSample) the stack is walked
 * from the top: the first Mojarra frame (com.sun.faces / jakarta.faces) is the "entry" the cost is charged to, so time
 * spent in JDK collections, String, EL, Weld... called from Mojarra lands on the Mojarra method that called it.
 * Inclusive counts (each Mojarra method once per stack) are reported too.
 *
 * <p>
 * Usage: java JfrMojarra.java recording.jfr [top_n]
 */
public class JfrMojarra {

    static final class Summary {
        double total;
        final Map<String, Double> top = new HashMap<>();
        final Map<String, Double> entry = new HashMap<>();
        final Map<String, Double> inclusive = new HashMap<>();

        void add(List<RecordedFrame> frames, double weight) {
            if (frames.isEmpty()) {
                return;
            }
            total += weight;
            top.merge(name(frames.get(0)), weight, Double::sum);
            Set<String> seen = new HashSet<>();
            boolean charged = false;
            for (RecordedFrame frame : frames) {
                String name = name(frame);
                if (!isMojarra(name)) {
                    continue;
                }
                if (!charged) {
                    entry.merge(name, weight, Double::sum);
                    charged = true;
                }
                if (seen.add(name)) {
                    inclusive.merge(name, weight, Double::sum);
                }
            }
        }
    }

    static String name(RecordedFrame frame) {
        return frame.getMethod().getType().getName() + "." + frame.getMethod().getName();
    }

    static boolean isMojarra(String name) {
        return name.startsWith("com.sun.faces.") || name.startsWith("jakarta.faces.");
    }

    public static void main(String[] args) throws IOException {
        Path path = Path.of(args[0]);
        int n = args.length > 1 ? Integer.parseInt(args[1]) : 30;
        Summary cpu = new Summary();
        Summary alloc = new Summary();
        try (RecordingFile recording = new RecordingFile(path)) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                String type = event.getEventType().getName();
                if (event.getStackTrace() == null) {
                    continue;
                }
                if (type.equals("jdk.ExecutionSample")) {
                    cpu.add(event.getStackTrace().getFrames(), 1);
                } else if (type.equals("jdk.ObjectAllocationSample")) {
                    alloc.add(event.getStackTrace().getFrames(), event.getLong("weight"));
                }
            }
        }
        System.out.printf("### CPU (%,.0f samples)%n%n", cpu.total);
        table("Top frame (self time, any code)", cpu.top, cpu.total, n, "samples");
        table("Charged to first Mojarra frame", cpu.entry, cpu.total, n, "samples");
        table("Mojarra inclusive", cpu.inclusive, cpu.total, n, "samples");
        System.out.printf("### Allocation (%,.0f MB sampled weight)%n%n", alloc.total / 1e6);
        table("Charged to first Mojarra frame", alloc.entry, alloc.total, n, "bytes");
        table("Mojarra inclusive", alloc.inclusive, alloc.total, n, "bytes");
    }

    static void table(String title, Map<String, Double> counter, double total, int n, String unit) {
        System.out.printf("#### %s%n%n| # | Method | %s | %% |%n|---:|---|---:|---:|%n", title, unit);
        int[] i = { 0 };
        counter.entrySet().stream().sorted(Map.Entry.<String, Double> comparingByValue().reversed()).limit(n)
                .forEach(e -> System.out.printf("| %d | `%s` | %,.0f | %.1f%% |%n", ++i[0], e.getKey(), e.getValue(),
                        100.0 * e.getValue() / total));
        System.out.println();
    }
}
