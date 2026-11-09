package com.aresstack.spaceharvester.application.knowledge;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Instant;
import java.util.Objects;

/**
 * Gespeicherte Aussagenanalyse einer Seite (#499): wozu, woraus und womit sie berechnet wurde. Je Seite gibt es
 * höchstens eine; eine neue manuelle Analyse ersetzt sie samt ihrer Graph-Projektion in einem Schritt.
 *
 * @param key             Seite
 * @param spaceKey        Space der Seite oder {@code null}; für Rechteprüfungen
 * @param title           Titel der Seite beim Analysieren (Anzeige)
 * @param revision        Revision der analysierten Fassung oder {@code null}
 * @param inputSha256     {@code PageText#inputSha256()} der analysierten Textprojektion
 * @param analyzerId      {@link StatementAnalyzer#id()}
 * @param analyzerVersion {@link StatementAnalyzer#version()} beim Analysieren (Parser, Modell, Regeln)
 * @param analyzedAt      Zeitpunkt
 * @param result          Ergebnis; {@link ParsedStatements#conllu()} nur, wenn geladen
 */
public record PageStatementAnalysis(PageKey key, String spaceKey, String title, String revision, String inputSha256,
                                    String analyzerId, String analyzerVersion, Instant analyzedAt,
                                    ParsedStatements result) {

    /** Schema der Ablage; steigt mit jeder inkompatiblen Änderung. */
    public static final String SCHEMA = "page-statements/v1";

    public PageStatementAnalysis {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(inputSha256, "inputSha256");
        Objects.requireNonNull(analyzerId, "analyzerId");
        Objects.requireNonNull(analyzerVersion, "analyzerVersion");
        Objects.requireNonNull(analyzedAt, "analyzedAt");
        Objects.requireNonNull(result, "result");
    }

    public PageStatementAnalysis withResult(ParsedStatements other) {
        return new PageStatementAnalysis(key, spaceKey, title, revision, inputSha256, analyzerId, analyzerVersion,
                analyzedAt, other);
    }

    /**
     * Präfix aller IDs dieser Analyse: {@code quelle/seite@<12 Zeichen des Eingabe-Hashs>}. So bleiben Erwähnungen und
     * Aussagen zweier Seiten und zweier Fassungen derselben Seite getrennt.
     */
    public static String documentId(PageKey key, String inputSha256) {
        String hex = inputSha256.startsWith("sha256:") ? inputSha256.substring(7) : inputSha256;
        return key.sourceId() + "/" + key.pageId() + "@" + hex.substring(0, Math.min(12, hex.length()));
    }
}
