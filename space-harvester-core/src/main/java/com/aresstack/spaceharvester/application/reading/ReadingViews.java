package com.aresstack.spaceharvester.application.reading;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.query.DeliveredPage;
import com.aresstack.spaceharvester.application.query.PageQueries;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * Anwendungsfall „Lese-Ansicht einer Seite“ (#176): die Seite so, wie die Quelle sie rendert, mit ausgeführten
 * Makros ({@code include}, {@code excerpt-include}, {@code children}, Jira), dazu Lesetext und Bilder der Ansicht.
 *
 * <p>Rechte und Version wie beim Lesen der Seite ({@link PageQueries#page}): erst danach wird die Ansicht gezeigt,
 * und nur zur geprüften Version. Die Ansicht liegt neben dem Raw-Snapshot ({@link ReadingViewStore}); der Snapshot
 * bleibt unverändert die Quelle der Wahrheit. Eine abgelegte Ansicht derselben Payload wird ohne neuen Abruf wieder
 * ausgeliefert.
 *
 * <p>Bindet die Seite Fremdes ein ({@link ForeignContent}), hat die Quelle diese Teile mit ihren eigenen Zugangsdaten
 * gerendert. Dann gilt:
 * <ul>
 *   <li>Die Ansicht erhält nur, wer alles lesen darf, was diese Zugangsdaten sehen
 *       ({@link AccessGuard#permitsSourceWide}); sonst {@link ReadingView.Status#RESTRICTED} ohne Inhalt.</li>
 *   <li>Eingebundenes ändert sich ohne neue Version der Seite; die Ansicht wird daher jedes Mal neu abgerufen und
 *       nur dann aus der Ablage geliefert, wenn die Quelle gerade nicht erreichbar ist.</li>
 * </ul>
 */
public class ReadingViews {

    private static final System.Logger LOG = System.getLogger(ReadingViews.class.getName());

    private final SourceCatalog catalog;
    private final PageQueries pages;
    private final AccessGuard access;
    private final ReadingViewStore store;
    private final Clock clock;

    public ReadingViews(SourceCatalog catalog, PageQueries pages, AccessGuard access, ReadingViewStore store,
                        Clock clock) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.pages = Objects.requireNonNull(pages, "pages");
        this.access = Objects.requireNonNull(access, "access");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Die Lese-Ansicht einer Seite.
     *
     * @param refresh wie bei {@link PageQueries#page}: Prüfung gegen die Quelle ohne Rückgriff auf den Cache; dazu
     *                wird die Ansicht neu abgerufen
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException     wie {@link PageQueries#page}
     * @throws com.aresstack.spaceharvester.application.query.CacheMissException         wie {@link PageQueries#page}
     * @throws SourceAccessException wenn die Quelle die Ansicht nicht liefert und keine abgelegte vorliegt, oder wenn
     *                               sich die Seite beim Lesen wiederholt geändert hat ({@code UNAVAILABLE})
     */
    public ReadingView view(AccessContext caller, String sourceId, String pageId, boolean refresh) {
        DeliveredPage page = pages.page(caller, sourceId, pageId, refresh);
        ReadingView view = view(caller, page, refresh);
        if (view != null) {
            return view;
        }
        // Die Seite änderte sich zwischen Prüfung und Ansicht: einmal gegen die Quelle neu prüfen, dann gilt die neue
        // Version mit ihren Rechten.
        view = view(caller, pages.page(caller, sourceId, pageId, true), true);
        if (view == null) {
            throw new SourceAccessException(sourceId, SourceAccessException.Reason.UNAVAILABLE,
                    "Die Seite ändert sich gerade; die Lese-Ansicht passt zu keiner geprüften Version", null);
        }
        return view;
    }

    /** @return {@code null}, wenn die gelieferte Ansicht nicht zur geprüften Version gehört */
    private ReadingView view(AccessContext caller, DeliveredPage page, boolean refresh) {
        String sourceId = page.header().key().sourceId();
        ForeignContent foreign = ForeignContent.of(page.snapshot());
        if (foreign.present() && !access.permitsSourceWide(caller, sourceId, AccessKind.CONTENT)) {
            return ReadingView.without(page.header(), ReadingView.Status.RESTRICTED, foreign);
        }
        String rawSha256 = page.snapshot().payload().rawSha256();
        Optional<StoredReadingView> stored = storedQuietly(page).filter(view -> view.rawSha256().equals(rawSha256));
        if (stored.isPresent() && !refresh && !foreign.present()) {
            return delivered(page, stored.get(), foreign, true);
        }
        HarvestSource source = catalog.find(sourceId).orElseThrow(() -> new UnknownSourceException(sourceId));
        HarvestSource.ReadingPage live;
        try {
            live = source.readingPage(page.header().key().pageId());
        } catch (SourceAccessException e) {
            if (e.reason() == SourceAccessException.Reason.UNSUPPORTED) {
                return ReadingView.without(page.header(), ReadingView.Status.UNSUPPORTED, foreign);
            }
            boolean unreachable = e.reason() == SourceAccessException.Reason.UNAVAILABLE
                    || e.reason() == SourceAccessException.Reason.RATE_LIMITED;
            if (unreachable && !refresh && stored.isPresent()) {
                LOG.log(System.Logger.Level.INFO, "Quelle {0} für die Lese-Ansicht nicht erreichbar ({1}), "
                        + "abgelegte Ansicht ausgeliefert", sourceId, e.reason());
                return delivered(page, stored.get(), foreign, true);
            }
            throw e;
        }
        String version = page.snapshot().metadata().version();
        if (live.version() == null || !live.version().equals(version)) {
            return null;
        }
        StoredReadingView fresh = new StoredReadingView(page.header().key(), rawSha256, version, live.html(),
                live.baseUri(), live.text(), live.images(), clock.instant());
        saveQuietly(fresh);
        return delivered(page, fresh, foreign, false);
    }

    private static ReadingView delivered(DeliveredPage page, StoredReadingView view, ForeignContent foreign,
                                         boolean stored) {
        return new ReadingView(page.header(), ReadingView.Status.AVAILABLE, view.version(), view.html(),
                view.baseUri(), view.text(), view.images(), foreign, stored, view.renderedAt());
    }

    /** Lesen aus der Ablage darf die Ansicht nie verhindern; ohne Ablage wird neu abgerufen. */
    private Optional<StoredReadingView> storedQuietly(DeliveredPage page) {
        try {
            return store.find(page.header().key());
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Abgelegte Lese-Ansicht nicht lesbar ({0})", e.getClass().getName());
            return Optional.empty();
        }
    }

    /** Ablegen darf die Auslieferung nie verhindern; der nächste Abruf legt erneut ab. */
    private void saveQuietly(StoredReadingView view) {
        try {
            store.save(view);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Lese-Ansicht nicht abgelegt ({0})", e.getClass().getName());
        }
    }
}
