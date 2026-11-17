package com.aresstack.spaceharvester.application.standing;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.changelog.ChangeLog;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * {@link RecentChanges} aus dem Änderungsprotokoll (ADR 0030): nur gelesen, nur die Quellen der Auswahl, je Quelle ein
 * Gang durch den Index ({@link ChangeLog#latestContentChanges}); liegt die Auswahl einer Quelle in genau einem Space,
 * nur dieser Space.
 */
public final class ChangeLogRecentChanges implements RecentChanges {

    private final ChangeLog changeLog;

    public ChangeLogRecentChanges(ChangeLog changeLog) {
        this.changeLog = Objects.requireNonNull(changeLog, "changeLog");
    }

    @Override
    public Map<PageKey, Instant> since(AccessContext caller, Map<String, Set<String>> spaces, Instant from,
                                       BooleanSupplier cancelled) {
        Map<PageKey, Instant> latest = new HashMap<>();
        for (Map.Entry<String, Set<String>> source : spaces.entrySet()) {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("Leseempfehlung abgebrochen");
            }
            Set<String> keys = source.getValue();
            // Ein Space ohne Schlüssel (null) zählt wie mehrere: dann die ganze Quelle.
            String spaceKey = keys.size() == 1 ? keys.iterator().next() : null;
            latest.putAll(changeLog.latestContentChanges(caller, source.getKey(), spaceKey, from));
        }
        return latest;
    }
}
