package com.aresstack.spaceharvester.application.standing;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.changelog.ChangeEntries;
import com.aresstack.spaceharvester.application.changelog.ChangeEntry;
import com.aresstack.spaceharvester.application.changelog.ChangeFilter;
import com.aresstack.spaceharvester.application.changelog.ChangeKind;
import com.aresstack.spaceharvester.application.changelog.ChangeLog;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** {@link RecentChanges} aus dem Änderungsprotokoll (ADR 0030): nur gelesen, seitenweise, nur sichtbare Zeilen. */
public final class ChangeLogRecentChanges implements RecentChanges {

    /** Zählt als Inhaltsänderung; Umbenennen, Verschieben, Labels und Erstladen nicht. */
    static final Set<ChangeKind> CONTENT = Set.of(ChangeKind.NEW, ChangeKind.CONTENT_CHANGED);

    private final ChangeLog changeLog;

    public ChangeLogRecentChanges(ChangeLog changeLog) {
        this.changeLog = Objects.requireNonNull(changeLog, "changeLog");
    }

    @Override
    public Map<PageKey, Instant> since(AccessContext caller, Instant from, BooleanSupplier cancelled) {
        Map<PageKey, Instant> latest = new HashMap<>();
        int offset = 0;
        while (true) {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("Leseempfehlung abgebrochen");
            }
            ChangeEntries page = changeLog.entries(caller, new ChangeFilter(null, null, CONTENT, Set.of(), null,
                    from, null, offset, ChangeFilter.MAX_LIMIT));
            for (ChangeEntry entry : page.entries()) {
                if (entry.sourceId() != null && entry.pageId() != null && entry.at() != null) {
                    latest.merge(new PageKey(entry.sourceId(), entry.pageId()), entry.at(),
                            (a, b) -> a.isAfter(b) ? a : b);
                }
            }
            offset += page.entries().size();
            if (page.entries().isEmpty() || offset >= page.total()) {
                return latest;
            }
        }
    }
}
