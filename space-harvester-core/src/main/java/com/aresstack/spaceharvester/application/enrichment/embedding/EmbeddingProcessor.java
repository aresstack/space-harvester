package com.aresstack.spaceharvester.application.enrichment.embedding;

import com.aresstack.spaceharvester.application.enrichment.EnrichmentOutput;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentProcessor;
import com.aresstack.spaceharvester.application.enrichment.JsonText;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;
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
 * <p>Länge der Anfrage (#181, wie {@code RetrieveKnowledgeUseCase.MAX_QUERY_TOKENS} im Enterprise AI Client): Eine
 * Suchanfrage über dem Token-Budget der {@link TextSegmentation} (dieselbe Grenze wie für die eingebetteten Stücke,
 * {@code embedding-chunk-tokens}) wird vor dem Einbetten an einer Wortgrenze gekürzt, statt den Dienst mit einem Text
 * über dessen Tokengrenze scheitern zu lassen. Ins Log kommen nur die Tokenzahlen, nie die Anfrage.
 *
 * <p>Die Version enthält Modell und Dimension: Ein Modellwechsel macht genau diese Ergebnisse veraltet, wie eine neue
 * Prozessorversion. Wie jeder Prozessor sieht er nur die sichere Projektion, nie Makroinhalte oder Anhänge.
 */
public class EmbeddingProcessor implements EnrichmentProcessor {

    /** Version der Zerlegung und des Ergebnisformats; 2 seit der Zerlegung nach Sätzen und Token-Budget (#181). */
    static final String FORMAT = "2";
    static final int MAX_CHUNKS = 200;

    private static final System.Logger LOG = System.getLogger(EmbeddingProcessor.class.getName());

    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9._@/:-]{1,100}");

    private final Supplier<EmbeddingProvider> provider;
    private final int maxChunkChars;
    private final TextSegmentation segmentation;
    private final Predicate<String> sources;
    private final boolean remote;
    /** Absätze wie askai arch statt fester Stücke; {@code null}: Zerlegung nach Token-Budget. */
    private final PassageChunking passages;

    /** Für alle Quellen, im aufrufenden Thread (Tests, eingebettete Modelle). */
    public EmbeddingProcessor(EmbeddingProvider provider, int maxChunkChars, TextSegmentation segmentation) {
        this(fixed(provider), maxChunkChars, segmentation, sourceId -> true, false);
    }

    /**
     * Für einen externen Dienst (ADR 0011): nur Seiten aus Quellen, deren Inhalte an ihn gehen dürfen, und im
     * Hintergrund von {@link com.aresstack.spaceharvester.application.enrichment.Enrichments}.
     *
     * @param mayProcessExternally ob Inhalte der Quelle an den Dienst gehen dürfen
     */
    public EmbeddingProcessor(EmbeddingProvider provider, int maxChunkChars, TextSegmentation segmentation,
                              Predicate<String> mayProcessExternally) {
        this(fixed(provider), maxChunkChars, segmentation, mayProcessExternally, true);
    }

    /**
     * Mit austauschbarem Modell (Konfiguration zur Laufzeit, ADR 0015): {@code provider} liefert das aktuelle Modell
     * oder {@code null}, solange keines eingestellt ist; dann bettet der Prozessor nichts ein ({@link #available()}).
     * Ein Wechsel ändert {@link #version()}: Die Vektoren des alten Modells gelten als veraltet und werden ohne neuen
     * Abruf der Seiten aus dem Cache neu berechnet, sobald die Suche sie braucht. Wer das Modell setzt, prüft es vorher
     * mit {@link #check(EmbeddingProvider)}.
     *
     * @param mayProcessExternally ob Inhalte der Quelle an den Dienst gehen dürfen; wird bei jeder Seite neu gefragt
     */
    public EmbeddingProcessor(Supplier<EmbeddingProvider> provider, int maxChunkChars, TextSegmentation segmentation,
                              Predicate<String> mayProcessExternally) {
        this(provider, maxChunkChars, segmentation, mayProcessExternally, true);
    }

    private EmbeddingProcessor(Supplier<EmbeddingProvider> provider, int maxChunkChars,
                               TextSegmentation segmentation, Predicate<String> sources, boolean remote) {
        this.segmentation = Objects.requireNonNull(segmentation, "segmentation");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.remote = remote;
        this.provider = Objects.requireNonNull(provider, "provider");
        if (maxChunkChars < 100) {
            throw new IllegalArgumentException("maxChunkChars muss mindestens 100 sein");
        }
        this.maxChunkChars = maxChunkChars;
        this.passages = null;
    }

    private EmbeddingProcessor(EmbeddingProcessor base, PassageChunking passages) {
        this.segmentation = base.segmentation;
        this.sources = base.sources;
        this.remote = base.remote;
        this.provider = base.provider;
        this.maxChunkChars = base.maxChunkChars;
        this.passages = passages;
    }

    /**
     * Derselbe Prozessor, der wie {@code IndexKnowledgeUseCase.withPassages} im Enterprise AI Client je semantischem
     * Absatz ein eigenes Embedding bildet (#181, ADR 0020) statt Sätze bis zum Token-Budget zu packen. Die Sätze für die
     * Themengrenzen bettet dasselbe Modell ein; ein Absatz über {@link #maxChunkChars} Zeichen wird wie bisher geteilt.
     * Die Version nennt die Zerlegung: Vorhandene Embeddings veralten und werden aus dem Cache neu berechnet.
     */
    public EmbeddingProcessor withPassages(PassageChunking value) {
        return new EmbeddingProcessor(this, Objects.requireNonNull(value, "passages"));
    }

    /** Ob die Stücke semantische Absätze sind ({@link #withPassages}). */
    public boolean passages() {
        return passages != null;
    }

    /** Ob die Absätze ({@link #passages}) Sätze aus einem Satzmodell bilden statt aus dem Regel-Splitter. */
    public boolean sentenceModel() {
        return passages != null && passages.sentenceModel();
    }

    private static Supplier<EmbeddingProvider> fixed(EmbeddingProvider provider) {
        check(Objects.requireNonNull(provider, "provider"));
        return () -> provider;
    }

    /**
     * Prüft Modellkennung und Dimension eines Modells, bevor es eingesetzt wird.
     *
     * @throws IllegalArgumentException wenn Kennung oder Dimension unbrauchbar sind
     */
    public static void check(EmbeddingProvider provider) {
        if (!MODEL_ID.matcher(provider.modelId()).matches()) {
            throw new IllegalArgumentException("Ungültige Modellkennung");
        }
        if (provider.dimensions() < 1 || provider.dimensions() > 4096) {
            throw new IllegalArgumentException("Dimension muss zwischen 1 und 4096 liegen");
        }
    }

    /** Ob gerade ein Modell eingestellt ist. */
    public boolean available() {
        return provider.get() != null;
    }

    /** Das aktuelle Modell. */
    private EmbeddingProvider provider() {
        EmbeddingProvider current = provider.get();
        if (current == null) {
            throw new EmbeddingUnavailableException("Kein Embedding-Modell eingestellt");
        }
        return current;
    }

    @Override
    public String id() {
        return "embedding";
    }

    @Override
    public boolean appliesTo(PageKey key) {
        return covers(key.sourceId());
    }

    /** Ob Inhalte dieser Quelle eingebettet werden; ohne Modell keine. */
    public boolean covers(String sourceId) {
        return available() && sources.test(sourceId);
    }

    @Override
    public boolean background() {
        return remote;
    }

    @Override
    public String version() {
        EmbeddingProvider current = provider.get();
        return current == null ? "off" : version(current);
    }

    private String version(EmbeddingProvider current) {
        return FORMAT + "+" + current.modelId() + "+" + current.dimensions() + "+" + maxChunkChars + "+"
                + segmentation.id() + (passages == null ? "" : "+passages:" + passages.fingerprint());
    }

    /**
     * Prüft die Freigabe der Quelle erst hier, beim Rechnen: Eine Berechnung, die vor dem Entzug eingereiht wurde,
     * schickt danach nichts mehr an den Dienst (ADR 0015).
     *
     * @throws IllegalStateException wenn Inhalte der Quelle nicht an den Dienst gehen dürfen
     */
    @Override
    public EnrichmentOutput process(PageKey key, PageText text) {
        if (!sources.test(key.sourceId())) {
            throw new IllegalStateException("Quelle nicht für den KI-Dienst freigegeben");
        }
        return process(text);
    }

    @Override
    public EnrichmentOutput process(PageText text) {
        // Ein Modell für den ganzen Lauf, auch wenn es währenddessen gewechselt wird.
        EmbeddingProvider provider = provider();
        List<Chunk> chunks = passages == null ? chunks(text) : passageChunks(text, provider);
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
            vector(json, vectors.get(i), provider.dimensions());
            json.append('}');
        }
        // Mit der Version dieses Modells, nicht der bei Abschluss eingestellten.
        return new EnrichmentOutput(json.append("]}").toString(), warnings, version(provider));
    }

    /**
     * Das jetzt eingestellte Modell mit seiner Version, fest für einen ganzen Suchvorgang: Wechselt die Konfiguration
     * währenddessen das Modell (ADR 0015), passen Anfragevektor und gesuchte Vektoren trotzdem zusammen.
     *
     * @return leer, solange kein Modell eingestellt ist
     */
    public Optional<Generation> current() {
        EmbeddingProvider current = provider.get();
        return current == null ? Optional.empty() : Optional.of(new Generation(current, version(current)));
    }

    /** Ein Modell und die Version seiner Embeddings. */
    public final class Generation {

        private final EmbeddingProvider provider;
        private final String version;

        private Generation(EmbeddingProvider provider, String version) {
            this.provider = provider;
            this.version = version;
        }

        /** Version der Embeddings dieses Modells, wie {@link EmbeddingProcessor#version()}. */
        public String version() {
            return version;
        }

        /** Kennung des Modells, z. B. {@code e5-small-v2@2024-01}. */
        public String modelId() {
            return provider.modelId();
        }

        /**
         * Bettet Texte als Dokumente mit diesem Modell ein, in derselben Reihenfolge (semantische Absätze, #181).
         *
         * @throws EmbeddingUnavailableException wenn das Modell nicht erreichbar ist oder nicht je Text einen
         *                                       endlichen Vektor der Dimension liefert
         */
        public List<float[]> embed(List<String> texts) {
            List<float[]> vectors = provider.embed(List.copyOf(texts));
            if (vectors == null || vectors.size() != texts.size()) {
                throw new EmbeddingUnavailableException("Modell lieferte nicht je Text einen Vektor");
            }
            for (float[] vector : vectors) {
                if (vector == null || vector.length != provider.dimensions()) {
                    throw new EmbeddingUnavailableException("Modell lieferte keinen Vektor der Dimension "
                            + provider.dimensions());
                }
                for (float v : vector) {
                    if (!Float.isFinite(v)) {
                        throw new EmbeddingUnavailableException("Modell lieferte keinen endlichen Vektor");
                    }
                }
            }
            return vectors;
        }

        /**
         * Bettet eine Suchanfrage mit diesem Modell ein.
         *
         * @throws EmbeddingUnavailableException wenn das Modell nicht erreichbar ist oder einen unbrauchbaren Vektor
         *                                       liefert
         */
        public float[] embedQuery(String text) {
            return EmbeddingProcessor.this.embedQuery(provider, text);
        }
    }

    /**
     * Bettet eine Suchanfrage mit dem jetzt eingestellten Modell ein; für einen Vorgang, der auch Vektoren sucht,
     * {@link #current()} nehmen.
     *
     * @throws EmbeddingUnavailableException wenn das Modell nicht erreichbar ist oder einen unbrauchbaren Vektor liefert
     */
    public float[] embedQuery(String text) {
        return embedQuery(provider(), text);
    }

    private float[] embedQuery(EmbeddingProvider provider, String text) {
        Objects.requireNonNull(text, "text");
        List<float[]> vectors = provider.embedQueries(List.of(shortened(text)));
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

    /**
     * Die Anfrage, höchstens {@link TextSegmentation#maxTokens()} Token lang, an einer Wortgrenze gekürzt; wie
     * {@code RetrieveKnowledgeUseCase.shortened} im Enterprise AI Client.
     */
    String shortened(String query) {
        int limit = segmentation.maxTokens();
        int tokens = segmentation.tokens(query);
        if (tokens <= limit) {
            return query;
        }
        StringBuilder kept = new StringBuilder();
        int keptTokens = 0;
        for (String word : query.trim().split("\\s+")) {
            int wordTokens = segmentation.tokens(word);
            if (keptTokens + wordTokens > limit) {
                break;
            }
            if (kept.length() > 0) {
                kept.append(' ');
            }
            kept.append(word);
            keptTokens += wordTokens;
        }
        if (kept.length() == 0) {
            // ein einziges überlanges "Wort": höchstens ein Token je Zeichen
            String head = query.trim().substring(0, Math.min(query.trim().length(), limit));
            kept.append(head);
            keptTokens = segmentation.tokens(head);
        }
        // Nur Tokenzahlen, nie die Anfrage.
        LOG.log(System.Logger.Level.INFO, "Anfrage für die semantische Suche von {0} auf {1} Token gekürzt"
                + " (Tokengrenze des Embedding-Modells)", tokens, keptTokens);
        return kept.toString();
    }

    private static void vector(StringBuilder json, float[] vector, int dimensions) {
        if (vector == null || vector.length != dimensions) {
            throw new IllegalStateException("Vektor hat nicht die Dimension " + dimensions);
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

    /** Je Absatz ein Stück, eingebettet ohne Vorspann wie im Client; zu lange Absätze wie bisher geteilt. */
    private List<Chunk> passageChunks(PageText text, EmbeddingProvider provider) {
        Generation generation = new Generation(provider, version(provider));
        List<Chunk> chunks = new ArrayList<>();
        for (PassageChunking.Piece piece : passages.passages(text, generation::embed)) {
            if (piece.end() <= piece.start()) {
                continue;
            }
            if (piece.end() - piece.start() <= maxChunkChars) {
                chunks.add(new Chunk(piece.start(), piece.end(), "", piece.section()));
            } else {
                chunk(text.text(), new Span(piece.start(), piece.end(), piece.section(), null), chunks);
            }
        }
        return chunks;
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
