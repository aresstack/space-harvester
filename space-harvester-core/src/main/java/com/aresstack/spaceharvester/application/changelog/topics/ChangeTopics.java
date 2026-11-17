package com.aresstack.spaceharvester.application.changelog.topics;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.changelog.ChangeReport;
import com.aresstack.spaceharvester.application.changelog.ReportedPage;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProvider;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.passages.SentenceSegmenter;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * {@link ChangeTopicsQuery} über dem Änderungsprotokoll. Holt den Bericht mit Unterschieden für den Aufrufer, beschränkt
 * ihn auf die Ebene, bildet Änderungseinheiten, bettet freigegebene Einheiten ein (wenn ein Dienst da ist) und schlägt
 * Themen vor. Ohne Embedding-Dienst gruppiert sie nur nach Struktur und Begriffen; {@link TopicSet#missingSignals()}
 * sagt das.
 *
 * <p>Die Ablage der Ergebnisse ist nach dem Hash des gefilterten Berichts samt Freigaben geschlüsselt; sie gibt also nie
 * mehr zurück, als der Bericht des Aufrufers zulässt. Sie liegt nur im Speicher und ist klein.
 */
public final class ChangeTopics implements ChangeTopicsQuery {

    /** Neue Vektoren je Lauf höchstens. */
    public static final int DEFAULT_BUDGET = 2_000;
    /** Abgelegte Ergebnisse höchstens. */
    static final int CACHED = 16;

    /** Liefert den Bericht eines Updates für den Aufrufer, etwa {@code ChangeLog::report}. */
    @FunctionalInterface
    public interface Reports {

        ChangeReport report(AccessContext caller, String sourceId, String update, boolean withDiffs);
    }

    private final Reports reports;
    private final Predicate<PageKey> released;
    private final ChangeUnits units;
    private final Supplier<EmbeddingProvider> provider;
    private final ChangeUnitVectors.Cache vectors;
    private final TopicProposer proposer;
    private final int budget;
    private final Map<String, TopicSet> cache = new LinkedHashMap<>(CACHED, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, TopicSet> eldest) {
            return size() > CACHED;
        }
    };

    /**
     * @param reports   Berichte mit Rechteprüfung
     * @param released  KI-Freigabe je Seite, etwa {@code AiRelease::mayProcess}
     * @param sentences Satzzerlegung
     * @param provider  liefert den aktuellen Embedding-Dienst oder {@code null} (Rückfall: nur Struktur und Begriffe);
     *                  je Lauf gefragt, damit ein Wechsel der Einstellungen greift
     * @param vectors   Ablage der Vektoren
     */
    public ChangeTopics(Reports reports, Predicate<PageKey> released, SentenceSegmenter sentences,
                        Supplier<EmbeddingProvider> provider, ChangeUnitVectors.Cache vectors, TopicWeights weights,
                        int budget) {
        this.reports = Objects.requireNonNull(reports, "reports");
        this.released = Objects.requireNonNull(released, "released");
        this.units = new ChangeUnits(sentences);
        this.provider = Objects.requireNonNull(provider, "provider");
        this.vectors = Objects.requireNonNull(vectors, "vectors");
        this.proposer = new TopicProposer(weights);
        this.budget = budget;
    }

    /** Rückfall ohne Embedding-Dienst: nur Struktur und Begriffe. */
    public static ChangeTopics structureOnly(Reports reports, Predicate<PageKey> released,
                                             SentenceSegmenter sentences) {
        return new ChangeTopics(reports, released, sentences, () -> null, ChangeUnitVectors.Cache.inMemory(),
                TopicWeights.DEFAULT, 0);
    }

    @Override
    public TopicSet topics(AccessContext caller, String sourceId, String updateId, Optional<String> spaceKey,
                           JobContext job) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(job, "job");
        Optional<String> level = spaceKey == null ? Optional.empty() : spaceKey;
        ChangeReport report = level(reports.report(caller, sourceId, updateId, true), level);
        job.checkpoint();
        ChangeUnits.Result built = units.build(report, released);
        EmbeddingProvider service = provider.get();
        String key = key(report, built, service == null ? null : service.modelId());
        synchronized (cache) {
            TopicSet known = cache.get(key);
            if (known != null) {
                return known;
            }
        }
        ChangeUnitVectors.Result embedded = new ChangeUnitVectors(service, vectors).embed(built.units(), budget, job);
        job.checkpoint();
        TopicSet set = proposer.propose(sourceId, report.update().id(), level, built,
                new TopicProposer.Inputs(embedded.modelId(), embedded.vectors(), null, null, List.of()));
        // Unvollständige Vektoren (Obergrenze, Dienst nicht erreichbar) nicht ablegen; der nächste Lauf holt nach.
        if (service == null || embedded.skipped() == 0) {
            synchronized (cache) {
                cache.put(key, set);
            }
        }
        return set;
    }

    /** Nur die Seiten des Space; eine verschobene Seite zählt in beiden Spaces. */
    static ChangeReport level(ChangeReport report, Optional<String> spaceKey) {
        if (spaceKey.isEmpty()) {
            return report;
        }
        String space = spaceKey.get();
        List<ReportedPage> pages = report.pages().stream().filter(page -> space.equals(page.spaceKey())
                || space.equals(page.oldSpaceKey()) || space.equals(page.newSpaceKey())).toList();
        return new ChangeReport(report.update(), pages);
    }

    /** Hash über den gefilterten Bericht, die daraus gebildeten Einheiten samt Freigabe und das Modell. */
    private static String key(ChangeReport report, ChangeUnits.Result built, String modelId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(report.toString().getBytes(StandardCharsets.UTF_8));
            digest.update(built.toString().getBytes(StandardCharsets.UTF_8));
            digest.update(String.valueOf(modelId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
