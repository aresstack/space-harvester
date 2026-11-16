package com.aresstack.spaceharvester.persistence.lucene;

import com.aresstack.spaceharvester.application.changelog.ChangeEntry;
import com.aresstack.spaceharvester.application.changelog.ChangeKind;
import com.aresstack.spaceharvester.application.changelog.ChangeTrigger;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.lucene.document.Document;

/**
 * Was ein Schreiblauf an jeder Seite geändert hat, als Zeilen des Änderungsprotokolls (ADR 0030): der bestätigte Stand
 * beim ersten Anfassen der Seite gegen den letzten Stand des Laufs. Mehrere Schritte an derselben Seite in einem Lauf
 * (Kopfdaten, dann Inhalt) ergeben so eine Änderung, nicht mehrere; unveränderte Seiten ergeben keine Zeile.
 */
final class ChangeRows {

    /** Gespeicherte Felder des Seitendokuments, die der Vergleich braucht. */
    static final Set<String> FIELDS = Set.of(PageCacheSchema.SPACE_KEY, PageCacheSchema.PARENT_ID,
            PageCacheSchema.TITLE, PageCacheSchema.VERSION_FIELD, PageCacheSchema.METADATA_HASH,
            PageCacheSchema.ATTRIBUTES_HASH,
            PageCacheSchema.RAW_SHA256, PageCacheSchema.SNAPSHOT + PageCacheSchema.VERSION_FIELD,
            PageCacheSchema.SNAPSHOT + PageCacheSchema.METADATA_HASH);

    /** Der für das Protokoll wichtige Stand einer Seite. */
    record State(String spaceKey, String parentId, String title, String version, String metadataHash,
                 String attributesHash,
                 boolean content, String contentVersion, String contentMetadataHash, String rawSha256) {

        static State of(Document page) {
            String raw = page.get(PageCacheSchema.RAW_SHA256);
            return new State(page.get(PageCacheSchema.SPACE_KEY), page.get(PageCacheSchema.PARENT_ID),
                    page.get(PageCacheSchema.TITLE), page.get(PageCacheSchema.VERSION_FIELD),
                    page.get(PageCacheSchema.METADATA_HASH), page.get(PageCacheSchema.ATTRIBUTES_HASH), raw != null,
                    page.get(PageCacheSchema.SNAPSHOT + PageCacheSchema.VERSION_FIELD),
                    page.get(PageCacheSchema.SNAPSHOT + PageCacheSchema.METADATA_HASH), raw);
        }

        /** Aktuelle Kopfdaten mit dem gespeicherten Snapshot, {@code snapshot} {@code null} ohne Inhalt. */
        static State of(PageHeader header, PageSnapshot snapshot) {
            return new State(header.metadata().partitionKey(), header.metadata().parentPageId(),
                    header.metadata().title(), header.metadata().version(), header.metadataHash(), header.attributesHash(), snapshot != null,
                    snapshot == null ? null : snapshot.metadata().version(),
                    snapshot == null ? null : snapshot.metadataHash(),
                    snapshot == null ? null : snapshot.payload().rawSha256());
        }
    }

    private enum Outcome { PRESENT, REMOVED, WITHHELD }

    private record After(Outcome outcome, State state) {
    }

    /** Bestätigter Stand beim ersten Anfassen; {@code null}-Wert: die Seite war nicht im Cache. */
    private final Map<PageKey, State> before = new HashMap<>();
    private final Map<PageKey, After> after = new LinkedHashMap<>();
    private final Set<String> removedSources = new HashSet<>();
    private final Map<String, Long> generations = new HashMap<>();

    /** Ob der bisherige Stand der Seite schon feststeht; sonst {@link #before} vor dem Schreiben aufrufen. */
    boolean known(PageKey key) {
        return before.containsKey(key) || removedSources.contains(key.sourceId());
    }

    /** Der bestätigte Stand vor diesem Lauf, {@code null} für eine unbekannte Seite. */
    void before(PageKey key, Document page) {
        before.put(key, page == null ? null : State.of(page));
    }

    /**
     * Ob {@code state} einen Inhalt bringt, der eine schon vor dem Lauf gespeicherte andere Fassung im Sinn des
     * Protokolls ersetzt (ADR 0030, Abschnitt 4): eine andere Version des Inhalts, bei Inhalten ohne Version ein anderer
     * Inhalts-Hash wie bei {@link ChangeKind#CONTENT_CHANGED}. Dazu zählt das Nachladen einer erst im Katalog gesehenen
     * Version. Andere Rohbytes derselben Version (dynamische Makros) sind keine neue Fassung.
     */
    boolean replacesContent(PageKey key, State state) {
        State was = before.get(key);
        if (was == null || !was.content() || !state.content() || Objects.equals(was.rawSha256(), state.rawSha256())) {
            return false;
        }
        return was.contentVersion() == null || state.contentVersion() == null
                || !was.contentVersion().equals(state.contentVersion());
    }

    void present(PageKey key, State state) {
        after.put(key, new After(Outcome.PRESENT, state));
    }

    void removed(PageKey key) {
        after.put(key, new After(Outcome.REMOVED, null));
    }

    void withheld(PageKey key) {
        after.put(key, new After(Outcome.WITHHELD, null));
    }

    /** Die Quelle wird samt Protokoll entfernt; was danach im selben Lauf geschieht, beginnt ohne Stand. */
    void sourceRemoved(String sourceId) {
        removedSources.add(sourceId);
        before.keySet().removeIf(key -> key.sourceId().equals(sourceId));
        after.keySet().removeIf(key -> key.sourceId().equals(sourceId));
    }

    void generation(String sourceId, String spaceKey, long generation) {
        generations.put(sourceId + '\u001f' + spaceKey, generation);
    }

    boolean isEmpty() {
        return after.isEmpty();
    }

    /** Die Zeilen des Laufs, alle mit Zeitpunkt {@code at} und Auslöser {@code trigger}. */
    List<ChangeEntry> entries(Instant at, ChangeTrigger trigger) {
        List<ChangeEntry> entries = new ArrayList<>();
        after.forEach((key, now) -> {
            State was = before.get(key);
            switch (now.outcome()) {
                case REMOVED -> {
                    if (was != null) {
                        entries.add(entry(key, was.spaceKey(), at, ChangeKind.REMOVED, trigger, was, null, false));
                    }
                }
                case WITHHELD -> {
                    if (was != null) {
                        entries.add(entry(key, was.spaceKey(), at, ChangeKind.WITHHELD, trigger, was, null, false));
                    }
                }
                case PRESENT -> changes(key, was, now.state(), at, trigger, entries);
            }
        });
        return entries;
    }

    private void changes(PageKey key, State was, State is, Instant at, ChangeTrigger trigger,
                         List<ChangeEntry> entries) {
        String space = is.spaceKey();
        if (was == null) {
            entries.add(entry(key, space, at, ChangeKind.NEW, trigger, null, is, !is.content()));
            return;
        }
        // Ohne Versionskennung zählt wie in SyncPlanner.classify der metadataHash (z. B. geändertes modifiedAt).
        boolean newVersion = !Objects.equals(was.version(), is.version()) || was.version() == null
                && (!Objects.equals(was.metadataHash(), is.metadataHash())
                || was.content() && is.content() && !Objects.equals(was.rawSha256(), is.rawSha256()));
        if (newVersion) {
            boolean pending = !is.content() || is.version() != null && !is.version().equals(is.contentVersion())
                    || is.version() == null && !Objects.equals(is.metadataHash(), is.contentMetadataHash());
            entries.add(entry(key, space, at, ChangeKind.CONTENT_CHANGED, trigger, was, is, pending));
        } else if (is.content() && !was.content()) {
            entries.add(entry(key, space, at, ChangeKind.LOADED, trigger, was, is, false));
        } else if (is.content() && (!Objects.equals(was.contentVersion(), is.contentVersion())
                || !Objects.equals(was.rawSha256(), is.rawSha256()))) {
            entries.add(entry(key, space, at, ChangeKind.RELOADED, trigger, was, is, false));
        }
        if (!Objects.equals(was.title(), is.title())) {
            entries.add(entry(key, space, at, ChangeKind.RENAMED, trigger, was, is, false));
        }
        if (!Objects.equals(was.spaceKey(), is.spaceKey())) {
            entries.add(entry(key, space, at, ChangeKind.MOVED_OUT, trigger, was, is, false));
        } else if (!Objects.equals(was.parentId(), is.parentId())) {
            entries.add(entry(key, space, at, ChangeKind.MOVED, trigger, was, is, false));
        }
        if (!Objects.equals(was.attributesHash(), is.attributesHash())) {
            entries.add(entry(key, space, at, ChangeKind.LABELS_CHANGED, trigger, was, is, false));
        }
    }

    private ChangeEntry entry(PageKey key, String spaceKey, Instant at, ChangeKind kind, ChangeTrigger trigger,
                              State was, State is, boolean pending) {
        return new ChangeEntry(key.sourceId(), spaceKey, key.pageId(), at, kind, trigger,
                was == null ? null : was.version(), is == null ? null : is.version(),
                was == null ? null : was.title(), is == null ? null : is.title(),
                was == null ? null : was.parentId(), is == null ? null : is.parentId(),
                was == null ? null : was.spaceKey(), is == null ? null : is.spaceKey(),
                generations.get(key.sourceId() + '\u001f' + spaceKey), pending);
    }
}
