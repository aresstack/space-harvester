package com.aresstack.spaceharvester.app.runtime.program;

import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentOutput;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentProcessor;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.ProcessingNotAllowedException;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import tools.jackson.databind.json.JsonMapper;

/** Register the manual AI result in the existing enrichment store without automatic AI execution. */
public final class CobolBusinessEnrichment implements EnrichmentProcessor {
    public static final String ID = "cobol-business";
    public static final String VERSION = "1;parser=" + CobolProgramEnrichment.VERSION;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final Map<String, List<String>> libraries;
    private final Function<PageKey, Optional<PageSnapshot>> snapshots;

    public CobolBusinessEnrichment(Map<String, List<String>> libraries,
                                   Function<PageKey, Optional<PageSnapshot>> snapshots) {
        this.libraries = Map.copyOf(libraries);
        this.snapshots = Objects.requireNonNull(snapshots);
    }

    @Override public String id() { return ID; }
    @Override public String version() { return VERSION; }
    @Override public boolean appliesTo(PageKey key) { return false; }
    @Override public EnrichmentOutput process(PageText text) {
        throw new ProcessingNotAllowedException("Fachliche KI-Analyse nur auf ausdrücklichen Abruf");
    }

    @Override public boolean outdated(Enrichment stored) {
        if (stored.status() != Enrichment.Status.OK) return true;
        try {
            var artifact = read(stored.payloadJson());
            var key = stored.key();
            if (!Objects.equals(libraries.get(key.sourceId()), artifact.syslib())) return true;
            if (!snapshots.apply(key).map(page -> page.payload().rawSha256())
                    .filter(artifact.rawSha256()::equals).isPresent()) return true;
            for (var probe : artifact.probes()) {
                String current = snapshots.apply(new PageKey(key.sourceId(), probe.pageId()))
                        .map(page -> page.payload().rawSha256()).orElse(null);
                if (!Objects.equals(current, probe.rawSha256())) return true;
            }
            return false;
        } catch (RuntimeException invalid) {
            return true;
        }
    }

    @Override
    public List<PageKey> evidence(Enrichment stored) {
        try {
            return StoredCopybooks.resolved(stored.key().sourceId(), read(stored.payloadJson()).probes());
        } catch (RuntimeException invalid) {
            return List.of();
        }
    }

    public static Artifact read(String json) {
        return JSON.readValue(json, Artifact.class);
    }

    public static String digest(String parserJson) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(parserJson.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static Enrichment stored(PageSnapshot page, Enrichment parser, CobolBusinessAnalysis.Result result,
                                    java.time.Instant now) {
        var facts = CobolProgramEnrichment.read(parser.payloadJson());
        var artifact = new Artifact(facts.rawSha256(), digest(parser.payloadJson()), facts.syslib(), facts.probes(), result);
        return new Enrichment(page.key(), ID, VERSION, parser.inputSha256(), page.revision(),
                Enrichment.Status.OK, JSON.writeValueAsString(artifact),
                List.of("KI-Hypothesen; Belegverweise formal geprüft, fachlich nicht verifiziert."),
                null, now);
    }

    public record Artifact(String rawSha256, String parserDigest, List<String> syslib,
                           List<StoredCopybooks.Probe> probes, CobolBusinessAnalysis.Result result) {
        public Artifact {
            syslib = List.copyOf(syslib);
            probes = List.copyOf(probes);
        }
    }
}
