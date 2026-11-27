package com.aresstack.spaceharvester.browser.runtime;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Die JARs des Treiber-Prozesses der Chat-Websuche (ADR 0046): playwright4j startet den Playwright-Treiber in einem
 * eigenen Java-Prozess auf GraalVM 25. Diese JARs liegen im App-JAR im Ordner {@value #FOLDER}, nie im Klassenpfad
 * der App (dort steht GraalJS 21.3). Vor dem ersten Browserstart werden sie unter
 * {@code <Datenordner>/playwright-driver/<Fassung>} entpackt und über {@value #PROPERTY} an playwright4j gegeben.
 * Ist die Eigenschaft schon gesetzt (Tests, Entwicklung), bleibt sie.
 */
final class PlaywrightDriverJars {

    static final String PROPERTY = "playwright4j.driver.classpath";
    static final String FOLDER = "playwright-driver/";

    private PlaywrightDriverJars() {
    }

    /** Setzt {@value #PROPERTY}, falls nötig und möglich; ohne App-JAR (Entwicklung) bleibt alles, wie es ist. */
    static synchronized void prepare(Path dataDirectory) throws IOException {
        String set = System.getProperty(PROPERTY);
        if (set != null && !set.isBlank()) {
            return;
        }
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (!entry.endsWith(".jar")) {
                continue;
            }
            List<Path> jars = extract(Path.of(entry), dataDirectory.resolve("playwright-driver"));
            if (!jars.isEmpty()) {
                List<String> paths = new ArrayList<>();
                for (Path jar : jars) {
                    paths.add(jar.toAbsolutePath().toString());
                }
                System.setProperty(PROPERTY, String.join(File.pathSeparator, paths));
                return;
            }
        }
    }

    /**
     * Entpackt die Treiber-JARs aus {@code appJar} nach {@code target/<Fassung>}; die Fassung ergibt sich aus Namen
     * und Größen der JARs, so liegt jede Fassung nur einmal da. Ohne solche JARs eine leere Liste.
     */
    static List<Path> extract(Path appJar, Path target) throws IOException {
        if (!Files.isRegularFile(appJar)) {
            return List.of();
        }
        try (ZipFile zip = new ZipFile(appJar.toFile())) {
            List<ZipEntry> entries = new ArrayList<>();
            StringBuilder fingerprint = new StringBuilder();
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                String name = entry.getName();
                if (!entry.isDirectory() && name.startsWith(FOLDER) && name.endsWith(".jar")
                        && name.indexOf('/', FOLDER.length()) < 0) {
                    entries.add(entry);
                    fingerprint.append(name).append(':').append(entry.getSize()).append(';');
                }
            }
            if (entries.isEmpty()) {
                return List.of();
            }
            Path folder = target.resolve(Integer.toHexString(fingerprint.toString().hashCode()));
            Files.createDirectories(folder);
            List<Path> jars = new ArrayList<>();
            for (ZipEntry entry : entries) {
                Path jar = folder.resolve(entry.getName().substring(FOLDER.length()));
                if (!Files.isRegularFile(jar) || Files.size(jar) != entry.getSize()) {
                    Path part = folder.resolve(jar.getFileName() + ".part");
                    try (InputStream in = zip.getInputStream(entry)) {
                        Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
                    }
                    Files.move(part, jar, StandardCopyOption.REPLACE_EXISTING);
                }
                jars.add(jar);
            }
            return jars;
        }
    }
}
