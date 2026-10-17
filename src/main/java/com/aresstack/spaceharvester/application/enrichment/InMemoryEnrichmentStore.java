package com.aresstack.spaceharvester.application.enrichment;

import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingIndex;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingPayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Flüchtige Ablage, solange kein Cache-Verzeichnis eingerichtet ist; auch Test-Fake. Die Vektorsuche vergleicht jedes
 * gespeicherte Stück einzeln, also exakt statt angenähert wie HNSW.
 */
public final class InMemoryEnrichmentStore implements EnrichmentStore, EmbeddingIndex {

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
    public synchronized List<ChunkHit> nearest(String sourceId, String processorVersion, float[] query, int k) {
        Objects.requireNonNull(sourceId, "sourceId");
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
            if (!page.getKey().sourceId().equals(sourceId) || result == null
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
                .thenComparing(hit -> hit.page().pageId()).thenComparingInt(ChunkHit::chunk));
        return List.copyOf(hits.subList(0, Math.min(k, hits.size())));
    }
}
