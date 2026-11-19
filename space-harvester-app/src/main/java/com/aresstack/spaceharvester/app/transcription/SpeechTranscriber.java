package com.aresstack.spaceharvester.app.transcription;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Recognize spoken language in 16-kHz mono S16LE PCM without depending on Swing. */
public interface SpeechTranscriber {

    /**
     * Read the PCM stream to its end and report each recognized window at once. The stream may still be growing:
     * a read blocks until the decoder delivered more audio or finished.
     */
    void transcribe(InputStream pcm, Path modelDirectory, BooleanSupplier cancelled,
                    Consumer<TranscriptSegment> onSegment) throws Exception;
}
