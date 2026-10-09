/*
 * Copyright (c) 2026 Contributors to Eclipse Foundation.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package com.sun.faces.context;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * The UTF-8 response buffer must produce exactly the bytes of the JDK's UTF-8 encoder (which also replaces unpaired
 * surrogates with '?'), however the text is split across write calls and buffer drains.
 */
class Utf8ResponseOutputWriterTest {

    private static final String[] PIECES = { "a", "<div class=\"x\">", "é", "ÿ", "Ā", "߿", "ࠀ", "€", "あ",
            "￿", "😀", "\uD83D", "\uDE00", "\n", "\u0000", "Lorem ipsum dolor sit amet " };

    @Test
    void matchesJdkEncoder() throws IOException {
        Random random = new Random(7);
        for (int n = 0; n < 3000; n++) {
            StringBuilder text = new StringBuilder();
            int pieces = random.nextInt(10) == 0 ? 2000 : random.nextInt(40);
            for (int i = 0; i < pieces; i++) {
                text.append(PIECES[random.nextInt(PIECES.length)]);
            }
            String string = text.toString();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ExternalContextImpl.Utf8ResponseOutputWriter writer = new ExternalContextImpl.Utf8ResponseOutputWriter(out);
            int i = 0;
            while (i < string.length()) {
                int chunk = Math.min(string.length() - i, 1 + random.nextInt(random.nextBoolean() ? 5 : 12000));
                switch (random.nextInt(3)) {
                    case 0 -> writer.write(string, i, chunk);
                    case 1 -> writer.write(string.toCharArray(), i, chunk);
                    default -> {
                        for (int j = 0; j < chunk; j++) {
                            writer.write(string.charAt(i + j));
                        }
                    }
                }
                i += chunk;
                if (random.nextInt(20) == 0) {
                    writer.drain();
                }
            }
            writer.close();
            assertArrayEquals(string.getBytes(UTF_8), out.toByteArray(), () -> "text of length " + string.length());
        }
    }

    @Test
    void discardDropsBufferedOutput() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExternalContextImpl.Utf8ResponseOutputWriter writer = new ExternalContextImpl.Utf8ResponseOutputWriter(out);
        writer.write("kept");
        writer.drain();
        writer.write("dropped\uD83D");
        writer.discard();
        writer.write("after");
        writer.close();
        assertEquals("keptafter", out.toString(UTF_8));
    }
}
