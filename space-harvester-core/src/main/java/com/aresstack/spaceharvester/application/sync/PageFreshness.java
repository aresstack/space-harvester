package com.aresstack.spaceharvester.application.sync;

import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadataListing;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Explizite Aktualitätsprüfung einer einzelnen Seite gegen die Quelle ({@code ?refresh=true}, #27). Liest die
 * Einzelmetadaten live und lädt den Inhalt nur, wenn die Revision nicht mehr zum Cache passt.
 *
 * <p>Schreibt selbst nicht in den Cache; das Ablegen nachgeladener Seiten übernimmt der Aufrufer
 * ({@code PageQueries} mit {@code PageCacheFill}, ADR 0010).
 * Ein Fehler der Quelle wird als {@link SourceAccessException} weitergereicht, nie durch den Cache-Stand ersetzt;
 * Rechte prüft der Aufrufer, vor dem Aufruf für die Quelle und danach am gelieferten {@link Refreshed#header()}
 * (aktueller Space).
 *
 * <p>Auch ohne Cache-Eintrag aufrufbar (beide Optionals leer): Ergebnis ist dann {@link Outcome#CONTENT_CHANGED} mit
 * der live geladenen Seite oder {@link Outcome#GONE}. Der Aufruf ist synchron; er dauert höchstens so lange wie die
 * {@link #DEFAULT_TIMEOUT} (bzw. die übergebene Frist) für beide Abrufe zusammen; eine Überschreitung bricht ab und kommt
 * als {@link SourceAccessException} mit {@code UNAVAILABLE}. Ausnahmen und Logs enthalten keine Seiteninhalte.
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
     * @param header    gültige Kopfdaten (bei {@link Outcome#CURRENT} die gecachten, sonst live); {@code null} nur bei
     *                  {@link Outcome#GONE}
     * @param snapshot  auszuliefernde Payload: bei {@link Outcome#CURRENT} und {@link Outcome#HEADER_CHANGED} die
     *                  gecachte, bei {@link Outcome#CONTENT_CHANGED} die live geladene; {@code null} nur bei
     *                  {@link Outcome#GONE}
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

    /** Frist für eine Prüfung (Einzelmetadaten und gegebenenfalls Inhalt). */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private static final ExecutorService CHECKS = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("page-freshness-", 0).factory());

    private final SourceQueries queries;
    private final PageContentFetcher fetcher;
    private final Clock clock;
    private final Duration timeout;

    /** @param fetcher Inhaltsabruf oder {@code null}, solange keiner angebunden ist */
    public PageFreshness(SourceQueries queries, PageContentFetcher fetcher, Clock clock) {
        this(queries, fetcher, clock, DEFAULT_TIMEOUT);
    }

    /** @param timeout Frist für eine ganze Prüfung */
    public PageFreshness(SourceQueries queries, PageContentFetcher fetcher, Clock clock, Duration timeout) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.fetcher = fetcher;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout muss positiv sein");
        }
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
        return check(sourceId, pageId, cachedHeader, cachedSnapshot, header -> true);
    }

    /**
     * Wie {@link #check(String, String, Optional, Optional)}, mit Rechteprüfung an den live gelesenen Kopfdaten,
     * bevor ein Inhalt geladen wird: Ist die Seite für den Aufrufer nicht lesbar, endet die Prüfung als
     * {@link Outcome#GONE}, ohne Inhaltsabruf.
     */
    public Refreshed check(String sourceId, String pageId, Optional<PageHeader> cachedHeader,
                           Optional<PageSnapshot> cachedSnapshot, Predicate<PageHeader> permitted) {
        Objects.requireNonNull(permitted, "permitted");
        if (!available()) {
            throw new SyncUnavailableException("Kein Inhaltsabruf angebunden");
        }
        // Eigener virtueller Thread, damit der Request-Thread nach der Frist zurückkehrt, auch wenn ein Abruf
        // Unterbrechungen ignoriert; der abgebrochene Abruf endet dann mit den Zeitgrenzen der Quelle.
        Future<Refreshed> check = CHECKS.submit(() -> checkNow(sourceId, pageId, cachedHeader, cachedSnapshot,
                permitted));
        try {
            return check.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            check.cancel(true);
            throw new SourceAccessException(sourceId, SourceAccessException.Reason.UNAVAILABLE,
                    "Prüfung gegen die Quelle hat die Frist überschritten", null);
        } catch (InterruptedException e) {
            check.cancel(true);
            Thread.currentThread().interrupt();
            throw new SourceAccessException(sourceId, SourceAccessException.Reason.UNAVAILABLE,
                    "Prüfung gegen die Quelle unterbrochen", null);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Prüfung gegen die Quelle fehlgeschlagen", e.getCause());
        }
    }

    private Refreshed checkNow(String sourceId, String pageId, Optional<PageHeader> cachedHeader,
                               Optional<PageSnapshot> cachedSnapshot, Predicate<PageHeader> permitted) {
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
        PageHeader liveHeader = new PageHeader(key, live.page(), live.metadataHash(), live.attributesHash(),
                clock.instant());
        if (!permitted.test(liveHeader)) {
            // Erst die Rechte, dann der Inhalt: eine nicht lesbare Seite wird gar nicht geladen.
            return new Refreshed(Outcome.GONE, null, null, clock.instant());
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
