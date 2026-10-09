package org.mojarra.bench;

import java.io.IOException;
import java.io.Writer;

/**
 * Prototype of a proposed {@code HtmlUtils.writeText}/{@code writeAttribute} replacement, kept output-compatible with the
 * current implementation for the HTML (non-XML) path exercised by the benchmarks.
 *
 * <p>Differences with the current implementation:
 * <ul>
 * <li>A 128-entry lookup table replaces the chain of range/equality checks (flyweight table shared by all callers).</li>
 * <li>Characters that are written unchanged (TAB, LF, CR, 0x7F-0x9F, and, when neither ISO nor Unicode escaping is
 * active, everything above 0x9F) no longer break the current safe run. In the current code every such character costs a
 * run flush plus a single-char {@code write(int)}: a CJK or accented text, or a multi-line text, degenerates into one
 * writer call per character.</li>
 * <li>String input is scanned in place first; when nothing needs escaping (the overwhelmingly common case for ids, CSS
 * classes, URLs and most text) the String is handed to {@code Writer.write(String)} as is, skipping the copy into the
 * char buffer. With a StringBuilder-backed writer this keeps a LATIN1 compact string a byte-for-byte arraycopy instead of
 * a char[] to byte[] compression.</li>
 * </ul>
 */
public final class ProposedHtmlUtils {

    private static final char[] AMP_CHARS = "&amp;".toCharArray();
    private static final char[] QUOT_CHARS = "&quot;".toCharArray();
    private static final char[] GT_CHARS = "&gt;".toCharArray();
    private static final char[] LT_CHARS = "&lt;".toCharArray();

    // true = written unchanged. Index = char < 128.
    private static final boolean[] TEXT_SAFE = new boolean[128];
    private static final boolean[] ATTR_SAFE = new boolean[128];

    static {
        for (int c = 0x20; c < 0x80; c++) {
            TEXT_SAFE[c] = true;
        }
        TEXT_SAFE['\t'] = TEXT_SAFE['\n'] = TEXT_SAFE['\r'] = true;
        TEXT_SAFE['<'] = TEXT_SAFE['>'] = TEXT_SAFE['&'] = false;
        System.arraycopy(TEXT_SAFE, 0, ATTR_SAFE, 0, 128);
        ATTR_SAFE['"'] = false;
        // 's' is routed to the slow path so the "script:" guard keeps working.
        ATTR_SAFE['s'] = false;
    }

    private ProposedHtmlUtils() {
    }

    /** HTML text, no ISO/Unicode escaping (i.e. a UTF-8 response, the default). */
    public static void writeText(Writer out, String text, char[] buf) throws IOException {
        int len = text.length();
        int i = 0;
        for (; i < len; i++) {
            char ch = text.charAt(i);
            if (ch < 128 && !TEXT_SAFE[ch]) {
                break;
            }
        }
        if (i == len) {
            out.write(text);
            return;
        }
        char[] target = len > buf.length ? new char[len] : buf;
        text.getChars(0, len, target, 0);
        int runStart = 0;
        for (; i < len; i++) {
            char ch = target[i];
            if (ch >= 128 || TEXT_SAFE[ch]) {
                continue;
            }
            if (i > runStart) {
                out.write(target, runStart, i - runStart);
            }
            runStart = i + 1;
            switch (ch) {
                case '<' -> out.write(LT_CHARS);
                case '>' -> out.write(GT_CHARS);
                case '&' -> out.write(AMP_CHARS);
                case 0x0C -> out.write(ch);
                default -> { } // other C0 controls are dropped
            }
        }
        if (runStart < len) {
            out.write(target, runStart, len - runStart);
        }
    }

    /**
     * Variant of {@link #writeText(Writer, String, char[])} that never copies the String: safe runs are handed to
     * {@code Writer.write(String, int, int)}, which a StringBuilder-backed writer appends with an arraycopy.
     */
    public static void writeTextNoCopy(Writer out, String text) throws IOException {
        int len = text.length();
        int runStart = 0;
        for (int i = 0; i < len; i++) {
            char ch = text.charAt(i);
            if (ch >= 128 || TEXT_SAFE[ch]) {
                continue;
            }
            if (i > runStart) {
                out.write(text, runStart, i - runStart);
            }
            runStart = i + 1;
            switch (ch) {
                case '<' -> out.write(LT_CHARS);
                case '>' -> out.write(GT_CHARS);
                case '&' -> out.write(AMP_CHARS);
                case 0x0C -> out.write(ch);
                default -> { }
            }
        }
        if (runStart == 0) {
            out.write(text);
        } else if (runStart < len) {
            out.write(text, runStart, len - runStart);
        }
    }

    /** HTML attribute value, no ISO/Unicode escaping, script: guard enabled. */
    public static void writeAttribute(Writer out, String text, char[] buf) throws IOException {
        int len = text.length();
        int i = 0;
        for (; i < len; i++) {
            char ch = text.charAt(i);
            if (ch < 128 && !ATTR_SAFE[ch]) {
                if (ch == 's' && !text.startsWith("script:", i)) {
                    continue;
                }
                break;
            }
        }
        if (i == len) {
            out.write(text);
            return;
        }
        char[] target = len > buf.length ? new char[len] : buf;
        text.getChars(0, len, target, 0);
        int runStart = 0;
        for (; i < len; i++) {
            char ch = target[i];
            if (ch >= 128 || ATTR_SAFE[ch]) {
                continue;
            }
            if (ch == 's') {
                if (i + 6 < len && target[i + 1] == 'c' && target[i + 2] == 'r' && target[i + 3] == 'i' && target[i + 4] == 'p'
                        && target[i + 5] == 't' && target[i + 6] == ':') {
                    return;
                }
                continue;
            }
            if (i > runStart) {
                out.write(target, runStart, i - runStart);
            }
            runStart = i + 1;
            switch (ch) {
                case '<' -> out.write(LT_CHARS);
                case '>' -> out.write(GT_CHARS);
                case '"' -> out.write(QUOT_CHARS);
                case '&' -> {
                    if (i + 1 < len && target[i + 1] == '{') {
                        out.write('&');
                    } else {
                        out.write(AMP_CHARS);
                    }
                }
                case 0x0C -> out.write(ch);
                default -> { }
            }
        }
        if (runStart < len) {
            out.write(target, runStart, len - runStart);
        }
    }
}
