package com.aresstack.spaceharvester.persistence.lucene;

import com.aresstack.spaceharvester.application.acquisition.BackgroundFill;
import com.aresstack.spaceharvester.application.sync.BaselineEntry;
import com.aresstack.spaceharvester.application.sync.SyncBaselineStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Lücken des Katalogs im Seiten-Cache für die {@link BackgroundFill} (ADR 0014/0016), nur lesend und ohne Payload:
 * Spaces mit bestätigter Generation; je Seite nach ihren aktuellen Kopfdaten {@link BackgroundFill.Kind#NOT_LOADED},
 * wenn kein Inhalt gespeichert ist, und {@link BackgroundFill.Kind#OUTDATED}, wenn die Kopfdaten eine andere Version
 * nennen als der gespeicherte Inhalt; zuletzt geänderte zuerst ({@link BackgroundFill#RANK}). Gesperrte Seiten haben
 * kein Seitendokument mehr und erscheinen nicht.
 */
public final class LuceneCatalogGaps implements BackgroundFill.Catalog {

    private final LucenePageCache cache;

    public LuceneCatalogGaps(LucenePageCache cache) {
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    @Override
    public List<SyncBaselineStore.SpaceRef> spaces() {
        return cache.confirmedSpaces();
    }

    @Override
    public List<BackgroundFill.Gap> gaps(String sourceId, String spaceKey, int limit) {
        List<BackgroundFill.Gap> gaps = new ArrayList<>();
        if (limit < 1) {
            return gaps;
        }
        Map<String, BaselineEntry> stored = cache.knownPages(sourceId, spaceKey);
        String after = null;
        while (true) {
            List<CachedPageHeader> page = cache.spacePages(sourceId, spaceKey, after, LucenePageCache.MAX_PAGE_SIZE);
            for (CachedPageHeader header : page) {
                Instant modifiedAt = header.header().metadata().modifiedAt();
                if (header.rawSha256() == null) {
                    gaps.add(new BackgroundFill.Gap(header.key(), BackgroundFill.Kind.NOT_LOADED, modifiedAt));
                    continue;
                }
                BaselineEntry snapshot = stored.get(header.key().pageId());
                String current = header.header().revision();
                if (snapshot != null && current != null && snapshot.version() != null
                        && !current.equals(snapshot.version())) {
                    gaps.add(new BackgroundFill.Gap(header.key(), BackgroundFill.Kind.OUTDATED, modifiedAt));
                }
            }
            if (page.size() < LucenePageCache.MAX_PAGE_SIZE) {
                break;
            }
            after = page.getLast().key().pageId();
        }
        // Stabil: bei gleichem Datum nach Seiten-ID.
        gaps.sort(BackgroundFill.RANK);
        return gaps.size() > limit ? List.copyOf(gaps.subList(0, limit)) : gaps;
    }
}
