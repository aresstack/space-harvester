package com.aresstack.spaceharvester.app.runtime.program;

import com.aresstack.spaceharvester.application.enrichment.EnrichmentOutput;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentProcessor;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.ProcessingNotAllowedException;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.mainframe.parser.NaturalAnalysis;
import com.aresstack.spaceharvester.mainframe.parser.NaturalAnalyzer;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/** Persist structural Natural facts for verified, native NDV objects. */
public final class NaturalProgramEnrichment implements EnrichmentProcessor {
    public static final String ID = "natural-program";
    public static final String VERSION = "1";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final Set<String> sources;

    public NaturalProgramEnrichment(Set<String> sources) { this.sources = Set.copyOf(sources); }

    @Override public String id() { return ID; }
    @Override public String version() { return VERSION; }
    @Override public boolean appliesTo(PageKey key) { return sources.contains(key.sourceId()); }
    @Override public EnrichmentOutput process(PageText text) {
        throw new ProcessingNotAllowedException("A verified NDV snapshot is required");
    }
    @Override public EnrichmentOutput process(PageSnapshot snapshot, PageText text) {
        if (!appliesTo(snapshot.key())) throw new ProcessingNotAllowedException("Not an NDV source");
        var source = StoredNaturalSource.read(snapshot).orElseThrow(() -> new ProcessingNotAllowedException("Not verified Natural"));
        var analysis = new NaturalAnalyzer().analyze(source.library(), source.name(), source.extension(), source.content());
        return new EnrichmentOutput(JSON.writeValueAsString(new Artifact(snapshot.payload().rawSha256(), analysis)),
                List.of("Statische strukturelle Teilanalyse; Hinweise beachten."));
    }

    public static Artifact read(String json) { return JSON.readValue(json, Artifact.class); }
    public record Artifact(String rawSha256, NaturalAnalysis analysis) { }
}
