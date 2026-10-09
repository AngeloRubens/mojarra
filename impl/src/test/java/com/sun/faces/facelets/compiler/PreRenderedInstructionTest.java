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

package com.sun.faces.facelets.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.sun.faces.config.FaceletsConfiguration;
import com.sun.faces.config.WebConfiguration;
import com.sun.faces.io.FastStringWriter;
import com.sun.faces.renderkit.html_basic.HtmlResponseWriter;

import jakarta.faces.context.FacesContext;
import jakarta.faces.context.ResponseWriter;

/**
 * Pre-rendered literal markup must be byte-for-byte what the individual instructions write, on first use (captured
 * through a writer clone) and on reuse, for each writer configuration, and must leave the writer in the same state.
 */
class PreRenderedInstructionTest {

    private static Instruction[] card() {
        return new Instruction[] {
                new StartElementInstruction("div"),
                new LiteralAttributeInstruction("class", "card \"x\" & <y>"),
                new LiteralAttributeInstruction("id", "main"),
                new StartElementInstruction("span"),
                new LiteralTextInstruction("Perché è già <b> & più"),
                new EndElementInstruction("span"),
                new StartElementInstruction("br"),
                new EndElementInstruction("br"),
                new LiteralTextInstruction("日本語 😀 tail"),
                new EndElementInstruction("div"),
                // Unbalanced tail: an element left open for a following component, as in a split template.
                new StartElementInstruction("p"),
                new LiteralAttributeInstruction("class", "open"),
        };
    }

    @Test
    void coalescesOnlyBalancedRuns() {
        Instruction[] coalesced = PreRenderedInstruction.coalesce(card());
        assertEquals(3, coalesced.length);
        assertTrue(coalesced[0] instanceof PreRenderedInstruction);
        assertTrue(coalesced[1] instanceof StartElementInstruction);
        assertTrue(coalesced[2] instanceof LiteralAttributeInstruction);
    }

    @Test
    void leavesScriptStyleHeadBodyAndLeadingEndElementsAlone() {
        Instruction[] script = { new StartElementInstruction("script"), new LiteralTextInstruction("var a = 1 < 2;"), new EndElementInstruction("script") };
        assertSame(script, PreRenderedInstruction.coalesce(script));
        Instruction[] body = { new StartElementInstruction("body"), new LiteralTextInstruction("x"), new EndElementInstruction("body") };
        Instruction[] coalescedBody = PreRenderedInstruction.coalesce(body);
        assertTrue(coalescedBody[0] instanceof StartElementInstruction);
        assertTrue(coalescedBody[coalescedBody.length - 1] instanceof EndElementInstruction);
        Instruction[] closing = { new EndElementInstruction("div"), new StartElementInstruction("i"), new EndElementInstruction("i") };
        Instruction[] coalescedClosing = PreRenderedInstruction.coalesce(closing);
        assertTrue(coalescedClosing[0] instanceof EndElementInstruction);
        assertTrue(coalescedClosing[1] instanceof PreRenderedInstruction);
    }

    @Test
    void sameOutputAsInstructions() throws IOException {
        for (boolean partial : new boolean[] { false, true }) {
            for (boolean escapeInlineText : new boolean[] { true, false }) {
                for (WebConfiguration.DisableUnicodeEscaping escaping : WebConfiguration.DisableUnicodeEscaping.values()) {
                    for (boolean openTagBefore : new boolean[] { false, true }) {
                        String expected = render(card(), partial, escapeInlineText, escaping, openTagBefore, 2);
                        // Twice through the same instructions: first captures, second reuses the captured markup.
                        String actual = render(PreRenderedInstruction.coalesce(card()), partial, escapeInlineText, escaping, openTagBefore, 2);
                        assertEquals(expected, actual,
                                "partial=" + partial + " escapeInlineText=" + escapeInlineText + " escaping=" + escaping + " open=" + openTagBefore);
                    }
                }
            }
        }
    }

    /**
     * Writes "prefix|" then the instructions {@code times} times and closes everything, returning the output.
     */
    private static String render(Instruction[] instructions, boolean partial, boolean escapeInlineText, WebConfiguration.DisableUnicodeEscaping escaping,
            boolean openTagBefore, int times) throws IOException {
        FastStringWriter out = new FastStringWriter();
        HtmlResponseWriter writer = new HtmlResponseWriter(out, "text/html", "ISO-8859-1", false, false, escaping, partial);
        FacesContext context = context(writer, escapeInlineText);

        writer.write("prefix|");
        if (openTagBefore) {
            writer.startElement("section", null);
            writer.writeAttribute("data-x", "1", null);
        }
        for (int t = 0; t < times; t++) {
            for (Instruction instruction : instructions) {
                instruction.write(context);
            }
            writer.endElement("p");
        }
        if (openTagBefore) {
            writer.endElement("section");
        }
        writer.flush();
        return out.toString();
    }

    private static FacesContext context(ResponseWriter writer, boolean escapeInlineText) {
        FacesContext context = mock(FacesContext.class);
        Map<Object, Object> attributes = new HashMap<>();
        FaceletsConfiguration configuration = mock(FaceletsConfiguration.class);
        when(configuration.isEscapeInlineText(any())).thenReturn(escapeInlineText);
        attributes.put(FaceletsConfiguration.FACELETS_CONFIGURATION_ATTRIBUTE_NAME, configuration);
        when(context.getAttributes()).thenReturn(attributes);
        ResponseWriter[] current = { writer };
        when(context.getResponseWriter()).thenAnswer(invocation -> current[0]);
        doAnswer(invocation -> {
            current[0] = invocation.getArgument(0);
            return null;
        }).when(context).setResponseWriter(any());
        return context;
    }
}
