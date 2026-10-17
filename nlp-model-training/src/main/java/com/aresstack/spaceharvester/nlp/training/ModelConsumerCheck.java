package com.aresstack.spaceharvester.nlp.training;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.google.gson.Gson;

/**
 * Prüft ein Modell-Asset so, wie ein Verbraucher es nutzt, ohne Treebank und ohne Training: SHA-256 des ZIP gegen den
 * erwarteten Wert, Manifest-Schema, {@code mcoSha256} nach dem Entpacken, Eingabevertrag, Laden über die
 * thread-sichere MaltParser-API und Parsen eines Satzes zu einem gültigen Baum mit Labels aus dem Manifest.
 */
public final class ModelConsumerCheck {

    private static final Gson GSON = new Gson();

    private ModelConsumerCheck() {
    }

    public record Result(ModelManifest manifest, Path mco, ConlluSentence parsed) {
    }

    public static Result check(Path zip, String expectedZipSha256, Path directory, ConlluSentence sample) {
        String actual = Hashing.sha256(zip);
        if (!actual.equals(expectedZipSha256)) {
            throw new IllegalStateException(zip.getFileName() + ": SHA-256 " + actual + " statt " + expectedZipSha256);
        }
        unzip(zip, directory);
        ModelManifest manifest;
        try {
            manifest = GSON.fromJson(Files.readString(directory.resolve("model-manifest.json"), StandardCharsets.UTF_8),
                    ModelManifest.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (!ModelManifest.SCHEMA.equals(manifest.schemaVersion())) {
            throw new IllegalStateException("Manifest-Schema " + manifest.schemaVersion() + " statt " + ModelManifest.SCHEMA);
        }
        if (!manifest.mcoFile().equals(manifest.configName() + ".mco")) {
            throw new IllegalStateException("mcoFile " + manifest.mcoFile() + " passt nicht zu configName " + manifest.configName());
        }
        if (!InputContract.COLUMNS.containsAll(manifest.inputSchema().columns())) {
            throw new IllegalStateException("Modell verlangt Spalten " + manifest.inputSchema().columns()
                    + ", die Vorverarbeitung liefert nur " + InputContract.COLUMNS);
        }
        Path mco = directory.resolve(manifest.mcoFile());
        String mcoSha = Hashing.sha256(mco);
        if (!mcoSha.equals(manifest.mcoSha256())) {
            throw new IllegalStateException(manifest.mcoFile() + ": SHA-256 " + mcoSha + " statt " + manifest.mcoSha256());
        }
        ConlluSentence parsed = MaltModel.load(mco).parse(InputContract.forParsing(sample));
        checkTree(parsed, new HashSet<>(manifest.labels()));
        return new Result(manifest, mco, parsed);
    }

    static void checkTree(ConlluSentence parsed, Set<String> labels) {
        TreebankValidation.Report report = TreebankValidation.validate(List.of(parsed));
        if (!report.valid()) {
            throw new IllegalStateException("Geparster Satz ist kein gültiger Baum: " + report.errors());
        }
        for (String label : report.labels().keySet()) {
            if (!labels.contains(label)) {
                throw new IllegalStateException("Label " + label + " fehlt im Manifest");
            }
        }
    }

    private static void unzip(Path zip, Path directory) {
        try {
            MaltModelTrainer.deleteTree(directory);
            Files.createDirectories(directory);
            Path root = directory.toAbsolutePath().normalize();
            try (InputStream in = Files.newInputStream(zip); ZipInputStream zipIn = new ZipInputStream(in)) {
                ZipEntry entry;
                while ((entry = zipIn.getNextEntry()) != null) {
                    Path target = root.resolve(entry.getName()).normalize();
                    if (!target.startsWith(root) || entry.isDirectory()) {
                        throw new IllegalStateException("unerwarteter ZIP-Eintrag " + entry.getName());
                    }
                    Files.copy(zipIn, target);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
