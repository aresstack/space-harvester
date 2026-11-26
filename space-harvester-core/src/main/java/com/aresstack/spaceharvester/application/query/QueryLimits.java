package com.aresstack.spaceharvester.application.query;

/**
 * Stapelgrößen der Abfragen ({@code space-harvester.query.*}): wie viel eine Antwort höchstens enthält. Keine
 * Bestandsgrenze, denn jede Liste setzt sich über ihren Cursor fort; früher fest im Code ({@code
 * PageQueries.MAX_PAGE_LIMIT}, {@code TermQueries.MAX_SCAN}). Angelo 26.11.2026: Stapelgrößen sind einstellbar.
 *
 * @param maxPageLimit größte Stapelgröße der Seitenliste je Anfrage ({@code query.max-page-limit})
 * @param maxTermScan  so viele Indexeinträge liest eine Begriffsliste höchstens, dann endet sie an der nächsten
 *                     Begriffsgrenze und liefert den Cursor ({@code query.max-term-scan})
 */
public record QueryLimits(int maxPageLimit, int maxTermScan) {

    /** Vorgabe für {@link #maxPageLimit}; zugleich die Stapelgröße ohne Angabe in der REST-API. */
    public static final int DEFAULT_MAX_PAGE_LIMIT = 100;
    /** Vorgabe für {@link #maxTermScan}. */
    public static final int DEFAULT_MAX_TERM_SCAN = 50_000;

    public static final QueryLimits DEFAULTS = new QueryLimits(DEFAULT_MAX_PAGE_LIMIT, DEFAULT_MAX_TERM_SCAN);

    public QueryLimits {
        if (maxPageLimit < 1 || maxTermScan < 1) {
            throw new IllegalArgumentException("Stapelgrößen der Abfragen müssen positiv sein");
        }
    }
}
