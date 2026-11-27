package com.aresstack.spaceharvester.app.research;

import com.aresstack.knowledge4j.model.chat.ChatMessage;
import com.aresstack.knowledge4j.model.chat.MainModelChat;
import com.aresstack.knowledge4j.model.chat.MainModelChatResult;
import com.aresstack.spaceharvester.application.snapshot.PageKey;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * KI-Freigaben der Recherche, gebündelt (ADR 0028): Der Seitenabruf merkt sich jede gelesene Seite, und vor jedem
 * Modellaufruf gehen alle gemerkten in einer Änderung auf die Liste freigegebener Seiten. Eine Seite ist also
 * freigegeben, bevor ihr Text an das Modell geht, und eine Runde der Recherche löst eine Meldung an die Beobachter aus
 * statt einer je Seite. Je Seite gemeldet, startete jede Freigabe die automatischen Folgeläufe (Synonymerkennung,
 * Bildbeschreibung) neu, und sie kamen nie voran (Test des Produktverantwortlichen am 27.11.).
 */
final class PendingReleases {

    private final PageStock stock;
    private final Map<PageKey, String> pending = new LinkedHashMap<>();

    PendingReleases(PageStock stock) {
        this.stock = Objects.requireNonNull(stock, "stock");
    }

    /** Merkt die Seite für die nächste Freigabe vor. */
    synchronized void add(String spaceKey, PageKey key) {
        pending.putIfAbsent(Objects.requireNonNull(key, "key"), Objects.requireNonNull(spaceKey, "spaceKey"));
    }

    /** Gibt alle vorgemerkten Seiten in einer Änderung frei; wirft, wenn das scheitert (dann bleiben sie vorgemerkt). */
    synchronized void flush() {
        if (pending.isEmpty()) {
            return;
        }
        stock.release(Map.copyOf(new LinkedHashMap<>(pending)));
        pending.clear();
    }

    /**
     * Das Modell der Recherche: vor jedem Aufruf {@link #flush}. Scheitert die Freigabe, geht der Aufruf nicht hinaus
     * und endet als Fehler; die Recherche führt die Quelle dann ohne Urteil.
     */
    MainModelChat releasingBefore(MainModelChat chat) {
        Objects.requireNonNull(chat, "chat");
        return new MainModelChat() {
            @Override
            public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
                MainModelChatResult refused = released();
                return refused != null ? refused : chat.complete(messages, temperature, maxOutputTokens);
            }

            @Override
            public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature,
                                                    int maxOutputTokens, String schemaJson) {
                MainModelChatResult refused = released();
                return refused != null ? refused
                        : chat.completeJson(messages, temperature, maxOutputTokens, schemaJson);
            }

            @Override
            public int contextTokens() {
                return chat.contextTokens();
            }

            @Override
            public String modelName() {
                return chat.modelName();
            }

            @Override
            public void cancelInFlight() {
                chat.cancelInFlight();
            }
        };
    }

    private MainModelChatResult released() {
        try {
            flush();
            return null;
        } catch (RuntimeException failed) {
            return MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE,
                    "KI-Freigabe gescheitert (" + failed.getClass().getSimpleName() + ")");
        }
    }
}
