package com.aresstack.spaceharvester.application.enrichment;

import com.aresstack.spaceharvester.application.assets.DocumentKey;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingIndex;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingPayload;
import com.aresstack.spaceharvester.application.facts.EnrichmentFacts;
import com.aresstack.spaceharvester.application.facts.FactStore;
import com.aresstack.spaceharvester.application.facts.MetadataFact;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.terms.TermIndex;
import com.aresstack.spaceharvester.application.terms.TermPayload;
import com.aresstack.spaceharvester.application.terms.TermPosting;
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
 * Lucene-Ablage beim Speichern abgeleitet; der Begriffsindex liest die Ergebnisse von {@code terms} bei jeder Abfrage.
 */
public final class InMemoryEnrichmentStore implements EnrichmentStore, EmbeddingIndex, TermIndex, FactStore {

    private static final String EMBEDDING = "embedding";

    private final Map<PageKey, TreeMap<String, Enrichment>> results = new ConcurrentHashMap<>();
    /**
     * Ergebnisse von Anhängen und enthaltenen Dateien (ADR 0026), getrennt von denen der Seiten: Begriffe, Fakten und
     * Vektorsuche lesen sie nicht. Schlüssel {@link DocumentKey#value()}, darin Abschnitt und Prozessor.
     */
    private final Map<DocumentKey, TreeMap<String, Enrichment>> documents = new ConcurrentHashMap<>();

    @Override
    public synchronized void save(Enrichment enrichment) {
        if (enrichment.ofDocument()) {
            documents.computeIfAbsent(enrichment.document(), key -> new TreeMap<>())
                    .put(documentEntry(enrichment.section(), enrichment.processorId()), enrichment);
            return;
        }
        results.computeIfAbsent(enrichment.key(), key -> new TreeMap<>()).put(enrichment.processorId(), enrichment);
    }

    @Override
    public synchronized List<Enrichment> find(DocumentKey document) {
        if (Objects.requireNonNull(document, "document").isPage()) {
            return find(document.page());
        }
        TreeMap<String, Enrichment> found = documents.get(document);
        return found == null ? List.of() : List.copyOf(new ArrayList<>(found.values()));
    }

    @Override
    public synchronized List<Enrichment> findDocuments(PageKey key) {
        Objects.requireNonNull(key, "key");
        List<Enrichment> found = new ArrayList<>();
        documents.forEach((document, entries) -> {
            if (document.page().equals(key)) {
                found.addAll(entries.values());
            }
        });
        return List.copyOf(found);
    }

    @Override
    public synchronized void removeSection(DocumentKey document, int section) {
        if (Objects.requireNonNull(document, "document").isPage()) {
            throw new IllegalArgumentException("Seiten haben keine Abschnitte");
        }
        TreeMap<String, Enrichment> entries = documents.get(document);
        if (entries != null) {
            entries.values().removeIf(result -> result.section() == section);
            if (entries.isEmpty()) {
                documents.remove(document);
            }
        }
    }

    @Override
    public synchronized void removeDocument(DocumentKey document) {
        if (Objects.requireNonNull(document, "document").isPage()) {
            results.remove(document.page());
            return;
        }
        documents.keySet().removeIf(key -> key.equals(document) || !document.isEmbedded()
                && key.page().equals(document.page()) && key.attachmentId().equals(document.attachmentId()));
    }

    /** Abschnitt (sortierbar) und Prozessor: Ergebnisse aufsteigend nach Abschnitt, darin nach Prozessor. */
    private static String documentEntry(int section, String processorId) {
        return String.format("%010d", section) + '\u0000' + processorId;
    }

    @Override
    public synchronized List<Enrichment> find(PageKey key) {
        TreeMap<String, Enrichment> found = results.get(key);
        return found == null ? List.of() : List.copyOf(new ArrayList<>(found.values()));
    }

    @Override
    public synchronized void removeAll(PageKey key) {
        results.remove(key);
        documents.keySet().removeIf(document -> document.page().equals(key));
    }

    @Override
    public synchronized void remove(PageKey key, String processorId) {
        TreeMap<String, Enrichment> found = results.get(Objects.requireNonNull(key, "key"));
        if (found != null) {
            found.remove(processorId);
        }
        // Bearbeitet der Prozessor die Seite nicht mehr, gilt das auch für ihre Dokumente.
        documents.forEach((document, entries) -> {
            if (document.page().equals(key)) {
                entries.values().removeIf(result -> result.processorId().equals(processorId));
            }
        });
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
            collect(hits, page.getValue().get(EMBEDDING), sourceIds, processorVersion, normalized);
        }
        return top(hits, k);
    }

    @Override
    public synchronized List<ChunkHit> nearestDocuments(Set<String> sourceIds, String processorVersion,
                                                        float[] query, int k) {
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
        for (TreeMap<String, Enrichment> entries : documents.values()) {
            for (Enrichment result : entries.values()) {
                if (EMBEDDING.equals(result.processorId())) {
                    collect(hits, result, sourceIds, processorVersion, normalized);
                }
            }
        }
        return top(hits, k);
    }

    private static void collect(List<ChunkHit> hits, Enrichment result, Set<String> sourceIds,
                                String processorVersion, float[] normalized) {
        if (result == null || !sourceIds.contains(result.key().sourceId())
                || result.status() != Enrichment.Status.OK
                || !result.processorVersion().equals(processorVersion)) {
            return;
        }
        EmbeddingPayload payload;
        try {
            payload = EmbeddingPayload.parse(result.payloadJson());
        } catch (IllegalArgumentException e) {
            return;
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
            hits.add(new ChunkHit(result.key(), i, chunk.charStart(), chunk.charEnd(), result.inputSha256(),
                    Math.max(-1, Math.min(1, dot))));
        }
    }

    private static List<ChunkHit> top(List<ChunkHit> hits, int k) {
        hits.sort(Comparator.comparingDouble(ChunkHit::similarity).reversed()
                .thenComparing(hit -> hit.page().pageId()).thenComparing(hit -> hit.page().sourceId())
                .thenComparingInt(ChunkHit::chunk));
        return List.copyOf(hits.subList(0, Math.min(k, hits.size())));
    }

    @Override
    public synchronized List<TermPosting> postings(Filter filter, String from, boolean inclusive, int max) {
        if (max < 1 || max > MAX_BATCH) {
            throw new IllegalArgumentException("max muss zwischen 1 und " + MAX_BATCH + " liegen");
        }
        List<TermPosting> found = new ArrayList<>();
        for (TreeMap<String, Enrichment> page : results.values()) {
            Enrichment result = page.get(TermPayload.PROCESSOR);
            if (result == null) {
                continue;
            }
            for (TermPosting posting : TermPayload.postings(result)) {
                if (filter.matches(posting) && (from == null || (inclusive ? ORDER.compare(posting.sortKey(), from) >= 0
                        : ORDER.compare(posting.sortKey(), from) > 0))) {
                    found.add(posting);
                }
            }
        }
        found.sort(Comparator.comparing(TermPosting::sortKey, ORDER));
        return List.copyOf(found.subList(0, Math.min(max, found.size())));
    }
}
