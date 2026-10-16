package com.aresstack.enterpriseai.source.api;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;

import java.util.regex.Pattern;

/**
 * Optionale Fähigkeit einer Wissensquelle: eine vom Benutzer eingegebene Adresse als Ressource dieser konkreten
 * Quelle erkennen und auflösen.
 *
 * <p>Das {@link #addressPattern()} gehört zur Quelle selbst. Es wird gegen die vollständige, getrimmte Eingabe mit
 * {@link java.util.regex.Matcher#matches()} geprüft. Liefert eine Hülle {@code null}, besitzt ihre eigentliche Quelle
 * diese Fähigkeit nicht. Erst wenn das Pattern passt, darf {@link #resolveAddress(String)} aufgerufen werden.
 */
public interface AddressableKnowledgeSource {

    /**
     * Das vollständige Adressmuster dieser Quelle oder {@code null}, wenn sie keine direkte Adressierung anbietet.
     * Das Pattern soll die Quelle ausreichend eng identifizieren; die eigentliche fachliche Prüfung bleibt trotzdem
     * Aufgabe von {@link #resolveAddress(String)}.
     */
    Pattern addressPattern();

    /**
     * Löst eine Adresse im Kontext genau dieser Quelle auf.
     *
     * @return die Ressource oder {@code null}, wenn die Adresse zwar syntaktisch zur Quelle passt, aber keine
     *         auflösbare Ressource bezeichnet
     */
    KnowledgeResourceId resolveAddress(String address) throws KnowledgeSourceException;
}
