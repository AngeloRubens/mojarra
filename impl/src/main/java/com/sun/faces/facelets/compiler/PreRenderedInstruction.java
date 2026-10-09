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

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import com.sun.faces.config.FaceletsConfiguration;
import com.sun.faces.io.FastStringWriter;
import com.sun.faces.renderkit.html_basic.HtmlResponseWriter;

import jakarta.el.ELContext;
import jakarta.el.ExpressionFactory;
import jakarta.faces.context.FacesContext;
import jakarta.faces.context.ResponseWriter;

/**
 * A self-contained block of literal template markup (elements, attributes and text that contain no EL) written as one
 * string, rendered once per writer configuration and shared by every request through the compiled Facelet (a
 * flyweight), instead of re-running startElement/writeAttribute/writeText/endElement and re-escaping the same constant
 * strings on every request.
 *
 * <p>
 * The block is "balanced": every element it opens it also closes, so the writer's element stack and open start tag
 * are left exactly as the individual instructions would leave them. The pre-rendered form is used only when the
 * response writer is Mojarra's own {@link HtmlResponseWriter} (directly or through its partial response writers) in a
 * state where its output depends only on its configuration (see {@link HtmlResponseWriter#getLiteralMarkupKey()});
 * otherwise, e.g. with a third-party {@link ResponseWriter} wrapper, the original instructions run as before. Enabled
 * with the {@code com.sun.faces.preRenderLiteralMarkup} context parameter.
 */
final class PreRenderedInstruction implements Instruction {

    /** Elements whose start or end has side effects beyond writing markup, or that switch the writer's mode. */
    private static final List<String> EXCLUDED_ELEMENTS = List.of("script", "style", "cdata", "head", "body");

    private final Instruction[] instructions;

    // Indexed by HtmlResponseWriter.getLiteralMarkupKey() plus the escape-inline-text flag. Strings are immutable, so
    // the benign race of two requests rendering the same entry at once is harmless.
    private final String[] rendered = new String[HtmlResponseWriter.LITERAL_MARKUP_KEYS * 2];

    private PreRenderedInstruction(Instruction[] instructions) {
        this.instructions = instructions;
    }

    @Override
    public void write(FacesContext context) throws IOException {
        ResponseWriter writer = context.getResponseWriter();
        HtmlResponseWriter htmlWriter = HtmlResponseWriter.unwrapForLiteralMarkup(writer);
        int key = htmlWriter == null ? -1 : htmlWriter.getLiteralMarkupKey();
        if (key < 0) {
            writeInstructions(context);
            return;
        }
        if (FaceletsConfiguration.getInstance(context).isEscapeInlineText(context)) {
            key += HtmlResponseWriter.LITERAL_MARKUP_KEYS;
        }
        String markup = rendered[key];
        if (markup == null) {
            markup = render(context, htmlWriter, writer);
            rendered[key] = markup;
        }
        htmlWriter.writePreRendered(markup);
    }

    /** Runs the instructions against a clone of the writer that writes into a string. */
    private String render(FacesContext context, HtmlResponseWriter htmlWriter, ResponseWriter original) throws IOException {
        FastStringWriter capture = new FastStringWriter(256);
        ResponseWriter clone = htmlWriter.cloneWithWriter(capture);
        context.setResponseWriter(clone);
        try {
            writeInstructions(context);
            clone.flush();
        } finally {
            context.setResponseWriter(original);
        }
        return capture.toString();
    }

    private void writeInstructions(FacesContext context) throws IOException {
        for (Instruction instruction : instructions) {
            instruction.write(context);
        }
    }

    @Override
    public Instruction apply(ExpressionFactory factory, ELContext ctx) {
        return this;
    }

    @Override
    public boolean isLiteral() {
        return true;
    }

    @Override
    public String toString() {
        return "PreRendered" + Arrays.toString(instructions);
    }

    /**
     * Replaces every balanced run of literal element/attribute/text instructions with a {@link PreRenderedInstruction};
     * returns the given array itself when there is nothing to replace.
     */
    static Instruction[] coalesce(Instruction[] instructions) {
        List<Instruction> result = null;
        int i = 0;
        while (i < instructions.length) {
            int end = balancedRunEnd(instructions, i);
            if (end > i) {
                if (result == null) {
                    result = new ArrayList<>(Arrays.asList(instructions).subList(0, i));
                }
                result.add(new PreRenderedInstruction(Arrays.copyOfRange(instructions, i, end)));
                i = end;
            } else {
                if (result != null) {
                    result.add(instructions[i]);
                }
                i++;
            }
        }
        return result == null ? instructions : result.toArray(new Instruction[0]);
    }

    /**
     * Returns the end (exclusive) of the longest run starting at {@code start} that ends with every element it opened
     * closed again (and not right after an open start tag), or {@code start} if there is none.
     */
    private static int balancedRunEnd(Instruction[] instructions, int start) {
        Deque<String> open = new ArrayDeque<>();
        int lastBalancedEnd = start;
        for (int j = start; j < instructions.length; j++) {
            Instruction instruction = instructions[j];
            if (instruction instanceof StartElementInstruction) {
                String element = ((StartElementInstruction) instruction).getElement();
                if (isExcluded(element)) {
                    break;
                }
                open.push(element);
            } else if (instruction instanceof EndElementInstruction) {
                String element = ((EndElementInstruction) instruction).getElement();
                if (open.isEmpty() || !open.peek().equals(element) || isExcluded(element)) {
                    break;
                }
                open.pop();
            } else if (instruction instanceof LiteralAttributeInstruction) {
                if (open.isEmpty()) {
                    break;
                }
            } else if (!(instruction instanceof LiteralTextInstruction)) {
                break;
            }
            if (open.isEmpty() && !(instruction instanceof LiteralAttributeInstruction)) {
                lastBalancedEnd = j + 1;
            }
        }
        return lastBalancedEnd;
    }

    private static boolean isExcluded(String element) {
        for (String excluded : EXCLUDED_ELEMENTS) {
            if (excluded.equalsIgnoreCase(element)) {
                return true;
            }
        }
        return false;
    }
}
