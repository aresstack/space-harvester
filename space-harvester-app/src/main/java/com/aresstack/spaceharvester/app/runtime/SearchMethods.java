package com.aresstack.spaceharvester.app.runtime;

import java.util.Objects;
import java.util.prefs.Preferences;

/**
 * Die zuschaltbaren Suchmethoden, wie der Benutzer sie in den Suchoptionen gesetzt hat (Angelo 21.11.2026: die
 * bisherige Suche bleibt Standard, jede Erweiterung schaltet der Benutzer per Häkchen zu). Die Stellung bleibt über
 * den Neustart erhalten; Suche und Chat lesen dieselbe.
 */
public final class SearchMethods {

    static final String NEIGHBORS = "search.method.neighbors";
    static final String TERM_PAGES = "search.method.term-pages";

    private static final SearchMethods USER = new SearchMethods(Preferences.userNodeForPackage(SearchMethods.class));

    private final Preferences preferences;

    SearchMethods(Preferences preferences) {
        this.preferences = Objects.requireNonNull(preferences, "preferences");
    }

    /** Die Stellung des Benutzers. */
    public static SearchMethods user() {
        return USER;
    }

    /** Suchmethode „Nachbarseiten“ (R2); Standard aus. */
    public boolean neighbors() {
        try {
            Boolean held = session;
            return held != null ? held : preferences.getBoolean(NEIGHBORS, false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public void neighbors(boolean on) {
        try {
            preferences.putBoolean(NEIGHBORS, on);
        } catch (RuntimeException e) {
            // Ohne Ablage gilt die Stellung nur bis zum Ende der Sitzung; die Suche läuft trotzdem.
            session = on;
            return;
        }
        session = null;
    }

    private volatile Boolean session;

    /**
     * Suchmethode „Seiten zu Fachbegriffen“ (W8); Standard aus. Wie die Nachbarseiten bleibt die Stellung über den
     * Neustart erhalten; der Suchqualitätslauf übernimmt sie als Vorgabe.
     */
    public boolean termPages() {
        try {
            Boolean held = termPagesSession;
            return held != null ? held : preferences.getBoolean(TERM_PAGES, false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public void termPages(boolean on) {
        try {
            preferences.putBoolean(TERM_PAGES, on);
        } catch (RuntimeException e) {
            // Ohne Ablage gilt die Stellung nur bis zum Ende der Sitzung; die Suche läuft trotzdem.
            termPagesSession = on;
            return;
        }
        termPagesSession = null;
    }

    private volatile Boolean termPagesSession;

    /** Suchmethode „Synonyme der Fachbegriffe einbeziehen“ (K5a); Standard aus, gilt nur für die Sitzung. */
    private volatile boolean glossarySynonyms;

    /** Ob die Suche zu bestätigten Fachbegriffen auch ihre vorgeschlagenen Synonyme sucht (K5a). */
    public boolean glossarySynonyms() {
        return glossarySynonyms;
    }

    public void glossarySynonyms(boolean on) {
        glossarySynonyms = on;
    }
}
