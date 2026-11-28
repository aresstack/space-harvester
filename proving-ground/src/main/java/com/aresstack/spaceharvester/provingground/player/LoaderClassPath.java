package com.aresstack.spaceharvester.provingground.player;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardLocation;

/**
 * The compiler's class path taken from a class loader instead of the file system. The packaged app is a Spring Boot
 * JAR: Playwright and Proving Ground lie as JARs inside it ({@code BOOT-INF/lib}), the JVM's class path names only the
 * outer JAR, and the code sources are {@code jar:nested:} URLs the compiler cannot open. So the classes the generated
 * code uses are listed from the loader that loaded them (nested JAR, plain JAR or folder) and read through it.
 *
 * <p>Only packages the standard class path does not know are served from the loader; the JDK's own classes come from
 * the platform as usual.</p>
 */
final class LoaderClassPath extends ForwardingJavaFileManager<JavaFileManager> {

    private final ClassLoader loader;
    private final List<URL> roots = new ArrayList<>();
    private final Map<String, List<JavaFileObject>> packages = new LinkedHashMap<>();

    /**
     * @param anchors classes whose JARs are searched, too, when the loader lists no folder for a package (JARs
     *                without directory entries)
     */
    LoaderClassPath(JavaFileManager standard, ClassLoader loader, List<Class<?>> anchors) {
        super(standard);
        this.loader = loader;
        for (Class<?> anchor : anchors) {
            URL own = loader.getResource(anchor.getName().replace('.', '/') + ".class");
            if (own != null && "jar".equals(own.getProtocol())) {
                String text = own.toString();
                try {
                    roots.add(new URL(text.substring(0, text.lastIndexOf("!/") + 2)));
                } catch (java.net.MalformedURLException odd) {
                    // then only the loader's folders count
                }
            }
        }
    }

    @Override
    public Iterable<JavaFileObject> list(Location location, String packageName, Set<JavaFileObject.Kind> kinds,
                                         boolean recurse) throws IOException {
        Iterable<JavaFileObject> standard = super.list(location, packageName, kinds, recurse);
        if (location != StandardLocation.CLASS_PATH || !kinds.contains(JavaFileObject.Kind.CLASS)
                || packageName.startsWith("java.") || packageName.startsWith("javax.")) {
            return standard;
        }
        if (standard.iterator().hasNext()) {
            return standard;
        }
        return packages.computeIfAbsent(packageName, this::fromLoader);
    }

    @Override
    public String inferBinaryName(Location location, JavaFileObject file) {
        return file instanceof LoadedClass loaded ? loaded.binaryName : super.inferBinaryName(location, file);
    }

    @Override
    public boolean isSameFile(javax.tools.FileObject a, javax.tools.FileObject b) {
        if (a instanceof LoadedClass || b instanceof LoadedClass) {
            return a.toUri().equals(b.toUri());
        }
        return super.isSameFile(a, b);
    }

    /** The class files of {@code packageName} (not its subpackages) wherever the loader finds the package. */
    private List<JavaFileObject> fromLoader(String packageName) {
        String folder = packageName.replace('.', '/');
        List<JavaFileObject> classes = new ArrayList<>();
        try {
            List<URL> found = java.util.Collections.list(loader.getResources(folder));
            List<URL> places = found.isEmpty() ? roots : found;
            for (URL place : places) {
                for (String entry : entries(place, folder)) {
                    String simple = entry.substring(folder.length() + 1, entry.length() - ".class".length());
                    if (simple.equals("module-info") || simple.equals("package-info")) {
                        continue;
                    }
                    classes.add(new LoadedClass(loader, entry, packageName + "." + simple));
                }
            }
        } catch (IOException unreadable) {
            // then the package stays unknown and the compiler says which symbol is missing
        }
        return classes;
    }

    /** Names of the class files directly in {@code folder} at {@code place} (a JAR, nested JAR or directory). */
    private static List<String> entries(URL place, String folder) throws IOException {
        List<String> names = new ArrayList<>();
        if ("file".equals(place.getProtocol())) {
            try (var files = Files.list(Path.of(place.toURI()))) {
                files.map(Path::getFileName).map(Path::toString).filter(name -> name.endsWith(".class"))
                        .forEach(name -> names.add(folder + "/" + name));
            } catch (URISyntaxException | IllegalArgumentException odd) {
                // not a plain folder
            }
            return names;
        }
        URLConnection connection = place.openConnection();
        connection.setUseCaches(false);
        if (connection instanceof JarURLConnection jar) {
            try (JarFile file = jar.getJarFile()) {
                Enumeration<JarEntry> all = file.entries();
                while (all.hasMoreElements()) {
                    String name = all.nextElement().getName();
                    if (name.startsWith(folder + "/") && name.endsWith(".class")
                            && name.indexOf('/', folder.length() + 1) < 0) {
                        names.add(name);
                    }
                }
            }
        }
        return names;
    }

    /** A class file read through the class loader. */
    private static final class LoadedClass extends SimpleJavaFileObject {

        private final ClassLoader loader;
        private final String resource;
        private final String binaryName;

        LoadedClass(ClassLoader loader, String resource, String binaryName) {
            super(URI.create("loader:///" + resource), Kind.CLASS);
            this.loader = loader;
            this.resource = resource;
            this.binaryName = binaryName;
        }

        @Override
        public InputStream openInputStream() throws IOException {
            InputStream in = loader.getResourceAsStream(resource);
            if (in == null) {
                throw new IOException("Klasse nicht lesbar: " + resource);
            }
            return in;
        }

        @Override
        public boolean isNameCompatible(String simpleName, Kind kind) {
            return kind == Kind.CLASS && binaryName.endsWith("." + simpleName);
        }
    }

    /** Whether {@code entry} of the class path is a real file the compiler can open itself. */
    static boolean openable(String entry) {
        return entry != null && !entry.isBlank() && new File(entry).exists();
    }
}
