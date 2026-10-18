package com.aresstack.enterpriseai.chat.api;

import com.aresstack.enterpriseai.astu.chat.ChatRequest;
import com.aresstack.enterpriseai.astu.chat.ChatResponse;

/**
 * Port zu einem Chat-Modell. Implementierungen sind threadsicher und dürfen mehrere Anfragen gleichzeitig
 * bedienen (eine je Konversation ist der Normalfall).
 */
public interface ChatCompletionPort {

    /**
     * Fordert eine vollständige Antwort an und blockiert, bis sie vorliegt.
     *
     * @throws ChatCompletionException wenn der Provider nicht erreichbar ist, die Anfrage ablehnt oder
     *                                 eine unlesbare Antwort liefert
     */
    ChatResponse complete(ChatRequest request);

    /**
     * Startet eine Streaming-Antwort und kehrt sofort zurück. Der Listener wird in der Regel auf einem
     * Thread des Adapters benachrichtigt, kann aber (etwa bei einem sofort erkannten Fehler) auch schon vor
     * der Rückkehr dieser Methode auf dem aufrufenden Thread aufgerufen werden; Aufrufer müssen beides
     * vertragen.
     *
     * <p>Lebenszyklus, den jede Implementierung einhält: höchstens einmal
     * {@link ChatStreamListener#onStart()}, danach beliebig viele
     * {@link ChatStreamListener#onDelta(String)}, danach genau ein Abschluss: {@code onComplete},
     * {@code onError} oder {@code onCancelled}. Nach dem Abschluss kommen keine weiteren Aufrufe.
     *
     * @return Handle zum Abbrechen; nie {@code null}
     */
    ChatTask stream(ChatRequest request, ChatStreamListener listener);
}
