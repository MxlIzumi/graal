/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.api.instrumentation;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.impl.Accessor;
import com.oracle.truffle.api.instrumentation.InstrumentableFactory.WrapperNode;
import com.oracle.truffle.api.instrumentation.ProbeNode.EventChainNode;
import com.oracle.truffle.api.instrumentation.TruffleInstrument.Env;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeVisitor;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;

/**
 * Central coordinator class for the Truffle instrumentation framework. Allocated once per
 * {@linkplain com.oracle.truffle.api.vm.PolyglotEngine engine}.
 */
final class InstrumentationHandler {

    /* Enable trace output to stdout. */
    private static final boolean TRACE = Boolean.getBoolean("truffle.instrumentation.trace");

    /* All roots that have been initialized (executed at least once) */
    private final Map<RootNode, Void> roots = Collections.synchronizedMap(new WeakHashMap<RootNode, Void>());

    /* All bindings that have been globally created by instrumenter instances. */
    private final List<EventBinding<?>> bindings = new ArrayList<>();

    /* Cached instance for reuse for newly installed root nodes. */
    private final AddBindingsVisitor addAllBindingsVisitor = new AddBindingsVisitor(bindings);

    /*
     * Fast lookup of instrumenter instances based on a key provided by the accessor.
     */
    private final Map<Object, AbstractInstrumenter> instrumenterMap = new HashMap<>();

    /* Has the instrumentation framework been initialized? */
    private volatile boolean instrumentationInitialized;

    private final OutputStream out;
    private final OutputStream err;
    private final InputStream in;

    private InstrumentationHandler(OutputStream out, OutputStream err, InputStream in) {
        this.out = out;
        this.err = err;
        this.in = in;
    }

    void installRootNode(RootNode root) {
        if (!ACCESSOR.isInstrumentable(root)) {
            return;
        }
        if (!instrumentationInitialized) {
            initializeInstrumentation();
        }
        roots.put(root, null);
        visitRoot(root, addAllBindingsVisitor);
    }

    void addInstrument(Object key, Class<?> clazz) {
        addInstrumenter(key, new InstrumentClientInstrumenter(clazz, out, err, in));
    }

    void disposeInstrumenter(Object key, boolean cleanupRequired) {
        if (TRACE) {
            trace("Dispose instrumenter %n", key);
        }
        AbstractInstrumenter disposedInstrumenter = instrumenterMap.get(key);
        List<EventBinding<?>> disposedBindings = new ArrayList<>();
        for (Iterator<EventBinding<?>> iterator = bindings.listIterator(); iterator.hasNext();) {
            EventBinding<?> binding = iterator.next();
            if (binding.getInstrumenter() == disposedInstrumenter) {
                iterator.remove();
                disposedBindings.add(binding);
            }
        }
        disposedInstrumenter.dispose();
        instrumenterMap.remove(key);

        if (cleanupRequired) {
            DisposeBindingsVisitor disposeVisitor = new DisposeBindingsVisitor(disposedBindings);
            for (RootNode root : roots.keySet()) {
                visitRoot(root, disposeVisitor);
            }
        }

        if (TRACE) {
            trace("Disposed instrumenter %n", key);
        }
    }

    Instrumenter forLanguage(TruffleLanguage.Env context, TruffleLanguage<?> language) {
        return new LanguageClientInstrumenter<>(language, context);
    }

    void detachLanguage(Object context) {
        if (instrumenterMap.containsKey(context)) {
            /*
             * TODO (chumer): do we need cleanup/invalidate here? With shared CallTargets we
             * probably will.
             */
            disposeInstrumenter(context, false);
        }
    }

    <T> EventBinding<T> addBinding(EventBinding<T> binding) {
        if (TRACE) {
            trace("Adding binding %s, %s%n", binding.getFilter(), binding.getElement());
        }

        this.bindings.add(binding);

        if (instrumentationInitialized) {
            AddBindingVisitor addBindingsVisitor = new AddBindingVisitor(binding);
            for (RootNode root : roots.keySet()) {
                visitRoot(root, addBindingsVisitor);
            }
        }

        if (TRACE) {
            trace("Added binding %s, %s%n", binding.getFilter(), binding.getElement());
        }

        return binding;
    }

    void disposeBinding(EventBinding<?> binding) {
        if (TRACE) {
            trace("Dispose binding %s, %s%n", binding.getFilter(), binding.getElement());
        }

        this.bindings.remove(binding);
        DisposeBindingVisitor disposeVisitor = new DisposeBindingVisitor(binding);
        for (RootNode root : roots.keySet()) {
            visitRoot(root, disposeVisitor);
        }

        if (TRACE) {
            trace("Disposed binding %s, %s%n", binding.getFilter(), binding.getElement());
        }
    }

    EventChainNode installBindings(ProbeNode probeNodeImpl) {
        EventContext context = probeNodeImpl.getContext();
        SourceSection sourceSection = context.getInstrumentedSourceSection();
        if (TRACE) {
            trace("Lazy update for %s%n", sourceSection);
        }
        EventChainNode root = null;
        EventChainNode parent = null;
        for (int i = 0; i < bindings.size(); i++) {
            EventBinding<?> binding = bindings.get(i);
            if (isInstrumented(null, probeNodeImpl, binding, sourceSection)) {
                if (TRACE) {
                    trace("Found binding %s, %s%n", binding.getFilter(), binding.getElement());
                }
                EventChainNode next = probeNodeImpl.createEventChainCallback(binding);
                if (next == null) {
                    continue;
                }

                if (root == null) {
                    root = next;
                } else {
                    assert parent != null;
                    parent.setNext(next);
                }
                parent = next;
            }
        }

        if (TRACE) {
            trace("Lazy updated for %s%n", sourceSection);
        }
        return root;
    }

    private void initializeInstrumentation() {
        synchronized (this) {
            if (!instrumentationInitialized) {
                if (TRACE) {
                    trace("Initialize instrumentation%n");
                }
                for (AbstractInstrumenter instrumenter : instrumenterMap.values()) {
                    instrumenter.initialize();
                }
                if (TRACE) {
                    trace("Initialized instrumentation%n");
                }
                instrumentationInitialized = true;
            }
        }
    }

    private void addInstrumenter(Object key, AbstractInstrumenter instrumenter) throws AssertionError {
        if (instrumenterMap.containsKey(key)) {
            throw new AssertionError("Instrument already added.");
        }

        if (instrumentationInitialized) {
            instrumenter.initialize();
            List<EventBinding<?>> addedBindings = new ArrayList<>();
            for (EventBinding<?> binding : bindings) {
                if (binding.getInstrumenter() == instrumenter) {
                    addedBindings.add(binding);
                }
            }

            AddBindingsVisitor visitor = new AddBindingsVisitor(addedBindings);
            for (RootNode root : roots.keySet()) {
                visitRoot(root, visitor);
            }
        }
        instrumenterMap.put(key, instrumenter);
    }

    @SuppressWarnings("unchecked")
    private void insertWrapper(Node instrumentableNode, SourceSection sourceSection) {
        Node node = instrumentableNode;
        Node parent = node.getParent();
        if (parent instanceof WrapperNode) {
            // already wrapped, need to invalidate the wrapper something changed
            invalidateWrapperImpl((WrapperNode) parent, node);
            return;
        }
        ProbeNode probe = new ProbeNode(InstrumentationHandler.this, sourceSection);
        WrapperNode wrapper;
        try {
            Class<?> factory = null;
            Class<?> currentClass = instrumentableNode.getClass();
            while (currentClass != null) {
                Instrumentable instrumentable = currentClass.getAnnotation(Instrumentable.class);
                if (instrumentable != null) {
                    factory = instrumentable.factory();
                    break;
                }
                currentClass = currentClass.getSuperclass();
            }

            if (factory == null) {
                if (TRACE) {
                    trace("No wrapper inserted for %s, section %s. Not annotated with @Instrumentable.%n", node, sourceSection);
                }
                // node or superclass is not annotated with @Instrumentable
                return;
            }

            if (TRACE) {
                trace("Insert wrapper for %s, section %s%n", node, sourceSection);
            }

            wrapper = ((InstrumentableFactory<Node>) factory.newInstance()).createWrapper(instrumentableNode, probe);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create wrapper node. ", e);
        }

        if (!(wrapper instanceof Node)) {
            throw new IllegalStateException(String.format("Implementation of %s must be a subclass of %s.", WrapperNode.class.getSimpleName(), Node.class.getSimpleName()));
        }

        Node wrapperNode = (Node) wrapper;
        if (wrapperNode.getParent() != null) {
            throw new IllegalStateException(String.format("Instance of provided %s is already adopted by another parent.", WrapperNode.class.getSimpleName()));
        }
        if (parent == null) {
            throw new IllegalStateException(String.format("Instance of instrumentable %s is not adopted by a parent.", Node.class.getSimpleName()));
        }

        if (!node.isSafelyReplaceableBy(wrapperNode)) {
            throw new IllegalStateException(String.format("WrapperNode implementation %s cannot be safely replaced in parent node class %s.", wrapperNode.getClass().getName(),
                            parent.getClass().getName()));
        }
        node.replace(wrapperNode);
        if (node.getParent() != wrapperNode) {
            throw new IllegalStateException("InstrumentableNode must have a WrapperNode as parent after createInstrumentationWrappwer is invoked.");
        }
    }

    private <T extends ExecutionEventNodeFactory> EventBinding<T> attachFactory(AbstractInstrumenter instrumenter, SourceSectionFilter filter, T factory) {
        return addBinding(new EventBinding<>(instrumenter, filter, factory));
    }

    private <T extends ExecutionEventListener> EventBinding<T> attachListener(AbstractInstrumenter instrumenter, SourceSectionFilter filter, T listener) {
        return addBinding(new EventBinding<>(instrumenter, filter, listener));
    }

    private static boolean isInstrumentableNode(Node node) {
        return !(node instanceof WrapperNode) && !(node instanceof RootNode);
    }

    private static boolean isInstrumented(RootNode rootNode, Node node, EventBinding<?> binding, SourceSection section) {
        if (isInstrumentedLeaf(binding, section)) {
            RootNode root = rootNode == null ? node.getRootNode() : rootNode;
            if (root == null) {
                return false;
            }
            return isInstrumentedRoot(root, binding, root.getSourceSection());
        }
        return false;
    }

    private static boolean isInstrumentedRoot(RootNode node, EventBinding<?> binding, SourceSection section) {
        return binding.getInstrumenter().isInstrumentable(node) && binding.getFilter().isInstrumentedRoot(section);
    }

    private static boolean isInstrumentedLeaf(EventBinding<?> binding, SourceSection section) {
        return binding.getFilter().isInstrumentedNode(section);
    }

    private static void trace(String message, Object... args) {
        PrintStream out = System.out;
        out.printf(message, args);
    }

    private static void visitRoot(final RootNode root, final AbstractNodeVisitor visitor) {
        if (TRACE) {
            trace("Visit root %s wrappers for %s%n", visitor, root.toString());
        }

        visitor.root = root;
        if (visitor.shouldVisit()) {
            // found a filter that matched
            root.atomic(new Runnable() {
                public void run() {
                    root.accept(visitor);
                }
            });
        }
        visitor.root = null;
        if (TRACE) {
            trace("Visited root %s wrappers for %s%n", visitor, root.toString());
        }
    }

    static void removeWrapper(ProbeNode node) {
        if (TRACE) {
            trace("Remove wrapper for %s%n", node.getContext().getInstrumentedSourceSection());
        }
        WrapperNode wrapperNode = node.findWrapper();
        ((Node) wrapperNode).replace(wrapperNode.getDelegateNode());
    }

    private static void invalidateWrapper(Node node) {
        Node parent = node.getParent();
        if (!(parent instanceof WrapperNode)) {
            // not yet wrapped
            return;
        }
        invalidateWrapperImpl((WrapperNode) parent, node);
    }

    private static void invalidateWrapperImpl(WrapperNode parent, Node node) {
        ProbeNode probeNode = parent.getProbeNode();
        if (TRACE) {
            SourceSection section = probeNode.getContext().getInstrumentedSourceSection();
            trace("Invalidate wrapper for %s, section %s %n", node, section);
        }
        if (probeNode != null) {
            probeNode.invalidate();
        }
    }

    static Instrumentable getInstrumentable(Node node) {
        Instrumentable instrumentable = node.getClass().getAnnotation(Instrumentable.class);
        if (instrumentable != null && !(node instanceof WrapperNode)) {
            return instrumentable;
        }
        return null;
    }

    private <T> T lookup(Object key, Class<T> type) {
        AbstractInstrumenter value = instrumenterMap.get(key);
        return value == null ? null : value.lookup(this, type);
    }

    private abstract class AbstractNodeVisitor implements NodeVisitor {

        protected RootNode root;

        abstract boolean shouldVisit();

    }

    private abstract class AbstractBindingVisitor extends AbstractNodeVisitor {

        protected final EventBinding<?> binding;

        AbstractBindingVisitor(EventBinding<?> binding) {
            this.binding = binding;
        }

        @Override
        boolean shouldVisit() {
            return isInstrumentedRoot(root, binding, root.getSourceSection());
        }

        public final boolean visit(Node node) {
            SourceSection sourceSection = node.getSourceSection();
            if (sourceSection != null) {
                if (isInstrumentableNode(node) && isInstrumentedLeaf(binding, sourceSection)) {
                    if (TRACE) {
                        trace("Filter hit section:%s%n", sourceSection);
                    }
                    visitInstrumented(node, sourceSection);
                }
            }
            return true;
        }

        protected abstract void visitInstrumented(Node node, SourceSection section);

    }

    private abstract class AbstractBindingsVisitor extends AbstractNodeVisitor {

        private final List<EventBinding<?>> bindings;

        AbstractBindingsVisitor(List<EventBinding<?>> bindings) {
            this.bindings = bindings;
        }

        @Override
        boolean shouldVisit() {
            SourceSection sourceSection = root.getSourceSection();
            for (int i = 0; i < bindings.size(); i++) {
                EventBinding<?> binding = bindings.get(i);
                if (isInstrumentedRoot(root, binding, sourceSection)) {
                    return true;
                }
            }
            return false;
        }

        public final boolean visit(Node node) {
            SourceSection sourceSection = node.getSourceSection();
            if (sourceSection != null) {
                List<EventBinding<?>> b = bindings;
                for (int i = 0; i < b.size(); i++) {
                    EventBinding<?> binding = b.get(i);
                    if (isInstrumentableNode(node) && isInstrumented(root, node, binding, sourceSection)) {
                        if (TRACE) {
                            trace("Filter hit section:%s", sourceSection);
                        }
                        visitInstrumented(node, sourceSection);
                        break;
                    }
                }
            }
            return true;
        }

        protected abstract void visitInstrumented(Node node, SourceSection section);

    }

    /* Insert wrappers for a single bindings. */
    private final class AddBindingVisitor extends AbstractBindingVisitor {

        AddBindingVisitor(EventBinding<?> filter) {
            super(filter);
        }

        @Override
        protected void visitInstrumented(Node node, SourceSection section) {
            insertWrapper(node, section);
        }

    }

    private final class DisposeBindingVisitor extends AbstractBindingVisitor {

        DisposeBindingVisitor(EventBinding<?> binding) {
            super(binding);
        }

        @Override
        protected void visitInstrumented(Node node, SourceSection section) {
            invalidateWrapper(node);
        }
    }

    private final class AddBindingsVisitor extends AbstractBindingsVisitor {

        AddBindingsVisitor(List<EventBinding<?>> bindings) {
            super(bindings);
        }

        @Override
        protected void visitInstrumented(Node node, SourceSection section) {
            insertWrapper(node, section);
        }
    }

    private final class DisposeBindingsVisitor extends AbstractBindingsVisitor {

        DisposeBindingsVisitor(List<EventBinding<?>> bindings) {
            super(bindings);
        }

        @Override
        protected void visitInstrumented(Node node, SourceSection section) {
            invalidateWrapper(node);
        }

    }

    /**
     * Provider of instrumentation services for {@linkplain TruffleInstrument external clients} of
     * instrumentation.
     */
    final class InstrumentClientInstrumenter extends AbstractInstrumenter {

        private final Class<?> instrumentClass;
        private Object[] services;
        private TruffleInstrument instrument;
        private final Env env;

        InstrumentClientInstrumenter(Class<?> instrumentClass, OutputStream out, OutputStream err, InputStream in) {
            this.instrumentClass = instrumentClass;
            this.env = new Env(this, out, err, in);
        }

        @Override
        boolean isInstrumentable(Node rootNode) {
            return true;
        }

        Class<?> getInstrumentClass() {
            return instrumentClass;
        }

        Env getEnv() {
            return env;
        }

        @Override
        void initialize() {
            if (TRACE) {
                trace("Initialize instrument %s class %s %n", instrument, instrumentClass);
            }
            assert instrument == null;
            try {
                this.instrument = (TruffleInstrument) instrumentClass.newInstance();
            } catch (InstantiationException | IllegalAccessException e) {
                failInstrumentInitialization(String.format("Failed to create new instrumenter class %s", instrumentClass.getName()), e);
                return;
            }
            try {
                services = env.onCreate(instrument);
            } catch (Throwable e) {
                failInstrumentInitialization(String.format("Failed calling onCreate of instrument class %s", instrumentClass.getName()), e);
                return;
            }
            if (TRACE) {
                trace("Initialized instrument %s class %s %n", instrument, instrumentClass);
            }
        }

        private void failInstrumentInitialization(String message, Throwable t) {
            Exception exception = new Exception(message, t);
            PrintStream stream = new PrintStream(env.err());
            exception.printStackTrace(stream);
        }

        boolean isInitialized() {
            return instrument != null;
        }

        TruffleInstrument getInstrument() {
            return instrument;
        }

        @Override
        void dispose() {
            if (isInitialized()) {
                instrument.onDispose(env);
            }
        }

        @Override
        <T> T lookup(InstrumentationHandler handler, Class<T> type) {
            if (instrument == null) {
                handler.initializeInstrumentation();
            }
            if (services != null) {
                for (Object service : services) {
                    if (type.isInstance(service)) {
                        return type.cast(service);
                    }
                }
            }
            return null;
        }

    }

    /**
     * Provider of instrumentation services for {@linkplain TruffleLanguage language
     * implementations}.
     */
    final class LanguageClientInstrumenter<T> extends AbstractInstrumenter {
        @SuppressWarnings("unused") private final TruffleLanguage.Env env;
        private final TruffleLanguage<T> language;

        LanguageClientInstrumenter(TruffleLanguage<T> language, TruffleLanguage.Env env) {
            this.language = language;
            this.env = env;
        }

        @Override
        boolean isInstrumentable(Node node) {
            if (ACCESSOR.findLanguage(node.getRootNode()) != language.getClass()) {
                return false;
            }
            // TODO (chumer) check for the context instance
            return true;
        }

        @Override
        void initialize() {
            // nothing to do
        }

        @Override
        void dispose() {
            // nothing to do
        }

        @Override
        <S> S lookup(InstrumentationHandler handler, Class<S> type) {
            return null;
        }
    }

    /**
     * Shared implementation of instrumentation services for clients whose requirements and
     * privileges may vary.
     */
    abstract class AbstractInstrumenter extends Instrumenter {

        abstract void initialize();

        abstract void dispose();

        abstract <T> T lookup(InstrumentationHandler handler, Class<T> type);

        void disposeBinding(EventBinding<?> binding) {
            InstrumentationHandler.this.disposeBinding(binding);
        }

        abstract boolean isInstrumentable(Node rootNode);

        @Override
        public final <T extends ExecutionEventNodeFactory> EventBinding<T> attachFactory(SourceSectionFilter filter, T factory) {
            return InstrumentationHandler.this.attachFactory(this, filter, factory);
        }

        @Override
        public final <T extends ExecutionEventListener> EventBinding<T> attachListener(SourceSectionFilter filter, T listener) {
            return InstrumentationHandler.this.attachListener(this, filter, listener);
        }

    }

    static final AccessorInstrumentHandler ACCESSOR = new AccessorInstrumentHandler();

    static final class AccessorInstrumentHandler extends Accessor {

        @SuppressWarnings("rawtypes")
        @Override
        protected Class<? extends TruffleLanguage> findLanguage(RootNode n) {
            return super.findLanguage(n);
        }

        @SuppressWarnings("rawtypes")
        @Override
        protected CallTarget parse(Class<? extends TruffleLanguage> languageClass, Source code, Node context, String... argumentNames) throws IOException {
            return super.parse(languageClass, code, context, argumentNames);
        }

        @Override
        protected Object createInstrumentationHandler(Object vm, OutputStream out, OutputStream err, InputStream in) {
            return new InstrumentationHandler(out, err, in);
        }

        @Override
        protected void addInstrument(Object instrumentationHandler, Object key, Class<?> instrumentClass) {
            ((InstrumentationHandler) instrumentationHandler).addInstrument(key, instrumentClass);
        }

        @Override
        protected void disposeInstrument(Object instrumentationHandler, Object key, boolean cleanupRequired) {
            ((InstrumentationHandler) instrumentationHandler).disposeInstrumenter(key, cleanupRequired);
        }

        @Override
        protected boolean isInstrumentable(RootNode rootNode) {
            return super.isInstrumentable(rootNode);
        }

        @Override
        protected void collectEnvServices(Set<Object> collectTo, Object vm, TruffleLanguage<?> impl, TruffleLanguage.Env env) {
            InstrumentationHandler instrumentationHandler = (InstrumentationHandler) ACCESSOR.getInstrumentationHandler(vm);
            Instrumenter instrumenter = instrumentationHandler.forLanguage(env, impl);
            collectTo.add(instrumenter);
        }

        @Override
        protected <T> T getInstrumentationHandlerService(Object vm, Object key, Class<T> type) {
            InstrumentationHandler instrumentationHandler = (InstrumentationHandler) vm;
            return instrumentationHandler.lookup(key, type);
        }

        @Override
        protected void detachLanguageFromInstrumentation(Object vm, com.oracle.truffle.api.TruffleLanguage.Env env) {
            InstrumentationHandler instrumentationHandler = (InstrumentationHandler) ACCESSOR.getInstrumentationHandler(vm);
            instrumentationHandler.detachLanguage(findContext(env));
        }

        @Override
        protected void initializeCallTarget(RootCallTarget target) {
            Object instrumentationHandler = ACCESSOR.getInstrumentationHandler(null);
            // we want to still support cases where call targets are executed without an enclosing
            // engine.
            if (instrumentationHandler != null) {
                ((InstrumentationHandler) instrumentationHandler).installRootNode(target.getRootNode());
            }
        }
    }

}
