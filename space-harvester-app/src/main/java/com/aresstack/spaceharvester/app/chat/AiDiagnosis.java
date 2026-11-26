package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.ai.AiServiceSettings;

/**
 * Die Zeile „Diagnose“ (Einstellungen › KI-Dienst › Modelle, Angelo 26.11.2026) für alle KI-Erweiterungen der
 * Diagnose, etwa die MVS-Binärdiagnose im Command Deck: Schalter „Diagnose per KI“ und Modell. Die App setzt sie nach
 * jedem Laden der Konfiguration, wie {@link com.aresstack.spaceharvester.app.deck.DocumentTexts}; ohne Laden gilt die
 * Vorgabe (eingeschaltet, {@value AiServiceSettings#DEFAULT_DIAGNOSIS_MODEL}).
 */
public final class AiDiagnosis {

    private static volatile AiServiceSettings.Diagnosis current = AiServiceSettings.Diagnosis.DEFAULT;

    private AiDiagnosis() {
    }

    /** Übernimmt die gelesene Zeile; {@code null} heißt Vorgabe. */
    public static void settings(AiServiceSettings.Diagnosis diagnosis) {
        current = diagnosis == null ? AiServiceSettings.Diagnosis.DEFAULT : diagnosis;
    }

    /** Die zuletzt gelesene Zeile „Diagnose“. */
    public static AiServiceSettings.Diagnosis settings() {
        return current;
    }

    /** Ob die Diagnose per KI eingeschaltet ist; sonst verschwinden ihre Einträge und Werkzeuge. */
    public static boolean on() {
        return current.on();
    }
}
