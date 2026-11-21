package com.aresstack.spaceharvester.application.changelog;

import com.aresstack.spaceharvester.application.convert.SpaceNames;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.PageTextProjector;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * {@link SummaryMaterial} aus den gespeicherten Fassungen im Cache: Lesetext über die Projektoren der Volltextsuche,
 * Space-Name aus der Payload ({@code space.name}). Lädt nichts nach.
 */
public final class SnapshotSummaryMaterial implements SummaryMaterial {

    private final Function<PageKey, Optional<PageSnapshot>> snapshots;
    private final PageTextProjector projector;

    public SnapshotSummaryMaterial(Function<PageKey, Optional<PageSnapshot>> snapshots, PageTextProjector projector) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.projector = Objects.requireNonNull(projector, "projector");
    }

    @Override
    public Optional<String> text(PageKey page) {
        try {
            return snapshots.apply(page).flatMap(projector::project).map(PageText::text);
        } catch (RuntimeException e) {
            // Payload fehlt, ist beschädigt oder verletzt ihr Format: kein Auszug, die Seite geht nur mit Titel ein.
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> spaceName(PageKey page, String spaceKey) {
        try {
            return snapshots.apply(page).flatMap(snapshot -> SpaceNames.of(snapshot, spaceKey));
        } catch (RuntimeException e) {
            // Nie die Anzeige der Zusammenfassung kosten (Payload fehlt im Cache o. ä.): dann der Schlüssel.
            return Optional.empty();
        }
    }
}
