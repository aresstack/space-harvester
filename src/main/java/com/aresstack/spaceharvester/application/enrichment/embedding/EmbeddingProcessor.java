package com.aresstack.spaceharvester.application.enrichment.embedding;

import com.aresstack.spaceharvester.application.enrichment.EnrichmentOutput;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentProcessor;
import com.aresstack.spaceharvester.application.enrichment.JsonText;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Embeddings als Anreicherung (ADR 0004): zerlegt die Textprojektion an Abschnittsgrenzen in Stücke von höchstens
 * {@link #maxChunkChars} Zeichen, bettet sie über den {@link EmbeddingProvider} ein und liefert
 * {@code {"model","dimensions","chunks":[{"charStart","charEnd","section","vector":[…]}]}}.
 *
 * <p>Die Version enthält Modell und Dimension: Ein Modellwechsel macht genau diese Ergebnisse veraltet, wie eine neue
 * Prozessorversion. Wie jeder Prozessor sieht er nur die sichere Projektion, nie Makroinhalte oder Anhänge.
 */
public class EmbeddingProcessor implements EnrichmentProcessor {

    /** Version der Zerlegung und des Ergebnisformats. */
    static final String FORMAT = "1";
    static final int MAX_CHUNKS = 200;

    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9._@/:-]{1,100}");

    private final EmbeddingProvider provider;
    private final int maxChunkChars;

    public EmbeddingProcessor(EmbeddingProvider provider, int maxChunkChars) {
        this.provider = Objects.requireNonNull(provider, "provider");
        if (!MODEL_ID.matcher(provider.modelId()).matches()) {
            throw new IllegalArgumentException("Ungültige Modellkennung");
        }
        if (provider.dimensions() < 1 || provider.dimensions() > 4096) {
            throw new IllegalArgumentException("Dimension muss zwischen 1 und 4096 liegen");
        }
        if (maxChunkChars < 100) {
            throw new IllegalArgumentException("maxChunkChars muss mindestens 100 sein");
        }
        this.maxChunkChars = maxChunkChars;
    }

    @Override
    public String id() {
        return "embedding";
    }

    @Override
    public String version() {
        return FORMAT + "+" + provider.modelId() + "+" + provider.dimensions() + "+" + maxChunkChars;
    }

    @Override
    public EnrichmentOutput process(PageText text) {
        List<Chunk> chunks = chunks(text);
        List<String> warnings = new ArrayList<>();
        if (chunks.size() > MAX_CHUNKS) {
            warnings.add("Nur die ersten " + MAX_CHUNKS + " von " + chunks.size() + " Abschnitten eingebettet");
            chunks = chunks.subList(0, MAX_CHUNKS);
        }
        List<float[]> vectors = chunks.isEmpty() ? List.of()
                : provider.embed(chunks.stream().map(chunk -> text.text().substring(chunk.start, chunk.end)).toList());
        if (vectors.size() != chunks.size()) {
            throw new IllegalStateException("Modell lieferte " + vectors.size() + " statt " + chunks.size()
                    + " Vektoren");
        }
        StringBuilder json = new StringBuilder("{\"model\":").append(JsonText.quote(provider.modelId()))
                .append(",\"dimensions\":").append(provider.dimensions()).append(",\"chunks\":[");
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            PageText.Section section = text.sectionAt(chunk.start);
            json.append(i == 0 ? "" : ",")
                    .append("{\"charStart\":").append(chunk.start)
                    .append(",\"charEnd\":").append(chunk.end)
                    .append(",\"section\":").append(section == null ? "null" : JsonText.quote(section.heading()))
                    .append(",\"vector\":");
            vector(json, vectors.get(i));
            json.append('}');
        }
        return new EnrichmentOutput(json.append("]}").toString(), warnings);
    }

    private void vector(StringBuilder json, float[] vector) {
        if (vector == null || vector.length != provider.dimensions()) {
            throw new IllegalStateException("Vektor hat nicht die Dimension " + provider.dimensions());
        }
        json.append('[');
        for (int k = 0; k < vector.length; k++) {
            if (!Float.isFinite(vector[k])) {
                throw new IllegalStateException("Vektor enthält keinen endlichen Wert");
            }
            json.append(k == 0 ? "" : ",").append(Float.toString(vector[k]));
        }
        json.append(']');
    }

    /** Stücke an Abschnittsgrenzen; ein zu langer Abschnitt wird an Zeilen-, sonst an Leerzeichengrenzen geteilt. */
    List<Chunk> chunks(PageText text) {
        List<int[]> spans = new ArrayList<>();
        int previous = 0;
        for (PageText.Section section : text.sections()) {
            if (section.start() > previous) {
                spans.add(new int[] {previous, section.start()});
            }
            spans.add(new int[] {section.start(), section.end()});
            previous = section.end();
        }
        if (previous < text.text().length()) {
            spans.add(new int[] {previous, text.text().length()});
        }
        List<Chunk> chunks = new ArrayList<>();
        for (int[] span : spans) {
            int start = span[0];
            while (start < span[1]) {
                int end = Math.min(span[1], start + maxChunkChars);
                if (end < span[1]) {
                    int cut = text.text().lastIndexOf('\n', end - 1);
                    if (cut <= start) {
                        cut = text.text().lastIndexOf(' ', end - 1);
                    }
                    if (cut > start) {
                        end = cut + 1;
                    }
                }
                if (!text.text().substring(start, end).isBlank()) {
                    chunks.add(new Chunk(start, end));
                }
                start = end;
            }
        }
        return chunks;
    }

    record Chunk(int start, int end) {
    }
}
