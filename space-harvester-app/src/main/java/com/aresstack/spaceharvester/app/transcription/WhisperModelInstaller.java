package com.aresstack.spaceharvester.app.transcription;

import com.aresstack.spaceharvester.app.hf.HuggingFaceClient;
import com.aresstack.spaceharvester.app.hf.HuggingFaceFile;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * Lädt ein Whisper-Modell von Hugging Face in den Benutzerordner (Muster aus askai {@code WhisperInstaller}, der
 * Download selbst über den aus askai übernommenen {@link HuggingFaceClient}): Dateien in ein Zwischenverzeichnis
 * {@code whisper-<Modell>.part} laden, jede gegen ihre Prüfsumme prüfen, dann das Verzeichnis als Ganzes an seinen
 * Platz schieben. Ein vorhandener Ordner ist damit immer vollständig. Nach Abbruch oder Fehler bleibt das
 * Zwischenverzeichnis liegen, der nächste Versuch setzt dort fort. Blockierend; der Aufrufer läuft im Hintergrund.
 */
public final class WhisperModelInstaller {

    static final List<String> REQUIRED = List.of("config.json", "model.safetensors", "tokenizer.json", "vocab.json");
    /** Braucht nur ein Teil der Modelle (etwa Large v3 mit eigenem Wortschatz); geladen, wenn das Repository sie hat. */
    static final List<String> OPTIONAL = List.of("generation_config.json");
    private static final String WEIGHTS = "model.safetensors";

    /** Fortschritt für die Oberfläche. */
    public interface Progress {
        /** Schritt in Worten, etwa „Wird heruntergeladen …“. */
        void step(String text);

        /** Geladene Bytes des Modells; {@code total} ist {@code -1}, wenn die Länge unbekannt ist. */
        void bytes(long done, long total);
    }

    private final Path models;
    private final HuggingFaceClient client;

    /**
     * @param models Ordner aller Modelle, siehe {@link WhisperModelLocation#modelsDirectory}
     * @param client Hugging Face über den Netzweg der App (Proxy, TLS)
     */
    public WhisperModelInstaller(Path models, HuggingFaceClient client) {
        this.models = Objects.requireNonNull(models, "models");
        this.client = Objects.requireNonNull(client, "client");
    }

    /** Wohin {@code model} geladen wird. */
    public Path directory(WhisperModel model) {
        return WhisperModelLocation.directory(models, model);
    }

    /** Ob {@code model} benutzbar auf der Platte liegt (auch von Hand kopiert oder aus einer früheren Fassung). */
    public boolean installed(WhisperModel model) {
        return WhisperModelLocation.find(models, model).isPresent();
    }

    /**
     * Lädt, prüft und aktiviert {@code model}.
     *
     * @throws CancellationException wenn {@code cancelled} während des Laufs wahr wird; Geladenes bleibt zum Fortsetzen
     * @throws IOException           bei Netz-, Prüfsummen- oder Dateifehlern; ein vorhandenes Modell bleibt
     */
    public void install(WhisperModel model, Progress progress, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(progress, "progress");
        Objects.requireNonNull(cancelled, "cancelled");
        Path target = directory(model);
        Path staging = target.resolveSibling(target.getFileName() + ".part");
        Files.createDirectories(staging);
        progress.step("Dateiliste wird gelesen …");
        String revision = model.revision();
        List<HuggingFaceFile> files = model.pinned().isEmpty() ? null : pinned(model);
        if (files == null) {
            revision = client.resolveRevision(model.repository(), "main");
            files = wanted(model, client.listFiles(model.repository(), revision));
        }
        check(cancelled);
        progress.step("Wird heruntergeladen …");
        long total = files.stream().allMatch(file -> file.size() > 0)
                ? files.stream().mapToLong(HuggingFaceFile::size).sum() : -1;
        long before = 0;
        for (HuggingFaceFile file : files) {
            Path destination = staging.resolve(file.fileName());
            long done = before;
            boolean weights = WEIGHTS.equals(file.fileName());
            if (!Files.isRegularFile(destination)) {
                // Ohne bekannte Gesamtlänge zählt allein die große Gewichtsdatei für den Fortschritt.
                client.download(file, revision, destination, (bytes, of) -> {
                    if (total > 0) {
                        progress.bytes(done + bytes, total);
                    } else if (weights) {
                        progress.bytes(bytes, of);
                    }
                }, cancelled);
            }
            before += Math.max(0, file.size());
            check(cancelled);
        }
        if (!WhisperSpeechTranscriber.isModelAvailable(staging)) {
            throw new IOException("Das geladene Modell ist unvollständig; nichts eingerichtet.");
        }
        progress.step("Wird eingerichtet …");
        activate(staging, target);
    }

    /** Beendet einen laufenden Download sofort (aus einem anderen Thread); {@link #install} meldet dann den Abbruch. */
    public void abort() {
        client.abort();
    }

    private static List<HuggingFaceFile> pinned(WhisperModel model) {
        List<HuggingFaceFile> files = new ArrayList<>();
        for (String name : REQUIRED) {
            files.add(new HuggingFaceFile(model.repository(), name, -1, model.pinned().get(name), ""));
        }
        return files;
    }

    /** Die Dateien des Modells aus der Liste des Repositorys; die Gewichte nur mit Prüfsumme von Hugging Face. */
    static List<HuggingFaceFile> wanted(WhisperModel model, List<HuggingFaceFile> listed) throws IOException {
        List<HuggingFaceFile> files = new ArrayList<>();
        for (String name : REQUIRED) {
            HuggingFaceFile file = listed.stream().filter(each -> each.path().equals(name)).findFirst()
                    .orElseThrow(() -> new IOException(model.repository() + " enthält " + name
                            + " nicht; nichts geladen."));
            if (WEIGHTS.equals(name) && file.sha256().isEmpty()) {
                throw new IOException("Hugging Face nennt für " + name + " keine Prüfsumme; ungeprüft wird nichts "
                        + "geladen.");
            }
            files.add(file);
        }
        for (String name : OPTIONAL) {
            listed.stream().filter(each -> each.path().equals(name)).findFirst().ifPresent(files::add);
        }
        return files;
    }

    private static void activate(Path staging, Path target) throws IOException {
        Path old = null;
        if (Files.exists(target)) {
            old = target.resolveSibling(target.getFileName() + ".old-" + System.nanoTime());
            Files.move(target, old);
        }
        try {
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(staging, target);
            }
        } catch (IOException e) {
            if (old != null && !Files.exists(target)) {
                Files.move(old, target);
            }
            throw e;
        }
        if (old != null) {
            deleteRecursively(old);
        }
    }

    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Download abgebrochen");
        }
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    path.toFile().deleteOnExit();
                }
            });
        } catch (IOException ignored) {
            // Reste eines alten Modellordners stören nicht.
        }
    }
}
