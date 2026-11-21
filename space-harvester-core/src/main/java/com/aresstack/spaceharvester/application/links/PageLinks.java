package com.aresstack.spaceharvester.application.links;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.holdings.HoldingsChanges;
import com.aresstack.spaceharvester.application.observe.Coalescer;
import com.aresstack.spaceharvester.application.observe.Listeners;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Verzeichnis der Verweise zwischen lokal gespeicherten Seiten (Paket V1): liefert für eine Seite, was sie verweist und
 * welche Seiten auf sie verweisen, beides als {@link PageKey}, und wie weit das Verzeichnis die Quelle kennt („aus N
 * von M Seiten“). Grundlage für den Reiter „Verweise“ (Rückrichtung, die der Confluence-Anschluss nicht kennt) und für
 * die Verweisanteile des Strukturgraphen (G1).
 *
 * <p><b>Herkunft.</b> Die Ziele liest ein quellenspezifischer {@link PageLinkExtractor} aus der gespeicherten nativen
 * Payload; die Quelle wird nie gefragt. Erfasst ist eine Seite, deren Payload lokal liegt und deren Quelle einen
 * Extraktor hat. Die Rückrichtung ist darum genau so vollständig wie der lokale Bestand: {@link Coverage} nennt, aus
 * wie vielen von wie vielen bekannten Seiten sie stammt. Eine Quelle ohne Extraktor hat keine erfassten Seiten
 * ({@link Links#outgoingKnown()} {@code false}); geraten wird nichts.
 *
 * <p><b>Zuordnung.</b> Ein Ziel über die Seiten-ID gilt, wenn der Cache die Zielseite kennt. Ein Ziel über Space und
 * Titel gilt nur, wenn genau eine erfasste Seite so heißt (für die Rückrichtung: die abgefragte Seite selbst oder keine
 * andere); sonst entsteht keine Kante. Benennt jemand eine Zielseite um, verschwindet die alte Kante mit dem nächsten
 * Erfassen der Zielseite, eine neue entsteht erst, wenn eine Seite auf den neuen Titel verweist.
 *
 * <p><b>Aktualität.</b> Dieser Anwendungsfall hängt als {@link HoldingsChanges}-Empfänger am Seiten-Cache: Jeder
 * Commit meldet geschriebene, entfernte und gesperrte Seiten, hier wird nur vorgemerkt und gebündelt auf dem Thread
 * eines {@link Coalescer} erfasst. {@link #reconcileOnStart} gleicht beim Start im Hintergrund über den Hash der Payload
 * ab und lädt nur abweichende Payloads; solange er läuft, nennt die {@link Coverage} den wahren Stand. Jede Änderung
 * meldet {@link #subscribe} gebündelt (ADR 0027); niemand fragt in Abständen.
 *
 * <p><b>Rechte.</b> Abgefragt wird mit den Rechten des Aufrufers: {@code content} auf die Seite, jede Gegenseite nur
 * mit {@code metadata}; was er nicht sehen darf, fehlt ohne Spur. Die Zähler nennt die {@link Coverage} nur, wenn er die
 * ganze Quelle sehen darf, sonst {@code -1}. <b>Die KI-Freigabe (ADR 0028) prüft dieses Verzeichnis nicht:</b> Wer
 * Verweise oder verwiesene Seiten an ein Modell gibt (Chat, Strukturgraph), prüft die Freigabe jeder Seite selbst.
 */
public final class PageLinks implements HoldingsChanges, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(PageLinks.class.getName());

    /** Vormerkung „ganze Quelle abgleichen“ statt einzelner Seiten. */
    private static final String WHOLE_SOURCE = "\u0000*";

    /** So viele Seiten liest der Abgleich je Schritt aus dem Cache. */
    static final int BATCH = 500;

    /** Mehr Seiten nennt eine Meldung nicht; dann bleibt die Menge leer und gilt für die ganze Quelle. */
    public static final int MAX_PAGES_PER_MESSAGE = 1_000;

    private final StoredPages pages;
    private final LinkIndex index;
    private final List<PageLinkExtractor> extractors;
    private final AccessGuard access;
    private final Listeners<Consumer<LinksChanged>> listeners = new Listeners<>("Verweise");
    private final Coalescer<String, String> pending;
    private volatile boolean closed;

    public PageLinks(StoredPages pages, LinkIndex index, List<PageLinkExtractor> extractors, AccessGuard access) {
        this(pages, index, extractors, access, Coalescer.INTERVAL);
    }

    /** Mit eigenem Bündelungsintervall, für Tests. */
    PageLinks(StoredPages pages, LinkIndex index, List<PageLinkExtractor> extractors, AccessGuard access,
              Duration interval) {
        this.pages = Objects.requireNonNull(pages, "pages");
        this.index = Objects.requireNonNull(index, "index");
        this.extractors = List.copyOf(extractors);
        this.access = Objects.requireNonNull(access, "access");
        this.pending = new Coalescer<>("page-links", interval, this::deliver);
    }

    // --- Abfragen -------------------------------------------------------------------------------------------------

    /**
     * Verweise einer Seite in beide Richtungen.
     *
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException ohne {@code content} auf die
     *                                                                               Quelle; verdeckt ohne
     *                                                                               {@code content} auf die Seite
     */
    public Links links(AccessContext caller, PageKey page) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(page, "page");
        access.require(caller, ResourceScope.source(page.sourceId()), AccessKind.CONTENT);
        Optional<PageHeader> header = pages.header(page);
        access.requireConcealed(caller, ResourceScope.page(page.sourceId(),
                header.map(found -> found.metadata().partitionKey()).orElse(null), page.pageId()), AccessKind.CONTENT);
        return visible(caller, compute(page, header), coverage(caller, page.sourceId()));
    }

    /**
     * Verweise mehrerer Seiten, etwa für den Strukturgraphen; Seiten ohne {@code content}-Recht fehlen im Ergebnis.
     *
     * @return je sichtbarer Seite ihre Verweise, in der Reihenfolge der Eingabe
     */
    public Map<PageKey, Links> links(AccessContext caller, Collection<PageKey> pageKeys) {
        Objects.requireNonNull(caller, "caller");
        Map<PageKey, Links> result = new LinkedHashMap<>();
        Map<String, Coverage> coverages = new LinkedHashMap<>();
        for (PageKey page : new LinkedHashSet<>(pageKeys)) {
            Optional<PageHeader> header = pages.header(page);
            String spaceKey = header.map(found -> found.metadata().partitionKey()).orElse(null);
            if (!access.permits(caller, ResourceScope.page(page.sourceId(), spaceKey, page.pageId()),
                    AccessKind.CONTENT)) {
                continue;
            }
            Coverage coverage = coverages.computeIfAbsent(page.sourceId(), sourceId -> coverage(caller, sourceId));
            result.put(page, visible(caller, compute(page, header), coverage));
        }
        return result;
    }

    /** Wie weit das Verzeichnis eine Quelle kennt; {@code -1} je Zähler, wenn der Aufrufer nicht alles sehen darf. */
    public Coverage coverage(AccessContext caller, String sourceId) {
        if (!access.permitsSourceWide(caller, sourceId, AccessKind.METADATA)) {
            return Coverage.HIDDEN;
        }
        long known = pages.known(sourceId);
        return new Coverage(index.count(sourceId), (int) Math.min(Integer.MAX_VALUE, known));
    }

    /**
     * Meldet einen Empfänger für jede Änderung des Verzeichnisses an; zugestellt gebündelt auf einem eigenen Thread.
     * Die Meldung nennt die Quelle und die betroffenen Seiten; der Empfänger liest den Stand selbst.
     */
    public Subscription subscribe(Consumer<LinksChanged> listener) {
        return listeners.add(listener);
    }

    private Links compute(PageKey page, Optional<PageHeader> header) {
        String sourceId = page.sourceId();
        Optional<LinkIndex.Entry> entry = index.entry(page);
        Set<PageKey> outgoing = new LinkedHashSet<>();
        entry.ifPresent(found -> {
            for (LinkTarget target : found.targets()) {
                PageKey resolved = resolve(sourceId, target);
                if (resolved != null && !resolved.equals(page)) {
                    outgoing.add(resolved);
                }
            }
        });
        TreeSet<String> incoming = new TreeSet<>();
        for (LinkIndex.Entry linking : index.linkingToId(sourceId, page.pageId())) {
            incoming.add(linking.page().pageId());
        }
        if (header.isPresent()) {
            String titleKey = LinkTarget.titleKey(header.get().metadata().partitionKey(),
                    header.get().metadata().title());
            List<LinkIndex.Entry> named = index.titled(sourceId, titleKey);
            if (named.isEmpty() || named.size() == 1 && named.getFirst().page().equals(page)) {
                for (LinkIndex.Entry linking : index.linkingToTitle(sourceId, titleKey)) {
                    incoming.add(linking.page().pageId());
                }
            }
        }
        incoming.remove(page.pageId());
        return new Links(page, List.copyOf(outgoing),
                incoming.stream().map(pageId -> new PageKey(sourceId, pageId)).toList(), entry.isPresent(),
                Coverage.HIDDEN);
    }

    /** Die Seite hinter einem Ziel oder {@code null}, wenn sie nicht lokal bekannt oder nicht eindeutig ist. */
    private PageKey resolve(String sourceId, LinkTarget target) {
        if (target.byId()) {
            PageKey key = new PageKey(sourceId, target.pageId());
            return pages.header(key).isPresent() ? key : null;
        }
        List<LinkIndex.Entry> named = index.titled(sourceId, target.titleKey());
        return named.size() == 1 ? named.getFirst().page() : null;
    }

    private Links visible(AccessContext caller, Links links, Coverage coverage) {
        return new Links(links.page(), visible(caller, links.outgoing()), visible(caller, links.incoming()),
                links.outgoingKnown(), coverage);
    }

    private List<PageKey> visible(AccessContext caller, List<PageKey> keys) {
        List<PageKey> shown = new ArrayList<>(keys.size());
        for (PageKey key : keys) {
            String spaceKey = pages.header(key).map(found -> found.metadata().partitionKey()).orElse(null);
            if (access.permits(caller, ResourceScope.page(key.sourceId(), spaceKey, key.pageId()),
                    AccessKind.METADATA)) {
                shown.add(key);
            }
        }
        return List.copyOf(shown);
    }

    // --- Aktualisieren --------------------------------------------------------------------------------------------

    /** Reiht den Abgleich der Quellen mit dem Cache ein und kehrt sofort zurück. */
    public void reconcileOnStart(Collection<String> sourceIds) {
        for (String sourceId : List.copyOf(sourceIds)) {
            pending.mark(sourceId, Set.of(WHOLE_SOURCE));
        }
    }

    @Override
    public void pagesChanged(String sourceId, String spaceKey, Collection<String> pageIds) {
        if (!closed && !pageIds.isEmpty()) {
            pending.mark(sourceId, pageIds);
        }
    }

    @Override
    public void spaceChanged(String sourceId, String spaceKey) {
        if (!closed) {
            pending.mark(sourceId, Set.of(WHOLE_SOURCE));
        }
    }

    @Override
    public void sourceChanged(String sourceId) {
        if (!closed) {
            pending.mark(sourceId, Set.of(WHOLE_SOURCE));
        }
    }

    @Override
    public void loading(String sourceId, String spaceKey, Collection<String> pageIds) {
        // Erst das Ende des Ladens ändert etwas.
    }

    /** Erfasst sofort im aufrufenden Thread, wie es der Bündler täte; für Tests. */
    void applyNow(String sourceId, Collection<String> pageIds) {
        deliver(sourceId, Set.copyOf(pageIds));
    }

    /** Gleicht eine Quelle sofort im aufrufenden Thread ab; für Tests. */
    void reconcileNow(String sourceId) {
        deliver(sourceId, Set.of(WHOLE_SOURCE));
    }

    /** Läuft auf dem Thread des Bündlers. */
    private void deliver(String sourceId, Set<String> pageIds) {
        if (closed) {
            return;
        }
        try {
            if (pageIds.contains(WHOLE_SOURCE)) {
                reconcile(sourceId);
                return;
            }
            Set<String> changed = new HashSet<>();
            for (String pageId : pageIds) {
                if (closed) {
                    return;
                }
                refresh(new PageKey(sourceId, pageId), changed);
            }
            index.flush();
            announce(sourceId, changed);
        } catch (RuntimeException e) {
            // Nur Quelle und Ausnahmeklasse: Titel und Inhalte gehören nicht ins Protokoll.
            LOG.log(System.Logger.Level.WARNING, "Verweise der Quelle {0} nicht erfasst ({1})", sourceId,
                    e.getClass().getName());
        }
    }

    /** Gleicht eine Quelle über den Hash der Payload mit dem Cache ab; meldet nach jedem Schritt. */
    private void reconcile(String sourceId) {
        Set<String> seen = new HashSet<>();
        int refreshed = 0;
        String after = null;
        while (!closed) {
            List<StoredPage> batch = pages.pages(sourceId, after, BATCH);
            Set<String> changed = new HashSet<>();
            for (StoredPage stored : batch) {
                if (closed) {
                    return;
                }
                PageKey key = stored.header().key();
                seen.add(key.pageId());
                Optional<LinkIndex.Entry> entry = index.entry(key);
                if (stored.rawSha256() == null || extractor(stored.header().metadata()) == null) {
                    if (entry.isPresent()) {
                        refresh(key, changed);
                    }
                    continue;
                }
                if (entry.isEmpty() || !current(entry.get(), stored.rawSha256(), stored.header().metadata())) {
                    refresh(key, changed);
                    refreshed++;
                }
            }
            index.flush();
            announce(sourceId, changed);
            if (batch.size() < BATCH) {
                break;
            }
            after = batch.getLast().header().key().pageId();
        }
        if (closed) {
            return;
        }
        Set<String> removed = new HashSet<>();
        for (String pageId : index.pageIds(sourceId)) {
            if (!seen.contains(pageId)) {
                refresh(new PageKey(sourceId, pageId), removed);
            }
        }
        index.flush();
        announce(sourceId, removed);
        if (refreshed > 0 || !removed.isEmpty()) {
            LOG.log(System.Logger.Level.INFO, "Verweise der Quelle {0} abgeglichen: {1} Seiten erfasst", sourceId,
                    refreshed);
        }
    }

    /**
     * Erfasst eine Seite neu, wenn sich Payload, Space oder Titel geändert haben, und entfernt sie, wenn sie nicht mehr
     * lokal liegt; sammelt die betroffenen Seiten-IDs.
     */
    private void refresh(PageKey key, Set<String> changed) {
        Optional<LinkIndex.Entry> previous = index.entry(key);
        Optional<PageHeader> header = pages.header(key);
        PageLinkExtractor extractor = header.map(found -> extractor(found.metadata())).orElse(null);
        Optional<PageSnapshot> snapshot = extractor == null ? Optional.empty() : pages.snapshot(key);
        if (snapshot.isEmpty()) {
            if (index.remove(key)) {
                affected(previous.get(), changed);
            }
            return;
        }
        PageMetadata metadata = header.get().metadata();
        String sha = snapshot.get().payload().rawSha256();
        if (previous.isPresent() && current(previous.get(), sha, metadata)) {
            return;
        }
        List<LinkTarget> targets;
        if (previous.isPresent() && previous.get().rawSha256().equals(sha)) {
            targets = previous.get().targets();
        } else {
            try {
                targets = extractor.targets(snapshot.get());
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Verweise einer Seite der Quelle {0} nicht lesbar ({1})",
                        key.sourceId(), e.getClass().getName());
                if (index.remove(key)) {
                    affected(previous.get(), changed);
                }
                return;
            }
        }
        LinkIndex.Entry next = new LinkIndex.Entry(key, metadata.partitionKey(), metadata.title(), sha, targets);
        previous.ifPresent(old -> affected(old, changed));
        index.put(next);
        affected(next, changed);
    }

    private static boolean current(LinkIndex.Entry entry, String rawSha256, PageMetadata metadata) {
        return entry.rawSha256().equals(rawSha256) && entry.spaceKey().equals(metadata.partitionKey())
                && entry.title().equals(metadata.title());
    }

    /** Die Seite selbst, ihre Ziele und alle, die auf ihren Titel verweisen: Deren Kanten hängen an diesem Eintrag. */
    private void affected(LinkIndex.Entry entry, Set<String> changed) {
        String sourceId = entry.page().sourceId();
        changed.add(entry.page().pageId());
        for (LinkTarget target : entry.targets()) {
            if (target.byId()) {
                changed.add(target.pageId());
            } else {
                index.titled(sourceId, target.titleKey()).forEach(named -> changed.add(named.page().pageId()));
            }
        }
        index.linkingToTitle(sourceId, entry.titleKey()).forEach(linking -> changed.add(linking.page().pageId()));
    }

    private PageLinkExtractor extractor(PageMetadata metadata) {
        for (PageLinkExtractor extractor : extractors) {
            if (extractor.supports(metadata)) {
                return extractor;
            }
        }
        return null;
    }

    private void announce(String sourceId, Set<String> changed) {
        if (changed.isEmpty() || listeners.isEmpty()) {
            return;
        }
        LinksChanged event = new LinksChanged(sourceId,
                changed.size() > MAX_PAGES_PER_MESSAGE ? Set.of() : changed);
        listeners.deliver(sourceId, listener -> listener.accept(event));
    }

    /** Beendet die Erfassung; ein laufender Abgleich hört nach der aktuellen Seite auf. */
    @Override
    public void close() {
        closed = true;
        pending.close();
        index.flush();
    }

    // --- Typen ----------------------------------------------------------------------------------------------------

    /**
     * Port auf den Seiten-Cache, den das Verzeichnis liest.
     */
    public interface StoredPages {

        /** Aktuelle Kopfdaten einer Seite, auch ohne Payload. */
        Optional<PageHeader> header(PageKey key);

        /** Gespeicherter Snapshot einer Seite; leer ohne Payload oder für eine gesperrte Seite. */
        Optional<PageSnapshot> snapshot(PageKey key);

        /**
         * Seiten einer Quelle nach Seiten-ID aufsteigend, ohne Payload.
         *
         * @param afterPageId nur größere Seiten-IDs oder {@code null}
         */
        List<StoredPage> pages(String sourceId, String afterPageId, int limit);

        /** Anzahl der bekannten Seiten einer Quelle, mit und ohne Payload. */
        long known(String sourceId);
    }

    /**
     * Eine Seite im Cache.
     *
     * @param rawSha256 Hash der gespeicherten Payload oder {@code null}, wenn nur Kopfdaten vorliegen
     */
    public record StoredPage(PageHeader header, String rawSha256) {

        public StoredPage {
            Objects.requireNonNull(header, "header");
        }
    }

    /**
     * Verweise einer Seite.
     *
     * @param outgoing      Seiten, auf die sie verweist, in Reihenfolge der Seite; leer, wenn sie nicht erfasst ist
     * @param incoming      Seiten, die auf sie verweisen, nach Seiten-ID; aus den erfassten Seiten
     * @param outgoingKnown ob die Seite selbst erfasst ist
     * @param coverage      wie weit das Verzeichnis ihre Quelle kennt
     */
    public record Links(PageKey page, List<PageKey> outgoing, List<PageKey> incoming, boolean outgoingKnown,
                        Coverage coverage) {

        public Links {
            Objects.requireNonNull(page, "page");
            outgoing = List.copyOf(outgoing);
            incoming = List.copyOf(incoming);
            Objects.requireNonNull(coverage, "coverage");
        }

        /** Ob ausgehende und eingehende Verweise vollständig sind: Seite erfasst, alle bekannten Seiten erfasst. */
        public boolean complete() {
            return outgoingKnown && coverage.complete();
        }
    }

    /**
     * Wie weit das Verzeichnis eine Quelle kennt: erfasste von bekannten Seiten.
     *
     * @param indexed erfasste Seiten (Payload lokal, Extraktor vorhanden) oder {@code -1}, wenn verborgen
     * @param known   bekannte Seiten der Quelle, mit und ohne Payload, oder {@code -1}, wenn verborgen
     */
    public record Coverage(int indexed, int known) {

        /** Zähler, die der Aufrufer nicht sehen darf. */
        public static final Coverage HIDDEN = new Coverage(-1, -1);

        /** Ob alle bekannten Seiten erfasst sind. */
        public boolean complete() {
            return indexed >= 0 && indexed >= known;
        }

        /** Ob die Zähler bekannt sind. */
        public boolean shown() {
            return indexed >= 0 && known >= 0;
        }
    }

    /**
     * Eine Änderung des Verzeichnisses.
     *
     * @param pageIds betroffene Seiten der Quelle; leer heißt: viele, die ganze Quelle neu lesen
     */
    public record LinksChanged(String sourceId, Set<String> pageIds) {

        public LinksChanged {
            Objects.requireNonNull(sourceId, "sourceId");
            pageIds = Set.copyOf(pageIds);
        }
    }
}
