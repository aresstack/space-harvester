package com.aresstack.spaceharvester.application.passages;

import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.embedding.TextSegmentation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text → Blöcke → Sätze → semantische Passagen, übernommen aus dem Enterprise AI Client {@code PassageSegmentation}
 * (dort aus askai-java8 arch, {@code BoundaryMode.SEMANTIC}). DIE STRUKTUR DES TEXTES KOMMT ZUERST: Überschriften,
 * Listenpunkte, Tabellenzeilen, Zitate und Code-Blöcke sind harte Grenzen; nur in Absätzen verfeinert die
 * Themenwechsel-Erkennung: nicht {@code cos(s[n], s[n+1])}, sondern die Ähnlichkeit eines LINKEN und RECHTEN
 * Satzfensters (bis {@link #WINDOW_SIZE} Sätze). Mindest- und Höchstzahl an Sätzen verhindern Mini-Passagen und
 * Textwände. Werte wie in arch und im Client (3, 0.35, 2, 8, 350 Tokens).
 *
 * <p>Abweichungen vom Client, weil hier Stellen im Text gebraucht werden: Jede Passage ist ein Ausschnitt
 * {@code [start, end)} der {@link PageText}, nicht ein neu zusammengefügter Text; Überschriften kommen aus
 * {@link PageText#sections()}. Die Blöcke einer Confluence-Projektion sind ihre Zeilen (jedes Blockelement eine), wie
 * die Strukturblöcke, die der Client aus dem Dokumentbaum der Quelle liest; andere Texte (Dateien) liest er wie der
 * Client: Absätze zwischen Leerzeilen, Code-Zäune, Listenpunkte, {@code |}-Tabellenzeilen, {@code >}-Zitate. Die Sätze
 * aller Absätze gehen in Stapeln an das Modell statt je Absatz (gleiche Vektoren). Ohne Modell
 * ({@code embedder == null}) bleibt es bei den Grenzen der Struktur und des Token-Budgets: die Rückfallebene. Ein eigenes
 * Embedding je Passage entsteht hier nicht; das bleibt Sache des Prozessors {@code embedding}.
 */
public final class PassageSegmentation {

    static final int WINDOW_SIZE = 3;
    static final double BOUNDARY_THRESHOLD = 0.35;
    static final int MIN_PASSAGE_SENTENCES = 2;
    static final int MAX_PASSAGE_SENTENCES = 8;
    /** Version der Zerlegung wie im Client. */
    public static final String SEGMENTATION_VERSION = "semantic-v2";
    /** Höchstgröße einer Passage in Token, sonst an Wortgrenzen geteilt. */
    public static final int MAX_PASSAGE_TOKENS = 350;
    /** Sätze je Embedding-Aufruf. */
    static final int EMBEDDING_BATCH = 32;
    /** Höchstens so viele Sätze einer Seite gehen an das Modell; dahinter gilt die Struktur. */
    static final int MAX_EMBEDDED_SENTENCES = 2_000;

    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:[-*+•]|\\d{1,3}[.)])\\s+\\S.*$");
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^ {0,3}#{1,6}[ \\t].*$");
    private static final Pattern WORD = Pattern.compile("\\S+");

    /** Art eines Blocks; nur {@link #PARAGRAPH} wird semantisch verfeinert. */
    public enum Kind {
        PARAGRAPH,
        LIST_ITEM,
        TABLE_ROW,
        QUOTE,
        CODE
    }

    /** Warum eine Passage hier beginnt. */
    public enum Boundary {
        /** Anfang eines Blocks (Absatz, Listenpunkt, Tabellenzeile, Zitat, Code). */
        STRUCTURE,
        /** Themenwechsel: die Satzfenster davor und danach sind sich unähnlich. */
        TOPIC,
        /** Die vorige Passage hatte schon {@link #MAX_PASSAGE_SENTENCES} Sätze. */
        LENGTH,
        /** Die vorige Passage war länger als {@link #MAX_PASSAGE_TOKENS} Token und wurde an einer Wortgrenze geteilt. */
        TOKENS
    }

    /**
     * Eine Passage.
     *
     * @param start         erstes Zeichen in {@link PageText#text()} (UTF-16)
     * @param end           Ende, exklusiv
     * @param firstSentence Nummer ihres ersten Satzes in der Seite, ab 0
     * @param sentences     Zahl ihrer Sätze (bei {@link Boundary#TOKENS} geteilter Sätze: Teile)
     * @param section       Überschriftenkette ({@code Handbuch > Betrieb}) oder {@code null} vor der ersten
     * @param kind          Art ihres Blocks
     * @param boundary      warum sie hier beginnt
     * @param similarity    bei {@link Boundary#TOPIC} die gemessene Ähnlichkeit der Satzfenster an der Grenze, sonst
     *                      {@code null}
     */
    public record Passage(int start, int end, int firstSentence, int sentences, String section, Kind kind,
                          Boundary boundary, Double similarity) {

        public Passage {
            if (start < 0 || end <= start || firstSentence < 0 || sentences < 1) {
                throw new IllegalArgumentException("Ungültige Passage");
            }
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(boundary, "boundary");
        }
    }

    /**
     * Ergebnis einer Zerlegung.
     *
     * @param passages  in Lesereihenfolge
     * @param sentences Zahl aller Sätze der Passagen
     * @param semantic  ob Themenwechsel gemessen wurden; {@code false} ist die Rückfallebene nach Struktur
     * @param truncated ob Absätze nach {@link #MAX_EMBEDDED_SENTENCES} Sätzen nur nach Struktur geteilt sind
     */
    public record Result(List<Passage> passages, int sentences, boolean semantic, boolean truncated) {

        public Result {
            passages = List.copyOf(passages);
        }
    }

    private final SentenceSegmenter sentences;
    private final TextSegmentation tokens;

    /**
     * @param sentences Satzgrenzen
     * @param tokens    Zählweise der Token wie im Client ({@code KnowledgeTokenCounter.wordsAndSymbols()}), produktiv
     *                  {@code KnowledgeTextSegmentation.defaults()}
     */
    public PassageSegmentation(SentenceSegmenter sentences, TextSegmentation tokens) {
        this.sentences = Objects.requireNonNull(sentences, "sentences");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
    }

    /** Ob ein Satzmodell die Sätze trennt, nicht der Regel-Splitter ({@link SentenceSegmenter#splitter}). */
    public boolean sentenceModel() {
        return !"splitter".equals(sentences.id());
    }

    /**
     * Was die Passagen bestimmt (Verfahren, Parameter, Satzmodell), z. B.
     * {@code semantic-v2;window=3;threshold=0.35;min=2;max=8;maxTokens=350;sentences=opennlp-de}, wie im Client.
     */
    public String fingerprint() {
        return SEGMENTATION_VERSION + ";window=" + WINDOW_SIZE + ";threshold=" + BOUNDARY_THRESHOLD + ";min="
                + MIN_PASSAGE_SENTENCES + ";max=" + MAX_PASSAGE_SENTENCES + ";maxTokens=" + MAX_PASSAGE_TOKENS
                + ";sentences=" + sentences.id();
    }

    /**
     * Die Passagen eines Textes in Lesereihenfolge.
     *
     * @param embedder bettet Sätze ein (je Text ein Vektor, gleiche Reihenfolge) oder {@code null} für die Rückfallebene
     *                 nach Struktur
     */
    public Result segment(PageText text, Function<List<String>, List<float[]>> embedder) {
        Objects.requireNonNull(text, "text");
        String content = text.text();
        List<Block> blocks = blocks(text);
        List<List<int[]>> blockSentences = new ArrayList<>(blocks.size());
        List<Boolean> embedded = new ArrayList<>(blocks.size());
        List<String> toEmbed = new ArrayList<>();
        boolean truncated = false;
        for (Block block : blocks) {
            List<int[]> found = sentencesOf(content, block);
            blockSentences.add(found);
            boolean refine = embedder != null && refinable(block, found);
            if (refine && !truncated && toEmbed.size() + found.size() <= MAX_EMBEDDED_SENTENCES) {
                for (int[] sentence : found) {
                    toEmbed.add(content.substring(sentence[0], sentence[1]));
                }
            } else if (refine) {
                truncated = true; // ab hier nur Struktur, auch für spätere kleinere Absätze
                refine = false;
            }
            embedded.add(refine);
        }
        List<float[]> vectors = embedder == null ? List.of() : embed(toEmbed, embedder);

        List<Passage> passages = new ArrayList<>();
        int ordinal = 0;
        int vectorOffset = 0;
        for (int b = 0; b < blocks.size(); b++) {
            Block block = blocks.get(b);
            List<int[]> found = blockSentences.get(b);
            if (found.isEmpty()) {
                continue;
            }
            List<float[]> blockVectors = null;
            if (embedded.get(b)) {
                blockVectors = vectors.subList(vectorOffset, vectorOffset + found.size());
                vectorOffset += found.size();
            }
            for (Split split : blockVectors == null ? List.of(new Split(0, found.size(), Boundary.STRUCTURE, null))
                    : splitSemantically(blockVectors)) {
                int from = found.get(split.from)[0];
                int to = found.get(split.to - 1)[1];
                bounded(passages, content, from, to, ordinal + split.from, split.to - split.from, block,
                        split.boundary, split.similarity);
            }
            ordinal += found.size();
        }
        return new Result(passages, ordinal, embedder != null, truncated);
    }

    private static boolean refinable(Block block, List<int[]> sentences) {
        return block.kind == Kind.PARAGRAPH && sentences.size() > MIN_PASSAGE_SENTENCES;
    }

    private static List<float[]> embed(List<String> texts, Function<List<String>, List<float[]>> embedder) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += EMBEDDING_BATCH) {
            List<String> slice = texts.subList(from, Math.min(texts.size(), from + EMBEDDING_BATCH));
            List<float[]> batch = embedder.apply(List.copyOf(slice));
            if (batch == null || batch.size() != slice.size()) {
                throw new IllegalStateException("Modell lieferte nicht je Satz einen Vektor");
            }
            vectors.addAll(batch);
        }
        return vectors;
    }

    /** Semantische Verfeinerung INNERHALB eines Absatzes, Ablauf wie im Client. */
    private static List<Split> splitSemantically(List<float[]> vectors) {
        List<Split> splits = new ArrayList<>();
        int size = vectors.size();
        int start = 0;
        Boundary reason = Boundary.STRUCTURE;
        Double reasonSimilarity = null;
        for (int i = 0; i < size; i++) {
            int current = i - start + 1;
            boolean last = i == size - 1;
            boolean maxReached = current >= MAX_PASSAGE_SENTENCES;
            Double similarity = null;
            boolean semanticBoundary = false;
            if (!last && current >= MIN_PASSAGE_SENTENCES && size - i - 1 >= MIN_PASSAGE_SENTENCES) {
                similarity = boundaryStrength(vectors, i);
                semanticBoundary = similarity < BOUNDARY_THRESHOLD;
            }
            if (last || maxReached || semanticBoundary) {
                splits.add(new Split(start, i + 1, reason, reasonSimilarity));
                start = i + 1;
                reason = semanticBoundary ? Boundary.TOPIC : Boundary.LENGTH;
                reasonSimilarity = semanticBoundary ? similarity : null;
            }
        }
        return splits;
    }

    /** Ähnlichkeit der Fenster LINKS der Grenze (…i) und RECHTS davon (i+1…): niedrig = Themenwechsel. */
    static double boundaryStrength(List<float[]> vectors, int boundaryAfter) {
        int leftFrom = Math.max(0, boundaryAfter - WINDOW_SIZE + 1);
        int rightTo = Math.min(vectors.size(), boundaryAfter + 1 + WINDOW_SIZE);
        return VectorMath.cosine(VectorMath.meanValues(vectors.subList(leftFrom, boundaryAfter + 1)),
                VectorMath.meanValues(vectors.subList(boundaryAfter + 1, rightTo)));
    }

    /** Eine Passage über {@link #MAX_PASSAGE_TOKENS} an Wortgrenzen geteilt; sonst unverändert. */
    private void bounded(List<Passage> passages, String content, int from, int to, int firstSentence, int count,
                         Block block, Boundary boundary, Double similarity) {
        if (tokens.tokens(content.substring(from, to)) <= MAX_PASSAGE_TOKENS) {
            passages.add(new Passage(from, to, firstSentence, count, block.section, block.kind, boundary,
                    similarity));
            return;
        }
        // Token je Wort zählen: Leerraum trennt immer, die Summe ist die Zahl des Ausschnitts.
        Matcher word = WORD.matcher(content).region(from, to);
        int partStart = -1;
        int partEnd = -1;
        int partTokens = 0;
        boolean first = true;
        while (word.find()) {
            int wordTokens = tokens.tokens(word.group());
            if (partStart >= 0 && partTokens + wordTokens > MAX_PASSAGE_TOKENS) {
                passages.add(new Passage(partStart, partEnd, firstSentence, count, block.section, block.kind,
                        first ? boundary : Boundary.TOKENS, first ? similarity : null));
                first = false;
                partStart = -1;
                partTokens = 0;
            }
            if (partStart < 0) {
                partStart = word.start();
            }
            partEnd = word.end();
            partTokens += wordTokens;
        }
        if (partStart >= 0) {
            passages.add(new Passage(partStart, partEnd, firstSentence, count, block.section, block.kind,
                    first ? boundary : Boundary.TOKENS, first ? similarity : null));
        }
    }

    /** Die Sätze eines Blocks als Stellen im Text; jeder Block hat mindestens einen. */
    private List<int[]> sentencesOf(String content, Block block) {
        List<int[]> found = new ArrayList<>();
        if (block.kind == Kind.CODE || block.kind == Kind.TABLE_ROW) {
            found.add(new int[] {block.start, block.end}); // Code und Tabellenzeilen nie in Scheinsätze zerlegen
            return found;
        }
        String text = content.substring(block.start, block.end);
        int cursor = 0;
        List<String> detected = sentences.sentences(text);
        for (String sentence : detected == null ? List.<String>of() : detected) {
            int at = sentence.isEmpty() ? -1 : text.indexOf(sentence, cursor);
            if (at < 0) {
                // Satz nicht wörtlich im Block: lieber ein Satz für den ganzen Rest als falsche Stellen.
                found.add(new int[] {block.start + cursor, block.end});
                return trimmed(content, found);
            }
            found.add(new int[] {block.start + at, block.start + at + sentence.length()});
            cursor = at + sentence.length();
        }
        if (found.isEmpty()) {
            found.add(new int[] {block.start, block.end});
        }
        return trimmed(content, found);
    }

    private static List<int[]> trimmed(String content, List<int[]> spans) {
        List<int[]> result = new ArrayList<>(spans.size());
        for (int[] span : spans) {
            int start = span[0];
            int end = span[1];
            while (start < end && Character.isWhitespace(content.charAt(start))) {
                start++;
            }
            while (end > start && Character.isWhitespace(content.charAt(end - 1))) {
                end--;
            }
            if (end > start) {
                result.add(new int[] {start, end});
            }
        }
        return result;
    }

    /**
     * Die Blöcke des Textes ohne Überschriften, mit dem Abschnittspfad, in dem sie stehen; auch die Absätze der
     * Satzanalyse ({@code statements}, #108).
     */
    public static List<Block> blocks(PageText text) {
        String content = text.text();
        List<PageText.Section> sections = text.sections();
        boolean lines = text.schema().startsWith("confluence-storage-text/");
        List<Block> blocks = new ArrayList<>();
        int paragraphStart = -1;
        int paragraphEnd = -1;
        int codeStart = -1;
        int position = 0;
        while (position <= content.length()) {
            int newline = content.indexOf('\n', position);
            int lineEnd = newline < 0 ? content.length() : newline;
            String line = content.substring(position, lineEnd);
            String trimmed = line.strip();
            boolean heading = isHeading(sections, position, lineEnd);
            if (codeStart >= 0) {
                if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                    add(blocks, text, Kind.CODE, codeStart, position);
                    codeStart = -1;
                }
            } else if (heading || trimmed.isEmpty() || MARKDOWN_HEADING.matcher(line).matches() && !lines) {
                paragraphStart = flush(blocks, text, paragraphStart, paragraphEnd);
            } else if (!lines && (trimmed.startsWith("```") || trimmed.startsWith("~~~"))) {
                paragraphStart = flush(blocks, text, paragraphStart, paragraphEnd);
                codeStart = newline < 0 ? content.length() : newline + 1;
            } else if (trimmed.startsWith("|") || lines && trimmed.contains(" | ")) {
                paragraphStart = flush(blocks, text, paragraphStart, paragraphEnd);
                if (!trimmed.matches("\\|?[\\s:|-]+\\|?")) { // Trennzeile einer Tabelle auslassen
                    add(blocks, text, Kind.TABLE_ROW, position, lineEnd);
                }
            } else if (trimmed.startsWith(">") && !lines) {
                paragraphStart = flush(blocks, text, paragraphStart, paragraphEnd);
                add(blocks, text, Kind.QUOTE, position + line.indexOf('>') + 1, lineEnd);
            } else if (LIST_ITEM.matcher(line).matches()) {
                paragraphStart = flush(blocks, text, paragraphStart, paragraphEnd);
                add(blocks, text, Kind.LIST_ITEM, position, lineEnd);
            } else if (lines) {
                add(blocks, text, Kind.PARAGRAPH, position, lineEnd);
            } else {
                if (paragraphStart < 0) {
                    paragraphStart = position;
                }
                paragraphEnd = lineEnd;
            }
            if (newline < 0) {
                break;
            }
            position = newline + 1;
        }
        if (codeStart >= 0) {
            add(blocks, text, Kind.CODE, codeStart, content.length());
        }
        flush(blocks, text, paragraphStart, paragraphEnd);
        return blocks;
    }

    private static int flush(List<Block> blocks, PageText text, int start, int end) {
        if (start >= 0) {
            add(blocks, text, Kind.PARAGRAPH, start, end);
        }
        return -1;
    }

    private static void add(List<Block> blocks, PageText text, Kind kind, int start, int end) {
        String content = text.text();
        while (start < end && Character.isWhitespace(content.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(content.charAt(end - 1))) {
            end--;
        }
        if (end > start) {
            blocks.add(new Block(kind, path(text.sections(), start), start, end));
        }
    }

    /** Ob die Zeile die Überschrift eines Abschnitts ist. */
    private static boolean isHeading(List<PageText.Section> sections, int lineStart, int lineEnd) {
        for (PageText.Section section : sections) {
            if (section.start() >= lineStart && section.start() < Math.max(lineEnd, lineStart + 1)) {
                return true;
            }
        }
        return false;
    }

    /** Die Überschriftenkette an einer Stelle, getrennt durch {@code " > "}, oder {@code null} vor der ersten. */
    static String path(List<PageText.Section> sections, int offset) {
        List<PageText.Section> chain = new ArrayList<>();
        for (PageText.Section section : sections) {
            if (section.start() > offset) {
                break;
            }
            while (!chain.isEmpty() && chain.get(chain.size() - 1).level() >= section.level()) {
                chain.remove(chain.size() - 1);
            }
            chain.add(section);
        }
        if (chain.isEmpty()) {
            return null;
        }
        StringBuilder path = new StringBuilder();
        for (PageText.Section section : chain) {
            String heading = section.heading().strip();
            if (heading.isEmpty()) {
                continue;
            }
            path.append(path.length() > 0 ? " > " : "").append(heading);
        }
        return path.length() == 0 ? null : path.toString();
    }

    /**
     * Ein Strukturblock.
     *
     * @param kind    Art
     * @param section Überschriftenkette oder {@code null}
     * @param start   erstes Zeichen in {@link PageText#text()}
     * @param end     Ende, exklusiv
     */
    public record Block(Kind kind, String section, int start, int end) {
    }

    private record Split(int from, int to, Boundary boundary, Double similarity) {
    }
}
