/*
 * Copyright (c) 2013, 2017 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.truffle;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerOptions;
import com.oracle.truffle.api.ExecutionContext;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleOptions;
import com.oracle.truffle.api.instrumentation.Instrumenter;
import com.oracle.truffle.api.object.DynamicObject;
import org.jruby.truffle.builtins.PrimitiveManager;
import org.jruby.truffle.core.CoreLibrary;
import org.jruby.truffle.core.CoreMethods;
import org.jruby.truffle.core.encoding.EncodingManager;
import org.jruby.truffle.core.exception.CoreExceptions;
import org.jruby.truffle.core.kernel.AtExitManager;
import org.jruby.truffle.core.kernel.TraceManager;
import org.jruby.truffle.core.module.ModuleOperations;
import org.jruby.truffle.core.objectspace.ObjectSpaceManager;
import org.jruby.truffle.core.rope.RopeTable;
import org.jruby.truffle.core.string.CoreStrings;
import org.jruby.truffle.core.string.FrozenStrings;
import org.jruby.truffle.core.symbol.SymbolTable;
import org.jruby.truffle.core.thread.ThreadManager;
import org.jruby.truffle.interop.InteropManager;
import org.jruby.truffle.language.CallStackManager;
import org.jruby.truffle.language.LexicalScope;
import org.jruby.truffle.language.RubyGuards;
import org.jruby.truffle.language.SafepointManager;
import org.jruby.truffle.language.arguments.RubyArguments;
import org.jruby.truffle.language.control.JavaException;
import org.jruby.truffle.language.loader.CodeLoader;
import org.jruby.truffle.language.loader.FeatureLoader;
import org.jruby.truffle.language.loader.SourceLoader;
import org.jruby.truffle.language.methods.DeclarationContext;
import org.jruby.truffle.language.methods.InternalMethod;
import org.jruby.truffle.language.objects.shared.SharedObjects;
import org.jruby.truffle.options.Options;
import org.jruby.truffle.options.OptionsBuilder;
import org.jruby.truffle.options.Verbosity;
import org.jruby.truffle.platform.NativePlatform;
import org.jruby.truffle.platform.NativePlatformFactory;
import org.jruby.truffle.stdlib.CoverageManager;
import org.jruby.truffle.stdlib.readline.ConsoleHolder;
import org.jruby.truffle.tools.InstrumentationServerManager;
import org.jruby.truffle.tools.callgraph.CallGraph;
import org.jruby.truffle.tools.callgraph.SimpleWriter;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.CodeSource;

public class RubyContext extends ExecutionContext {

    private final TruffleLanguage.Env env;

    private final Options options;

    private final String rubyHome;
    private String originalInputFile;

    private InputStream syntaxCheckInputStream;
    private boolean verbose;

    private final RopeTable ropeTable = new RopeTable();
    private final PrimitiveManager primitiveManager = new PrimitiveManager();
    private final SafepointManager safepointManager = new SafepointManager(this);
    private final SymbolTable symbolTable;
    private final InteropManager interopManager = new InteropManager(this);
    private final CodeLoader codeLoader = new CodeLoader(this);
    private final FeatureLoader featureLoader = new FeatureLoader(this);
    private final TraceManager traceManager;
    private final ObjectSpaceManager objectSpaceManager = new ObjectSpaceManager(this);
    private final SharedObjects sharedObjects = new SharedObjects(this);
    private final AtExitManager atExitManager = new AtExitManager(this);
    private final SourceLoader sourceLoader = new SourceLoader(this);
    private final CallStackManager callStack = new CallStackManager(this);
    private final CoreStrings coreStrings = new CoreStrings(this);
    private final FrozenStrings frozenStrings = new FrozenStrings(this);
    private final CoreExceptions coreExceptions = new CoreExceptions(this);
    private final EncodingManager encodingManager = new EncodingManager(this);

    private final CompilerOptions compilerOptions = Truffle.getRuntime().createCompilerOptions();

    private final NativePlatform nativePlatform;
    private final CoreLibrary coreLibrary;
    private final CoreMethods coreMethods;
    private final ThreadManager threadManager;
    private final LexicalScope rootLexicalScope;
    private final InstrumentationServerManager instrumentationServerManager;
    private final CallGraph callGraph;
    private final PrintStream debugStandardOut;
    private final CoverageManager coverageManager;
    private final ConsoleHolder consoleHolder;

    private final Object classVariableDefinitionLock = new Object();

    private String currentDirectory;

    public static ThreadLocal<RubyContext> contextsBeingCreated = new ThreadLocal<>();

    public RubyContext(TruffleLanguage.Env env) {
        contextsBeingCreated.set(this);

        try {
            this.env = env;

            final OptionsBuilder optionsBuilder = new OptionsBuilder();
            optionsBuilder.set(env.getConfig());
            optionsBuilder.set(System.getProperties());
            options = optionsBuilder.build();

            rubyHome = findRubyHome();
            Log.config("home: %s", rubyHome);

            currentDirectory = System.getProperty("user.dir");
            verbose = options.VERBOSITY.equals(Verbosity.TRUE);

            if (options.CALL_GRAPH) {
                callGraph = new CallGraph();
            } else {
                callGraph = null;
            }

            // Stuff that needs to be loaded before we load any code

        /*
         * The Graal option TimeThreshold sets how long a method has to become hot after it has started running, in ms.
         * This is designed to not try to compile cold methods that just happen to be called enough times during a
         * very long running program. We haven't worked out the best value of this for Ruby yet, and the default value
         * produces poor benchmark results. Here we just set it to a very high value, to effectively disable it.
         */

            if (compilerOptions.supportsOption("MinTimeThreshold")) {
                compilerOptions.setOption("MinTimeThreshold", 100000000);
            }

        /*
         * The Graal option InliningMaxCallerSize sets the maximum size of a method for where we consider to inline
         * calls from that method. So it's the caller method we're talking about, not the called method. The default
         * value doesn't produce good results for Ruby programs, but we aren't sure why yet. Perhaps it prevents a few
         * key methods from the core library from inlining other methods.
         */

            if (compilerOptions.supportsOption("MinInliningMaxCallerSize")) {
                compilerOptions.setOption("MinInliningMaxCallerSize", 5000);
            }

            // Load the core library classes

            coreLibrary = new CoreLibrary(this);
            coreLibrary.initialize();

            symbolTable = new SymbolTable(coreLibrary.getSymbolFactory());

            // Create objects that need core classes

            nativePlatform = NativePlatformFactory.createPlatform(this);
            rootLexicalScope = new LexicalScope(null, coreLibrary.getObjectClass());

            // The encoding manager relies on POSIX having been initialized, so we can't process it during
            // normal core library initialization.
            coreLibrary.initializeEncodingManager();

            threadManager = new ThreadManager(this);
            threadManager.initialize();

            // Load the nodes

            Main.printTruffleTimeMetric("before-load-nodes");
            coreLibrary.addCoreMethods(primitiveManager);
            Main.printTruffleTimeMetric("after-load-nodes");

            // Capture known builtin methods

            final Instrumenter instrumenter = env.lookup(Instrumenter.class);
            traceManager = new TraceManager(this, instrumenter);
            coreMethods = new CoreMethods(this);
            coverageManager = new CoverageManager(this, instrumenter);

            // Load the reset of the core library

            coreLibrary.loadRubyCore();

            // Load other subsystems

            final PrintStream configStandardOut = System.out;
            debugStandardOut = (configStandardOut == System.out) ? null : configStandardOut;

            // The instrumentation server can't be run with AOT because com.sun.net.httpserver.spi.HttpServerProvider uses runtime class loading.
            if (!TruffleOptions.AOT && options.INSTRUMENTATION_SERVER_PORT != 0) {
                instrumentationServerManager = new InstrumentationServerManager(this, options.INSTRUMENTATION_SERVER_PORT);
                instrumentationServerManager.start();
            } else {
                instrumentationServerManager = null;
            }

            coreLibrary.initializePostBoot();

            consoleHolder = new ConsoleHolder();

            // Share once everything is loaded
            if (options.SHARED_OBJECTS_ENABLED && options.SHARED_OBJECTS_FORCE) {
                sharedObjects.startSharing();
            }
        } finally {
            contextsBeingCreated.remove();
        }
    }

    private String findRubyHome() {
        if (options.HOME != null) {
            return options.HOME;
        }

        String fromENV = System.getenv("JRUBY_HOME");
        if (fromENV != null) {
            return fromENV;
        }

        String fromProperty = System.getProperty("jruby.home");
        if (fromProperty != null) {
            return fromProperty;
        }

        if (!TruffleOptions.AOT) {
            // Set JRuby home automatically for GraalVM and mx from the current jar path
            CodeSource result;
            try {
                result = Class.forName("org.jruby.Ruby").getProtectionDomain().getCodeSource();
            } catch (Exception e1) {
                throw new RuntimeException("Error getting the classic code source", e1);
            }
            final CodeSource codeSource = result;
            if (codeSource != null) {
                final File currentJarFile;
                try {
                    currentJarFile = new File(codeSource.getLocation().toURI());
                } catch (URISyntaxException e) {
                    throw new JavaException(e);
                }

                if (currentJarFile.getName().equals("ruby.jar")) {
                    File jarDir = currentJarFile.getParentFile();

                    // GraalVM
                    if (new File(jarDir, "lib").isDirectory()) {
                        return jarDir.getPath();
                    }

                    // mx: mxbuild/dists/ruby.jar
                    if (jarDir.getName().equals("dists") && jarDir.getParentFile().getName().equals("mxbuild")) {
                        String mxbuildDir = currentJarFile.getParentFile().getParent();
                        File mxJRubyHome = new File(mxbuildDir, "ruby-zip-extracted");
                        if (mxJRubyHome.isDirectory()) {
                            return mxJRubyHome.getPath();
                        }
                    }
                }
            }
        }

        return null;
    }

    public Object send(Object object, String methodName, DynamicObject block, Object... arguments) {
        CompilerAsserts.neverPartOfCompilation();

        assert block == null || RubyGuards.isRubyProc(block);

        final InternalMethod method = ModuleOperations.lookupMethod(coreLibrary.getMetaClass(object), methodName);

        if (method == null || method.isUndefined()) {
            return null;
        }

        return method.getCallTarget().call(
                RubyArguments.pack(null, null, method, DeclarationContext.METHOD, null, object, block, arguments));
    }

    public void shutdown() {
        if (options.ROPE_PRINT_INTERN_STATS) {
            System.out.println("Ropes re-used: " + getRopeTable().getRopesReusedCount());
            System.out.println("Rope byte arrays re-used: " + getRopeTable().getByteArrayReusedCount());
            System.out.println("Rope bytes saved: " + getRopeTable().getRopeBytesSaved());
            System.out.println("Total ropes interned: " + getRopeTable().totalRopes());
        }

        atExitManager.runSystemExitHooks();

        if (instrumentationServerManager != null) {
            instrumentationServerManager.shutdown();
        }

        threadManager.shutdown();

        if (options.COVERAGE_GLOBAL) {
            coverageManager.print(System.out);
        }

        if (callGraph != null) {
            callGraph.resolve();

            if (options.CALL_GRAPH_WRITE != null) {
                try (PrintStream stream = new PrintStream(options.CALL_GRAPH_WRITE, StandardCharsets.UTF_8.name())) {
                    new SimpleWriter(callGraph, stream).write();
                } catch (FileNotFoundException | UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public Options getOptions() {
        return options;
    }

    public TruffleLanguage.Env getEnv() {
        return env;
    }

    public NativePlatform getNativePlatform() {
        return nativePlatform;
    }

    public CoreLibrary getCoreLibrary() {
        return coreLibrary;
    }

    public CoreMethods getCoreMethods() {
        return coreMethods;
    }

    public PrintStream getDebugStandardOut() {
        return debugStandardOut;
    }

    public FeatureLoader getFeatureLoader() {
        return featureLoader;
    }

    public ObjectSpaceManager getObjectSpaceManager() {
        return objectSpaceManager;
    }

    public SharedObjects getSharedObjects() {
        return sharedObjects;
    }

    public ThreadManager getThreadManager() {
        return threadManager;
    }

    public AtExitManager getAtExitManager() {
        return atExitManager;
    }

    public TraceManager getTraceManager() {
        return traceManager;
    }

    public SafepointManager getSafepointManager() {
        return safepointManager;
    }

    public LexicalScope getRootLexicalScope() {
        return rootLexicalScope;
    }

    @Override
    public CompilerOptions getCompilerOptions() {
        return compilerOptions;
    }

    public PrimitiveManager getPrimitiveManager() {
        return primitiveManager;
    }

    public CoverageManager getCoverageManager() {
        return coverageManager;
    }

    public static RubyContext getInstance() {
        try {
            return RubyLanguage.INSTANCE.unprotectedFindContext(RubyLanguage.INSTANCE.unprotectedCreateFindContextNode());
        } catch (IllegalStateException e) {
            return contextsBeingCreated.get();
        }
    }

    public SourceLoader getSourceLoader() {
        return sourceLoader;
    }

    public RopeTable getRopeTable() {
        return ropeTable;
    }

    public SymbolTable getSymbolTable() {
        return symbolTable;
    }

    public CallGraph getCallGraph() {
        return callGraph;
    }

    public CodeLoader getCodeLoader() {
        return codeLoader;
    }

    public InteropManager getInteropManager() {
        return interopManager;
    }

    public CallStackManager getCallStack() {
        return callStack;
    }

    public CoreStrings getCoreStrings() {
        return coreStrings;
    }

    public FrozenStrings getFrozenStrings() {
        return frozenStrings;
    }

    public Object getClassVariableDefinitionLock() {
        return classVariableDefinitionLock;
    }

    public Instrumenter getInstrumenter() {
        return env.lookup(Instrumenter.class);
    }

    public CoreExceptions getCoreExceptions() {
        return coreExceptions;
    }

    public EncodingManager getEncodingManager() {
        return encodingManager;
    }

    public String getCurrentDirectory() {
        return currentDirectory;
    }

    public void setCurrentDirectory(String currentDirectory) {
        this.currentDirectory = currentDirectory;
    }

    public void setOriginalInputFile(String originalInputFile) {
        this.originalInputFile = originalInputFile;
    }

    public String getOriginalInputFile() {
        return originalInputFile;
    }

    public String getRubyHome() {
        return rubyHome;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    public void setVerboseNil() {
        verbose = false;
    }

    public boolean warningsEnabled() {
        return verbose;
    }

    public boolean isVerbose() {
        return verbose;
    }

    public InputStream getSyntaxCheckInputStream() {
        return syntaxCheckInputStream;
    }

    public void setSyntaxCheckInputStream(InputStream syntaxCheckInputStream) {
        this.syntaxCheckInputStream = syntaxCheckInputStream;
    }

    public ConsoleHolder getConsoleHolder() {
        return consoleHolder;
    }

}
