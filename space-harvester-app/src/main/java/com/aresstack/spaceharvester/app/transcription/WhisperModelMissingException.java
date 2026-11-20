package com.aresstack.spaceharvester.app.transcription;

import java.io.IOException;

/** Das eingestellte Whisper-Modell liegt noch nicht auf diesem Rechner; es wird nur nach Rückfrage geladen. */
public final class WhisperModelMissingException extends IOException {

    private final transient WhisperModel model;

    public WhisperModelMissingException(WhisperModel model) {
        super("Das Whisper-Modell „" + model.label() + "“ ist noch nicht geladen. Unter Einstellungen › Modelle "
                + "lässt es sich von Hugging Face laden.");
        this.model = model;
    }

    public WhisperModel model() {
        return model;
    }
}
