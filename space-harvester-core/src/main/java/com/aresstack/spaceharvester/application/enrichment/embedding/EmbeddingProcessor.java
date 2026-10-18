package com.aresstack.spaceharvester.application.enrichment.embedding;

import com.aresstack.spaceharvester.application.enrichment.EnrichmentOutput;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentProcessor;
import com.aresstack.spaceharvester.application.enrichment.JsonText;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Embeddings als Anreicherung (ADR 0004): zerlegt die Textprojektion in Stücke, bettet sie über den
 * {@link EmbeddingProvider} ein und liefert
 * {@code {"model","dimensions","chunks":[{"charStart","charEnd","section","vector":[…]}]}}.
 *
 * <p>Zerlegung (#181, wie {@code KnowledgeChunker} im Enterprise AI Client): Abschnitte sind harte Grenzen. Innerhalb
 * eines Abschnitts werden Sätze bis zum Token-Budget der {@link TextSegmentation} gepackt (Standard 350 Tokens,
 * gezählt nach Wörtern und Symbolen); das nächste Stück
 * beginnt mit dem letzten Satz des vorigen. Ein Satz über dem Budget wird an Wortgrenzen geteilt. Zusätzlich hat kein
 * Stück mehr als {@link #maxChunkChars} Zeichen. Eingebettet wird jedes Stück mit der Überschriftenkette seines
 * Abschnitts davor, wenn es nicht selbst mit der Überschrift beginnt; {@code charStart}/{@code charEnd} bleiben
 * Stellen in der Textprojektion. Anders als dort entstehen die Stücke als Ausschnitte des Textes, damit Treffer auf
 * ihre Stelle zeigen.
 *
 * <p>Die Version enthält Modell und Dimension: Ein Modellwechsel macht genau diese Ergebnisse veraltet, wie eine neue
 * Prozessorversion. Wie jeder Prozessor sieht er nur die sichere Projektion, nie Makroinhalte oder Anhänge.
 */
public class EmbeddingProcessor implements EnrichmentProcessor {

    /** Version der Zerlegung und des Ergebnisformats; 2 seit der Zerlegung nach Sätzen und Token-Budget (#181). */
    static final String FORMAT = "2";
    static final int MAX_CHUNKS = 200;

    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9._@/:-]{1,100}");

    private final EmbeddingProvider provider;
    private final int maxChunkChars;
    private final TextSegmentation segmentation;
    private final Predicate<String> sources;
    private final boolean remote;

    /** Für alle Quellen, im aufrufenden Thread (Tests, eingebettete Modelle). */
    public EmbeddingProcessor(EmbeddingProvider provider, int maxChunkChars, TextSegmentation segmentation) {
        this(provider, maxChunkChars, segmentation, sourceId -> true, false);
    }

    /**
     * Für einen externen Dienst (ADR 0011): nur Seiten aus Quellen, deren Inhalte an ihn gehen dürfen, und im
     * Hintergrund von {@link com.aresstack.spaceharvester.application.enrichment.Enrichments}.
     *
     * @param mayProcessExternally ob Inhalte der Quelle an den Dienst gehen dürfen
     */
    public EmbeddingProcessor(EmbeddingProvider provider, int maxChunkChars, TextSegmentation segmentation,
                              Predicate<String> mayProcessExternally) {
        this(provider, maxChunkChars, segmentation, mayProcessExternally, true);
    }

    private EmbeddingProcessor(EmbeddingProvider provider, int maxChunkChars, TextSegmentation segmentation,
                               Predicate<String> sources, boolean remote) {
        this.segmentation = Objects.requireNonNull(segmentation, "segmentation");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.remote = remote;
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
    public boolean appliesTo(PageKey key) {
        return sources.test(key.sourceId());
    }

    /** Ob Inhalte dieser Quelle eingebettet werden. */
    public boolean covers(String sourceId) {
        return sources.test(sourceId);
    }

    @Override
    public boolean background() {
        return remote;
    }

    @Override
    public String version() {
        return FORMAT + "+" + provider.modelId() + "+" + provider.dimensions() + "+" + maxChunkChars + "+"
                + segmentation.id();
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
                : provider.embed(chunks.stream().map(chunk -> chunk.input(text.text())).toList());
        if (vectors.size() != chunks.size()) {
            throw new IllegalStateException("Modell lieferte " + vectors.size() + " statt " + chunks.size()
                    + " Vektoren");
        }
        StringBuilder json = new StringBuilder("{\"model\":").append(JsonText.quote(provider.modelId()))
                .append(",\"dimensions\":").append(provider.dimensions()).append(",\"chunks\":[");
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            json.append(i == 0 ? "" : ",")
                    .append("{\"charStart\":").append(chunk.start)
                    .append(",\"charEnd\":").append(chunk.end)
                    .append(",\"section\":").append(chunk.section == null ? "null" : JsonText.quote(chunk.section))
                    .append(",\"vector\":");
            vector(json, vectors.get(i));
            json.append('}');
        }
        return new EnrichmentOutput(json.append("]}").toString(), warnings);
    }

    /**
     * Bettet eine Suchanfrage mit demselben Modell ein wie die Seiten dieser {@link #version()}.
     *
     * @throws EmbeddingUnavailableException wenn das Modell nicht erreichbar ist oder einen unbrauchbaren Vektor liefert
     */
    public float[] embedQuery(String text) {
        Objects.requireNonNull(text, "text");
        List<float[]> vectors = provider.embedQueries(List.of(text));
        float[] vector = vectors == null || vectors.size() != 1 ? null : vectors.get(0);
        if (vector == null || vector.length != provider.dimensions()) {
            throw new EmbeddingUnavailableException("Modell lieferte keinen Vektor der Dimension "
                    + provider.dimensions());
        }
        for (float v : vector) {
            if (!Float.isFinite(v)) {
                throw new EmbeddingUnavailableException("Modell lieferte keinen endlichen Vektor");
            }
        }
        return vector.clone();
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

    /** Stücke je Abschnitt, Sätze bis zum Budget gepackt, mit einem Satz Überlappung. */
    List<Chunk> chunks(PageText text) {
        List<Span> spans = new ArrayList<>();
        List<PageText.Section> open = new ArrayList<>();
        int previous = 0;
        for (PageText.Section section : text.sections()) {
            if (section.start() > previous) {
                spans.add(new Span(previous, section.start(), null, null));
            }
            while (!open.isEmpty() && open.getLast().level() >= section.level()) {
                open.removeLast();
            }
            String ancestors = open.isEmpty() ? null
                    : String.join(" > ", open.stream().map(PageText.Section::heading).toList());
            open.add(section);
            spans.add(new Span(section.start(), section.end(),
                    String.join(" > ", open.stream().map(PageText.Section::heading).toList()), ancestors));
            previous = section.end();
        }
        if (previous < text.text().length()) {
            spans.add(new Span(previous, text.text().length(), null, null));
        }
        List<Chunk> chunks = new ArrayList<>();
        for (Span span : spans) {
            chunk(text.text(), span, chunks);
        }
        return chunks;
    }

    private void chunk(String text, Span span, List<Chunk> chunks) {
        String section = span.heading() == null || span.heading().isBlank() ? null : span.heading().strip();
        String heading = section;
        String prefix = prefix(section);
        // Beginnt das Stück mit der Überschrift selbst, stehen davor nur die übergeordneten Überschriften.
        String opening = prefix(span.ancestors() == null || span.ancestors().isBlank() ? null
                : span.ancestors().strip());
        List<int[]> units = new ArrayList<>();
        for (int[] sentence : sentences(text, span.start(), span.end())) {
            units.addAll(fit(text, sentence, prefix));
        }
        List<int[]> previous = List.of();
        int next = 0;
        while (next < units.size()) {
            int overlap = Math.min(segmentation.overlapSentences(), previous.size());
            List<int[]> current = new ArrayList<>(previous.subList(previous.size() - overlap, previous.size()));
            current.add(units.get(next++));
            while (current.size() > 1 && !fits(text, prefix, current.getFirst()[0], current.getLast()[1])) {
                // Die Überlappung weicht, der neue Satz bleibt.
                current.removeFirst();
            }
            while (next < units.size() && fits(text, prefix, current.getFirst()[0], units.get(next)[1])) {
                current.add(units.get(next++));
            }
            int start = current.getFirst()[0];
            int end = current.getLast()[1];
            boolean headed = heading != null && start > span.start();
            chunks.add(new Chunk(start, end, headed ? prefix : opening, section));
            previous = current;
        }
    }

    /** Überschriftenkette als Vorspann, höchstens ein Viertel eines Stücks, die hinteren Glieder zuerst. */
    private String prefix(String headings) {
        if (headings == null) {
            return "";
        }
        String kept = headings.length() > maxChunkChars / 4 ? headings.substring(headings.length() - maxChunkChars / 4)
                : headings;
        return kept + "\n\n";
    }

    /** Sätze als Stellen im Text, ohne Leerraum am Rand; Zeilen und Absätze sind eigene Grenzen. */
    private List<int[]> sentences(String text, int start, int end) {
        List<int[]> found = new ArrayList<>();
        int line = start;
        while (line < end) {
            int stop = text.indexOf('\n', line);
            stop = stop < 0 || stop >= end ? end : stop + 1;
            String block = text.substring(line, stop);
            int cursor = 0;
            for (String sentence : segmentation.sentences(block)) {
                int at = block.indexOf(sentence, cursor);
                if (at < 0) {
                    // Nicht wiedergefunden: die ganze Zeile als ein Satz.
                    found.add(new int[] {line, stop});
                    cursor = block.length();
                    break;
                }
                found.add(new int[] {line + at, line + at + sentence.length()});
                cursor = at + sentence.length();
            }
            line = stop;
        }
        return found;
    }

    /** Teilt einen Satz über dem Budget an Wortgrenzen; ein einzelnes zu langes Wort an der Zeichengrenze. */
    private List<int[]> fit(String text, int[] sentence, String prefix) {
        if (fits(text, prefix, sentence[0], sentence[1])) {
            return List.of(sentence);
        }
        List<int[]> pieces = new ArrayList<>();
        int start = sentence[0];
        while (start < sentence[1]) {
            int end = start;
            int cursor = start;
            while (cursor < sentence[1]) {
                int space = text.indexOf(' ', cursor);
                int wordEnd = space < 0 || space >= sentence[1] ? sentence[1] : space;
                if (!fits(text, prefix, start, wordEnd)) {
                    break;
                }
                end = wordEnd;
                cursor = wordEnd + 1;
            }
            if (end == start) {
                // Ein Wort allein ist zu lang: so viele Zeichen, wie Zeichen- und Token-Budget erlauben.
                end = longestFit(text, prefix, start, sentence[1]);
            }
            pieces.add(new int[] {start, end});
            start = end;
            while (start < sentence[1] && text.charAt(start) == ' ') {
                start++;
            }
        }
        return pieces;
    }

    /** Das späteste Ende ab {@code start + 1}, bei dem das Stück noch passt; mindestens ein Zeichen. */
    private int longestFit(String text, String prefix, int start, int limit) {
        int low = start + 1;
        int high = Math.min(limit, start + Math.max(1, maxChunkChars - prefix.length()));
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (fits(text, prefix, start, middle)) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low;
    }

    private boolean fits(String text, String prefix, int start, int end) {
        return prefix.length() + end - start <= maxChunkChars
                && segmentation.tokens(prefix + text.substring(start, end)) <= segmentation.maxTokens();
    }

    /** Ein Abschnitt oder Text zwischen Abschnitten mit seiner Überschriftenkette. */
    private record Span(int start, int end, String heading, String ancestors) {
    }

    /**
     * Ein Stück: Stelle in der Textprojektion und die Überschriftenkette, die beim Einbetten davor steht.
     */
    record Chunk(int start, int end, String prefix, String section) {

        String input(String text) {
            return prefix + text.substring(start, end);
        }
    }
}
