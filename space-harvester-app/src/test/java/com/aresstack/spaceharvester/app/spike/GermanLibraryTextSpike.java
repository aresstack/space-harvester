package com.aresstack.spaceharvester.app.spike;

import com.aresstack.spaceharvester.application.enrichment.PageText;
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
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Run a bounded German-language noun/verb analysis of public library help documents.
 * Preserve original page references and keep candidate concepts distinct from verified domain facts.
 */
public final class GermanLibraryTextSpike {

    private static final String SOURCE_ID = "staatsbibliothek-berlin";
    private static final int MAX_ANALYSIS_CHARACTERS = 5500;
    private static final List<HelpPage> PAGES = Arrays.asList(
            new HelpPage("anmeldung", "Anmeldung und Bibliotheksausweis",
                    "https://staatsbibliothek-berlin.de/service/anmeldung"),
            new HelpPage("ausleihe", "Bestellung, Ausleihe und Rückgabe",
                    "https://staatsbibliothek-berlin.de/service/hinweise-zu-ausleihe-und-benutzung"),
            new HelpPage("leihfrist", "Leihfrist, Verlängerung und Mahngebühr",
                    "https://staatsbibliothek-berlin.de/service/hinweise-zu-ausleihe-und-benutzung/leihfrist-verlaengerung-mahngebuehr/"),
            new HelpPage("faq", "Häufige Fragen zur Benutzung",
                    "https://staatsbibliothek-berlin.de/service/faq"));

    private final LibraryHelpTextFetcher fetcher = new LibraryHelpTextFetcher();

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2) {
            throw new IllegalArgumentException("Usage: <outputDirectory> <pinnedGermanModelDirectory>");
        }
        new GermanLibraryTextSpike().run(Paths.get(arguments[0]), Paths.get(arguments[1]));
    }

    private void run(Path outputDirectory, Path modelDirectory) throws Exception {
        Files.createDirectories(outputDirectory);
        List<HelpText> texts = fetchPages(outputDirectory);
        if (texts.isEmpty()) {
            throw new IllegalStateException("No German library text could be retrieved");
        }
        GermanDomainCandidateAnalyzer analyzer = new GermanDomainCandidateAnalyzer(modelDirectory);
        GermanDomainCandidateAnalyzer.Report report = analyzer.analyze(texts);
        Files.write(outputDirectory.resolve("analysis.md"), report.markdown.getBytes(StandardCharsets.UTF_8));
        Files.write(outputDirectory.resolve("terms.tsv"), report.terms.getBytes(StandardCharsets.UTF_8));
        Files.write(outputDirectory.resolve("relations.tsv"), report.relations.getBytes(StandardCharsets.UTF_8));
        System.out.println(report.markdown);
        System.out.println("OUTPUT " + outputDirectory.toAbsolutePath());
    }

    private List<HelpText> fetchPages(Path directory) throws Exception {
        List<HelpText> result = new ArrayList<HelpText>();
        List<String> sources = new ArrayList<String>();
        sources.add("pageId\ttitle\turl\tcharacters\tanalyzedCharacters\tinputSha256");
        for (HelpPage page : PAGES) {
            String fullText = fetcher.fetch(page.url);
            String limitedText = limitAtBlockBoundary(fullText);
            Files.write(directory.resolve(page.id + ".txt"), fullText.getBytes(StandardCharsets.UTF_8));
            sources.add(page.id + "\t" + page.title + "\t" + page.url + "\t"
                    + fullText.length() + "\t" + limitedText.length() + "\t"
                    + new PageText("library-help-html-text/v1", fullText, Collections.emptyList()).inputSha256());
            result.add(new HelpText(page, limitedText));
            System.out.println("FETCHED " + page.id + " chars=" + fullText.length()
                    + " analyzed=" + limitedText.length());
        }
        Files.write(directory.resolve("sources.tsv"), sources, StandardCharsets.UTF_8);
        return result;
    }

    private static String limitAtBlockBoundary(String text) {
        if (text.length() <= MAX_ANALYSIS_CHARACTERS) {
            return text;
        }
        int boundary = text.lastIndexOf('\n', MAX_ANALYSIS_CHARACTERS);
        return text.substring(0, boundary > MAX_ANALYSIS_CHARACTERS / 2
                ? boundary : MAX_ANALYSIS_CHARACTERS);
    }

    private static final class HelpPage {
        private final String id;
        private final String title;
        private final URI url;

        private HelpPage(String id, String title, String url) {
            this.id = id;
            this.title = title;
            this.url = URI.create(url);
        }
    }

    private static final class HelpText {
        private final HelpPage page;
        private final String text;

        private HelpText(HelpPage page, String text) {
            this.page = page;
            this.text = text;
        }
    }

    /**
     * Project the primary TYPO3 content region onto visible text without executing
     * scripts or following links inside the documents.
     */
    private static final class LibraryHelpTextFetcher {

        String fetch(URI url) throws IOException {
            if (!"https".equals(url.getScheme())
                    || !"staatsbibliothek-berlin.de".equals(url.getHost())) {
                throw new IllegalArgumentException("Only public Staatsbibliothek Berlin pages are permitted");
            }
            Document document = Jsoup.connect(url.toString())
                    .userAgent("SpaceHarvester-GermanLibraryTextSpike/1.0")
                    .timeout(25000)
                    .maxBodySize(2_000_000)
                    .get();
            Element content = document.selectFirst(".maincontent-wrapper");
            if (content == null) {
                throw new IOException("Primary content region not found: " + url);
            }
            content.select("script, style, nav, aside, form, iframe, noscript, .d-none, .breadcrumb, .sr-only").remove();
            List<String> lines = new ArrayList<String>();
            for (Element element : content.select("h1, h2, h3, h4, p, li, dt, dd")) {
                if ("p".equals(element.tagName()) && element.parents().stream()
                        .anyMatch(parent -> "li".equals(parent.tagName()))) {
                    continue;
                }
                String value = element.text().trim().replaceAll("\\s+", " ");
                if (value.length() > 3 && (lines.isEmpty() || !value.equals(lines.get(lines.size() - 1)))) {
                    lines.add(value);
                }
            }
            String text = String.join("\n", lines);
            if (text.length() < 350) {
                throw new IOException("Too little primary German text extracted: " + url);
            }
            return text;
        }
    }

    /**
     * Derive candidate nouns, verbal actions, and grammatical relations from one
     * shared, pinned German parser instance; retain sentence-level evidence.
     */
    private static final class GermanDomainCandidateAnalyzer {

        private final DependencyAnalysis dependencyAnalysis;
        private final ObjectCentricExtraction extraction =
                new ObjectCentricExtraction(ExtractionSettings.defaults());

        private GermanDomainCandidateAnalyzer(Path modelDirectory) throws Exception {
            dependencyAnalysis = new DependencyAnalysis(OpenNlpGermanPreprocessor.fromClasspath(),
                    MaltModelLoader.loadPinned(modelDirectory));
        }

        Report analyze(List<HelpText> pages) {
            Map<String, TermEvidence> nouns = new LinkedHashMap<String, TermEvidence>();
            Map<String, TermEvidence> verbs = new LinkedHashMap<String, TermEvidence>();
            List<String> triples = new ArrayList<String>();
            List<String> summaries = new ArrayList<String>();
            int sentenceCount = 0;
            for (HelpText page : pages) {
                ObjectCentricAnalysis analysis = analyzePage(page);
                int sentences = analysis.document().sentences().size();
                sentenceCount += sentences;
                summaries.add("| " + page.page.id + " | " + sentences + " | "
                        + analysis.mentions().size() + " | " + analysis.statements().size() + " |");
                collectNouns(page, analysis, nouns);
                collectVerbs(page, analysis, verbs);
                collectTriples(page, analysis, triples);
            }
            String markdown = renderMarkdown(pages.size(), sentenceCount, summaries, nouns, verbs, triples);
            return new Report(markdown, toTsv(nouns, verbs), "pageId\tactor\tverb\tobject\tevidence\n"
                    + String.join("\n", triples) + "\n");
        }

        private ObjectCentricAnalysis analyzePage(HelpText page) {
            DocumentRef ref = new DocumentRef(SOURCE_ID, page.page.id, null);
            ParsedDocument parsed = dependencyAnalysis.analyze(ref, page.text);
            return extraction.analyze(parsed);
        }

        private void collectNouns(HelpText page, ObjectCentricAnalysis analysis,
                                  Map<String, TermEvidence> nouns) {
            for (Mention mention : analysis.mentions()) {
                if (mention.kind() == Mention.Kind.NOUN || mention.kind() == Mention.Kind.PROPER_NOUN) {
                    add(nouns, term(mention.key()), page.page.id);
                }
            }
        }

        private void collectVerbs(HelpText page, ObjectCentricAnalysis analysis,
                                  Map<String, TermEvidence> verbs) {
            for (Statement statement : analysis.statements()) {
                String value = statement.predicate().lemma() == null
                        ? statement.predicate().text() : statement.predicate().lemma();
                add(verbs, term(value), page.page.id);
            }
        }

        private void collectTriples(HelpText page, ObjectCentricAnalysis analysis, List<String> triples) {
            for (Statement statement : analysis.statements()) {
                Argument actor = statement.core(Role.ACTOR);
                Argument object = statement.core(Role.AFFECTED_OBJECT);
                if (actor == null || object == null) {
                    continue;
                }
                String sentence = analysis.evidence(statement).text().replaceAll("\\s+", " ");
                String line = page.page.id + "\t" + normalizeField(analysis.mention(actor.mention()).text()) + "\t"
                        + normalizeField(statement.predicate().text()) + "\t"
                        + normalizeField(analysis.mention(object.mention()).text()) + "\t"
                        + normalizeField(sentence);
                triples.add(line);
            }
        }

        private static String normalizeField(String value) {
            return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
        }

        private static String term(String value) {
            return value == null ? "" : value.toLowerCase(Locale.GERMAN).trim();
        }

        private static void add(Map<String, TermEvidence> destination, String word, String documentId) {
            if (word.isEmpty()) {
                return;
            }
            TermEvidence count = destination.get(word);
            if (count == null) {
                count = new TermEvidence();
                destination.put(word, count);
            }
            count.occurrences++;
            count.pages.add(documentId);
        }

        private static String toTsv(Map<String, TermEvidence> nouns, Map<String, TermEvidence> verbs) {
            StringBuilder tsv = new StringBuilder("kind\tlemma\toccurrences\tpages\tpageIds\tnominalizedCandidate\n");
            appendTerms(tsv, "NOUN", nouns);
            appendTerms(tsv, "VERB", verbs);
            return tsv.toString();
        }

        private static void appendTerms(StringBuilder tsv, String kind, Map<String, TermEvidence> terms) {
            for (Map.Entry<String, TermEvidence> entry : ordered(terms)) {
                String lemma = entry.getKey();
                TermEvidence tally = entry.getValue();
                tsv.append(kind).append('\t').append(lemma).append('\t').append(tally.occurrences)
                        .append('\t').append(tally.pages.size()).append('\t')
                        .append(String.join(",", tally.pages)).append('\t')
                        .append("NOUN".equals(kind) && nominalizedCandidate(lemma)).append('\n');
            }
        }

        private static String renderMarkdown(int pages, int sentences, List<String> summaries,
                                             Map<String, TermEvidence> nouns,
                                             Map<String, TermEvidence> verbs, List<String> triples) {
            StringBuilder result = new StringBuilder();
            result.append("# Deutsche Bibliotheksdomäne: NLP-Exploration\n\n")
                    .append("Ungeprüfte sprachliche Kandidaten, keine bestätigten Geschäftsobjekte oder Use Cases.\n\n")
                    .append("Seiten: ").append(pages).append("; analysierte Sätze: ")
                    .append(sentences).append(".\n\n")
                    .append("## Analyse pro Seite\n\n")
                    .append("| Seite | Sätze | Erwähnungen | Aussagen |\n|---|---:|---:|---:|\n")
                    .append(String.join("\n", summaries)).append("\n\n");
            result.append("## Häufigste Nomen\n\n").append(top(nouns, 25)).append("\n\n");
            result.append("## Häufigste Verben\n\n").append(top(verbs, 25)).append("\n\n");
            result.append("## Nominalisierte Tätigkeiten: Formkandidaten\n\n")
                    .append(ordered(nouns).stream().map(Map.Entry::getKey)
                            .filter(GermanDomainCandidateAnalyzer::nominalizedCandidate).limit(35)
                            .collect(Collectors.joining(", "))).append("\n\n");
            result.append("## Erste syntaktische Relationenkandidaten\n\n")
                    .append("| Seite | Akteur | Prädikat | Objekt | Beleg |\n|---|---|---|---|---|\n");
            for (String triple : triples.stream().limit(15).collect(Collectors.toList())) {
                String[] fields = triple.split("\\t", -1);
                result.append("| ").append(String.join(" | ", Arrays.asList(fields).stream()
                        .map(item -> item.replace("|", "\\|")).collect(Collectors.toList())))
                        .append(" |\n");
            }
            result.append("\nDie maschinellen Satzbeziehungen müssen anhand des Belegs überprüft werden. ")
                    .append("Häufigkeit bezeichnet grammatische Erwähnungen, keine fachliche Wichtigkeit.\n");
            return result.toString();
        }

        private static boolean nominalizedCandidate(String lemma) {
            return lemma.length() > 5 && (lemma.endsWith("ung") || lemma.endsWith("ierung")
                    || lemma.endsWith("tion") || lemma.endsWith("nahme"));
        }

        private static String top(Map<String, TermEvidence> terms, int limit) {
            return ordered(terms).stream().limit(limit)
                    .map(entry -> entry.getKey() + " (" + entry.getValue().occurrences
                            + "×, " + entry.getValue().pages.size() + " Seiten)")
                    .collect(Collectors.joining(", "));
        }

        private static List<Map.Entry<String, TermEvidence>> ordered(Map<String, TermEvidence> terms) {
            List<Map.Entry<String, TermEvidence>> ordered =
                    new ArrayList<Map.Entry<String, TermEvidence>>(terms.entrySet());
            Collections.sort(ordered, Comparator
                    .<Map.Entry<String, TermEvidence>>comparingInt(entry -> entry.getValue().occurrences)
                    .reversed().thenComparing(Map.Entry::getKey));
            return ordered;
        }

        private static final class TermEvidence {
            private int occurrences;
            private final Set<String> pages = new LinkedHashSet<String>();
        }

        private static final class Report {
            private final String markdown;
            private final String terms;
            private final String relations;

            private Report(String markdown, String terms, String relations) {
                this.markdown = markdown;
                this.terms = terms;
                this.relations = relations;
            }
        }
    }
}
