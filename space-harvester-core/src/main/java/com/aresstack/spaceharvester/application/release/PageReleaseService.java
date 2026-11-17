package com.aresstack.spaceharvester.application.release;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.holdings.Holdings;
import com.aresstack.spaceharvester.application.holdings.Selection;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Die Liste freigegebener Seiten für die Oberfläche (ADR 0028, Abschnitte 3 und 6): Eintragen aus der links gewählten
 * Auswahl, die Liste gebündelt nach Quelle, Space und Knoten, Entfernen je Seite, Gruppe oder Space und die Zahl der
 * Seiten einer Auswahl, die neu auf die Liste kämen. Änderungen meldet die Liste selbst ({@link PageReleases#subscribe},
 * ADR 0027); was ein Eintragen oder Entfernen an KI-Ergebnissen auslöst, hängt dort an.
 *
 * <p>Nur Seiten, die der Aufrufer sehen darf ({@code metadata} auf die Seite, wie im Bestand), erscheinen, lassen sich
 * entfernen und kommen in seinen Meldungen ({@link #subscribe}) vor; den Titel eines Knotens nennt sie nur, wenn er
 * den Knoten sehen darf.
 */
public final class PageReleaseService {

    private final PageReleases pages;
    private final Holdings holdings;
    private final AccessGuard access;
    private final Function<PageKey, Optional<PageHeader>> headers;
    private final Predicate<PageKey> indexed;
    private final Clock clock;

    /**
     * @param headers die Kopfdaten einer Seite im Cache, für Titel
     * @param indexed ob die Seite ein Embedding hat
     */
    public PageReleaseService(PageReleases pages, Holdings holdings, AccessGuard access,
                              Function<PageKey, Optional<PageHeader>> headers, Predicate<PageKey> indexed,
                              Clock clock) {
        this.pages = Objects.requireNonNull(pages, "pages");
        this.holdings = Objects.requireNonNull(holdings, "holdings");
        this.access = Objects.requireNonNull(access, "access");
        this.headers = Objects.requireNonNull(headers, "headers");
        this.indexed = Objects.requireNonNull(indexed, "indexed");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Eine Gruppe der Liste: die Seiten einer Quelle und eines Space, die über denselben Knoten hineinkamen.
     *
     * @param node       Seite des Knotens (Seite samt Unterseiten) oder {@code null} für den ganzen Space
     * @param nodeTitle  Titel des Knotens aus dem Cache oder {@code null}, wenn er dort fehlt oder kein Knoten ist
     * @param count      Anzahl der Seiten
     * @param releasedAt jüngster Zeitpunkt des Eintragens in der Gruppe
     */
    public record Group(String sourceId, String spaceKey, String node, String nodeTitle, int count,
                        Instant releasedAt) {

        public Group {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(spaceKey, "spaceKey");
            Objects.requireNonNull(releasedAt, "releasedAt");
        }
    }

    /**
     * Eine Seite der Liste.
     *
     * @param title   Titel aus dem Cache oder {@code null}, wenn die Seite dort fehlt
     * @param indexed ob sie ein Embedding hat
     */
    public record ReleasedPage(PageKey key, String spaceKey, String node, String title, Instant releasedAt,
                               boolean indexed) {

        public ReleasedPage {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(spaceKey, "spaceKey");
            Objects.requireNonNull(releasedAt, "releasedAt");
        }
    }

    /**
     * Trägt die lokal gespeicherten Seiten der Auswahl ein, mit der Wurzel der Auswahl als Knoten (ADR 0028,
     * Abschnitt 3): so beim Senden im Chat. Was nicht lokal ist, kommt nicht auf die Liste; schon eingetragene Seiten
     * behalten ihren ersten Eintrag. Ohne Quellenzugriff.
     *
     * @return die neu eingetragenen Seiten
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException wie {@link Holdings#resolve}
     */
    public List<PageReleases.Entry> release(AccessContext caller, Selection selection) {
        Instant now = clock.instant();
        List<PageReleases.Entry> entries = new ArrayList<>();
        for (Holdings.StoredPage page : holdings.storedPages(caller, selection)) {
            entries.add(new PageReleases.Entry(page.key(), page.spaceKey(), page.rootPageId(), PageReleases.CHAT,
                    now));
        }
        return entries.isEmpty() ? List.of() : pages.add(entries);
    }

    /**
     * Wie viele lokal gespeicherte Seiten der Auswahl neu auf die Liste kämen, damit der Chat es vor dem Senden nennen
     * kann; trägt nichts ein.
     */
    public int newPages(AccessContext caller, Selection selection) {
        int count = 0;
        for (Holdings.StoredPage page : holdings.storedPages(caller, selection)) {
            if (!pages.contains(page.key())) {
                count++;
            }
        }
        return count;
    }

    /** Die Gruppen der Liste, nach Quelle, Space und Knoten (der ganze Space zuerst, dann nach Titel). */
    public List<Group> groups(AccessContext caller) {
        Map<GroupKey, List<PageReleases.Entry>> grouped = grouped(caller);
        List<Group> groups = new ArrayList<>(grouped.size());
        grouped.forEach((key, entries) -> groups.add(new Group(key.sourceId(), key.spaceKey(), key.node(),
                key.node() == null || !visible(caller, key.sourceId(), key.spaceKey(), key.node()) ? null
                        : title(new PageKey(key.sourceId(), key.node())), entries.size(),
                entries.stream().map(PageReleases.Entry::releasedAt).max(Comparator.naturalOrder()).orElseThrow())));
        groups.sort(Comparator.comparing(Group::sourceId).thenComparing(Group::spaceKey)
                .thenComparing(group -> group.node() != null)
                .thenComparing(group -> sortKey(group.nodeTitle(), group.node())));
        return List.copyOf(groups);
    }

    /**
     * Die Seiten einer Gruppe mit Titel, Zeitpunkt und ob sie indexiert sind, nach Titel.
     *
     * @param node wie {@link Group#node()}
     */
    public List<ReleasedPage> pages(AccessContext caller, String sourceId, String spaceKey, String node) {
        List<PageReleases.Entry> entries = grouped(caller).getOrDefault(new GroupKey(sourceId, spaceKey, node),
                List.of());
        List<ReleasedPage> released = new ArrayList<>(entries.size());
        for (PageReleases.Entry entry : entries) {
            released.add(new ReleasedPage(entry.key(), entry.spaceKey(), entry.node(), title(entry.key()),
                    entry.releasedAt(), indexed.test(entry.key())));
        }
        released.sort(Comparator.comparing(page -> sortKey(page.title(), page.key().pageId())));
        return List.copyOf(released);
    }

    /** Entfernt Seiten von der Liste; ihre KI-Ergebnisse entfallen danach (ADR 0028, Abschnitt 4). */
    public List<PageReleases.Entry> remove(AccessContext caller, Collection<PageKey> keys) {
        Set<PageKey> wanted = new HashSet<>(keys);
        return removeWhere(caller, entry -> wanted.contains(entry.key()));
    }

    /** Entfernt eine Gruppe; {@code node} wie {@link Group#node()}. */
    public List<PageReleases.Entry> removeGroup(AccessContext caller, String sourceId, String spaceKey, String node) {
        GroupKey group = new GroupKey(sourceId, spaceKey, node);
        return removeWhere(caller, entry -> GroupKey.of(entry).equals(group));
    }

    /** Entfernt alle Seiten eines Space, über welchen Knoten sie auch hineinkamen. */
    public List<PageReleases.Entry> removeSpace(AccessContext caller, String sourceId, String spaceKey) {
        return removeWhere(caller, entry -> entry.key().sourceId().equals(sourceId)
                && entry.spaceKey().equals(spaceKey));
    }

    private List<PageReleases.Entry> removeWhere(AccessContext caller, Predicate<PageReleases.Entry> matching) {
        List<PageKey> keys = visible(caller).stream().filter(matching).map(PageReleases.Entry::key).toList();
        return keys.isEmpty() ? List.of() : pages.remove(keys);
    }

    private Map<GroupKey, List<PageReleases.Entry>> grouped(AccessContext caller) {
        Map<GroupKey, List<PageReleases.Entry>> grouped = new LinkedHashMap<>();
        for (PageReleases.Entry entry : visible(caller)) {
            grouped.computeIfAbsent(GroupKey.of(entry), key -> new ArrayList<>()).add(entry);
        }
        return grouped;
    }

    /**
     * Meldet einen Empfänger für Änderungen der Liste an (ADR 0027), nur mit den Seiten, die der Aufrufer sehen darf;
     * eine Änderung ganz ohne solche Seiten meldet sie nicht. Zugestellt auf dem Thread, der die Liste änderte.
     */
    public Subscription subscribe(AccessContext caller, Consumer<PageReleases.Change> listener) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(listener, "listener");
        return pages.subscribe(change -> {
            List<PageReleases.Entry> added = change.added().stream().filter(entry -> visible(caller, entry)).toList();
            List<PageReleases.Entry> removed = change.removed().stream().filter(entry -> visible(caller, entry))
                    .toList();
            if (!added.isEmpty() || !removed.isEmpty()) {
                listener.accept(new PageReleases.Change(added, removed));
            }
        });
    }

    /** Die Einträge, die der Aufrufer sehen darf. */
    private List<PageReleases.Entry> visible(AccessContext caller) {
        Objects.requireNonNull(caller, "caller");
        return pages.entries().stream().filter(entry -> visible(caller, entry)).toList();
    }

    private boolean visible(AccessContext caller, PageReleases.Entry entry) {
        return visible(caller, entry.key().sourceId(), entry.spaceKey(), entry.key().pageId());
    }

    private boolean visible(AccessContext caller, String sourceId, String spaceKey, String pageId) {
        return access.permits(caller, ResourceScope.page(sourceId, spaceKey, pageId), AccessKind.METADATA);
    }

    private String title(PageKey key) {
        return headers.apply(key).map(header -> header.metadata().title()).orElse(null);
    }

    private static String sortKey(String title, String fallback) {
        return (title == null ? "￿" + Objects.toString(fallback, "") : title).toLowerCase(Locale.ROOT);
    }

    private record GroupKey(String sourceId, String spaceKey, String node) {

        static GroupKey of(PageReleases.Entry entry) {
            return new GroupKey(entry.key().sourceId(), entry.spaceKey(), entry.node());
        }
    }
}
