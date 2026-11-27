package com.aresstack.askai.research.runtime.acquire;

import com.aresstack.askai.browser.BrowserPageReadiness;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Error-page detection from the probed title and excerpt: the 404 page and the unavailable ResearchGate page the
 * live run of 2026-11-09 stored as sources are parked, an article that mentions an error is not.
 */
public class ErrorPageSignalsTest {

    private static BrowserPageReadiness page(String title, String excerpt) {
        return new BrowserPageReadiness("https://x/y", title, 900, excerpt, false, "", false, "");
    }

    @Test
    public void aNotFoundPageIsRecognisedByItsTitleOrItsOpeningWords() {
        assertEquals("NOT_FOUND", ErrorPageSignals.reason(page("404 - Seite nicht gefunden | DGUV",
                "Startseite Themen Publikationen Leider konnte die Seite nicht gefunden werden.")));
        assertEquals("NOT_FOUND", ErrorPageSignals.reason(page("Page not found - BAuA", "Home Topics")));
        assertEquals("NOT_FOUND", ErrorPageSignals.reason(page("Bundesanstalt für Arbeitsschutz",
                "Fehler 404 Die angeforderte Seite konnte nicht gefunden werden. Zur Startseite")));
        assertEquals("NOT_FOUND", ErrorPageSignals.reason(page("example.org",
                "Oops! The page you requested could not be found. Try the search.")));
    }

    @Test
    public void anUnavailableOrBrokenSiteIsRecognised() {
        assertEquals("UNAVAILABLE", ErrorPageSignals.reason(page("ResearchGate - Temporarily Unavailable",
                "We are working on it. Please try again later.")));
        assertEquals("UNAVAILABLE", ErrorPageSignals.reason(page("Wartung",
                "Diese Seite ist vorübergehend nicht verfügbar. Bitte versuchen Sie es später erneut.")));
        assertEquals("SERVER_ERROR", ErrorPageSignals.reason(page("500 Internal Server Error", "nginx")));
        assertEquals("SERVER_ERROR", ErrorPageSignals.reason(page("Example",
                "Ein Fehler ist aufgetreten (Fehlercode 503). Bitte laden Sie die Seite neu.")));
    }

    @Test
    public void anArticleThatMentionsAnErrorIsNotAnErrorPage() {
        assertNull(ErrorPageSignals.reason(page("Wearables im Arbeitsschutz: Sensoren im Test",
                "Smart glasses, exoskeletons and sensor vests on construction sites were not found to reduce falls "
                        + "on their own; training matters. An error occurred in 3 of 40 devices.")));
        assertNull(ErrorPageSignals.reason(page("Fehlersuche im Netzwerk",
                "Wenn Geräte nicht gefunden werden, prüfen Sie zuerst die Verkabelung und die Firewall.")));
        assertNull(ErrorPageSignals.reason(page("DGUV Information 215-410",
                "Bildschirm- und Büroarbeitsplätze. Leitfaden für die Gestaltung. Stand 2023.")));
        assertNull(ErrorPageSignals.reason(page("", "")));
        assertNull(ErrorPageSignals.reason(null));
    }

    @Test
    public void bothJudgesParkAnErrorPageWhateverItsTextLength() {
        BrowserPageReadiness notFound = page("404 - Seite nicht gefunden", "Navigation Impressum Datenschutz "
                + "Leider konnte die Seite nicht gefunden werden. Vielleicht hilft die Suche weiter.");
        assertEquals(PageReadinessJudge.Verdict.UNREADABLE, new HeuristicPageReadinessJudge(48).judge(notFound));
        PageReadinessModel poison = new PageReadinessModel() {
            public String complete(String system, String user) {
                return "READABLE"; // must never be consulted for a page that says it is an error page
            }
        };
        assertEquals(PageReadinessJudge.Verdict.UNREADABLE, new ModelPageReadinessJudge(poison, 48).judge(notFound));
    }
}
