package com.aresstack.spaceharvester.app.spike;

import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataBatch;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.StoredPage;
import com.aresstack.spaceharvester.nlp.extraction.Argument;
import com.aresstack.spaceharvester.nlp.extraction.ExtractionSettings;
import com.aresstack.spaceharvester.nlp.extraction.Mention;
import com.aresstack.spaceharvester.nlp.extraction.ObjectCentricAnalysis;
import com.aresstack.spaceharvester.nlp.extraction.ObjectCentricExtraction;
import com.aresstack.spaceharvester.nlp.extraction.Role;
import com.aresstack.spaceharvester.nlp.extraction.Statement;
import com.aresstack.spaceharvester.nlp.malt.MaltModelLoader;
import com.aresstack.spaceharvester.nlp.opennlp.OpenNlpGermanPreprocessor;
import com.aresstack.spaceharvester.nlp.syntax.DependencyAnalysis;
import com.aresstack.spaceharvester.nlp.syntax.DocumentRef;
import com.aresstack.spaceharvester.nlp.syntax.ParsedDocument;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import com.aresstack.spaceharvester.source.confluence.ConfluenceStorageTextProjector;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.io.IOException;
import java.io.InputStream;
import opennlp.tools.lemmatizer.LemmatizerME;
import opennlp.tools.lemmatizer.LemmatizerModel;
import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTagFormat;
import opennlp.tools.postag.POSTaggerME;
import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;
import opennlp.tools.util.Span;

/**
 * Fetch a small, public Confluence corpus using the Harvester source and text projection.
 * Keep the German-language parser output separate from the English source evidence.
 */
public final class AtlassianTextSpike {

    private static final String SOURCE_ID = "confluence-atlassian";
    private static final int TARGET_PAGES = 3;
    private static final int MINIMUM_TEXT_LENGTH = 250;
    private static final int MAX_ANALYSIS_CHARACTERS = 2400;

    private final ConfluenceStorageTextProjector projector = new ConfluenceStorageTextProjector();

    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 2 || arguments.length > 3) {
            throw new IllegalArgumentException("Usage: <outputDirectory> <modelDirectory> [spaceKey]");
        }
        new AtlassianTextSpike().run(Path.of(arguments[0]), Path.of(arguments[1]),
                arguments.length == 3 ? arguments[2] : null);
    }

    private void run(Path directory, Path modelDirectory, String selectedSpace) throws Exception {
        Files.createDirectories(directory);
        SourceSettingsConfig config = new SourceSettingsConfig(SOURCE_ID, "confluence",
                "Confluence-Hilfe (Atlassian)", URI.create("https://confluence.atlassian.com"),
                SourceSettingsConfig.Auth.anonymous(), Map.of());
        try (ConfiguredSourceCatalog catalog = ConfiguredSourceCatalog.of(
                List.of(config), "SpaceHarvester-AtlassianTextSpike/1.0")) {
            HarvestSource source = catalog.find(SOURCE_ID).orElseThrow();
            List<Partition> partitions = source.partitions();
            System.out.println("CONNECTED source=" + source.id() + " spaces=" + partitions.size());
            partitions.stream().limit(14).forEach(space ->
                    System.out.println("SPACE " + space.key() + " | " + space.name()));
            List<HarvestedPage> pages = harvestSelectedPages(source, partitions, selectedSpace, directory);
            writeSourceSummary(pages, directory);
            System.out.println("HARVESTED pages=" + pages.size());
            if (!pages.isEmpty()) {
                analyzeEnglish(pages, directory);
                analyzePages(pages, modelDirectory, directory);
            }
        }
    }

    private List<HarvestedPage> harvestSelectedPages(HarvestSource source, List<Partition> partitions,
                                                      String selectedSpace, Path directory) throws Exception {
        List<Partition> candidateSpaces = partitions.stream()
                .filter(space -> selectedSpace == null || space.key().equalsIgnoreCase(selectedSpace))
                .sorted(Comparator.comparingInt(this::spacePriority).reversed())
                .limit(selectedSpace == null ? 8 : 1)
                .toList();
        if (candidateSpaces.isEmpty()) {
            throw new IllegalArgumentException("Space not found: " + selectedSpace);
        }

        List<HarvestedPage> harvested = new ArrayList<>();
        int fetched = 0;
        for (Partition space : candidateSpaces) {
            if (harvested.size() >= TARGET_PAGES || fetched >= 18) {
                break;
            }
            try {
                PageMetadataBatch batch = source.pageMetadata(space.key(), null, 12);
                System.out.println("LIST space=" + space.key() + " pages=" + batch.pages().size());
                for (PageMetadata metadata : batch.pages()) {
                    if (harvested.size() >= TARGET_PAGES || fetched >= 18) {
                        break;
                    }
                    fetched++;
                    try {
                        StoredPage stored = source.storedPage(metadata.pageId());
                        PageSnapshot snapshot = PageSnapshot.of(source.id(), stored.metadata(),
                                new NativePayload(stored.payload(), stored.mediaType(), stored.nativeFormat()),
                                stored.retrievedAt());
                        Optional<PageText> projection = projector.project(snapshot);
                        if (projection.isEmpty() || projection.get().text().length() < MINIMUM_TEXT_LENGTH) {
                            System.out.println("SKIP id=" + metadata.pageId() + " short-or-unsupported");
                            continue;
                        }
                        PageText text = projection.get();
                        String filename = "page-" + stored.metadata().pageId() + ".txt";
                        Files.writeString(directory.resolve(filename), text.text(), StandardCharsets.UTF_8);
                        harvested.add(new HarvestedPage(stored.metadata(), text));
                        System.out.println("PAGE id=" + stored.metadata().pageId()
                                + " title=" + printable(stored.metadata().title())
                                + " chars=" + text.text().length() + " sections=" + text.sections().size());
                    } catch (RuntimeException failure) {
                        System.out.println("FETCH_FAILED pageId=" + metadata.pageId()
                                + " reason=" + failure.getClass().getSimpleName());
                    }
                }
            } catch (RuntimeException failure) {
                System.out.println("LIST_FAILED space=" + space.key()
                        + " reason=" + failure.getClass().getSimpleName());
            }
        }
        return harvested;
    }

    private int spacePriority(Partition space) {
        String name = (space.key() + " " + space.name()).toLowerCase(Locale.ROOT);
        if (name.contains("confluence")) {
            return 3;
        }
        if (name.contains("documentation") || name.contains("cloud")) {
            return 2;
        }
        return 0;
    }

    private void writeSourceSummary(List<HarvestedPage> pages, Path directory) throws Exception {
        List<String> rows = new ArrayList<>();
        rows.add("pageId\tspace\tversion\tcharacters\ttitle\turl");
        for (HarvestedPage page : pages) {
            PageMetadata metadata = page.metadata();
            rows.add(metadata.pageId() + "\t" + metadata.partitionKey() + "\t" + metadata.version()
                    + "\t" + page.text().text().length() + "\t" + printable(metadata.title())
                    + "\t" + metadata.url());
        }
        Files.write(directory.resolve("sources.tsv"), rows, StandardCharsets.UTF_8);
    }

    private void analyzeEnglish(List<HarvestedPage> pages, Path directory) throws Exception {
        EnglishWordAnalysis analyzer = EnglishWordAnalysis.fromClasspath();
        List<String> summary = new ArrayList<>();
        Map<String, Integer> totalNounOccurrences = new TreeMap<>();
        Map<String, Set<String>> nounPageOccurrences = new TreeMap<>();
        summary.add("English UD part-of-speech baseline. Counts are lexical candidates, not domain concepts.");
        for (HarvestedPage page : pages) {
            EnglishWordAnalysis.Result result = analyzer.analyze(page.text().text());
            summary.add("PAGE " + page.metadata().pageId() + " | " + printable(page.metadata().title()));
            summary.add("SENTENCES " + result.sentences()
                    + " NOUN_TOKENS " + result.nounTokens() + " VERB_TOKENS " + result.verbTokens());
            summary.add("NOUNS " + result.topNouns());
            summary.add("VERBS " + result.topVerbs());
            summary.add("NOMINALIZED_ACTIVITY_CANDIDATES " + result.activityNounCandidates());
            summary.add("");
            result.nounCounts().forEach((term, count) -> {
                totalNounOccurrences.merge(term, count, Integer::sum);
                nounPageOccurrences.computeIfAbsent(term, unused -> new LinkedHashSet<>())
                        .add(page.metadata().pageId());
            });
        }
        summary.add("REPEATED_ACROSS_PAGES " + nounPageOccurrences.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .sorted(Comparator.<Map.Entry<String, Set<String>>>comparingInt(
                        entry -> totalNounOccurrences.get(entry.getKey())).reversed())
                .limit(18)
                .map(entry -> entry.getKey() + "=" + totalNounOccurrences.get(entry.getKey())
                        + " occurrences/" + entry.getValue().size() + " pages")
                .collect(Collectors.joining(", ")));
        Files.write(directory.resolve("english-pos-baseline.txt"), summary, StandardCharsets.UTF_8);
        summary.forEach(System.out::println);
    }

    private void analyzePages(List<HarvestedPage> pages, Path modelDirectory, Path directory) throws Exception {
        if (!Files.isDirectory(modelDirectory)) {
            System.out.println("PARSER_NOT_AVAILABLE modelDirectory=" + modelDirectory);
            return;
        }
        DependencyAnalysis parser = new DependencyAnalysis(OpenNlpGermanPreprocessor.fromClasspath(),
                MaltModelLoader.loadPinned(modelDirectory));
        ObjectCentricExtraction extractor = new ObjectCentricExtraction(ExtractionSettings.defaults());
        List<String> summary = new ArrayList<>();
        summary.add("German-parser exploratory output on English text. Not valid for glossary decisions.");
        for (HarvestedPage page : pages) {
            String text = boundedText(page.text().text());
            try {
                ParsedDocument parsed = parser.analyze(new DocumentRef(SOURCE_ID, page.metadata().pageId(),
                        page.metadata().version() == null ? "unknown" : page.metadata().version()), text);
                ObjectCentricAnalysis analysis = extractor.analyze(parsed);
                summary.add("PAGE " + page.metadata().pageId() + " | " + printable(page.metadata().title()));
                summary.add("SENTENCES " + parsed.sentences().size() + " MENTIONS " + analysis.mentions().size()
                        + " STATEMENTS " + analysis.statements().size());
                summary.add("NOUN_HEADS " + topNouns(analysis));
                summary.add("VERBS " + topVerbs(analysis));
                analysis.statements().stream().limit(8)
                        .forEach(statement -> summary.add(statementLine(analysis, statement)));
                summary.add("");
            } catch (RuntimeException failure) {
                summary.add("ANALYSIS_FAILED " + page.metadata().pageId() + " "
                        + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            }
        }
        Files.write(directory.resolve("german-parser-exploration.txt"), summary, StandardCharsets.UTF_8);
        summary.forEach(System.out::println);
    }

    private String topNouns(ObjectCentricAnalysis analysis) {
        return analysis.mentions().stream()
                .filter(mention -> mention.kind() == Mention.Kind.NOUN
                        || mention.kind() == Mention.Kind.PROPER_NOUN)
                .map(mention -> mention.key().toLowerCase(Locale.ROOT))
                .collect(Collectors.groupingBy(key -> key, LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(12).map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(", "));
    }

    private String topVerbs(ObjectCentricAnalysis analysis) {
        return analysis.statements().stream()
                .map(statement -> statement.predicate().lemma() == null
                        ? statement.predicate().text() : statement.predicate().lemma())
                .collect(Collectors.groupingBy(key -> key, LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(12).map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(", "));
    }

    private String statementLine(ObjectCentricAnalysis analysis, Statement statement) {
        return "TRIPLE " + argumentText(analysis, statement, Role.ACTOR)
                + " -> " + statement.predicate().text() + " -> "
                + argumentText(analysis, statement, Role.AFFECTED_OBJECT);
    }

    private String argumentText(ObjectCentricAnalysis analysis, Statement statement, Role role) {
        List<Argument> arguments = statement.withRole(role);
        return arguments.isEmpty() ? "?" : printable(analysis.mention(arguments.get(0).mention()).text());
    }

    private String boundedText(String fullText) {
        if (fullText.length() <= MAX_ANALYSIS_CHARACTERS) {
            return fullText;
        }
        int newline = fullText.lastIndexOf('\n', MAX_ANALYSIS_CHARACTERS);
        return fullText.substring(0, newline >= MAX_ANALYSIS_CHARACTERS / 2
                ? newline : MAX_ANALYSIS_CHARACTERS);
    }

    private static String printable(String value) {
        return value == null ? "" : value.replace('\t', ' ').replace('\n', ' ')
                .replace('\r', ' ');
    }

    private record HarvestedPage(PageMetadata metadata, PageText text) {
    }
}

/**
 * Extract English noun and verb candidates using trained English UD POS models.
 * Keep word counts separate from semantic concepts and syntactic relations.
 */
final class EnglishWordAnalysis {

    private final SentenceModel sentenceModel;
    private final TokenizerModel tokenizerModel;
    private final POSModel posModel;
    private final LemmatizerModel lemmaModel;

    private EnglishWordAnalysis(SentenceModel sentenceModel, TokenizerModel tokenizerModel, POSModel posModel,
                                LemmatizerModel lemmaModel) {
        this.sentenceModel = sentenceModel;
        this.tokenizerModel = tokenizerModel;
        this.posModel = posModel;
        this.lemmaModel = lemmaModel;
    }

    static EnglishWordAnalysis fromClasspath() throws IOException {
        try (InputStream sentences = model("opennlp-en-ud-ewt-sentence-1.3-2.5.4.bin");
             InputStream tokens = model("opennlp-en-ud-ewt-tokens-1.3-2.5.4.bin");
             InputStream tags = model("opennlp-en-ud-ewt-pos-1.3-2.5.4.bin");
             InputStream lemmas = model("opennlp-en-ud-ewt-lemmas-1.3-2.5.4.bin")) {
            return new EnglishWordAnalysis(new SentenceModel(sentences), new TokenizerModel(tokens),
                    new POSModel(tags), new LemmatizerModel(lemmas));
        }
    }

    Result analyze(String text) {
        SentenceDetectorME detector = new SentenceDetectorME(sentenceModel);
        TokenizerME tokenizer = new TokenizerME(tokenizerModel);
        POSTaggerME tagger = new POSTaggerME(posModel, POSTagFormat.UD);
        LemmatizerME lemmatizer = new LemmatizerME(lemmaModel);
        Map<String, Integer> nouns = new LinkedHashMap<>();
        Map<String, Integer> verbs = new LinkedHashMap<>();
        int sentences = 0;
        int nounTokens = 0;
        int verbTokens = 0;

        for (String block : text.split("\\n")) {
            for (Span span : detector.sentPosDetect(block)) {
                sentences++;
                String sentence = block.substring(span.getStart(), span.getEnd());
                String[] words = tokenizer.tokenize(sentence);
                if (words.length == 0) {
                    continue;
                }
                String[] tags = tagger.tag(words);
                String[] lemmas = lemmatizer.lemmatize(words, tags);
                for (int i = 0; i < words.length; i++) {
                    String term = lemmaOrWord(lemmas[i], words[i]);
                    if (tags[i].equals("NOUN") || tags[i].equals("PROPN")) {
                        nouns.merge(term, 1, Integer::sum);
                        nounTokens++;
                    } else if (tags[i].equals("VERB")) {
                        verbs.merge(term, 1, Integer::sum);
                        verbTokens++;
                    }
                }
            }
        }
        return new Result(sentences, nounTokens, verbTokens, top(nouns), top(verbs),
                Map.copyOf(nouns), Map.copyOf(verbs));
    }

    private static String lemmaOrWord(String lemma, String word) {
        String candidate = lemma == null || lemma.isBlank() || lemma.equals("O") || lemma.equals("_")
                ? word : lemma;
        return candidate.toLowerCase(Locale.ROOT);
    }

    private static InputStream model(String name) throws IOException {
        InputStream stream = EnglishWordAnalysis.class.getClassLoader().getResourceAsStream(name);
        if (stream == null) {
            throw new IOException("English OpenNLP model missing: " + name);
        }
        return stream;
    }

    private static String top(Map<String, Integer> terms) {
        return terms.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(18).map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(", "));
    }

    record Result(int sentences, int nounTokens, int verbTokens, String topNouns, String topVerbs,
                  Map<String, Integer> nounCounts, Map<String, Integer> verbCounts) {

        String activityNounCandidates() {
            return nounCounts.keySet().stream()
                    .filter(term -> term.endsWith("tion") || term.endsWith("ment") || term.endsWith("ing"))
                    .sorted().limit(15).collect(Collectors.joining(", "));
        }
    }
}
