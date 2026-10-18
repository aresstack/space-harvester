package com.aresstack.enterpriseai.astu.chat;

/** Warum der Provider die Generierung beendet hat. */
public enum ChatFinishReason {
    /** Natürliches Ende oder Stop-Sequenz. */
    STOP,
    /** Token-Limit erreicht; die Antwort ist abgeschnitten. */
    LENGTH,
    /** Inhalt vom Provider gefiltert. */
    CONTENT_FILTER,
    /** Das Modell ruft Werkzeuge auf ({@code tool_calls}); die Antwort trägt sie in der Nachricht. */
    TOOL_CALLS,
    /** Kein oder ein unbekannter Grund gemeldet. */
    UNKNOWN
}
