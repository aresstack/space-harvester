package com.aresstack.spaceharvester.app.speech;

import com.aresstack.spaceharvester.app.transcription.WhisperModelLocation;
import com.aresstack.windirectml.inference.whisper.WhisperTranscriber;
import com.aresstack.windirectml.whisper.audio.AskAiWhisperPipeline;

import java.io.IOException;

/**
 * Transcribe dictation with the published Ask AI pipeline: 80-Hz high-pass and VAD, then the
 * guarded Whisper decoder with 30-second windows, using the bundled local checkpoint.
 */
final class WhisperDictationService {
    private final AskAiWhisperPipeline audioPipeline = new AskAiWhisperPipeline();
    private WhisperTranscriber transcriber;

    synchronized String transcribe(RecordedSpeech speech) throws IOException {
        if (transcriber == null) {
            transcriber = new WhisperTranscriber(WhisperModelLocation.resolve());
        }
        return audioPipeline.transcribePlain(speech, transcriber);
    }
}
