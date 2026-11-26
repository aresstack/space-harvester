package com.aresstack.spaceharvester.app.runtime.program;

import com.aresstack.spaceharvester.application.assets.DocumentKey;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.evidence.SourceLocationEvidence;
import com.aresstack.spaceharvester.application.knowledge.StatementAnalysis;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalysis;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Builds the source-location evidence (ADR 0041, item 7; ADR 0032, update of 26.11.2026) for the AI hypotheses of the
 * COBOL business analysis, so they can enter the one Fachlogik store as program statements.
 *
 * <p>Revisions come from the stored analyses read through {@link ProgramAnalysisAccess} only: the program and copybook
 * content hashes of the parser result, every copybook probe (resolved with hash, or negative), and the identity of the
 * parser result as analysis version. A hypothesis whose cited location cannot be attributed to the program or one of
 * its resolved copybooks is left out and counted, never guessed.
 */
public final class ProgramHypothesisEvidence {

    private ProgramHypothesisEvidence() {
    }

    /**
     * @param hypotheses hypotheses with complete evidence
     * @param unmapped   hypotheses left out because a cited location could not be attributed
     */
    public record Mapped(List<StatementAnalysis.ProgramHypothesis> hypotheses, int unmapped) {
        public Mapped {
            hypotheses = List.copyOf(hypotheses);
        }
    }

    /**
     * @param program  the program page
     * @param parser   its current, visible COBOL parser result ({@link CobolProgramEnrichment})
     * @param business its current, visible business hypotheses ({@link CobolBusinessEnrichment})
     * @param lookup   authorized reads of stored pages (to attribute copybook locations)
     */
    public static Mapped of(PageSnapshot program, Enrichment parser, Enrichment business,
                            Function<PageKey, Optional<PageSnapshot>> lookup) {
        Objects.requireNonNull(lookup, "lookup");
        var facts = CobolProgramEnrichment.read(parser.payloadJson());
        var result = CobolBusinessEnrichment.read(business.payloadJson()).result();
        PageKey key = program.key();
        DocumentKey document = DocumentKey.page(key);
        Map<String, Site> sites = new HashMap<>();
        sites.put(facts.source(), new Site(document, facts.rawSha256()));
        List<SourceLocationEvidence.Dependency> dependencies = new ArrayList<>();
        for (var probe : facts.probes()) {
            if (probe.rawSha256() == null) {
                dependencies.add(SourceLocationEvidence.Dependency.missing(probe.pageId()));
                continue;
            }
            PageKey copybook = new PageKey(key.sourceId(), probe.pageId());
            dependencies.add(SourceLocationEvidence.Dependency.resolved(probe.pageId(), DocumentKey.page(copybook),
                    probe.rawSha256()));
            lookup.apply(copybook).filter(page -> probe.rawSha256().equals(page.payload().rawSha256()))
                    .flatMap(StoredFtpText::read)
                    .ifPresent(text -> sites.putIfAbsent(text.path(),
                            new Site(DocumentKey.page(copybook), probe.rawSha256())));
        }
        String version = ProgramAnalysisAccess.identity(parser);
        List<StatementAnalysis.ProgramHypothesis> mapped = new ArrayList<>();
        int unmapped = 0;
        for (var hypothesis : result.hypotheses()) {
            var evidence = evidence(document, facts.rawSha256(), hypothesis, sites, dependencies, version);
            if (evidence.isEmpty() || hypothesis.statement() == null || hypothesis.statement().isBlank()) {
                unmapped++;
                continue;
            }
            mapped.add(new StatementAnalysis.ProgramHypothesis(hypothesis.statement(), evidence.get()));
        }
        return new Mapped(mapped, unmapped);
    }

    private static Optional<SourceLocationEvidence> evidence(DocumentKey program, String revision,
                                                             CobolBusinessAnalysis.Hypothesis hypothesis,
                                                             Map<String, Site> sites,
                                                             List<SourceLocationEvidence.Dependency> dependencies,
                                                             String version) {
        if (hypothesis.evidence().isEmpty()) return Optional.empty();
        List<SourceLocationEvidence.CodeSite> codeSites = new ArrayList<>();
        List<SourceLocationEvidence.ParserFact> parserFacts = new ArrayList<>();
        for (var fact : hypothesis.evidence()) {
            CobolAnalysis.Span span = fact.location();
            Site site = span == null ? null : sites.get(span.start().source());
            if (site == null || span.start().line() < 1) return Optional.empty();
            int first = span.start().line();
            int last = Math.max(first, span.end() == null ? first : span.end().line());
            codeSites.add(new SourceLocationEvidence.CodeSite(site.document(), site.revision(), first, last));
            parserFacts.add(new SourceLocationEvidence.ParserFact(fact.kind(), fact.fact(), codeSites.size() - 1,
                    first, last));
        }
        return Optional.of(new SourceLocationEvidence(program, revision, codeSites, parserFacts, dependencies,
                version));
    }

    private record Site(DocumentKey document, String revision) { }
}
