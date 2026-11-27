package com.aresstack.askai.browser.playwright;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DriverClasspathJarTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void longClasspathBecomesOneShortEntryThatStillLoadsEveryResource() throws Exception {
        // 400 jars under a deep directory with a space: far beyond the 32 767 characters of CreateProcess.
        Path deep = temp.newFolder("a very long directory name").toPath();
        for (int i = 0; i < 6; i++) {
            deep = deep.resolve("nested-gradle-cache-like-segment-" + i);
        }
        Files.createDirectories(deep);
        List<String> entries = new ArrayList<String>();
        for (int i = 0; i < 400; i++) {
            Path jar = deep.resolve("library-with-a-long-artifact-name-" + i + ".jar");
            writeJarWithResource(jar, "marker-" + i + ".txt");
            entries.add(jar.toString());
        }
        Path dir = temp.newFolder("classes dir").toPath();
        Files.write(dir.resolve("from-directory.txt"), new byte[] {1});
        entries.add(dir.toString());
        String classpath = String.join(File.pathSeparator, entries);
        assertTrue(classpath.length() > 32767);

        Path pathing = DriverClasspathJar.write(classpath, temp.newFolder("cache").toPath());
        assertTrue(pathing.toString().length() < 400);
        try (JarFile jar = new JarFile(pathing.toFile())) {
            String manifestPath = jar.getManifest().getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
            assertEquals(401, manifestPath.split(" ").length);
        }
        try (URLClassLoader loader = new URLClassLoader(new URL[] {pathing.toUri().toURL()}, null)) {
            assertNotNull(loader.getResource("marker-0.txt"));
            assertNotNull(loader.getResource("marker-399.txt"));
            assertNotNull(loader.getResource("from-directory.txt"));
        }
        // Same classpath → same jar, reused.
        assertEquals(pathing, DriverClasspathJar.write(classpath, pathing.getParent()));
    }

    @Test
    public void installPointsPlaywright4jAtAJarThatReachesTheDriverMainClass() throws Exception {
        String before = System.getProperty(DriverClasspathJar.CLASSPATH_PROPERTY);
        try {
            System.clearProperty(DriverClasspathJar.CLASSPATH_PROPERTY);
            DriverClasspathJar.install();
            String jar = System.getProperty(DriverClasspathJar.CLASSPATH_PROPERTY);
            assertNotNull(jar);
            assertTrue(jar.endsWith(".jar"));
            try (URLClassLoader loader = new URLClassLoader(new URL[] {new File(jar).toURI().toURL()}, null);
                 InputStream main = loader.getResourceAsStream(
                         "com/aresstack/playwright4j/driver/GraalDriverMain.class")) {
                assertNotNull(main);
            }
            DriverClasspathJar.install(); // ours (JVM-wide marker): re-detected, not mistaken for a user setting
            assertTrue(System.getProperty(DriverClasspathJar.CLASSPATH_PROPERTY).endsWith(".jar"));
            // A jar left behind by an earlier plugin generation (marker set, static state gone) is replaced.
            System.setProperty(DriverClasspathJar.CLASSPATH_PROPERTY, "old-generation.jar");
            System.setProperty(DriverClasspathJar.OWNED_PROPERTY, "old-generation.jar");
            DriverClasspathJar.install();
            assertEquals(jar, System.getProperty(DriverClasspathJar.CLASSPATH_PROPERTY));
            System.setProperty(DriverClasspathJar.CLASSPATH_PROPERTY, "user-choice.jar");
            DriverClasspathJar.install();
            assertEquals("user-choice.jar", System.getProperty(DriverClasspathJar.CLASSPATH_PROPERTY));
        } finally {
            if (before == null) {
                System.clearProperty(DriverClasspathJar.CLASSPATH_PROPERTY);
            } else {
                System.setProperty(DriverClasspathJar.CLASSPATH_PROPERTY, before);
            }
        }
    }

    private static void writeJarWithResource(Path jar, String resource) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream zip = new JarOutputStream(out, manifest)) {
            zip.putNextEntry(new ZipEntry(resource));
            zip.write(1);
            zip.closeEntry();
        }
    }
}
