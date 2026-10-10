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

package com.sun.faces.el;

import jakarta.el.CompositeELResolver;
import jakarta.el.ELResolver;

/**
 * The resolvers registered with {@link jakarta.faces.application.Application#addELResolver}. Knows whether it holds
 * any, so the Faces chain can skip it while it is empty.
 */
public class ApplicationELResolvers extends CompositeELResolver {

    private volatile boolean empty = true;

    @Override
    public void add(ELResolver elResolver) {
        super.add(elResolver);
        empty = false;
    }

    public boolean isEmpty() {
        return empty;
    }
}
