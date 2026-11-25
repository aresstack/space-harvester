package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.ai.AiChat;
import java.util.List;

/**
 * Die Werkzeuge, die der eine globale Chat für eine Frage bekommt (ADR 0033, #1151): Je nach Perspektive und Bereich
 * sind es andere – im Space Harvester die Wissenswerkzeuge des gewählten Bereichs, im Command Deck die des
 * Maskenassistenten. Festgelegt beim Senden, gültig für diese eine Frage.
 */
public interface ChatToolset {

    /** Die Definitionen für die Anfrage. */
    List<AiChat.Tool> definitions();

    /** Führt einen Aufruf aus; das Ergebnis ist immer Text für das Modell, nie eine Ausnahme. */
    String run(AiChat.ToolCall call);

    /** Kurz, was der Aufruf tut, für die Statuszeile; ohne Argumente des Modells. */
    String describe(AiChat.ToolCall call);

    /** Describe the current semantic view independently of its rendered component tree. */
    default String viewContext() { return ""; }

    /** Identify the selected text for safe reuse of a captured snapshot. */
    default String viewIdentity() { return ""; }

    /** Pass the selected model before offering tools. */
    default void modelSelected(String model) {}

    /** Bricht laufende Arbeit ab (Stopp im Chat). */
    default void cancel() {
    }
}
