package com.aresstack.spaceharvester.app.runtime.program;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.query.DeliveredPage;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Explicitly request an analysis of one stored program; authorize and persist its exact evidence basis. */
public final class CobolBusinessUseCase {
    private final Enrichments enrichments;
    private final Function<PageKey, Optional<DeliveredPage>> pages;
    private final Predicate<String> allowed;
    private final Consumer<DeliveredPage> writable;
    private final BiConsumer<String, PageKey> release;
    private final Clock clock;

    public CobolBusinessUseCase(Enrichments enrichments, Function<PageKey, Optional<DeliveredPage>> pages,
                                Predicate<String> allowed, Consumer<DeliveredPage> writable,
                                BiConsumer<String, PageKey> release, Clock clock) {
        this.enrichments = Objects.requireNonNull(enrichments);
        this.pages = Objects.requireNonNull(pages);
        this.allowed = Objects.requireNonNull(allowed);
        this.writable = Objects.requireNonNull(writable);
        this.release = Objects.requireNonNull(release);
        this.clock = Objects.requireNonNull(clock);
    }

    public Enrichments.Run run(PageKey key, String requestedHash, Supplier<AiChat> chat, String model,
                                com.aresstack.spaceharvester.app.runtime.ModelBudget budget, JobContext context) {
        requireAllowed(key);
        var page = readable(key, requestedHash);
        writable.accept(page);
        var parser = currentParser(page);
        var facts = CobolProgramEnrichment.read(parser.payloadJson());
        var origins = origins(key, requestedHash, facts.probes());
        var modelClient = chat.get();
        if (modelClient == null || model == null || model.isBlank()) throw new IllegalStateException("Kein Chat-Modell eingestellt.");
        Runnable authorize = () -> {
            context.checkpoint();
            requireAllowed(key);
        };
        Runnable verifyInput = () -> {
            verifyOrigins(origins);
            verifyBasis(page, parser);
            writable.accept(readable(key, requestedHash));
        };
        authorize.run();
        verifyInput.run();
        for (var origin : origins.entrySet()) {
            authorize.run();
            var evidence = readable(origin.getKey(), origin.getValue());
            release.accept(evidence.header().metadata().partitionKey(), origin.getKey());
        }
        var result = new CobolBusinessAnalysis().analyze(facts.analysis(), modelClient, model, budget,
                authorize, verifyInput, context);
        authorize.run();
        verifyInput.run();
        var stored = CobolBusinessEnrichment.stored(page.snapshot(), parser, result, clock.instant());
        context.checkpoint();
        enrichments.store().save(stored);
        enrichments.flush();
        return new Enrichments.Run(true, 1, 0, 0);
    }

    /** Never display hypotheses for a different parser result or unreadable supporting documents. */
    public boolean visible(DeliveredPage page, Enrichment stored, List<Enrichments.View> views) {
        try {
            var artifact = CobolBusinessEnrichment.read(stored.payloadJson());
            if (!page.snapshot().payload().rawSha256().equals(artifact.rawSha256())) return false;
            var parser = views.stream().filter(view -> CobolProgramEnrichment.ID.equals(view.enrichment().processorId())
                            && view.freshness() == Enrichment.Freshness.CURRENT
                            && view.enrichment().status() == Enrichment.Status.OK)
                    .map(Enrichments.View::enrichment).findFirst().orElse(null);
            if (parser == null || !artifact.parserDigest().equals(CobolBusinessEnrichment.digest(parser.payloadJson()))) {
                return false;
            }
            verifyOrigins(origins(page.header().key(), artifact.rawSha256(), artifact.probes()));
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private Enrichment currentParser(DeliveredPage page) {
        var parser = enrichments.view(page.snapshot()).stream()
                .filter(view -> CobolProgramEnrichment.ID.equals(view.enrichment().processorId())
                        && view.freshness() == Enrichment.Freshness.CURRENT
                        && view.enrichment().status() == Enrichment.Status.OK
                        && CobolProgramEnrichment.VERSION.equals(view.enrichment().processorVersion()))
                .map(Enrichments.View::enrichment).findFirst()
                .orElseThrow(() -> new IllegalStateException("Zuerst die aktuelle Programmanalyse berechnen."));
        if (!page.snapshot().payload().rawSha256().equals(CobolProgramEnrichment.read(parser.payloadJson()).rawSha256())) {
            throw new IllegalStateException("Programmanalyse nicht mehr aktuell.");
        }
        return parser;
    }

    private void verifyBasis(DeliveredPage page, Enrichment parser) {
        var latest = readable(page.header().key(), page.snapshot().payload().rawSha256());
        if (!currentParser(latest).equals(parser)) {
            throw new IllegalStateException("Programmanalyse wurde während der KI-Analyse geändert.");
        }
    }

    private static Map<PageKey, String> origins(PageKey program, String hash, List<StoredCopybooks.Probe> probes) {
        var result = new LinkedHashMap<PageKey, String>();
        result.put(program, hash);
        for (var probe : probes) {
            if (probe.rawSha256() != null) result.put(new PageKey(program.sourceId(), probe.pageId()), probe.rawSha256());
        }
        return result;
    }

    private void verifyOrigins(Map<PageKey, String> origins) {
        origins.forEach(this::readable);
    }

    private void requireAllowed(PageKey key) {
        if (!allowed.test(key.sourceId())) throw new IllegalStateException("FTP-Wissensquelle nicht für KI freigegeben.");
    }

    private DeliveredPage readable(PageKey key, String hash) {
        var page = pages.apply(key).orElseThrow(() -> new IllegalStateException("Beleg nicht verfügbar."));
        if (!page.header().key().equals(key) || !hash.equals(page.snapshot().payload().rawSha256())
                || StoredFtpText.read(page.snapshot()).isEmpty()) {
            throw new IllegalStateException("Beleg nicht mehr aktuell oder nicht verifizierbar.");
        }
        return page;
    }
}
