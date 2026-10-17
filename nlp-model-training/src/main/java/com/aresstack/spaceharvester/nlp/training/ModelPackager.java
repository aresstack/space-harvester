package com.aresstack.spaceharvester.nlp.training;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Packt die Release-Assets eines Modells in ein eigenes Verzeichnis:
 * <ul>
 *     <li>{@code <tag>.zip}: {@code <configName>.mco}, {@code model-manifest.json}, {@code evaluation.json},
 *     {@code LICENSE-MODEL.md}, {@code ATTRIBUTION.md} und die Lizenzdatei der Treebank,</li>
 *     <li>{@code <tag>.manifest.json}: dasselbe Manifest einzeln,</li>
 *     <li>{@code SHA256SUMS}: {@code <sha256>  <Datei>} je Asset, wie {@code sha256sum}.</li>
 * </ul>
 * Das ZIP ist bei gleichem Inhalt byte-gleich (sortierte Einträge, feste Zeitstempel).
 */
public final class ModelPackager {

    /** Fester Zeitstempel aller ZIP-Einträge, damit gleicher Inhalt dieselbe SHA-256 ergibt. */
    private static final LocalDateTime ENTRY_TIME = LocalDateTime.of(1980, 2, 1, 0, 0);

    public record Assets(Path directory, Path zip, Path manifest, Path checksums, Map<String, String> sha256) {
    }

    /**
     * @param files Inhalt des ZIP: Eintragsname → Quelldatei; das Manifest kommt als Text dazu
     */
    public Assets pack(ModelRecipe recipe, String manifestJson, Map<String, Path> files, Path directory) {
        String tag = recipe.releaseTag();
        try {
            MaltModelTrainer.deleteTree(directory);
            Files.createDirectories(directory);
            Path zip = directory.resolve(tag + ".zip");
            Map<String, byte[]> entries = new TreeMap<>();
            for (Map.Entry<String, Path> file : files.entrySet()) {
                entries.put(file.getKey(), Files.readAllBytes(file.getValue()));
            }
            entries.put("model-manifest.json", manifestJson.getBytes(StandardCharsets.UTF_8));
            writeZip(zip, entries);
            Path manifest = directory.resolve(tag + ".manifest.json");
            Files.writeString(manifest, manifestJson, StandardCharsets.UTF_8);
            Map<String, String> sums = new TreeMap<>();
            sums.put(zip.getFileName().toString(), Hashing.sha256(zip));
            sums.put(manifest.getFileName().toString(), Hashing.sha256(manifest));
            Path checksums = directory.resolve("SHA256SUMS");
            List<String> lines = new ArrayList<>();
            sums.forEach((name, sha) -> lines.add(sha + "  " + name));
            Files.writeString(checksums, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
            return new Assets(directory, zip, manifest, checksums, sums);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void writeZip(Path zip, Map<String, byte[]> entries) throws IOException {
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream zipOut = new ZipOutputStream(out)) {
            zipOut.setLevel(9);
            for (Map.Entry<String, byte[]> entry : new TreeMap<>(entries).entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setTimeLocal(ENTRY_TIME);
                zipOut.putNextEntry(zipEntry);
                zipOut.write(entry.getValue());
                zipOut.closeEntry();
            }
        }
    }
}
