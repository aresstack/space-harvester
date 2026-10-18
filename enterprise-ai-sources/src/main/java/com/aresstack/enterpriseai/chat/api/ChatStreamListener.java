package com.aresstack.enterpriseai.chat.api;

import com.aresstack.enterpriseai.astu.chat.ChatResponse;

/**
 * Empfängt eine Streaming-Antwort. Reihenfolge und Exklusivität der Aufrufe regelt
 * {@link ChatCompletionPort#stream}. Implementierungen sollen schnell zurückkehren; UI-Code wechselt selbst
 * auf seinen Event-Thread.
 */
public interface ChatStreamListener {

    /** Der Provider hat die Anfrage angenommen; erste Deltas folgen. */
    void onStart();

    /** Ein nicht leeres Textstück der Antwort, in Empfangsreihenfolge. */
    void onDelta(String text);

    /** Die Antwort ist vollständig; {@code response.content()} ist die Verkettung aller Deltas. */
    void onComplete(ChatResponse response);

    /** Die Anfrage ist gescheitert; es folgen keine weiteren Aufrufe. */
    void onError(ChatCompletionException error);

    /** Die Anfrage wurde über {@link ChatTask#cancel()} abgebrochen; es folgen keine weiteren Aufrufe. */
    void onCancelled();
}
