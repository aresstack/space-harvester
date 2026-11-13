package com.aresstack.spaceharvester.application.release;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Die eine Freigabeprüfung des Core für den KI-Dienst (ADR 0028): Eine Seite darf an ihn gehen, wenn ihre Quelle in
 * {@code ai.external-processing-sources} steht oder sie auf der Liste der freigegebenen Seiten ({@link PageReleases})
 * steht. Beides liest bei jedem Aufruf den aktuellen Stand; ein Eintrag oder ein Entfernen gilt sofort, auch für schon
 * eingereihte Berechnungen. Text des Benutzers (Suchanfrage, Chat-Frage) prüft niemand.
 */
public final class AiRelease {

    private final Predicate<String> sources;
    private final PageReleases pages;

    /**
     * @param sources ob eine Quelle als Ganzes freigegeben ist, nach der wirksamen Konfiguration
     * @param pages   die Liste der freigegebenen Seiten
     */
    public AiRelease(Predicate<String> sources, PageReleases pages) {
        this.sources = Objects.requireNonNull(sources, "sources");
        this.pages = Objects.requireNonNull(pages, "pages");
    }

    /** Nur nach Quellen, mit einer leeren Liste im Speicher; etwa ohne Cache oder im Test. */
    public static AiRelease ofSources(Predicate<String> sources) {
        return new AiRelease(sources, PageReleases.inMemory());
    }

    /** Ob die Seite an den KI-Dienst gehen darf. */
    public boolean mayProcess(PageKey key) {
        return key != null && (sourceReleased(key.sourceId()) || pages.contains(key));
    }

    /** Wie {@link #mayProcess(PageKey)}; ohne Seite nur, wenn die ganze Quelle freigegeben ist. */
    public boolean mayProcess(String sourceId, String pageId) {
        if (sourceId == null || sourceId.isBlank()) {
            return false;
        }
        return pageId == null || pageId.isBlank() ? sourceReleased(sourceId)
                : mayProcess(new PageKey(sourceId, pageId));
    }

    /**
     * Ob die Quelle als Ganzes freigegeben ist; für Auswertungen, die keine einzelne Seite betreffen. Scheitert die
     * Konfiguration, gilt sie als nicht freigegeben.
     */
    public boolean sourceReleased(String sourceId) {
        if (sourceId == null || sourceId.isBlank()) {
            return false;
        }
        try {
            return sources.test(sourceId);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Die Liste der freigegebenen Seiten. */
    public PageReleases pages() {
        return pages;
    }
}
