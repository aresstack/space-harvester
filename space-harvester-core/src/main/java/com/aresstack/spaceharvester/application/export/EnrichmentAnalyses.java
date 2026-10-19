package com.aresstack.spaceharvester.application.export;

import com.aresstack.spaceharvester.application.assets.ImageDescription;
import com.aresstack.spaceharvester.application.convert.PageAnalyses;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.facts.FactStore;
import com.aresstack.spaceharvester.application.facts.MetadataFact;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Analysedaten aus der Ablage der Anreicherungen (#359): je Prozessor ein Abschnitt, Zustand gegenüber dem
 * exportierten Snapshot. Neue Prozessoren (etwa Begriffe, Passagen) erscheinen ohne Umbau als weitere Abschnitte.
 * Bildbeschreibungen kommen aus dem Asset-Speicher, über die exportierten Bildbytes, auch ohne Bildanalyse der Seite.
 * Liest nur; rechnet weder fehlende noch veraltete Ergebnisse nach.
 */
public final class EnrichmentAnalyses implements PageAnalyses {

    private final Enrichments enrichments;
    private final FactStore facts;
    private final Function<String, Optional<ImageDescription>> descriptions;

    public EnrichmentAnalyses(Enrichments enrichments) {
        this(enrichments, enrichments.facts().orElse(null));
    }

    /** @param facts die Fakten, etwa mit Prüfablage, oder {@code null} für keine */
    public EnrichmentAnalyses(Enrichments enrichments, FactStore facts) {
        this(enrichments, facts, sha256 -> Optional.empty());
    }

    /**
     * @param facts        die Fakten, etwa mit Prüfablage, oder {@code null} für keine
     * @param descriptions vorhandene Bildbeschreibung je {@code sha256}, etwa {@code Assets::storedDescription}
     */
    public EnrichmentAnalyses(Enrichments enrichments, FactStore facts,
                              Function<String, Optional<ImageDescription>> descriptions) {
        this.enrichments = Objects.requireNonNull(enrichments, "enrichments");
        this.facts = facts;
        this.descriptions = Objects.requireNonNull(descriptions, "descriptions");
    }

    @Override
    public List<Processor> processors() {
        List<Processor> processors = new ArrayList<>();
        enrichments.processorVersions().forEach((id, version) -> processors.add(new Processor(id, version)));
        return processors;
    }

    @Override
    public PageAnalysis of(PageSnapshot snapshot) {
        Enrichments.Inspection inspection = enrichments.inspect(snapshot);
        Map<String, Enrichments.View> stored = new LinkedHashMap<>();
        for (Enrichments.View view : inspection.stored()) {
            stored.put(view.enrichment().processorId(), view);
        }
        Map<String, String> versions = enrichments.processorVersions();
        List<Section> sections = new ArrayList<>();
        inspection.applicable().forEach((id, version) -> {
            Enrichments.View view = stored.remove(id);
            sections.add(view == null ? Section.missing(new Processor(id, version)) : section(view, version));
        });
        // Gespeichert, aber nicht (mehr) zuständig: ein Prozessor, den es nicht mehr gibt oder der die Seite auslässt.
        stored.values().stream().sorted(Comparator.comparing(view -> view.enrichment().processorId()))
                .forEach(view -> sections.add(section(view, versions.get(view.enrichment().processorId()))));
        List<MetadataFact> pageFacts = facts == null ? List.of() : facts.factsOf(snapshot.key());
        return new PageAnalysis(inspection.inputSha256(), sections, pageFacts);
    }

    @Override
    public Optional<PageAnalyses.StoredImageDescription> imageDescription(String sha256) {
        if (sha256 == null) {
            return Optional.empty();
        }
        return descriptions.apply(sha256).map(description -> new PageAnalyses.StoredImageDescription(
                description.imageSha256(), description.describer(), description.modelVersion(), description.text(),
                description.createdAt()));
    }

    private static Section section(Enrichments.View view, String currentVersion) {
        Enrichment enrichment = view.enrichment();
        State state = enrichment.status() == Enrichment.Status.FAILED ? State.FAILED
                : view.freshness() == Enrichment.Freshness.CURRENT ? State.CURRENT : State.STALE;
        boolean failed = enrichment.status() == Enrichment.Status.FAILED;
        return new Section(enrichment.processorId(), currentVersion, state, enrichment.processorVersion(),
                enrichment.inputSha256(), enrichment.revision(), enrichment.computedAt(),
                failed ? null : enrichment.payloadJson(), enrichment.warnings(), enrichment.errorCode());
    }
}
