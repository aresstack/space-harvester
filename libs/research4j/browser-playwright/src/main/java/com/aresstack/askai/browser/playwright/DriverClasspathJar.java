package com.aresstack.askai.browser.playwright;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;

/**
 * Keeps the command line of the playwright4j driver JVM short.
 *
 * <p>playwright4j starts the Playwright Core JS driver in a child JVM with {@code -cp <every jar it can see>}:
 * the plugin class loader's URLs plus the host's {@code java.class.path}. In a Gradle test run or with many
 * plugin jars under a long Windows profile path that string passes the 32 767-character limit of
 * {@code CreateProcess}, and the driver never starts ({@code CreateProcess error=206, filename or extension is
 * too long}). This class resolves the same classpath, writes it into the {@code Class-Path} manifest of a small
 * "pathing" jar and points playwright4j at that jar via {@code -Dplaywright4j.driver.classpath}. The child JVM
 * then gets exactly the classes it got before, through one short {@code -cp} entry — in tests and in the app.
 *
 * <p>An explicit {@code -Dplaywright4j.driver.classpath} set by the user is left untouched.
 */
final class DriverClasspathJar {

    static final String CLASSPATH_PROPERTY = "playwright4j.driver.classpath";

    private static final String DRIVER_CLASSPATH_CLASS = "com.microsoft.playwright.impl.driver.DriverClasspath";
    private static final String DRIVER_CLASS = "com.microsoft.playwright.impl.driver.Driver";

    /**
     * Marks the property value as ours, JVM-wide: a static field would be lost when a plugin refresh creates a
     * new class loader, and the next generation would then mistake the old jar for a user setting.
     */
    static final String OWNED_PROPERTY = "askai.playwright4j.driver.classpath.owned";

    /** Anchors of playwright4j's own detection (DriverClasspath.ANCHOR_RESOURCES). */
    private static final String[] ANCHOR_RESOURCES = {
            "com/aresstack/playwright4j/driver/GraalDriverMain.class",
            "com/microsoft/playwright/impl/driver/Driver.class",
            "org/graalvm/polyglot/Context.class",
            "com/oracle/truffle/api/TruffleLanguage.class",
            "com/oracle/truffle/js/lang/JavaScriptLanguage.class",
            "org/jcodec/api/awt/AWTSequenceEncoder.class",
    };

    private DriverClasspathJar() {
    }

    /**
     * Points the next driver start at a pathing jar; failures leave the previous value or playwright4j's own
     * detection in place. The property is never cleared on the way, so a launch running concurrently on
     * another owner thread always sees a usable value.
     */
    static synchronized void install() {
        String current = System.getProperty(CLASSPATH_PROPERTY);
        boolean ours = current != null && current.equals(System.getProperty(OWNED_PROPERTY));
        if (current != null && !current.trim().isEmpty() && !ours) {
            return; // configured by the user
        }
        try {
            Path cache = Paths.get(System.getProperty("java.io.tmpdir"), "playwright4j-driver");
            String classpath = resolve(cache.resolve("classpath"));
            String jar = write(classpath, cache.resolve("askai-pathing")).toString();
            System.setProperty(OWNED_PROPERTY, jar);
            System.setProperty(CLASSPATH_PROPERTY, jar);
        } catch (Exception | LinkageError failed) {
            System.err.println("[browser-mcp] driver classpath jar not prepared: " + failed);
        }
    }

    /**
     * playwright4j's detection (DriverClasspath.resolve) without its override check, so it can run while the
     * override is published: anchor class sources, the URLClassLoader chain, then java.class.path. Each URL
     * goes through playwright4j's own DriverClasspath.toClasspathEntry (nested jars are extracted there).
     */
    private static String resolve(Path nestedJarCache) throws ReflectiveOperationException {
        Class<?> driver = Class.forName(DRIVER_CLASS, false, DriverClasspathJar.class.getClassLoader());
        ClassLoader loader = driver.getClassLoader();
        Class<?> detection = Class.forName(DRIVER_CLASSPATH_CLASS, true, loader);
        Method toEntry = detection.getDeclaredMethod("toClasspathEntry", URL.class, Path.class);
        toEntry.setAccessible(true);
        Set<URL> urls = new LinkedHashSet<URL>();
        for (String resource : ANCHOR_RESOURCES) {
            URL url = loader.getResource(resource);
            if (url != null) {
                urls.add(url);
            }
        }
        if (loader.getResource(ANCHOR_RESOURCES[0]) == null) {
            throw new IllegalStateException("playwright4j driver main class not found");
        }
        for (ClassLoader current = loader; current != null; current = current.getParent()) {
            if (current instanceof URLClassLoader) {
                for (URL url : ((URLClassLoader) current).getURLs()) {
                    urls.add(url);
                }
            }
        }
        Set<String> entries = new LinkedHashSet<String>();
        for (URL url : urls) {
            String entry = (String) toEntry.invoke(null, url, nestedJarCache);
            if (entry != null) {
                entries.add(entry);
            }
        }
        for (String element : System.getProperty("java.class.path", "").split(Pattern.quote(File.pathSeparator))) {
            if (!element.isEmpty()) {
                entries.add(element);
            }
        }
        return String.join(File.pathSeparator, entries);
    }

    /**
     * Writes (or reuses) a jar whose manifest {@code Class-Path} lists every entry of {@code classpath}, named by
     * the hash of that list. @return the jar
     */
    static Path write(String classpath, Path directory) throws IOException {
        StringBuilder manifestPath = new StringBuilder();
        for (String element : classpath.split(Pattern.quote(File.pathSeparator))) {
            if (element.trim().isEmpty()) {
                continue;
            }
            if (manifestPath.length() > 0) {
                manifestPath.append(' ');
            }
            // Absolute file: URIs (directories end in '/'), spaces and non-ASCII percent-encoded.
            manifestPath.append(Paths.get(element).toAbsolutePath().toUri().toASCIIString());
        }
        Files.createDirectories(directory);
        Path jar = directory.resolve("driver-classpath-" + sha256(manifestPath.toString()) + ".jar");
        if (Files.isRegularFile(jar)) {
            return jar;
        }
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, manifestPath.toString());
        Path temp = Files.createTempFile(directory, "driver-classpath-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp); JarOutputStream ignored = new JarOutputStream(out, manifest)) {
                // the manifest is the whole content
            }
            try {
                Files.move(temp, jar, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException raced) {
                if (!Files.isRegularFile(jar)) {
                    throw raced;
                }
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        return jar;
    }

    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
