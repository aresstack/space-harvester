package com.aresstack.spaceharvester.app.runtime.program;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp.FtpProgramClassifier;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentOutput;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentProcessor;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.ProcessingNotAllowedException;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.mainframe.parser.JclAnalysis;
import com.aresstack.spaceharvester.mainframe.parser.JclAnalyzer;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/** Store JCL declaration facts in the same enrichment store as other program analyses. */
public final class JclProgramEnrichment implements EnrichmentProcessor {
    public static final String ID = "jcl-program";
    public static final String VERSION = "1";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final Set<String> sourceIds;

    public JclProgramEnrichment(Set<String> sourceIds) {
        this.sourceIds = Set.copyOf(sourceIds);
    }

    public static Optional<StoredFtpText> source(PageSnapshot snapshot) {
        return StoredFtpText.read(snapshot).filter(text ->
                FtpProgramClassifier.classify(text.content()) == FtpProgramClassifier.Kind.JCL);
    }

    @Override public String id() { return ID; }
    @Override public String version() { return VERSION; }
    @Override public boolean appliesTo(PageKey key) { return sourceIds.contains(key.sourceId()); }
    @Override public EnrichmentOutput process(PageText text) {
        throw new ProcessingNotAllowedException("A verified JCL snapshot is required");
    }
    @Override public EnrichmentOutput process(PageSnapshot snapshot, PageText text) {
        if (!appliesTo(snapshot.key())) throw new ProcessingNotAllowedException("Not an FTP source");
        var code = source(snapshot).orElseThrow(() -> new ProcessingNotAllowedException("Not verified JCL"));
        var analysis = new JclAnalyzer().analyze(code.path(), code.content());
        return new EnrichmentOutput(JSON.writeValueAsString(new Artifact(snapshot.payload().rawSha256(), analysis)),
                analysis.partial() ? List.of("Statische Teilanalyse; Hinweise beachten.") : List.of());
    }

    public static Artifact read(String json) { return JSON.readValue(json, Artifact.class); }

    public record Artifact(String rawSha256, JclAnalysis analysis) { }
}
