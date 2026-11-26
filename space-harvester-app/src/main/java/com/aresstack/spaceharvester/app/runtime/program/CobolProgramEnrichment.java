package com.aresstack.spaceharvester.app.runtime.program;

import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentOutput;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentProcessor;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.ProcessingNotAllowedException;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalysis;
import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalyzer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Persist parser facts and dependency probes in the existing page enrichment store. */
public final class CobolProgramEnrichment implements EnrichmentProcessor {
    public static final String ID = "cobol-program";
    public static final String VERSION = "2";
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private final Map<String, List<String>> libraries;
    private final Function<PageKey, Optional<PageSnapshot>> lookup;

    public CobolProgramEnrichment(Set<String> sourceIds) {
        this(sourceIds.stream().collect(java.util.stream.Collectors.toMap(id -> id, id -> List.of())),
                key -> Optional.empty());
    }

    public CobolProgramEnrichment(Map<String, List<String>> libraries,
                                  Function<PageKey, Optional<PageSnapshot>> lookup) {
        this.libraries = libraries.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        this.lookup = Objects.requireNonNull(lookup);
    }

    @Override public String id() { return ID; }
    @Override public String version() { return VERSION; }
    @Override public boolean appliesTo(PageKey key) { return libraries.containsKey(key.sourceId()); }

    @Override
    public EnrichmentOutput process(PageText text) {
        throw new ProcessingNotAllowedException("A verified program snapshot is required");
    }

    @Override
    public EnrichmentOutput process(PageSnapshot snapshot, PageText text) {
        if (!appliesTo(snapshot.key())) throw new ProcessingNotAllowedException("Not an FTP source");
        var source = StoredCobolSource.read(snapshot)
                .orElseThrow(() -> new ProcessingNotAllowedException("Not a verified fixed-format COBOL program"));
        List<String> syslib = libraries.get(snapshot.key().sourceId());
        var resolver = new StoredCopybooks(snapshot, syslib, lookup);
        CobolAnalysis analysis = CobolAnalyzer.proLeap().analyze(source, resolver);
        if (!resolver.warnings().isEmpty()) {
            var diagnostics = new ArrayList<>(analysis.diagnostics());
            resolver.warnings().forEach(warning -> diagnostics.add(new CobolAnalysis.Diagnostic(
                    warning, CobolAnalysis.Position.UNKNOWN, CobolAnalysis.DiagnosticKind.COPYBOOK)));
            analysis = new CobolAnalysis(analysis.elements(), analysis.calls(), analysis.references(), diagnostics, false);
        }
        Artifact artifact = new Artifact(source.name(), snapshot.payload().rawSha256(), analysis, syslib, resolver.probes());
        return new EnrichmentOutput(JSON.writeValueAsString(artifact), analysis.isPartial()
                ? List.of("Teilanalyse; Diagnosen und Copybook-Auflösung beachten.") : List.of());
    }

    @Override
    public boolean outdated(Enrichment stored) {
        if (stored.status() != Enrichment.Status.OK) return false;
        try {
            Artifact artifact = read(stored.payloadJson());
            if (!Objects.equals(libraries.get(stored.key().sourceId()), artifact.syslib())) return true;
            for (StoredCopybooks.Probe probe : artifact.probes()) {
                String current = lookup.apply(new PageKey(stored.key().sourceId(), probe.pageId()))
                        .map(page -> page.payload().rawSha256()).orElse(null);
                if (!Objects.equals(current, probe.rawSha256())) return true;
            }
            return false;
        } catch (RuntimeException invalid) {
            return true;
        }
    }

    public static Artifact read(String json) {
        return JSON.readValue(json, Artifact.class);
    }

    public record Artifact(String source, String rawSha256, CobolAnalysis analysis,
                           List<String> syslib, List<StoredCopybooks.Probe> probes) {
        public Artifact {
            syslib = syslib == null ? List.of() : List.copyOf(syslib);
            probes = probes == null ? List.of() : List.copyOf(probes);
        }
    }
}
