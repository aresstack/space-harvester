package com.aresstack.spaceharvester.app.runtime.program;

import com.aresstack.spaceharvester.application.enrichment.EnrichmentOutput;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentProcessor;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.ProcessingNotAllowedException;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalysis;
import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalyzer;
import com.aresstack.spaceharvester.mainframe.cobol.CopybookResolver;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Persist parser facts in the existing page enrichment store. */
public final class CobolProgramEnrichment implements EnrichmentProcessor {
    public static final String ID = "cobol-program";
    public static final String VERSION = "1";
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private final Set<String> sourceIds;

    public CobolProgramEnrichment(Set<String> sourceIds) {
        this.sourceIds = Set.copyOf(sourceIds);
    }

    @Override public String id() { return ID; }
    @Override public String version() { return VERSION; }
    @Override public boolean appliesTo(PageKey key) { return sourceIds.contains(key.sourceId()); }

    @Override
    public EnrichmentOutput process(PageText text) {
        throw new ProcessingNotAllowedException("A verified program snapshot is required");
    }

    @Override
    public EnrichmentOutput process(PageSnapshot snapshot, PageText text) {
        if (!appliesTo(snapshot.key())) throw new ProcessingNotAllowedException("Not an FTP source");
        var source = StoredCobolSource.read(snapshot)
                .orElseThrow(() -> new ProcessingNotAllowedException("Not a verified fixed-format COBOL program"));
        CobolAnalysis analysis = CobolAnalyzer.proLeap().analyze(source, CopybookResolver.NONE);
        Artifact artifact = new Artifact(source.name(), snapshot.payload().rawSha256(), analysis);
        return new EnrichmentOutput(JSON.writeValueAsString(artifact), analysis.isPartial()
                ? List.of("Teilanalyse; Copybooks werden noch nicht aus dem Bestand aufgelöst.") : List.of());
    }

    public static Artifact read(String json) {
        return JSON.readValue(json, Artifact.class);
    }

    public record Artifact(String source, String rawSha256, CobolAnalysis analysis) { }
}
