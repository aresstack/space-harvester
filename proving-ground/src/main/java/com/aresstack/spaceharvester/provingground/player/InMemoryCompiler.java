package com.aresstack.spaceharvester.provingground.player;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * One Java class compiled in memory with the JDK's compiler and loaded on top of a class loader, also inside the
 * packaged app (classes the code uses are read through {@link LoaderClassPath}).
 */
final class InMemoryCompiler {

    static final String NO_COMPILER = "Zum Abspielen wird ein JDK gebraucht (der Java-Compiler fehlt)";

    /** A compiler error: line in the source and message. */
    record Error(long line, String message) {
    }

    /**
     * The outcome: the loaded class, or the errors, or why there was nothing to compile with.
     *
     * @param type    the loaded class, or null
     * @param errors  compiler errors (empty when compiled)
     * @param failure why it could not be compiled or loaded at all, or null
     */
    record Compiled(Class<?> type, List<Error> errors, String failure) {
    }

    private InMemoryCompiler() {
    }

    static Compiled compile(String className, CharSequence source, ClassLoader runtime, String fileClasspath,
                            List<Class<?>> anchors) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return new Compiled(null, List.of(), NO_COMPILER);
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Map<String, ByteArrayOutputStream> classes = new HashMap<>();
        try (StandardJavaFileManager standard = compiler.getStandardFileManager(diagnostics, Locale.GERMAN,
                StandardCharsets.UTF_8)) {
            // Packaged app: Playwright lies in nested JARs the compiler cannot open; the loader that loaded it reads them.
            JavaFileManager loaded = new LoaderClassPath(standard, runtime, anchors);
            JavaFileManager memory = new ForwardingJavaFileManager<>(loaded) {
                @Override
                public JavaFileObject getJavaFileForOutput(Location location, String name, JavaFileObject.Kind kind,
                                                           FileObject sibling) {
                    return new SimpleJavaFileObject(URI.create("mem:///" + name.replace('.', '/') + kind.extension),
                            kind) {
                        @Override
                        public OutputStream openOutputStream() {
                            return classes.computeIfAbsent(name, key -> new ByteArrayOutputStream());
                        }
                    };
                }
            };
            JavaFileObject unit = new SimpleJavaFileObject(URI.create("string:///" + className + ".java"),
                    JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return source;
                }
            };
            boolean compiled = compiler.getTask(null, memory, diagnostics,
                    List.of("-classpath", fileClasspath, "-proc:none", "-nowarn", "-g"), null, List.of(unit)).call();
            if (!compiled) {
                List<Error> errors = new ArrayList<>();
                for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                    if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                        errors.add(new Error(diagnostic.getLineNumber(),
                                diagnostic.getMessage(Locale.GERMAN).lines().findFirst().orElse("")));
                    }
                }
                return new Compiled(null, errors, errors.isEmpty() ? "nicht übersetzbar" : null);
            }
        } catch (java.io.IOException broken) {
            return new Compiled(null, List.of(), "Java-Compiler: " + broken.getMessage());
        }
        try {
            ClassLoader loader = new ClassLoader(runtime) {
                @Override
                protected Class<?> findClass(String name) throws ClassNotFoundException {
                    ByteArrayOutputStream bytes = classes.get(name);
                    if (bytes == null) {
                        throw new ClassNotFoundException(name);
                    }
                    byte[] data = bytes.toByteArray();
                    return defineClass(name, data, 0, data.length);
                }
            };
            return new Compiled(loader.loadClass(className), List.of(), null);
        } catch (ReflectiveOperationException | LinkageError broken) {
            return new Compiled(null, List.of(), "nicht ladbar: " + broken);
        }
    }
}
