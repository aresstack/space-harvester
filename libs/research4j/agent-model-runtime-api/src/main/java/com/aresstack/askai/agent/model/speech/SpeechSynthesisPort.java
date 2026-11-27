package com.aresstack.askai.agent.model.speech;

/**
 * Host speech-output (text-to-speech) service for plugins, PER LANGUAGE: the user selects a voice
 * for each language separately (chat settings → Audio &amp; Dictation), exactly like the per-language
 * NLP models — a German model voice never reads English text. When no model voice is configured or
 * installed for the requested language, the port is inactive FOR THAT LANGUAGE and the caller keeps
 * its own default (e.g. the Windows OS voice in that language). The Windows voice deliberately
 * stays the plugin-side default — the model voice is an opt-in upgrade, never a silent replacement.
 *
 * <p>Contract: {@code languageCode} is ISO-639-1 ("de", "en"); null/unknown codes are simply
 * inactive. {@link #speak} is BLOCKING (synthesis + playback) and must be called off the EDT; a
 * second {@code speak} implicitly stops the first. {@code speak} returns {@code false} when no
 * model voice is active for the language or the engine failed — the caller then falls back to its
 * default voice, so read-aloud never goes silent because of a model problem.</p>
 */
public interface SpeechSynthesisPort {

    /** @return whether a model voice is selected AND installed for this language. */
    boolean isModelVoiceActive(String languageCode);

    /** @return a short human label of that language's active model voice, or "" when none. */
    String describeActiveVoice(String languageCode);

    /**
     * Speak plain text (no markdown) with the language's model voice, blocking until playback
     * finishes or {@link #stop()} interrupts it.
     *
     * @return true when the model voice spoke (even if interrupted); false when no model voice is
     *         active for the language or synthesis failed — the caller should fall back to its
     *         default voice
     */
    boolean speak(String plainText, String languageCode);

    /** Stop the current utterance immediately; no-op when silent. */
    void stop();

    /**
     * Like {@link #speak}, but with a deliberately ASSERTIVE delivery (slightly slower, weightier
     * pacing) — for short spoken confirmations such as a clicked search tag. Hosts without a tone
     * concept simply speak normally.
     */
    default boolean speakEmphatic(String plainText, String languageCode) {
        return speak(plainText, languageCode);
    }

    /**
     * Central user preference (chat settings → Audio &amp; Dictation): read-aloud should start
     * ACTIVE — new answers are spoken automatically without pressing Play. Engine-independent
     * (applies to the Windows default voice too); the plugin still owns the Play/Pause toggle.
     */
    default boolean isReadAloudActiveByDefault() {
        return false;
    }
}
