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

import java.io.Serial;
import java.util.List;
import java.util.function.Function;

import jakarta.el.ELContext;
import jakarta.el.ELResolver;
import jakarta.el.EvaluationListener;
import jakarta.el.ValueExpression;
import jakarta.el.ValueReference;

/**
 * A value expression of the form <code>#{name}</code> or <code>#{name.property.property...}</code> -- the large
 * majority of the expressions of a page -- created by the EL implementation (the delegate) and evaluated here without
 * the EL interpreter, the same way the interpreter does it:
 * <ol>
 * <li>the name is a variable captured from the variable mapper when the expression was created, whose expression is
 * evaluated, or else it is resolved by the resolver chain with no base;
 * <li>each property is resolved by the resolver chain on the previous value, until a value is <code>null</code>;
 * <li>the result is converted to the expected type with {@link ELContext#convertToType}.
 * </ol>
 * In addition, when the Faces chain would resolve a property with the BeanELResolver, the getter is called directly,
 * with an inline cache of the reader for the last base class seen.
 *
 * <p>
 * Everything else is delegated: lambda arguments shadowing the name, registered evaluation listeners, an unresolved
 * name or property (to throw the interpreter's own exception), and every other method. A getter that throws is called
 * once more through the BeanELResolver, so that its exception is wrapped exactly as the resolver wraps it. The
 * expression serializes as its delegate.
 */
final class PathValueExpression extends ValueExpression {

    @Serial
    private static final long serialVersionUID = 1L;

    /** A reader cached for one base class, valid as long as the chain's readers are the same instance. */
    private static final class ReaderCache {
        final Class<?> type;
        final ClassValue<BeanPropertyReaders> owner;
        final Function<Object, Object> reader;
        final ELResolver beanResolver;

        ReaderCache(Class<?> type, ClassValue<BeanPropertyReaders> owner, Function<Object, Object> reader, ELResolver beanResolver) {
            this.type = type;
            this.owner = owner;
            this.reader = reader;
            this.beanResolver = beanResolver;
        }
    }

    private final transient ValueExpression delegate;
    private final transient String name;
    private final transient String[] properties;
    private final transient Class<?> expectedType;
    private final transient ValueExpression variable;
    private final transient ReaderCache[] readerCaches;

    /**
     * @param delegate the expression created by the EL implementation
     * @param name the name
     * @param properties the properties, possibly none
     * @param expectedType the expected type the delegate was created with
     * @param variable the variable the variable mapper had for the name when the delegate was created, or
     * <code>null</code>
     */
    PathValueExpression(ValueExpression delegate, String name, String[] properties, Class<?> expectedType, ValueExpression variable) {
        this.delegate = delegate;
        this.name = name;
        this.properties = properties;
        this.expectedType = expectedType;
        this.variable = variable;
        readerCaches = new ReaderCache[properties.length];
    }

    ValueExpression getDelegate() {
        return delegate;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getValue(ELContext context) {
        List<EvaluationListener> listeners = context.getEvaluationListeners();
        if (listeners != null && !listeners.isEmpty() || context.isLambdaArgument(name)) {
            return delegate.getValue(context);
        }

        ELResolver resolver = context.getELResolver();
        Object base;
        if (variable != null) {
            base = variable.getValue(context);
        } else {
            context.setPropertyResolved(false);
            base = resolver.getValue(context, null, name);
            if (!context.isPropertyResolved()) {
                return delegate.getValue(context);
            }
        }

        if (properties.length > 0) {
            for (int i = 0; base != null && i < properties.length; i++) {
                context.setPropertyResolved(false);
                base = getProperty(context, resolver, base, i);
            }
            if (!context.isPropertyResolved()) {
                return delegate.getValue(context);
            }
        }

        if (expectedType != null) {
            base = context.convertToType(base, expectedType);
        }

        return (T) base;
    }

    private Object getProperty(ELContext context, ELResolver resolver, Object base, int index) {
        String property = properties[index];

        if (resolver instanceof DemuxCompositeELResolver) {
            ClassValue<BeanPropertyReaders> owner = ((DemuxCompositeELResolver) resolver).beanReaders();
            Class<?> type = base.getClass();
            ReaderCache cache = readerCaches[index];
            if (cache == null || cache.type != type || cache.owner != owner) {
                BeanPropertyReaders readers = owner.get(type);
                cache = new ReaderCache(type, owner, readers.reader(property), readers.beanResolver());
                readerCaches[index] = cache;
            }

            if (cache.reader != null) {
                // What the BeanELResolver does before calling the getter.
                context.setPropertyResolved(base, property);
                try {
                    return cache.reader.apply(base);
                } catch (VirtualMachineError e) {
                    throw e;
                } catch (Throwable e) {
                    // Let the BeanELResolver call it and wrap its exception.
                    return cache.beanResolver.getValue(context, base, property);
                }
            }
        }

        return resolver.getValue(context, base, property);
    }

    @Override
    public void setValue(ELContext context, Object value) {
        delegate.setValue(context, value);
    }

    @Override
    public boolean isReadOnly(ELContext context) {
        return delegate.isReadOnly(context);
    }

    @Override
    public Class<?> getType(ELContext context) {
        return delegate.getType(context);
    }

    @Override
    public Class<?> getExpectedType() {
        return delegate.getExpectedType();
    }

    @Override
    public ValueReference getValueReference(ELContext context) {
        return delegate.getValueReference(context);
    }

    @Override
    public String getExpressionString() {
        return delegate.getExpressionString();
    }

    @Override
    public boolean isLiteralText() {
        return delegate.isLiteralText();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof PathValueExpression ? delegate.equals(((PathValueExpression) obj).delegate) : delegate.equals(obj);
    }

    @Override
    public int hashCode() {
        return delegate.hashCode();
    }

    @Override
    public String toString() {
        return delegate.toString();
    }

    @Serial
    private Object writeReplace() {
        return delegate;
    }
}
