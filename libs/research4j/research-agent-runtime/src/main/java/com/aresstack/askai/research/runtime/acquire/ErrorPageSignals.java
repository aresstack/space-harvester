package com.aresstack.askai.research.runtime.acquire;

import com.aresstack.askai.browser.BrowserPageReadiness;

import java.util.Locale;

/**
 * Detects an ERROR page from a page's already-probed title and excerpt: a "404 / page not found", a server error
 * or a "temporarily unavailable" notice. Such a page has text (navigation, footer, a sorry note), so the text
 * threshold alone lets it through, and the live run of 2026-11-09 stored a 404 page and an unavailable
 * ResearchGate page as sources. Domain-agnostic: it keys on the error's own wording in English and German, never
 * on a site. Returns a short reason code (for logging) or {@code null} when the page is not an error page.
 *
 * <p>Deliberately narrow: an unambiguous phrase ("page not found", "temporarily unavailable", "internal server
 * error") counts on its own in the title or in the opening of the excerpt; a short phrase an article may use
 * ("not found", "an error occurred", a bare "404") counts only next to a status code.</p>
 */
final class ErrorPageSignals {

    /** How far into the excerpt the error wording may start to count. */
    static final int EXCERPT_WINDOW = 160;

    private static final String[] NOT_FOUND = {
            "page not found", "seite nicht gefunden", "seite wurde nicht gefunden", "page does not exist",
            "page doesn't exist", "seite existiert nicht", "page cannot be found", "page could not be found",
            "diese seite gibt es nicht", "file not found", "datei nicht gefunden", "the page you requested",
            "die von ihnen aufgerufene seite", "die angeforderte seite", "404 not found", "404 - not found",
            "error 404", "fehler 404", "http 404"};

    private static final String[] SERVER_ERROR = {
            "internal server error", "bad gateway", "gateway timeout", "gateway time-out", "interner serverfehler",
            "error 500", "error 502", "error 503", "error 504", "fehler 500", "fehler 502", "fehler 503",
            "http error 5", "http 500", "http 502", "http 503"};

    private static final String[] UNAVAILABLE = {
            "temporarily unavailable", "currently unavailable", "service unavailable", "site is unavailable",
            "vorübergehend nicht verfügbar", "derzeit nicht verfügbar", "zurzeit nicht verfügbar",
            "under maintenance", "down for maintenance", "we'll be back soon", "wir sind bald wieder"};

    /** Phrases an article may use; they count only next to a status code. */
    private static final String[] AMBIGUOUS = {
            "not found", "nicht gefunden", "nicht verfügbar", "serverfehler", "ein fehler ist aufgetreten",
            "an error occurred", "something went wrong", "etwas ist schiefgelaufen", "wartungsarbeiten",
            "existiert nicht"};

    private static final String[] CODES = {"404", "500", "502", "503", "504"};

    private ErrorPageSignals() {
    }

    /** The reason code ({@code NOT_FOUND}, {@code SERVER_ERROR}, {@code UNAVAILABLE}) or {@code null}. */
    static String reason(BrowserPageReadiness probe) {
        if (probe == null) {
            return null;
        }
        String title = (probe.title == null ? "" : probe.title).toLowerCase(Locale.ROOT).trim();
        String excerpt = (probe.excerpt == null ? "" : probe.excerpt).toLowerCase(Locale.ROOT).trim();
        String opening = excerpt.length() <= EXCERPT_WINDOW ? excerpt : excerpt.substring(0, EXCERPT_WINDOW);
        String reason = match(title);
        return reason != null ? reason : match(opening);
    }

    static boolean isErrorPage(BrowserPageReadiness probe) {
        return reason(probe) != null;
    }

    private static String match(String text) {
        if (text.isEmpty()) {
            return null;
        }
        if (containsAny(text, SERVER_ERROR)) {
            return "SERVER_ERROR";
        }
        if (containsAny(text, UNAVAILABLE)) {
            return "UNAVAILABLE";
        }
        if (containsAny(text, NOT_FOUND)) {
            return "NOT_FOUND";
        }
        if (containsAny(text, AMBIGUOUS) && containsAny(text, CODES)) {
            return text.contains("404") ? "NOT_FOUND" : "SERVER_ERROR";
        }
        return null;
    }

    private static boolean containsAny(String text, String[] phrases) {
        for (String phrase : phrases) {
            if (text.contains(phrase)) {
                return true;
            }
        }
        return false;
    }
}
