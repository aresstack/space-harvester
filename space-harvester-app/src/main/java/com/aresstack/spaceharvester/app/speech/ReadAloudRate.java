package com.aresstack.spaceharvester.app.speech;

import com.aresstack.enterpriseai.astu.acropolis.speech.SpeechRate;
import com.aresstack.spaceharvester.app.ui.AppState;

/**
 * Die gemerkte Sprechgeschwindigkeit des Vorlesens und die Grenzen des Reglers (Angelo 27.11.2026: Skala um den
 * Vorlese-Knopf). Alles steht im App-Zustand ({@code app-state.json}) und ist dort einstellbar; ohne Eintrag gelten die
 * Grenzen des Vorlese-Ablaufs ({@link SpeechRate#MIN} bis {@link SpeechRate#MAX}, normal {@link SpeechRate#NORMAL}).
 */
public final class ReadAloudRate {

    /** Die zuletzt eingestellte Geschwindigkeit in Prozent. */
    public static final String RATE = "readAloud.ratePercent";
    /** Langsamstes Ende der Skala in Prozent (links). */
    public static final String MIN = "readAloud.rateMinPercent";
    /** Schnellstes Ende der Skala in Prozent (rechts). */
    public static final String MAX = "readAloud.rateMaxPercent";
    /** Raster der Skala in Prozent. */
    public static final String STEP = "readAloud.rateStepPercent";
    /** So viele Sätze springt der untere Zeiger am Ende seiner Skala. */
    public static final String JUMP = "readAloud.jumpSentences";

    private static final int PERCENT = 100;
    /** Grenzen der Einstellfelder in Prozent: was der Vorlese-Ablauf kann. */
    public static final int MIN_PERCENT = (int) Math.round(SpeechRate.MIN * PERCENT);
    public static final int NORMAL_PERCENT = (int) Math.round(SpeechRate.NORMAL * PERCENT);
    public static final int MAX_PERCENT = (int) Math.round(SpeechRate.MAX * PERCENT);
    /** Gröbstes Raster und weitester Sprung, die sich einstellen lassen. */
    public static final int MAX_STEP_PERCENT = 50;
    public static final int MAX_JUMP_SENTENCES = 100;
    /** Raster wie der Regler des Enterprise AI Client ({@code ReadAloudRateControl}). */
    private static final int DEFAULT_STEP = 5;
    /** Ohne Eintrag: so viele Sätze am Ende der unteren Skala. */
    private static final int DEFAULT_JUMP = 10;

    private final AppState state;

    public ReadAloudRate(AppState state) {
        this.state = java.util.Objects.requireNonNull(state, "state");
    }

    /** Der Regler der laufenden App. */
    public static ReadAloudRate shared() {
        return new ReadAloudRate(AppState.shared());
    }

    /** Langsamstes Ende (Faktor), nie unter dem, was der Vorlese-Ablauf kann. */
    public double min() {
        return Math.min(SpeechRate.NORMAL, SpeechRate.clamp(percent(MIN, SpeechRate.MIN)));
    }

    /** Schnellstes Ende (Faktor), nie über dem, was der Vorlese-Ablauf kann. */
    public double max() {
        return Math.max(SpeechRate.NORMAL, SpeechRate.clamp(percent(MAX, SpeechRate.MAX)));
    }

    /** Raster (Faktor). */
    public double step() {
        return Math.max(1, state.getInt(STEP, DEFAULT_STEP)) / (double) PERCENT;
    }

    /** Sätze am Ende der unteren Skala, mindestens einer. */
    public int jumpSentences() {
        return Math.max(1, state.getInt(JUMP, DEFAULT_JUMP));
    }

    /** Die Grenzen in Prozent bzw. Sätzen, wie sie die Einstellungen zeigen. */
    public record Limits(int minPercent, int maxPercent, int stepPercent, int jumpSentences) {
    }

    public Limits limits() {
        return new Limits((int) Math.round(min() * PERCENT), (int) Math.round(max() * PERCENT),
                (int) Math.round(step() * PERCENT), jumpSentences());
    }

    /** Merkt die Grenzen aus den Einstellungen; sie gelten beim nächsten Einblenden der Skala. */
    public void limits(Limits limits) {
        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
        values.put(MIN, String.valueOf(Math.max(MIN_PERCENT, Math.min(NORMAL_PERCENT, limits.minPercent()))));
        values.put(MAX, String.valueOf(Math.max(NORMAL_PERCENT, Math.min(MAX_PERCENT, limits.maxPercent()))));
        values.put(STEP, String.valueOf(Math.max(1, Math.min(MAX_STEP_PERCENT, limits.stepPercent()))));
        values.put(JUMP, String.valueOf(Math.max(1, Math.min(MAX_JUMP_SENTENCES, limits.jumpSentences()))));
        state.putAll(values);
    }

    /** Die gemerkte Geschwindigkeit, innerhalb der Grenzen. */
    public double rate() {
        return Math.max(min(), Math.min(max(), percent(RATE, SpeechRate.NORMAL)));
    }

    /** Merkt die Geschwindigkeit. */
    public void remember(double rate) {
        state.put(RATE, (int) Math.round(Math.max(min(), Math.min(max(), rate)) * PERCENT));
    }

    private double percent(String key, double fallback) {
        return state.getInt(key, (int) Math.round(fallback * PERCENT)) / (double) PERCENT;
    }
}
