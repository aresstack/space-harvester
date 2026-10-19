package com.aresstack.spaceharvester.application.enrichment;

import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingIndex;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingPayload;
import com.aresstack.spaceharvester.application.facts.EnrichmentFacts;
import com.aresstack.spaceharvester.application.facts.FactStore;
import com.aresstack.spaceharvester.application.facts.MetadataFact;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Flüchtige Ablage, solange kein Cache-Verzeichnis eingerichtet ist; auch Test-Fake. Die Vektorsuche vergleicht jedes
 * gespeicherte Stück einzeln, also exakt statt angenähert wie HNSW. Fakten ({@link FactStore}, #182) werden wie in der
 * Lucene-Ablage beim Speichern abgeleitet.
 */
public final class InMemoryEnrichmentStore implements EnrichmentStore, EmbeddingIndex, FactStore {

    private static final String EMBEDDING = "embedding";

    private final Map<PageKey, TreeMap<String, Enrichment>> results = new ConcurrentHashMap<>();

    @Override
    public synchronized void save(Enrichment enrichment) {
        results.computeIfAbsent(enrichment.key(), key -> new TreeMap<>()).put(enrichment.processorId(), enrichment);
    }

    @Override
    public synchronized List<Enrichment> find(PageKey key) {
        TreeMap<String, Enrichment> found = results.get(key);
        return found == null ? List.of() : List.copyOf(new ArrayList<>(found.values()));
    }

    @Override
    public synchronized void removeAll(PageKey key) {
        results.remove(key);
    }

    @Override
    public synchronized List<MetadataFact> factsOf(PageKey key) {
        TreeMap<String, Enrichment> found = results.get(Objects.requireNonNull(key, "key"));
        if (found == null) {
            return List.of();
        }
        List<MetadataFact> facts = new ArrayList<>();
        found.values().forEach(result -> facts.addAll(EnrichmentFacts.of(result)));
        return List.copyOf(facts);
    }

    @Override
    public synchronized List<MetadataFact> facts(FactStore.Query query, String after, int limit) {
        Objects.requireNonNull(query, "query");
        if (limit < 1 || limit > FactStore.MAX_LIMIT) {
            throw new IllegalArgumentException("limit muss zwischen 1 und " + FactStore.MAX_LIMIT + " liegen");
        }
        TreeMap<String, MetadataFact> sorted = new TreeMap<>();
        for (Map.Entry<PageKey, TreeMap<String, Enrichment>> page : results.entrySet()) {
            if (!query.sourceIds().contains(page.getKey().sourceId())) {
                continue;
            }
            for (Enrichment result : page.getValue().values()) {
                for (MetadataFact fact : EnrichmentFacts.of(result)) {
                    if ((after == null || fact.id().compareTo(after) > 0) && query.matches(fact)) {
                        sorted.put(fact.id(), fact);
                    }
                }
            }
        }
        return sorted.values().stream().limit(limit).toList();
    }

    @Override
    public synchronized List<ChunkHit> nearest(Set<String> sourceIds, String processorVersion, float[] query,
                                               int k) {
        Objects.requireNonNull(sourceIds, "sourceIds");
        Objects.requireNonNull(processorVersion, "processorVersion");
        if (k < 1 || k > MAX_K) {
            throw new IllegalArgumentException("k muss zwischen 1 und " + MAX_K + " liegen");
        }
        float[] normalized = EmbeddingIndex.normalized(query);
        List<ChunkHit> hits = new ArrayList<>();
        boolean zero = true;
        for (float v : normalized) {
            zero &= v == 0;
        }
        if (zero) {
            return List.of();
        }
        for (Map.Entry<PageKey, TreeMap<String, Enrichment>> page : results.entrySet()) {
            Enrichment result = page.getValue().get(EMBEDDING);
            if (!sourceIds.contains(page.getKey().sourceId()) || result == null
                    || result.status() != Enrichment.Status.OK
                    || !result.processorVersion().equals(processorVersion)) {
                continue;
            }
            EmbeddingPayload payload;
            try {
                payload = EmbeddingPayload.parse(result.payloadJson());
            } catch (IllegalArgumentException e) {
                continue;
            }
            for (int i = 0; i < payload.chunks().size(); i++) {
                EmbeddingPayload.Chunk chunk = payload.chunks().get(i);
                float[] vector = EmbeddingIndex.normalized(chunk.vector());
                if (vector.length != normalized.length) {
                    continue;
                }
                double dot = 0;
                double norm = 0;
                for (int d = 0; d < vector.length; d++) {
                    dot += (double) vector[d] * normalized[d];
                    norm += (double) vector[d] * vector[d];
                }
                if (norm == 0) {
                    continue;
                }
                hits.add(new ChunkHit(page.getKey(), i, chunk.charStart(), chunk.charEnd(), result.inputSha256(),
                        Math.max(-1, Math.min(1, dot))));
            }
        }
        hits.sort(Comparator.comparingDouble(ChunkHit::similarity).reversed()
                .thenComparing(hit -> hit.page().pageId()).thenComparing(hit -> hit.page().sourceId())
                .thenComparingInt(ChunkHit::chunk));
        return List.copyOf(hits.subList(0, Math.min(k, hits.size())));
    }
}
