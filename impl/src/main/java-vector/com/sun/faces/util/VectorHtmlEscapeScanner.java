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

import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * {@link HtmlEscapeScanner} on the incubating Vector API: compares a full SIMD register of chars (8 with SSE, 16 with
 * AVX2, 32 with AVX-512) per step. Compiled separately for Java 21+ (src/main/java-vector) and only ever loaded
 * reflectively by {@link HtmlEscapeScanner#loadVectorScanner()}, so the rest of Mojarra keeps running on Java 17
 * without the incubator module.
 */
final class VectorHtmlEscapeScanner implements HtmlEscapeScanner {

    private static final VectorSpecies<Short> SPECIES = ShortVector.SPECIES_PREFERRED;

    @Override
    public int scan(char[] text, int from, int to, boolean attribute, boolean nonAsciiSensitive, boolean forXml) {
        int i = from;
        int step = SPECIES.length();
        for (int bound = to - step; i <= bound; i += step) {
            ShortVector v = ShortVector.fromCharArray(SPECIES, text, i);
            VectorMask<Short> m = v.compare(VectorOperators.UNSIGNED_LT, (short) 0x20)
                    .or(v.compare(VectorOperators.EQ, (short) '<'))
                    .or(v.compare(VectorOperators.EQ, (short) '>'))
                    .or(v.compare(VectorOperators.EQ, (short) '&'));
            if (attribute) {
                m = m.or(v.compare(VectorOperators.EQ, (short) '"'));
            }
            if (nonAsciiSensitive) {
                m = m.or(v.compare(VectorOperators.UNSIGNED_GE, (short) 0x80));
            } else if (forXml) {
                m = m.or(v.compare(VectorOperators.UNSIGNED_GE, (short) 0xD800));
            }
            if (m.anyTrue()) {
                return i + m.firstTrue();
            }
        }
        for (; i < to; i++) {
            if (HtmlEscapeScanner.mayNeedHandling(text[i], attribute, nonAsciiSensitive, forXml)) {
                return i;
            }
        }
        return to;
    }
}
