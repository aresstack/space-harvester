package com.aresstack.spaceharvester.application.sync;

import com.aresstack.spaceharvester.application.sources.PageMetadataListing;
import com.aresstack.spaceharvester.application.sources.SpaceFingerprint;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Ergebnis einer Inventur: die Seitenmetadaten eines Space, wie die Quelle sie über alle Cursor geliefert hat.
 *
 * @param sourceId   Quellen-ID
 * @param spaceKey   Space-Key
 * @param status     ob die Inventur vollständig und widerspruchsfrei ist
 * @param problem    Grund für {@link Status#INCOMPLETE} oder {@link Status#INCONSISTENT}, sonst {@code null}; ohne
 *                   Zugangsdaten
 * @param pages      alle gelesenen Seiten, je {@code pageId} einmal, in der Reihenfolge der Quelle; bei einem
 *                   Abbruch nur die bis dahin gelesenen
 * @param startedAt  Beginn der Inventur
 * @param finishedAt Ende der Inventur
 */
public record Inventory(String sourceId, String spaceKey, Status status, String problem,
                        List<PageMetadataListing.Entry> pages, Instant startedAt, Instant finishedAt) {

    public enum Status {
        /** Alle Cursor gelesen, keine Widersprüche: kann eine Generation bestätigen. */
        COMPLETE,
        /** Abgebrochen (Quelle nicht erreichbar, Zugriff verweigert, …): Gelesenes ist gültig, Fehlendes unbekannt. */
        INCOMPLETE,
        /** Gelesen, aber widersprüchlich (Seite aus fremdem Space, während der Listung geändert, Cursorschleife). */
        INCONSISTENT
    }

    public Inventory {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(spaceKey, "spaceKey");
        Objects.requireNonNull(status, "status");
        if ((status == Status.COMPLETE) != (problem == null)) {
            throw new IllegalArgumentException("problem genau dann, wenn die Inventur nicht vollständig ist");
        }
        pages = List.copyOf(pages);
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(finishedAt, "finishedAt");
    }

    /** Nur dann darf die Inventur Seiten als fehlend werten und eine Generation bestätigen. */
    public boolean confirmed() {
        return status == Status.COMPLETE;
    }

    /** {@code spaceFingerprint} ({@value SpaceFingerprint#SCHEMA}), nur für eine bestätigte Inventur. */
    public Optional<String> spaceFingerprint() {
        if (!confirmed()) {
            return Optional.empty();
        }
        return Optional.of(SpaceFingerprint.of(sourceId, spaceKey, pages.stream()
                .map(entry -> new SpaceFingerprint.Page(entry.page().pageId(), entry.metadataHash(),
                        entry.attributesHash()))
                .toList()));
    }

    Map<String, PageMetadataListing.Entry> byPageId() {
        Map<String, PageMetadataListing.Entry> byId = new LinkedHashMap<>();
        pages.forEach(entry -> byId.put(entry.page().pageId(), entry));
        return byId;
    }
}
