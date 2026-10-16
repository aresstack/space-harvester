package com.aresstack.spaceharvester.application.sync;

import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadataListing;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Explizite Aktualitätsprüfung einer einzelnen Seite gegen die Quelle ({@code ?refresh=true}, #27). Liest die
 * Einzelmetadaten live und lädt den Inhalt nur, wenn die Revision nicht mehr zum Cache passt.
 *
 * <p>Schreibt nicht in den Cache: Änderungen am Cache werden nur mit einer bestätigten Generation sichtbar (#16).
 * Eine neuere Seite liefert die Prüfung deshalb live, und der Cache-Stand gilt bis zum nächsten Abgleich als veraltet.
 * Ein Fehler der Quelle wird als {@link SourceAccessException} weitergereicht, nie durch den Cache-Stand ersetzt;
 * Rechte prüft der Aufrufer.
 */
public class PageFreshness {

    /** Ergebnis der Prüfung. */
    public enum Outcome {
        /** Gecachte Payload und Kopfdaten stimmen mit der Quelle überein. */
        CURRENT,
        /** Gleiche Revision, aber neue Kopfdaten (Labels, Titel, Eltern, Space): Payload gilt, Kopfdaten sind live. */
        HEADER_CHANGED,
        /** Neue Revision oder nicht im Cache: der Snapshot ist live geladen, der Cache bleibt bis zum Abgleich alt. */
        CONTENT_CHANGED,
        /** Die Quelle kennt die Seite nicht (mehr) oder sie ist nicht sichtbar. */
        GONE
    }

    /**
     * @param outcome   Ergebnis
     * @param header    gültige Kopfdaten oder {@code null} bei {@link Outcome#GONE}
     * @param snapshot  gültiger Snapshot (aus dem Cache oder live) oder {@code null} bei {@link Outcome#GONE}
     * @param checkedAt Zeitpunkt der Prüfung
     */
    public record Refreshed(Outcome outcome, PageHeader header, PageSnapshot snapshot, Instant checkedAt) {

        public Refreshed {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(checkedAt, "checkedAt");
            if ((outcome == Outcome.GONE) != (header == null || snapshot == null)) {
                throw new IllegalArgumentException("Kopfdaten und Snapshot genau dann, wenn die Seite existiert");
            }
        }

        /** Ob die gelieferten Daten nicht aus dem Cache stammen bzw. vom Cache abweichen. */
        public boolean cacheStale() {
            return outcome != Outcome.CURRENT;
        }
    }

    private final SourceQueries queries;
    private final PageContentFetcher fetcher;
    private final Clock clock;

    /** @param fetcher Inhaltsabruf oder {@code null}, solange keiner angebunden ist */
    public PageFreshness(SourceQueries queries, PageContentFetcher fetcher, Clock clock) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.fetcher = fetcher;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Ob eine Prüfung möglich ist; sonst antwortet die API mit {@code refresh-unavailable}, nie mit dem Cache. */
    public boolean available() {
        return fetcher != null;
    }

    /**
     * @param cachedHeader   aktuelle Kopfdaten im Cache, falls vorhanden
     * @param cachedSnapshot gespeicherter Snapshot im Cache, falls vorhanden
     * @throws SourceAccessException    wenn die Quelle nicht antworten kann (außer {@code NOT_FOUND}, das ist
     *                                  {@link Outcome#GONE})
     * @throws SyncUnavailableException wenn kein Inhaltsabruf angebunden ist ({@link #available()})
     */
    public Refreshed check(String sourceId, String pageId, Optional<PageHeader> cachedHeader,
                           Optional<PageSnapshot> cachedSnapshot) {
        if (!available()) {
            throw new SyncUnavailableException("Kein Inhaltsabruf angebunden");
        }
        PageKey key = new PageKey(sourceId, pageId);
        PageMetadataListing.Entry live;
        try {
            live = queries.pageMetadata(sourceId, pageId).entry();
        } catch (SourceAccessException e) {
            if (e.reason() == SourceAccessException.Reason.NOT_FOUND) {
                return new Refreshed(Outcome.GONE, null, null, clock.instant());
            }
            throw e;
        }
        String version = live.page().version();
        Optional<PageSnapshot> sameRevision = cachedSnapshot
                .filter(snapshot -> snapshot.key().equals(key) && version != null
                        && version.equals(snapshot.revision()));
        if (sameRevision.isPresent()) {
            boolean headerCurrent = cachedHeader
                    .filter(header -> header.metadataHash().equals(live.metadataHash())
                            && header.attributesHash().equals(live.attributesHash()))
                    .isPresent();
            Instant checkedAt = clock.instant();
            return headerCurrent
                    ? new Refreshed(Outcome.CURRENT, cachedHeader.get(), sameRevision.get(), checkedAt)
                    : new Refreshed(Outcome.HEADER_CHANGED, new PageHeader(key, live.page(), live.metadataHash(),
                    live.attributesHash(), checkedAt), sameRevision.get(), checkedAt);
        }
        PageSnapshot fresh = fetcher.fetch(sourceId, pageId);
        if (!fresh.key().equals(key)) {
            throw new SourceAccessException(sourceId, SourceAccessException.Reason.INVALID_RESPONSE,
                    "Die Quelle lieferte eine andere Seite", null);
        }
        return new Refreshed(Outcome.CONTENT_CHANGED, fresh.header(), fresh, clock.instant());
    }
}
