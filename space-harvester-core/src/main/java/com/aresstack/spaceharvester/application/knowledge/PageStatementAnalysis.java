package com.aresstack.spaceharvester.application.knowledge;

import com.aresstack.spaceharvester.application.assets.DocumentKey;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Instant;
import java.util.Objects;

/**
 * Gespeicherte Aussagenanalyse eines Dokuments (#499, ADR 0026 Punkt 10): wozu, woraus und womit sie berechnet wurde.
 * Ein Dokument ist eine Seite, ein Anhang oder eine enthaltene Datei; je Dokument gibt es höchstens eine Analyse, eine
 * neue manuelle Analyse ersetzt sie samt ihrer Graph-Projektion in einem Schritt. Für Seiten ändert sich nichts:
 * {@code document} ist die Seite selbst. Aussagen entstehen weiter nur auf ausdrücklichen Auftrag.
 *
 * @param key             Seite, unter der das Dokument steht und deren Rechte es hat
 * @param spaceKey        Space der Seite oder {@code null}; für Rechteprüfungen
 * @param title           Titel der Seite beim Analysieren (Anzeige)
 * @param revision        Revision der analysierten Fassung oder {@code null}; bei Anhang und enthaltener Datei der
 *                        Inhalts-Hash des Dokuments
 * @param inputSha256     {@code PageText#inputSha256()} der analysierten Textprojektion (enthält die Version der
 *                        Ansicht)
 * @param analyzerId      {@link StatementAnalyzer#id()}
 * @param analyzerVersion {@link StatementAnalyzer#version()} beim Analysieren (Parser, Modell, Regeln)
 * @param analyzedAt      Zeitpunkt
 * @param result          Ergebnis; {@link ParsedStatements#conllu()} nur, wenn geladen
 * @param document        das Dokument; {@link DocumentKey#page()} ist {@code key}
 * @param stage           Stand der Vorstufe „Sätze vereinfachen“ (ADR 0032, FL3): {@code null} ohne Vorstufe,
 *                        {@code <Betriebsart>|<Simplifications#version()>} mit ihr, {@link #STAGE_WITHDRAWN} nach dem
 *                        Entzug der KI-Freigabe (dann veraltet: weggefallene Aussagen der Vorstufe ließen die zuvor
 *                        ersetzten Original-Aussagen fehlen)
 */
public record PageStatementAnalysis(PageKey key, String spaceKey, String title, String revision, String inputSha256,
                                    String analyzerId, String analyzerVersion, Instant analyzedAt,
                                    ParsedStatements result, DocumentKey document, String stage) {

    /** {@link #stage()} nach dem Entzug der KI-Freigabe: Aussagen der Vorstufe entfernt, Analyse veraltet. */
    public static final String STAGE_WITHDRAWN = "withdrawn";

    /** Schema der Ablage; steigt mit jeder inkompatiblen Änderung. */
    public static final String SCHEMA = "page-statements/v1";

    public PageStatementAnalysis {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(inputSha256, "inputSha256");
        Objects.requireNonNull(analyzerId, "analyzerId");
        Objects.requireNonNull(analyzerVersion, "analyzerVersion");
        Objects.requireNonNull(analyzedAt, "analyzedAt");
        Objects.requireNonNull(result, "result");
        document = document == null ? DocumentKey.page(key) : document;
        if (!document.page().equals(key)) {
            throw new IllegalArgumentException("Das Dokument gehört zu einer anderen Seite");
        }
    }

    /** Ohne Vorstufe. */
    public PageStatementAnalysis(PageKey key, String spaceKey, String title, String revision, String inputSha256,
                                 String analyzerId, String analyzerVersion, Instant analyzedAt,
                                 ParsedStatements result, DocumentKey document) {
        this(key, spaceKey, title, revision, inputSha256, analyzerId, analyzerVersion, analyzedAt, result, document,
                null);
    }

    /** Analyse der Seite selbst (wie vor ADR 0026). */
    public PageStatementAnalysis(PageKey key, String spaceKey, String title, String revision, String inputSha256,
                                 String analyzerId, String analyzerVersion, Instant analyzedAt,
                                 ParsedStatements result) {
        this(key, spaceKey, title, revision, inputSha256, analyzerId, analyzerVersion, analyzedAt, result, null);
    }

    public PageStatementAnalysis withResult(ParsedStatements other) {
        return new PageStatementAnalysis(key, spaceKey, title, revision, inputSha256, analyzerId, analyzerVersion,
                analyzedAt, other, document, stage);
    }

    /** Dieselbe Analyse mit anderem Stand der Vorstufe. */
    public PageStatementAnalysis withStage(String other) {
        return new PageStatementAnalysis(key, spaceKey, title, revision, inputSha256, analyzerId, analyzerVersion,
                analyzedAt, result, document, other);
    }

    /** Ob die Analyse zu einem Anhang oder einer enthaltenen Datei gehört, nicht zur Seite selbst. */
    public boolean ofDocument() {
        return !document.isPage();
    }

    /** {@link #documentId(DocumentKey, String)} dieser Analyse. */
    public String documentId() {
        return documentId(document, inputSha256);
    }

    /**
     * Präfix aller IDs dieser Analyse: {@code quelle/seite@<12 Zeichen des Eingabe-Hashs>}. So bleiben Erwähnungen und
     * Aussagen zweier Seiten und zweier Fassungen derselben Seite getrennt.
     */
    public static String documentId(PageKey key, String inputSha256) {
        return documentId(DocumentKey.page(key), inputSha256);
    }

    /**
     * Wie oben; für einen Anhang oder eine enthaltene Datei folgt {@code ~<DocumentKey#fileName()>} (nur
     * {@code [0-9a-z-]}): {@code quelle/seite@<12 Zeichen>~a-…}. Die Seiten-ID bleibt byte-gleich; weil der
     * Dokumentteil weder {@code @} noch {@code #} enthält, findet {@link GraphNeighbourhood} die Seite weiter über das
     * letzte {@code @}. Für Seiten ändert sich nichts.
     */
    public static String documentId(DocumentKey document, String inputSha256) {
        PageKey key = document.page();
        String hex = inputSha256.startsWith("sha256:") ? inputSha256.substring(7) : inputSha256;
        String id = key.sourceId() + "/" + key.pageId() + "@" + hex.substring(0, Math.min(12, hex.length()));
        return document.isPage() ? id : id + DOCUMENT + document.fileName();
    }

    /** Trenner vor dem Dokumentteil einer ID. */
    static final String DOCUMENT = "~";
}
