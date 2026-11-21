package com.aresstack.spaceharvester.app.transcription;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Optional;

/**
 * Wo das eingestellte Whisper-Modell auf diesem Rechner liegt. Kein Modell steckt im Programm; das unter
 * Einstellungen › Modelle gewählte ({@link #select}) wird nach Rückfrage einmal von Hugging Face nach
 * {@code ~/.space-harvester/models/whisper-<Modell>} geladen ({@link WhisperModelInstaller}). Fehlt es, meldet
 * {@link #resolve} das, statt selbst zu laden.
 *
 * <p>Vorrang haben wie bisher: die Systemeigenschaft {@value #PROPERTY} mit einem beliebigen Modellordner und ein von
 * Hand nach {@code ~/.space-harvester/models/whisper} kopierter Checkpoint ({@code config.json},
 * {@code model.safetensors}, {@code tokenizer.json}, {@code vocab.json}, optional {@code generation_config.json}).
 */
public final class WhisperModelLocation {

    static final String PROPERTY = "spaceharvester.whisper.model";
    static final String INSTALLED_FOLDER = "whisper";
    /** Ordner, in den frühere Fassungen das mitgelieferte Tiny-Modell entpackt haben; gilt weiter als geladen. */
    static final String LEGACY_TINY_FOLDER = "whisper-tiny-" + WhisperModel.TINY.revision();

    private static volatile WhisperModel selected = WhisperModel.DEFAULT;
    private static volatile WhisperModel dictation = WhisperModel.DEFAULT;

    private WhisperModelLocation() {
    }

    /** Das unter Einstellungen › Modelle gewählte Modell; gilt beim nächsten Gebrauch. */
    public static void select(WhisperModel model) {
        selected = model == null ? WhisperModel.DEFAULT : model;
    }

    public static WhisperModel selected() {
        return selected;
    }

    /**
     * Das unter Einstellungen › Modelle gewählte Modell der Spracheingabe im Chat
     * ({@value WhisperModel#DICTATION_KEY}), getrennt von dem der Transkripte; gilt ab der nächsten Aufnahme.
     */
    public static void selectDictation(WhisperModel model) {
        dictation = model == null ? WhisperModel.DEFAULT : model;
    }

    public static WhisperModel dictation() {
        return dictation;
    }

    /** Der Ordner aller Modelle im Benutzerordner. */
    public static Path modelsDirectory() {
        return Paths.get(System.getProperty("user.home", "."), ".space-harvester", "models");
    }

    /**
     * Der Ordner des eingestellten Modells.
     *
     * @throws WhisperModelMissingException wenn es noch nicht geladen ist
     */
    public static Path resolve() throws WhisperModelMissingException {
        return resolve(selected);
    }

    /**
     * Der Ordner von {@code model}.
     *
     * @throws WhisperModelMissingException wenn es noch nicht geladen ist
     */
    public static Path resolve(WhisperModel model) throws WhisperModelMissingException {
        return find(model).orElseThrow(() -> new WhisperModelMissingException(model));
    }

    /** Der Ordner, aus dem {@code model} gelesen würde; leer, solange es nicht geladen ist. */
    public static Optional<Path> find(WhisperModel model) {
        Optional<Path> overridden = overridden();
        return overridden.isPresent() ? overridden : find(modelsDirectory(), model);
    }

    /** Der mit {@value #PROPERTY} vorgegebene Modellordner; er gilt für jedes Modell und wird nie nachgeladen. */
    static Optional<Path> overridden() {
        String overridden = System.getProperty(PROPERTY);
        return overridden == null || overridden.isBlank() ? Optional.empty()
                : Optional.of(Paths.get(overridden.strip()).toAbsolutePath());
    }

    static Optional<Path> find(Path models, WhisperModel model) {
        Objects.requireNonNull(model, "model");
        Path manual = models.resolve(INSTALLED_FOLDER);
        if (WhisperSpeechTranscriber.isModelAvailable(manual)) {
            return Optional.of(manual.toAbsolutePath().normalize());
        }
        Path loaded = directory(models, model);
        if (WhisperSpeechTranscriber.isModelAvailable(loaded)) {
            return Optional.of(loaded.toAbsolutePath().normalize());
        }
        Path legacy = models.resolve(LEGACY_TINY_FOLDER);
        if (model == WhisperModel.TINY && WhisperSpeechTranscriber.isModelAvailable(legacy)) {
            return Optional.of(legacy.toAbsolutePath().normalize());
        }
        return Optional.empty();
    }

    /** Wohin {@code model} geladen wird. */
    static Path directory(Path models, WhisperModel model) {
        return models.resolve("whisper-" + model.id());
    }
}
