package com.aresstack.enterpriseai.embedding.openai;

/**
 * Eine Spur zum Embedding-Endpunkt: höchstens ein Request dieses Adapters ist gleichzeitig unterwegs, und eine
 * wartende Suchanfrage kommt vor jedem wartenden Aufbereitungs-Request (Passagen, Sätze) an die Reihe.
 *
 * <p>Grund: Unternehmens-Gateways vor einem Embedding-Modell nehmen je Schlüssel oft nur eine Anfrage gleichzeitig an
 * und weisen jede weitere mit 429 ab. Ohne Spur stand das Embedding der Frage im Wettlauf mit dem Index-Thread der
 * Vorladung, der jede Seite Satz für Satz einbettet, und verlor ihn regelmäßig (semantische Suche „ausgefallen“).
 * Zwischen zwei Versuchen eines Requests wird die Spur freigegeben, damit niemand hinter einer Wartepause hängt.
 */
final class RequestLane {

    private boolean busy;
    private int waitingQueries;

    /**
     * Wartet, bis die Spur frei ist; Aufbereitungs-Requests ({@code query == false}) lassen wartenden Suchanfragen den
     * Vortritt.
     *
     * @throws InterruptedException wenn der Thread beim Warten unterbrochen wird; die Spur bleibt dann unbelegt
     */
    synchronized void enter(boolean query) throws InterruptedException {
        if (query) {
            waitingQueries++;
        }
        try {
            while (busy || (!query && waitingQueries > 0)) {
                wait();
            }
        } finally {
            if (query) {
                waitingQueries--;
            }
        }
        busy = true;
    }

    /** Gibt die Spur nach einem Request frei. */
    synchronized void leave() {
        busy = false;
        notifyAll();
    }
}
