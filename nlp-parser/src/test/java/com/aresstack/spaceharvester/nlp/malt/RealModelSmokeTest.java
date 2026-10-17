package com.aresstack.spaceharvester.nlp.malt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aresstack.spaceharvester.nlp.extraction.Argument;
import com.aresstack.spaceharvester.nlp.extraction.CoreRelation;
import com.aresstack.spaceharvester.nlp.extraction.ExtractionSettings;
import com.aresstack.spaceharvester.nlp.extraction.ObjectCentricAnalysis;
import com.aresstack.spaceharvester.nlp.extraction.ObjectCentricExtraction;
import com.aresstack.spaceharvester.nlp.extraction.Role;
import com.aresstack.spaceharvester.nlp.extraction.SalienceProjection;
import com.aresstack.spaceharvester.nlp.extraction.Statement;
import com.aresstack.spaceharvester.nlp.model.Sha256;
import com.aresstack.spaceharvester.nlp.opennlp.OpenNlpGermanPreprocessor;
import com.aresstack.spaceharvester.nlp.syntax.Component;
import com.aresstack.spaceharvester.nlp.syntax.ConllU;
import com.aresstack.spaceharvester.nlp.syntax.DocumentRef;
import com.aresstack.spaceharvester.nlp.syntax.ParsedDocument;
import com.aresstack.spaceharvester.nlp.syntax.Sentence;
import com.aresstack.spaceharvester.nlp.syntax.Token;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Rauchtest mit einem echten deutschen Modell, nur mit {@code -PnlpModelDir=<Verzeichnis>} (entpacktes Release aus
 * #99, siehe {@code downloadGermanParserModel}). Parst die Gold-Sätze einmal mit Gold-Wortarten und einmal mit von
 * OpenNLP vorhergesagten Wortarten, getrennt bewertet, und misst Laden, Speicher und Durchsatz. Der Bericht steht in
 * {@code build/reports/nlp-parser/smoke.txt}.
 */
@EnabledIfSystemProperty(named = "nlp.parser.modelDir", matches = ".+")
class RealModelSmokeTest {

    private static final List<String> GOLD = List.of("reference.conllu", "passive.conllu", "coordination.conllu",
            "pronouns.conllu", "dative-time.conllu", "subordinate.conllu", "repeated.conllu");

    private static MaltDependencyParser parser;
    private static OpenNlpGermanPreprocessor preprocessor;
    private static final StringBuilder REPORT = new StringBuilder();

    @BeforeAll
    static void load() throws IOException {
        Path dir = Path.of(System.getProperty("nlp.parser.modelDir"));
        Runtime runtime = Runtime.getRuntime();
        System.gc();
        long before = runtime.totalMemory() - runtime.freeMemory();
        if (GermanParserModelPin.expected().isPresent()) {
            parser = MaltModelLoader.loadPinned(dir);
        } else {
            // Noch kein Release aus #99: Modell aus dem Verzeichnis ungepinnt prüfen (nur für den Spike)
            ModelManifest manifest = MaltModelLoader.readManifest(dir.resolve(MaltModelLoader.MANIFEST));
            parser = MaltModelLoader.load(dir, new ExpectedModel(manifest.modelId(), manifest.modelVersion(),
                    manifest.configName(), Sha256.of(dir.resolve(manifest.mcoFile()))));
            line("WARNUNG: kein Modell in german-parser-model.properties hinterlegt; Prüfsumme aus dem Verzeichnis");
        }
        System.gc();
        long after = runtime.totalMemory() - runtime.freeMemory();
        long started = System.nanoTime();
        preprocessor = OpenNlpGermanPreprocessor.fromClasspath();
        line("Modell: " + parser.component().model() + " (" + parser.component().modelSha256() + ")");
        line("Laden MaltParser-Modell: " + parser.loadMillis() + " ms, Heap danach etwa +" + (after - before) / 1_000_000
                + " MB; OpenNLP-Modelle: " + (System.nanoTime() - started) / 1_000_000 + " ms");
        line("Labels im Modell: " + parser.manifest().labels());
    }

    @Test
    void goldAndPredictedPartOfSpeechAreEvaluatedSeparately() throws IOException {
        int[] gold = new int[3];
        int[] predicted = new int[3];
        for (String name : GOLD) {
            ParsedDocument document = gold(name);
            for (Sentence expected : document.sentences()) {
                Sentence withGoldTags = parser.parse(expected.withTokens(expected.tokens().stream()
                        .map(t -> t.withTags(null, t.upos(), null, Map.of()).withDependency(Token.NO_HEAD, null))
                        .toList()));
                score(expected, withGoldTags, gold);
                assertTrue(parser.manifest().labels().containsAll(
                        withGoldTags.tokens().stream().map(Token::deprel).toList()), "nur trainierte Labels");
                Sentence tagged = preprocessor.analyze(expected.text()).get(0);
                if (sameWords(expected, tagged)) {
                    score(expected, parser.parse(tagged), predicted);
                } else {
                    line("Tokenisierung weicht ab, nicht bewertet: " + expected.text());
                }
            }
        }
        line(String.format("Gold-Wortarten:   UAS %.1f %%, LAS %.1f %% (%d Wörter)", 100.0 * gold[1] / gold[0],
                100.0 * gold[2] / gold[0], gold[0]));
        line(String.format("OpenNLP-Wortarten: UAS %.1f %%, LAS %.1f %% (%d Wörter)",
                100.0 * predicted[1] / predicted[0], 100.0 * predicted[2] / predicted[0], predicted[0]));
    }

    @Test
    void referenceSentenceGivesTheCoreRelationFromRawText() {
        String text = "Der Sachbearbeiter übermittelt dem Vorgesetzten am Montag den Beleg des Kunden.";
        ParsedDocument document = analyze(text);
        line("Referenzsatz mit OpenNLP + MaltParser:\n" + ConllU.write(document));
        ObjectCentricAnalysis analysis = new ObjectCentricExtraction(ExtractionSettings.defaults()).analyze(document);
        CoreRelation core = SalienceProjection.project(analysis).core().get(0);
        assertEquals("Der Sachbearbeiter", core.subjectText());
        assertEquals("übermitteln", core.predicateLemma());
        assertEquals("den Beleg", core.objectText());
        // Empfänger, Zeitkontext und nominaler Bezug bleiben an derselben Aussage bzw. am Beleg referenzierbar
        Statement statement = analysis.statement(core.statement());
        assertEquals("dem Vorgesetzten", mentionText(analysis, statement.withRole(Role.RECIPIENT)));
        assertEquals("Montag", mentionText(analysis, statement.withRole(Role.TIME)));
        assertEquals("des Kunden", analysis.mention(analysis.attachmentsOf(
                statement.core(Role.AFFECTED_OBJECT).mention()).get(0).dependent()).text());
        line("Aussage: " + statement.arguments() + " " + statement.findings());
    }

    @Test
    void throughputSingleAndParallel() throws Exception {
        List<String> texts = new ArrayList<>();
        for (String name : GOLD) {
            gold(name).sentences().forEach(sentence -> texts.add(sentence.text()));
        }
        int rounds = 50;
        long started = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            texts.forEach(this::analyze);
        }
        long single = System.nanoTime() - started;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            started = System.nanoTime();
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < rounds; i++) {
                for (String text : texts) {
                    futures.add(pool.submit(() -> analyze(text)));
                }
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }
        long parallel = System.nanoTime() - started;
        int count = rounds * texts.size();
        line(String.format("Durchsatz Rohtext → Baum: %.2f ms/Satz einzeln, %.2f ms/Satz mit 4 Threads (%d Sätze)",
                single / 1e6 / count, parallel / 1e6 / count, count));
    }

    private static String mentionText(ObjectCentricAnalysis analysis, List<Argument> arguments) {
        return arguments.isEmpty() ? null : analysis.mention(arguments.get(0).mention()).text();
    }

    private ParsedDocument analyze(String text) {
        List<Sentence> sentences = preprocessor.analyze(text).stream().map(parser::parse).toList();
        List<Component> components = new ArrayList<>(preprocessor.components());
        components.add(parser.component());
        return new ParsedDocument(new DocumentRef("smoke", "text", null), text, sentences, components);
    }

    private static boolean sameWords(Sentence a, Sentence b) {
        return a.tokens().stream().map(Token::form).toList().equals(b.tokens().stream().map(Token::form).toList());
    }

    private static void score(Sentence expected, Sentence actual, int[] counts) {
        for (Token token : expected.tokens()) {
            if ("punct".equals(token.deprel())) {
                continue;
            }
            Token parsed = actual.token(token.id());
            counts[0]++;
            if (parsed.head() == token.head()) {
                counts[1]++;
                if (token.deprel().equals(parsed.deprel())) {
                    counts[2]++;
                }
            }
        }
    }

    private static ParsedDocument gold(String name) throws IOException {
        try (InputStream input = RealModelSmokeTest.class.getResourceAsStream(
                "/com/aresstack/spaceharvester/nlp/extraction/" + name)) {
            return ConllU.read(new DocumentRef("gold", name, null),
                    new String(input.readAllBytes(), StandardCharsets.UTF_8), List.of());
        }
    }

    private static synchronized void line(String text) {
        System.out.println(text);
        REPORT.append(text).append('\n');
        try {
            Path report = Path.of("build", "reports", "nlp-parser", "smoke.txt");
            Files.createDirectories(report.getParent());
            Files.writeString(report, REPORT.toString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
