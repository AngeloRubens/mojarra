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

package com.sun.faces.util;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;

import java.util.logging.Logger;

/**
 * Finds the first character of a char range that may need HTML/XML escaping. Implementations are allowed to be
 * conservative (report a character that turns out to be written unchanged), never the opposite: {@link HtmlUtils}
 * resumes its exact loop from the reported index.
 *
 * <p>
 * The only implementation is {@code VectorHtmlEscapeScanner}, built on the incubating Vector API
 * ({@code jdk.incubator.vector}) and compiled separately for Java 21+. It is used only when the JVM was started with
 * {@code --add-modules jdk.incubator.vector}, the class loads, and it passes a self-test against the scalar
 * definition below; otherwise {@link HtmlUtils} falls back to its non-Vector scan. The system property
 * {@code com.sun.faces.disableVectorEscaping=true} turns it off.
 */
interface HtmlEscapeScanner {

    String DISABLE_PROPERTY = "com.sun.faces.disableVectorEscaping";
    String VECTOR_MODULE = "jdk.incubator.vector";
    String VECTOR_IMPLEMENTATION = "com.sun.faces.util.VectorHtmlEscapeScanner";

    /**
     * Returns the index in [from, to) of the first character that may need handling, or {@code to}.
     *
     * @param text the characters
     * @param from first index to scan
     * @param to end index (exclusive)
     * @param attribute whether {@code "} needs handling (attribute values)
     * @param nonAsciiSensitive whether characters from 0x80 up may need handling (ISO or Unicode escaping enabled)
     * @param forXml whether characters from 0xD800 up may need handling (XML output: surrogate pairs, U+FFFE/U+FFFF)
     */
    int scan(char[] text, int from, int to, boolean attribute, boolean nonAsciiSensitive, boolean forXml);

    /**
     * The scalar reference definition every implementation must agree with on the characters it does not flag.
     */
    static boolean mayNeedHandling(char ch, boolean attribute, boolean nonAsciiSensitive, boolean forXml) {
        return ch < 0x20 || ch == '<' || ch == '>' || ch == '&' || attribute && ch == '"' || nonAsciiSensitive && ch >= 0x80
                || forXml && ch >= 0xD800;
    }

    /**
     * Loads the Vector API scanner when available and correct, otherwise returns {@code null}.
     */
    static HtmlEscapeScanner loadVectorScanner() {
        Logger logger = FacesLogger.UTIL.getLogger();
        if (Boolean.getBoolean(DISABLE_PROPERTY)) {
            logger.log(FINE, "Vector API escaping disabled by system property " + DISABLE_PROPERTY);
            return null;
        }
        try {
            if (ModuleLayer.boot().findModule(VECTOR_MODULE).isEmpty()) {
                logger.log(FINE, "Vector API escaping not used: module " + VECTOR_MODULE + " not present (start the JVM with --add-modules "
                        + VECTOR_MODULE + " to enable it)");
                return null;
            }
            HtmlEscapeScanner scanner = (HtmlEscapeScanner) Class.forName(VECTOR_IMPLEMENTATION, true, HtmlEscapeScanner.class.getClassLoader())
                    .getDeclaredConstructor().newInstance();
            if (!selfTest(scanner)) {
                logger.log(INFO, "Vector API escaping not used: " + VECTOR_IMPLEMENTATION + " failed its self-test");
                return null;
            }
            logger.log(FINE, "Vector API escaping enabled");
            return scanner;
        } catch (Throwable notUsable) {
            // Absent class (built without Java 21), incompatible incubator API, OSGi not importing the package, ...
            logger.log(FINE, "Vector API escaping not used: " + notUsable);
            return null;
        }
    }

    /**
     * Checks the scanner against {@link #mayNeedHandling} for every flag combination, on inputs of several lengths with
     * a single interesting character at every position (including the tail beyond the last full vector).
     */
    private static boolean selfTest(HtmlEscapeScanner scanner) {
        char[] probes = { '<', '>', '&', '"', '\n', 0x01, 0x7F, 0xE9, 0x3042, 0xD83D, 0xFFFE };
        for (int length : new int[] { 1, 15, 16, 33, 70 }) {
            char[] text = new char[length];
            for (int flags = 0; flags < 8; flags++) {
                boolean attribute = (flags & 1) != 0;
                boolean nonAscii = (flags & 2) != 0;
                boolean xml = (flags & 4) != 0;
                java.util.Arrays.fill(text, 'a');
                if (scanner.scan(text, 0, length, attribute, nonAscii, xml) != length) {
                    return false;
                }
                for (char probe : probes) {
                    boolean flagged = mayNeedHandling(probe, attribute, nonAscii, xml);
                    for (int pos = 0; pos < length; pos++) {
                        java.util.Arrays.fill(text, 'a');
                        text[pos] = probe;
                        int found = scanner.scan(text, 0, length, attribute, nonAscii, xml);
                        // Must stop at the probe when the reference flags it; may stop there when it does not.
                        if (flagged ? found != pos : found != length && found != pos) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }
}
