package com.aresstack.spaceharvester.app.runtime.program;

import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.query.DeliveredPage;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.mainframe.cobol.CobolSearch;
import com.aresstack.spaceharvester.mainframe.cobol.CobolSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Assemble a transient search view from already stored, readable parser artifacts. */
public final class StoredCobolSearch {
    private static final Pattern MEMBER = Pattern.compile("(.+)\\(([A-Z@#$][A-Z0-9@#$-]{0,7})\\)");
    private final Function<PageKey, Optional<DeliveredPage>> pages;
    private final Function<DeliveredPage, List<Enrichments.View>> analyses;

    public StoredCobolSearch(Function<PageKey, Optional<DeliveredPage>> pages,
                             Function<DeliveredPage, List<Enrichments.View>> analyses) {
        this.pages = pages;
        this.analyses = analyses;
    }

    public Snapshot build(String sourceId, List<String> syslib,
                          Consumer<Consumer<PageHeader>> visitHeaders, BooleanSupplier cancelled) {
        Collector collector = new Collector(sourceId, List.copyOf(syslib), cancelled);
        visitHeaders.accept(collector::accept);
        collector.checkCancelled();
        return collector.result();
    }

    /**
     * A document a search result rests on.
     *
     * @param analysis {@link ProgramAnalysisAccess#identity} of the stored analysis the facts came from, or
     *                 {@code null} when only the document content itself is evidence (for example a copybook)
     */
    public record Origin(PageKey key, String rawSha256, String analysis) {
        public Origin(PageKey key, String rawSha256) {
            this(key, rawSha256, null);
        }
    }

    public record Snapshot(CobolSearch search, String sourceId, int programs, int partial,
                           int missingAnalysis, int missingContent, int candidates,
                           Map<String, List<Origin>> evidence) {
        public Snapshot {
            evidence = Map.copyOf(evidence);
        }

        /** Include the program, its COPY dependencies and every displayed hierarchy candidate. */
        public List<Origin> evidenceFor(CobolSearch.Fact fact) {
            var origins = new java.util.LinkedHashSet<Origin>();
            origins.addAll(evidence.getOrDefault(fact.member(), List.of()));
            origins.addAll(evidence.getOrDefault(fact.source(), List.of()));
            for (String field : List.of("ROOT_SELECTOR", "HIERARCHY_FROM", "CALLER_CANDIDATES", "TARGET_CANDIDATES")) {
                String selectors = fact.attributes().getOrDefault(field, "");
                for (String selector : selectors.split(", ")) {
                    origins.addAll(evidence.getOrDefault(selector, List.of()));
                }
            }
            return List.copyOf(origins);
        }

        public String coverage() {
            return programs + " Programme mit aktueller Analyse · " + partial + " Teilanalysen · "
                    + missingAnalysis + " Programme ohne aktuelle lesbare Analyse · "
                    + missingContent + " Member ohne verifizierten Quelltext · "
                    + candidates + " gespeicherte SYSLIB-Kandidaten · nur lokaler Bestand";
        }
    }

    private final class Collector {
        private final String sourceId;
        private final List<String> syslib;
        private final BooleanSupplier cancelled;
        private final List<CobolSearch.Fact> facts = new ArrayList<>();
        private final Map<String, List<Origin>> evidence = new LinkedHashMap<>();
        private int programs, partial, missingAnalysis, missingContent, candidates;

        Collector(String sourceId, List<String> syslib, BooleanSupplier cancelled) {
            this.sourceId = sourceId;
            this.syslib = syslib;
            this.cancelled = cancelled;
        }

        void accept(PageHeader header) {
            checkCancelled();
            if (!sourceId.equals(header.key().sourceId())) return;
            String path = header.metadata().attributes().getOrDefault("ftp.path", "");
            var member = MEMBER.matcher(path);
            if (!member.matches()) return;
            var stored = pages.apply(header.key());
            if (stored.isEmpty()) {
                missingContent++;
                return;
            }
            var page = stored.get();
            if (!header.key().equals(page.header().key()) || !header.key().equals(page.snapshot().key())) return;
            var verified = StoredCobolSource.verified(page.snapshot());
            if (verified.isEmpty() || !path.equals(verified.get().name())) {
                missingContent++;
                return;
            }
            if (StoredCobolSource.isProgram(verified.get())) {
                addProgram(page, verified.get());
            } else if (syslib.contains(member.group(1))) {
                facts.add(copybook(member.group(2), page, CobolSearch.Kind.COPYBOOK_CANDIDATE));
                remember(path, List.of(origin(page)));
                candidates++;
            }
        }

        private void addProgram(DeliveredPage page, CobolSource source) {
            var stored = current(page, source);
            if (stored.isEmpty()) {
                missingAnalysis++;
                return;
            }
            var value = stored.get().artifact();
            var programOrigin = new Origin(page.header().key(), page.snapshot().payload().rawSha256(),
                    stored.get().identity());
            var dependencies = readableDependencies(value);
            if (dependencies.isEmpty()) {
                missingAnalysis++;
                return;
            }
            var origins = dependencies.get();
            origins.put(source.name(), page);
            var programFacts = CobolSearch.programFacts(source.name(), page.snapshot().revision(), value.analysis());
            if (programFacts.isEmpty()) {
                missingAnalysis++;
                return;
            }
            List<Origin> programEvidence = java.util.stream.Stream.concat(java.util.stream.Stream.of(programOrigin),
                    origins.values().stream().filter(document -> document != page).map(StoredCobolSearch::origin))
                    .distinct().toList();
            remember(source.name(), programEvidence);
            origins.forEach((path, document) -> remember(path,
                    List.of(document == page ? programOrigin : origin(document))));
            for (var fact : programFacts) {
                if (fact.kind() == CobolSearch.Kind.PROGRAM) {
                    remember(fact.member() + "#" + fact.name() + "@" + fact.line(), programEvidence);
                }
                facts.add(withOrigin(fact, origins, page));
            }
            // Preserve real identities for resolved copybooks instead of creating synthetic search hits.
            for (var fact : programFacts) {
                if (fact.kind() != CobolSearch.Kind.COPY_USAGE) continue;
                var resolved = origins.get(fact.attributes().get("RESOLVED_SOURCE"));
                if (resolved != null && !resolved.header().key().equals(page.header().key())) {
                    facts.add(copybook(fact.name(), resolved, CobolSearch.Kind.COPYBOOK));
                }
            }
            programs++;
            if (value.analysis().isPartial()) partial++;
        }

        private record Current(CobolProgramEnrichment.Artifact artifact, String identity) { }

        private Optional<Current> current(DeliveredPage page, CobolSource source) {
            var view = analyses.apply(page).stream().filter(item ->
                    CobolProgramEnrichment.ID.equals(item.enrichment().processorId())).findFirst().orElse(null);
            if (view == null || !page.snapshot().key().equals(view.enrichment().key())
                    || view.freshness() != Enrichment.Freshness.CURRENT
                    || view.enrichment().status() != Enrichment.Status.OK
                    || !CobolProgramEnrichment.VERSION.equals(view.enrichment().processorVersion())) return Optional.empty();
            try {
                var artifact = CobolProgramEnrichment.read(view.enrichment().payloadJson());
                return page.snapshot().payload().rawSha256().equals(artifact.rawSha256())
                        && source.name().equals(artifact.source()) && syslib.equals(artifact.syslib())
                        ? Optional.of(new Current(artifact, ProgramAnalysisAccess.identity(view.enrichment())))
                        : Optional.empty();
            } catch (RuntimeException invalid) {
                return Optional.empty();
            }
        }

        private Optional<Map<String, DeliveredPage>> readableDependencies(CobolProgramEnrichment.Artifact artifact) {
            Map<String, DeliveredPage> origins = new LinkedHashMap<>();
            for (var probe : artifact.probes()) {
                checkCancelled();
                if (probe.rawSha256() == null) continue;
                var key = new PageKey(sourceId, probe.pageId());
                var dependency = pages.apply(key);
                if (dependency.isEmpty() || !key.equals(dependency.get().header().key())
                        || !probe.rawSha256().equals(dependency.get().snapshot().payload().rawSha256())) {
                    return Optional.empty();
                }
                var source = StoredCobolSource.verified(dependency.get().snapshot());
                if (source.isEmpty()) return Optional.empty();
                origins.put(source.get().name(), dependency.get());
            }
            return Optional.of(origins);
        }

        private void checkCancelled() {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
        }

        private void remember(String selector, List<Origin> origins) {
            evidence.merge(selector, List.copyOf(origins), (left, right) ->
                    java.util.stream.Stream.concat(left.stream(), right.stream()).distinct().toList());
        }

        private Snapshot result() {
            return new Snapshot(new CobolSearch(facts), sourceId, programs, partial, missingAnalysis, missingContent, candidates, evidence);
        }
    }

    private static CobolSearch.Fact withOrigin(CobolSearch.Fact fact, Map<String, DeliveredPage> origins,
                                               DeliveredPage program) {
        Map<String, String> attributes = new LinkedHashMap<>(fact.attributes());
        attributes.put("PROGRAM_PAGE_ID", program.header().key().pageId());
        attributes.put("PROGRAM_RAW_SHA256", program.snapshot().payload().rawSha256());
        var origin = origins.get(fact.source());
        if (origin != null) identify(attributes, origin);
        return new CobolSearch.Fact(fact.kind(), fact.name(), fact.program(), fact.member(), fact.source(),
                fact.line(), fact.revision(), attributes, fact.partial());
    }

    private static CobolSearch.Fact copybook(String name, DeliveredPage page, CobolSearch.Kind kind) {
        Map<String, String> attributes = new LinkedHashMap<>();
        identify(attributes, page);
        attributes.put("STATUS", kind == CobolSearch.Kind.COPYBOOK ? "COPY-Auflösung"
                : "Gespeichertes SYSLIB-Member; nicht einzeln klassifiziert");
        return new CobolSearch.Fact(kind, name, "", "", page.snapshot().metadata().attributes().get("ftp.path"),
                0, page.snapshot().revision(), attributes, false);
    }

    private static Origin origin(DeliveredPage page) {
        return new Origin(page.header().key(), page.snapshot().payload().rawSha256());
    }

    private static void identify(Map<String, String> attributes, DeliveredPage page) {
        attributes.put("SOURCE_ID", page.header().key().sourceId());
        attributes.put("PAGE_ID", page.header().key().pageId());
        attributes.put("SOURCE_RAW_SHA256", page.snapshot().payload().rawSha256());
    }
}
