package com.aresstack.spaceharvester.app.speech;

import com.aresstack.spaceharvester.app.transcription.WhisperModelLocation;
import com.aresstack.windirectml.inference.whisper.WhisperTranscriber;
import com.aresstack.windirectml.whisper.audio.AskAiWhisperPipeline;

import java.io.IOException;

/** Apply the published Ask AI PCM/VAD pipeline to the bundled local Whisper checkpoint. */
final class WhisperDictationService {
    /**
     * The pipeline decodes greedily in six-second windows; even fast German speech stays well
     * below ten tokens per second, so a larger budget only gives decoding loops room to grow.
     */
    private static final int MAXIMUM_TOKENS_PER_WINDOW = 64;

    private final AskAiWhisperPipeline audioPipeline = new AskAiWhisperPipeline();
    private WhisperTranscriber transcriber;

    synchronized String transcribe(RecordedSpeech speech) throws IOException {
        if (transcriber == null) {
            transcriber = new WhisperTranscriber(WhisperModelLocation.resolve());
        }
        return RepetitionCollapser.collapse(
                audioPipeline.transcribePlain(speech, transcriber, MAXIMUM_TOKENS_PER_WINDOW));
    }
}
