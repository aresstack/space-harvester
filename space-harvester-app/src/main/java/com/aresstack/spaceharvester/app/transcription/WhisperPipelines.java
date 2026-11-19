package com.aresstack.spaceharvester.app.transcription;

import com.aresstack.windirectml.inference.whisper.WhisperDecodingOptions;
import com.aresstack.windirectml.whisper.audio.AskAiWhisperPipeline;

/**
 * Build the Whisper pipeline shared by dictation and video transcription. The spoken language is
 * German unless {@value #LANGUAGE} names another language code or {@code auto} for detection.
 */
public final class WhisperPipelines {
    static final String LANGUAGE = "spaceharvester.whisper.language";

    private WhisperPipelines() {
    }

    public static AskAiWhisperPipeline create() {
        return new AskAiWhisperPipeline(AskAiWhisperPipeline.defaultSpeechProfile(),
                WhisperDecodingOptions.defaults().withLanguage(System.getProperty(LANGUAGE, "de")));
    }
}
