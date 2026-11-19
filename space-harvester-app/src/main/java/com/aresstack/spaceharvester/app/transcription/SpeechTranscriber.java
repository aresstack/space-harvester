package com.aresstack.spaceharvester.app.transcription;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Recognize spoken language in a bounded PCM recording without depending on Swing. */
public interface SpeechTranscriber {
    void transcribe(Path pcmFile, Path modelDirectory, BooleanSupplier cancelled,
                    Consumer<TranscriptSegment> onSegment) throws Exception;
}
