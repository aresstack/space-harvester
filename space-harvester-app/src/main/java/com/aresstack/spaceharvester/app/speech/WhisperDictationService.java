package com.aresstack.spaceharvester.app.speech;

import com.aresstack.spaceharvester.app.transcription.WhisperModelLocation;
import com.aresstack.windirectml.inference.whisper.WhisperTranscriber;

import java.io.IOException;

/** Adapt the EAC dictation service to the bundled Whisper Tiny checkpoint. */
final class WhisperDictationService {
    private static final int SHORT_AUDIO_SAMPLES = RecordedSpeech.SAMPLE_RATE * 6;
    private static final int MAXIMUM_TOKENS = 256;
    private WhisperTranscriber transcriber;

    synchronized String transcribe(RecordedSpeech speech) throws IOException {
        if (transcriber == null) {
            transcriber = new WhisperTranscriber(WhisperModelLocation.resolve());
        }
        float[] samples = speech.samples();
        return samples.length <= SHORT_AUDIO_SAMPLES
                ? transcriber.transcribeShortRecording(samples, MAXIMUM_TOKENS)
                : transcriber.transcribe(samples, MAXIMUM_TOKENS);
    }
}
