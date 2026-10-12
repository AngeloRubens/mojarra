/*
 * Copyright (c) 2023 Contributors to Eclipse Foundation.
 * Copyright (c) 1997, 2020 Oracle and/or its affiliates. All rights reserved.
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

import static java.util.Map.entry;

import java.text.MessageFormat;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.sun.faces.context.flash.FlashELResolver;

import jakarta.el.ArrayELResolver;
import jakarta.el.BeanELResolver;
import jakarta.el.ELClass;
import jakarta.el.ELContext;
import jakarta.el.ELException;
import jakarta.el.ELResolver;
import jakarta.el.ListELResolver;
import jakarta.el.MapELResolver;
import jakarta.el.OptionalELResolver;
import jakarta.el.RecordELResolver;
import jakarta.el.ResourceBundleELResolver;
import jakarta.el.StaticFieldELResolver;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.faces.application.ResourceHandler;
import jakarta.faces.component.UIComponent;
import jakarta.faces.context.Flash;

/**
 * Maintains an ordered composite list of child <code>ELResolver for Faces</code>.
 *
 */
public class DemuxCompositeELResolver extends FacesCompositeELResolver {
    private final ELResolverChainType _chainType;

    private ELResolver[] _rootELResolvers = new ELResolver[2];
    private ELResolver[] _propertyELResolvers = new ELResolver[2];
    private ELResolver[] _allELResolvers = new ELResolver[2];
    private ELResolver[] _convertELResolvers = new ELResolver[2];

    private int _rootELResolverCount = 0;
    private int _propertyELResolverCount = 0;
    private int _allELResolverCount = 0;
    private int _convertELResolverCount = 0;

    /*
     * convertToType shortcut. Every value expression evaluation ends with ELContext.convertToType, which consults the
     * convert resolvers. Usually these are only the OptionalELResolver, which converts nothing but an Optional, and the
     * still empty Application#addELResolver composite, so the walk can be skipped when it certainly declines.
     */
    private int _unknownConvertResolverCount = 0;
    private ApplicationELResolvers[] _applicationConvertResolvers = new ApplicationELResolvers[0];

    /*
     * getValue shortcuts. Most resolvers of the chain decide whether they resolve from the base class alone (property
     * resolution: Map, List, Bean...) or from the root name alone (CDI bean names, "cc", resource bundle vars...), so
     * for a given base class or root name every evaluation walks the same leading resolvers that all decline. These
     * caches remember where the walk can start. A resolver is only ever skipped when it is one of the known resolvers
     * below and cannot resolve that class or name whatever the request; an unknown (application or faces-config)
     * resolver is a barrier that is always consulted, so the observable result is the same as walking the whole chain.
     */

    /**
     * Known property resolvers which resolve only bases of one kind: the resolver class, and whether it may resolve an
     * instance of a given class.
     */
    private static final Map<Class<?>, Predicate<Class<?>>> PROPERTY_BASES = Map.ofEntries(
            entry(FlashELResolver.class, Flash.class::isAssignableFrom),
            entry(CompositeComponentELResolver.class, baseClass -> false),
            entry(EmptyStringToNullELResolver.class, baseClass -> false),
            entry(CompositeComponentAttributesELResolver.class, UIComponent.class::isAssignableFrom),
            entry(ResourceELResolver.class, ResourceHandler.class::isAssignableFrom),
            entry(ResourceBundleELResolver.class, ResourceBundle.class::isAssignableFrom),
            entry(MapELResolver.class, Map.class::isAssignableFrom),
            entry(ListELResolver.class, List.class::isAssignableFrom),
            entry(ArrayELResolver.class, Class::isArray),
            entry(OptionalELResolver.class, Optional.class::isAssignableFrom),
            entry(RecordELResolver.class, Class::isRecord));

    /** Disabled by the context parameter <code>com.sun.faces.disableELResolverShortcuts</code>. */
    private volatile boolean _shortcuts = true;

    /** Root names: index of the first root resolver that may resolve the name. */
    private final Map<String, Integer> _rootStart = new ConcurrentHashMap<>();
    private static final int MAX_ROOT_NAMES = 4096;

    /** Base classes: index of the first property resolver that may resolve an instance of the class. */
    private volatile ClassValue<Integer> _propertyStart = newPropertyStarts();

    /**
     * Bean getters. When the first property resolver that may resolve a base class is the BeanELResolver, it resolves
     * every property of that class, so its getter is called directly, without the reflective lookup and call of the
     * BeanELResolver. The functions are held by this chain, which belongs to one application.
     */
    private volatile Map<Class<?>, BeanPropertyReaders> _beanReaders = new ConcurrentHashMap<>();
    private static final int MAX_BEAN_CLASSES = 4096;
    private static final BeanResolverKind BEAN_RESOLVER_KIND = BeanResolverKind.of(BeanELResolver.class);

    /** Incremented whenever the chain changes, so that a start computed against an older chain is not stored. */
    private volatile int _generation;

    /** The resolver of the CDI bean manager, and the source of the bean names and namespaces it may resolve. */
    private volatile ELResolver _cdiResolver;
    private volatile Supplier<Set<String>> _cdiNameSource;
    private volatile Set<String> _cdiNames;

    public DemuxCompositeELResolver(ELResolverChainType chainType) {
        if (chainType == null) {
            throw new NullPointerException();
        }

        _chainType = chainType;
    }

    @Override
    public ELResolverChainType getChainType() {
        return _chainType;
    }

    private void _addAllELResolver(ELResolver elResolver) {
        if (elResolver == null) {
            throw new NullPointerException();
        }

        // grow array, if necessary
        if (_allELResolverCount == _allELResolvers.length) {
            ELResolver[] biggerResolvers = new ELResolver[_allELResolverCount * 2];
            System.arraycopy(_allELResolvers, 0, biggerResolvers, 0, _allELResolverCount);
            _allELResolvers = biggerResolvers;
        }

        // assign new resolver to end
        _allELResolvers[_allELResolverCount] = elResolver;
        _allELResolverCount++;
    }

    private void _addRootELResolver(ELResolver elResolver) {
        if (elResolver == null) {
            throw new NullPointerException();
        }

        // grow array, if necessary
        if (_rootELResolverCount == _rootELResolvers.length) {
            ELResolver[] biggerResolvers = new ELResolver[_rootELResolverCount * 2];
            System.arraycopy(_rootELResolvers, 0, biggerResolvers, 0, _rootELResolverCount);
            _rootELResolvers = biggerResolvers;
        }

        // assign new resolver to end
        _rootELResolvers[_rootELResolverCount] = elResolver;
        _rootELResolverCount++;
    }

    /**
     * Registers <code>elResolver</code> for {@link #convertToType} only when it actually declares that method.
     * {@link ELResolver#convertToType} returns <code>null</code> without marking the property as resolved, so a
     * resolver which inherits it can never contribute a conversion, and consulting it is a no-op.
     */
    private void _addConvertELResolver(ELResolver elResolver) {
        if (!declaresConvertToType(elResolver)) {
            return;
        }

        if (elResolver.getClass() == ApplicationELResolvers.class) {
            _applicationConvertResolvers = Arrays.copyOf(_applicationConvertResolvers, _applicationConvertResolvers.length + 1);
            _applicationConvertResolvers[_applicationConvertResolvers.length - 1] = (ApplicationELResolvers) elResolver;
        } else if (elResolver.getClass() != OptionalELResolver.class) {
            _unknownConvertResolverCount++;
        }

        // grow array, if necessary
        if (_convertELResolverCount == _convertELResolvers.length) {
            ELResolver[] biggerResolvers = new ELResolver[_convertELResolverCount * 2];
            System.arraycopy(_convertELResolvers, 0, biggerResolvers, 0, _convertELResolverCount);
            _convertELResolvers = biggerResolvers;
        }

        // assign new resolver to end
        _convertELResolvers[_convertELResolverCount] = elResolver;
        _convertELResolverCount++;
    }

    static boolean declaresConvertToType(ELResolver elResolver) {
        try {
            return elResolver.getClass().getMethod("convertToType", ELContext.class, Object.class, Class.class).getDeclaringClass() != ELResolver.class;
        } catch (NoSuchMethodException e) {
            // Cannot happen: convertToType is public on ELResolver itself.
            return true;
        }
    }

    public void _addPropertyELResolver(ELResolver elResolver) {
        if (elResolver == null) {
            throw new NullPointerException();
        }

        // grow array, if necessary
        if (_propertyELResolverCount == _propertyELResolvers.length) {
            ELResolver[] biggerResolvers = new ELResolver[_propertyELResolverCount * 2];
            System.arraycopy(_propertyELResolvers, 0, biggerResolvers, 0, _propertyELResolverCount);
            _propertyELResolvers = biggerResolvers;
        }

        // assign new resolver to end
        _propertyELResolvers[_propertyELResolverCount] = elResolver;
        _propertyELResolverCount++;
    }

    @Override
    public void addRootELResolver(ELResolver elResolver) {
        // pass ELResolver to CompositeELResolver so that J2EE6 invoke() method works. Once we can
        // have a compile dependency on J2EE6, we can override invoke() ourselves and remove this.
        super.add(elResolver);

        _addRootELResolver(elResolver);
        _addAllELResolver(elResolver);
        _addConvertELResolver(elResolver);
        clearShortcuts();
    }

    @Override
    public void addPropertyELResolver(ELResolver elResolver) {
        // pass ELResolver to CompositeELResolver so that J2EE6 invoke() method works. Once we can
        // have a compile dependency on J2EE6, we can override invoke() ourselves and remove this.
        super.add(elResolver);

        _addPropertyELResolver(elResolver);
        _addAllELResolver(elResolver);
        _addConvertELResolver(elResolver);
        clearShortcuts();
    }

    @Override
    public void add(ELResolver elResolver) {
        // pass ELResolver to CompositeELResolver so that J2EE6 invoke() method works. Once we can
        // have a compile dependency on J2EE6, we can override invoke() ourselves and remove this.
        super.add(elResolver);

        _addRootELResolver(elResolver);
        _addPropertyELResolver(elResolver);
        _addAllELResolver(elResolver);
        _addConvertELResolver(elResolver);
        clearShortcuts();
    }

    /**
     * Makes {@link #getValue} and {@link #convertToType} always walk the whole chain.
     */
    public void disableShortcuts() {
        _shortcuts = false;
    }

    /**
     * Lets the root name shortcut skip <code>cdiResolver</code>, and any other resolver of Weld or OpenWebBeans, for a
     * name which is neither the name of a bean of <code>beanManager</code> nor a namespace of such a name. Without it,
     * these resolvers are consulted for every root name.
     *
     * @param cdiResolver the resolver of the bean manager, as added to this chain
     * @param beanManager the bean manager
     */
    public void setBeanManager(ELResolver cdiResolver, BeanManager beanManager) {
        setCdiNames(cdiResolver, () -> beanNamesAndNamespaces(beanManager));
    }

    /**
     * @param names the bean names and namespaces, asked once at the first evaluation which needs them; it may throw
     * while they are not known yet, and is then asked again later
     */
    void setCdiNames(ELResolver cdiResolver, Supplier<Set<String>> names) {
        _cdiResolver = cdiResolver;
        _cdiNameSource = names;
        _cdiNames = null;
        clearShortcuts();
    }

    /**
     * The names of the beans of <code>beanManager</code>, and their namespaces: <code>a</code> and <code>a.b</code> for
     * <code>a.b.c</code>. Bean names cannot change once the container has started.
     */
    private static Set<String> beanNamesAndNamespaces(BeanManager beanManager) {
        Set<String> names = new HashSet<>();
        for (Bean<?> bean : beanManager.getBeans(Object.class, Any.Literal.INSTANCE)) {
            String name = bean.getName();
            if (name != null) {
                names.add(name);
                for (int dot = name.indexOf('.'); dot > 0; dot = name.indexOf('.', dot + 1)) {
                    names.add(name.substring(0, dot));
                }
            }
        }
        return names;
    }

    private Object _getValue(int resolverCount, ELResolver[] resolvers, ELContext context, Object base, Object property) throws ELException {
        for (int i = 0; i < resolverCount; i++) {
            Object result = resolvers[i].getValue(context, base, property);

            if (context.isPropertyResolved()) {
                return result;
            }
        }

        return null;
    }

    @Override
    public Object getValue(ELContext context, Object base, Object property) throws ELException {
        context.setPropertyResolved(false);

        int resolverCount;
        ELResolver[] resolvers;

        if (base == null) {
            if (_shortcuts && property instanceof String) {
                return _getRootValue(context, (String) property);
            }
            resolverCount = _rootELResolverCount;
            resolvers = _rootELResolvers;
        } else if (base instanceof ELClass) {
            resolverCount = _rootELResolverCount;
            resolvers = _rootELResolvers;
        } else {
            if (_shortcuts && property != null) {
                return _getPropertyValue(context, base, property);
            }
            resolverCount = _propertyELResolverCount;
            resolvers = _propertyELResolvers;
        }

        return _getValue(resolverCount, resolvers, context, base, property);
    }

    private Object _getRootValue(ELContext context, String name) {
        int generation = _generation;
        ELResolver[] resolvers = _rootELResolvers;
        int count = _rootELResolverCount;
        Integer known = _rootStart.get(name);
        int start = known == null ? 0 : known;

        for (int i = start; i < count; i++) {
            Object result = resolvers[i].getValue(context, null, name);

            if (context.isPropertyResolved()) {
                if (known == null) {
                    rememberRootStart(name, generation, resolvers, i);
                }
                return result;
            }
        }

        if (known == null) {
            rememberRootStart(name, generation, resolvers, count);
        }
        return null;
    }

    private void rememberRootStart(String name, int generation, ELResolver[] resolvers, int resolvedAt) {
        if (generation != _generation || _rootStart.size() >= MAX_ROOT_NAMES) {
            return;
        }
        int start = 0;
        while (start < resolvedAt && neverResolvesRootName(resolvers[start], name)) {
            start++;
        }
        // Stored even when 0, so the name is not examined again.
        _rootStart.put(name, start);
        if (generation != _generation) {
            // The chain changed meanwhile, and clearShortcuts() may have run before the put.
            _rootStart.remove(name);
        }
    }

    private Object _getPropertyValue(ELContext context, Object base, Object property) {
        ClassValue<Integer> starts = _propertyStart;
        int start = starts.get(base.getClass());
        ELResolver[] resolvers = _propertyELResolvers;
        int count = _propertyELResolverCount;

        if (start < count && property instanceof String && resolvers[start].getClass() == BeanELResolver.class && BEAN_RESOLVER_KIND != null) {
            Function<Object, Object> reader = beanReader(base.getClass(), (String) property);
            if (reader != null) {
                return BEAN_RESOLVER_KIND.read(context, reader, base, property);
            }
        }

        for (int i = start; i < count; i++) {
            Object result = resolvers[i].getValue(context, base, property);

            if (context.isPropertyResolved()) {
                return result;
            }
        }

        return null;
    }

    /**
     * @return the getter of the property, or <code>null</code> when the BeanELResolver must read it
     */
    private Function<Object, Object> beanReader(Class<?> baseClass, String property) {
        Map<Class<?>, BeanPropertyReaders> beanReaders = _beanReaders;
        BeanPropertyReaders readers = beanReaders.get(baseClass);
        if (readers == null) {
            if (beanReaders.size() >= MAX_BEAN_CLASSES) {
                return null;
            }
            readers = beanReaders.computeIfAbsent(baseClass, BeanPropertyReaders::new);
        }
        return readers.reader(property);
    }

    private Integer computePropertyStart(Class<?> baseClass) {
        ELResolver[] resolvers = _propertyELResolvers;
        int count = _propertyELResolverCount;
        int start = 0;
        while (start < count && neverResolvesProperty(resolvers[start], baseClass)) {
            start++;
        }
        return start;
    }

    /**
     * Forgets every learned shortcut. Called after every change of the chain, including when an application resolver is
     * added to a composite this chain contains.
     */
    public synchronized void clearShortcuts() {
        _generation++;
        _rootStart.clear();
        // A fresh ClassValue drops every per-class start computed so far.
        _propertyStart = newPropertyStarts();
        _beanReaders = new ConcurrentHashMap<>();
    }

    private ClassValue<Integer> newPropertyStarts() {
        return new ClassValue<>() {
            @Override
            protected Integer computeValue(Class<?> baseClass) {
                return computePropertyStart(baseClass);
            }
        };
    }

    /**
     * Whether <code>resolver</code> can never resolve <code>getValue(context, null, name)</code>, whatever the request,
     * because it only resolves names fixed at deployment that do not include this one, or never resolves root names.
     * Only resolvers known to behave this way qualify.
     */
    private boolean neverResolvesRootName(ELResolver resolver, String name) {
        if (isCdiResolver(resolver)) {
            Set<String> cdiNames = cdiNames();
            return cdiNames != null && !cdiNames.contains(name);
        }
        if (resolver.getClass() == ApplicationELResolvers.class) {
            return allChildren((ApplicationELResolvers) resolver, child -> neverResolvesRootName(child, name));
        }
        Class<?> type = resolver.getClass();
        if (type == CompositeComponentELResolver.class) {
            return !CompositeComponentELResolver.COMPOSITE_COMPONENT_NAME.equals(name);
        }
        return type == FlashELResolver.class // flash is resolved as a property base only
                || type == StaticFieldELResolver.class // only resolves an ELClass base
                || type == EmptyStringToNullELResolver.class
                || isStreamResolver(type);
    }

    /**
     * Whether <code>resolver</code> can never resolve <code>getValue(context, base, property)</code> for any instance of
     * <code>baseClass</code> and any property. Only resolvers known to decide from the base type alone qualify.
     */
    private boolean neverResolvesProperty(ELResolver resolver, Class<?> baseClass) {
        if (isCdiResolver(resolver)) {
            // CDI resolvers only resolve properties of their own namespace objects.
            return !isCdiClass(baseClass);
        }
        if (resolver.getClass() == ApplicationELResolvers.class) {
            return allChildren((ApplicationELResolvers) resolver, child -> neverResolvesProperty(child, baseClass));
        }
        if (isStreamResolver(resolver.getClass())) {
            return true;
        }
        Predicate<Class<?>> mayResolve = PROPERTY_BASES.get(resolver.getClass());
        return mayResolve != null && !mayResolve.test(baseClass);
    }

    private boolean isCdiResolver(ELResolver resolver) {
        return resolver == _cdiResolver || isCdiClass(resolver.getClass());
    }

    private static boolean isCdiClass(Class<?> type) {
        String name = type.getName();
        return name.startsWith("org.jboss.weld.") || name.startsWith("org.apache.webbeans.");
    }

    /**
     * @return the bean names and namespaces, or <code>null</code> when they are not known
     */
    private Set<String> cdiNames() {
        Set<String> names = _cdiNames;
        if (names == null) {
            Supplier<Set<String>> source = _cdiNameSource;
            if (source == null) {
                return null;
            }
            try {
                names = source.get();
            } catch (RuntimeException e) {
                // The container is not ready yet: asked again for the next new name.
                return null;
            }
            _cdiNames = names;
        }
        return names;
    }

    private static boolean isStreamResolver(Class<?> type) {
        // getValue() of the stream resolvers of Tomcat EL and Expressly always returns null without resolving.
        String name = type.getName();
        return name.equals("org.apache.el.stream.StreamELResolverImpl") || name.equals("org.glassfish.expressly.stream.StreamELResolver");
    }

    /**
     * Whether all resolvers of the composite of the application resolvers satisfy the given rule, as when it is empty.
     */
    private static boolean allChildren(ApplicationELResolvers resolvers, Predicate<ELResolver> never) {
        for (ELResolver child : resolvers.getResolvers()) {
            if (!never.test(child)) {
                return false;
            }
        }
        return true;
    }

    private Class<?> _getType(int resolverCount, ELResolver[] resolvers, ELContext context, Object base, Object property) throws ELException {
        for (int i = 0; i < resolverCount; i++) {
            Class<?> type = resolvers[i].getType(context, base, property);

            if (context.isPropertyResolved()) {
                return type;
            }
        }

        return null;
    }

    @Override
    public Class<?> getType(ELContext context, Object base, Object property) throws ELException {
        context.setPropertyResolved(false);

        int resolverCount;
        ELResolver[] resolvers;

        if (base == null || base instanceof ELClass) {
            resolverCount = _rootELResolverCount;
            resolvers = _rootELResolvers;
        } else {
            resolverCount = _propertyELResolverCount;
            resolvers = _propertyELResolvers;
        }

        return _getType(resolverCount, resolvers, context, base, property);
    }

    private void _setValue(int resolverCount, ELResolver[] resolvers, ELContext context, Object base, Object property, Object val) throws ELException {
        for (int i = 0; i < resolverCount; i++) {
            resolvers[i].setValue(context, base, property, val);

            if (context.isPropertyResolved()) {
                return;
            }
        }
    }

    @Override
    public void setValue(ELContext context, Object base, Object property, Object val) throws ELException {
        context.setPropertyResolved(false);

        int resolverCount;
        ELResolver[] resolvers;

        if (base == null || base instanceof ELClass) {
            resolverCount = _rootELResolverCount;
            resolvers = _rootELResolvers;
        } else {
            resolverCount = _propertyELResolverCount;
            resolvers = _propertyELResolvers;
        }

        _setValue(resolverCount, resolvers, context, base, property, val);
    }

    private boolean _isReadOnly(int resolverCount, ELResolver[] resolvers, ELContext context, Object base, Object property) throws ELException {
        for (int i = 0; i < resolverCount; i++) {
            boolean isReadOnly = resolvers[i].isReadOnly(context, base, property);

            if (context.isPropertyResolved()) {
                return isReadOnly;
            }
        }

        return false;
    }

    @Override
    public boolean isReadOnly(ELContext context, Object base, Object property) throws ELException {
        context.setPropertyResolved(false);

        int resolverCount;
        ELResolver[] resolvers;

        if (base == null || base instanceof ELClass) {
            resolverCount = _rootELResolverCount;
            resolvers = _rootELResolvers;
        } else {
            resolverCount = _propertyELResolverCount;
            resolvers = _propertyELResolvers;
        }

        return _isReadOnly(resolverCount, resolvers, context, base, property);
    }

    @Override
    public <T> T convertToType(ELContext context, Object obj, Class<T> targetType) {
        context.setPropertyResolved(false);

        if (neverConverts(obj)) {
            return null;
        }

        for (int i = 0; i < _convertELResolverCount; i++) {
            T value = _convertELResolvers[i].convertToType(context, obj, targetType);

            if (context.isPropertyResolved()) {
                return value;
            }
        }

        return null;
    }

    /**
     * Whether {@link #convertToType} certainly declines to convert <code>obj</code>: all resolvers that can convert are
     * the {@link OptionalELResolver}, which only converts an {@link Optional}, and application resolver composites holding
     * no resolver that declares <code>convertToType</code>.
     *
     * @param obj the object to convert
     * @return <code>true</code> when no resolver of this chain would convert <code>obj</code>
     */
    boolean neverConverts(Object obj) {
        if (!_shortcuts || _unknownConvertResolverCount != 0 || obj instanceof Optional) {
            return false;
        }

        for (ApplicationELResolvers resolvers : _applicationConvertResolvers) {
            if (resolvers.mayConvert()) {
                return false;
            }
        }

        return true;
    }

    @Override
    public Class<?> getCommonPropertyType(ELContext context, Object base) {
        return null;
    }

    /**
     * How the BeanELResolver of the EL API in use calls a getter: when it marks the property as resolved, and how it
     * wraps an exception of the getter. A getter called directly behaves the same way.
     */
    private enum BeanResolverKind {

        /** The API of Apache Tomcat: resolved before the call, exception wrapped with a localized message. */
        TOMCAT {
            @Override
            Object read(ELContext context, Function<Object, Object> reader, Object base, Object property) {
                context.setPropertyResolved(base, property);
                try {
                    return reader.apply(base);
                } catch (VirtualMachineError e) {
                    throw e;
                } catch (Throwable e) {
                    throw new ELException(tomcatMessage(context, "propertyReadError", base.getClass().getName(), property.toString()), e);
                }
            }
        },

        /** The API of Jakarta Expression Language: resolved after the call, exception wrapped as is. */
        JAKARTA {
            @Override
            Object read(ELContext context, Function<Object, Object> reader, Object base, Object property) {
                Object value;
                try {
                    value = reader.apply(base);
                } catch (Throwable e) {
                    throw new ELException(e);
                }
                context.setPropertyResolved(base, property);
                return value;
            }
        };

        abstract Object read(ELContext context, Function<Object, Object> reader, Object base, Object property);

        /**
         * @return the kind of the given BeanELResolver class, or <code>null</code> when it is not known, and its getters
         * must not be called directly
         */
        static BeanResolverKind of(Class<?> beanResolverClass) {
            if (hasSibling(beanResolverClass, "BeanSupport")) {
                return TOMCAT;
            }
            if (hasSibling(beanResolverClass, "ELUtil")) {
                return JAKARTA;
            }
            return null;
        }

        private static boolean hasSibling(Class<?> type, String simpleName) {
            try {
                Class.forName(type.getPackageName() + "." + simpleName, false, type.getClassLoader());
                return true;
            } catch (ClassNotFoundException | LinkageError e) {
                return false;
            }
        }

        /** The message of <code>jakarta.el.Util.message</code> of Apache Tomcat. */
        static String tomcatMessage(ELContext context, String name, Object... props) {
            Locale locale = context.getLocale();
            if (locale == null) {
                locale = Locale.getDefault();
            }
            try {
                String template = ResourceBundle.getBundle("jakarta.el.LocalStrings", locale, BeanELResolver.class.getClassLoader()).getString(name);
                return MessageFormat.format(template, props);
            } catch (MissingResourceException e) {
                return "Missing Resource: '" + name + "' for Locale " + locale.getDisplayName();
            }
        }
    }
}
