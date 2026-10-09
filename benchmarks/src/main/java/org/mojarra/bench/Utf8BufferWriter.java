package org.mojarra.bench;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Prototype of a byte-level response buffer: chars are UTF-8 encoded straight into a growable {@code byte[]} (no
 * intermediate StringBuilder/String, no CharsetEncoder), and pre-encoded markup fragments can be appended with a plain
 * arraycopy via {@link #writeBytes(byte[])}. The buffer can then be handed to {@code ServletOutputStream} as is.
 */
public final class Utf8BufferWriter extends Writer {

    private byte[] buf;
    private int count;

    public Utf8BufferWriter(int initialCapacity) {
        buf = new byte[initialCapacity];
    }

    /** Pre-encodes a constant fragment once; the result is meant to be shared (flyweight). */
    public static byte[] encode(String constant) {
        return constant.getBytes(StandardCharsets.UTF_8);
    }

    public void writeBytes(byte[] bytes) {
        ensure(bytes.length);
        System.arraycopy(bytes, 0, buf, count, bytes.length);
        count += bytes.length;
    }

    @Override
    public void write(int c) {
        ensure(1);
        if (c < 0x80) {
            buf[count++] = (byte) c;
        } else {
            encodeNonAscii((char) c, (char) 0);
        }
    }

    @Override
    public void write(String str) {
        write(str, 0, str.length());
    }

    @Override
    public void write(String str, int off, int len) {
        ensure(len);
        byte[] b = buf;
        int pos = count;
        int end = off + len;
        int i = off;
        // ASCII fast path: one byte per char, capacity already reserved by ensure(len).
        for (; i < end; i++) {
            char c = str.charAt(i);
            if (c >= 0x80) {
                break;
            }
            b[pos++] = (byte) c;
        }
        count = pos;
        for (; i < end; i++) {
            char c = str.charAt(i);
            if (c < 0x80) {
                buf[count++] = (byte) c;
            } else if (encodeNonAscii(c, i + 1 < end ? str.charAt(i + 1) : 0)) {
                i++;
            }
        }
    }

    @Override
    public void write(char[] cbuf, int off, int len) {
        ensure(len);
        byte[] b = buf;
        int pos = count;
        int end = off + len;
        int i = off;
        for (; i < end; i++) {
            char c = cbuf[i];
            if (c >= 0x80) {
                break;
            }
            b[pos++] = (byte) c;
        }
        count = pos;
        for (; i < end; i++) {
            char c = cbuf[i];
            if (c < 0x80) {
                buf[count++] = (byte) c;
            } else if (encodeNonAscii(c, i + 1 < end ? cbuf[i + 1] : 0)) {
                i++;
            }
        }
    }

    /** Encodes one non-ASCII char (capacity already reserved); returns true when {@code next} was consumed as low surrogate. */
    private boolean encodeNonAscii(char c, char next) {
        if (c < 0x800) {
            buf[count++] = (byte) (0xC0 | (c >> 6));
            buf[count++] = (byte) (0x80 | (c & 0x3F));
            return false;
        }
        if (Character.isHighSurrogate(c) && Character.isLowSurrogate(next)) {
            int cp = Character.toCodePoint(c, next);
            buf[count++] = (byte) (0xF0 | (cp >> 18));
            buf[count++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
            buf[count++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
            buf[count++] = (byte) (0x80 | (cp & 0x3F));
            return true;
        }
        if (Character.isSurrogate(c)) {
            buf[count++] = '?'; // unpaired surrogate, same replacement as the JDK UTF-8 encoder
            return false;
        }
        buf[count++] = (byte) (0xE0 | (c >> 12));
        buf[count++] = (byte) (0x80 | ((c >> 6) & 0x3F));
        buf[count++] = (byte) (0x80 | (c & 0x3F));
        return false;
    }

    private void ensure(int extra) {
        // Worst case 3 bytes per char (a surrogate pair is 2 chars -> 4 bytes); callers pass char counts.
        int needed = count + extra * 3;
        if (needed > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(buf.length << 1, needed));
        }
    }

    public void writeTo(OutputStream out) throws IOException {
        out.write(buf, 0, count);
    }

    public int size() {
        return count;
    }

    public void reset() {
        count = 0;
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
}
