package com.aresstack.spaceharvester.persistence.files;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.assets.DocumentKey;
import com.aresstack.spaceharvester.application.assets.PartId;
import com.aresstack.spaceharvester.application.evidence.SourceLocationEvidence;
import com.aresstack.spaceharvester.application.knowledge.PageStatementAnalysis;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileKnowledgeGraphStoreTest {

    @TempDir
    Path directory;

    @Test
    void thePageNameIsTheHashOfItsDocumentKey() {
        // ADR 0026, Schlüsselform Punkt 7: der Graph legt Dokumente flach nach DocumentKey#hash() ab, die Seite bleibt.
        PageKey key = new PageKey("confluence", "4711");

        assertThat(FileKnowledgeGraphStore.name(key))
                .isEqualTo(com.aresstack.spaceharvester.application.assets.DocumentKey.page(key).hash() + ".json");
    }

    @Test
    void anAnalysisSurvivesARestartAndTheSyntaxDetailsAreReadOnDemand() {
        PageStatementAnalysis analysis = analysis("wiki", "1", "Beleg");
        new FileKnowledgeGraphStore(directory).replace(analysis);

        FileKnowledgeGraphStore restarted = new FileKnowledgeGraphStore(directory);

        assertThat(restarted.find(analysis.key()))
                .contains(analysis.withResult(analysis.result().withoutConllu()));
        assertThat(restarted.all()).hasSize(1);
        assertThat(restarted.conllu(analysis.key())).contains("1\tBeleg\t_\t_\t_\t_\t0\troot\t_\t_\n");
    }

    @Test
    void replacingIsIdempotentAndLeavesNoTemporaryFiles() throws IOException {
        FileKnowledgeGraphStore store = new FileKnowledgeGraphStore(directory);
        store.replace(analysis("wiki", "1", "Beleg"));
        store.replace(analysis("wiki", "1", "Antrag"));
        store.replace(analysis("wiki", "1", "Antrag"));
        store.replace(analysis("handbuch", "1", "Beleg"));

        assertThat(store.all()).extracting(PageStatementAnalysis::key)
                .containsExactlyInAnyOrder(new PageKey("wiki", "1"), new PageKey("handbuch", "1"));
        assertThat(new FileKnowledgeGraphStore(directory).find(new PageKey("wiki", "1")).orElseThrow().result()
                .mentions()).extracting(ParsedStatements.Mention::text).containsExactly("Sachbearbeiter", "Antrag");
        try (Stream<Path> files = Files.list(directory.resolve(FileKnowledgeGraphStore.PAGES))) {
            assertThat(files.map(path -> path.getFileName().toString()))
                    .hasSize(2).allSatisfy(name -> assertThat(name).endsWith(".json").doesNotContain("wiki"));
        }
    }

    @Test
    void anUnreadableFileIsSkippedAndTheOthersStay() throws IOException {
        FileKnowledgeGraphStore store = new FileKnowledgeGraphStore(directory);
        store.replace(analysis("wiki", "1", "Beleg"));
        Files.writeString(directory.resolve(FileKnowledgeGraphStore.PAGES).resolve("kaputt.json"), "{\"schema\":",
                StandardCharsets.UTF_8);

        assertThat(new FileKnowledgeGraphStore(directory).all()).extracting(PageStatementAnalysis::key)
                .containsExactly(new PageKey("wiki", "1"));
    }

    @Test
    void aFileWithAnInvalidTimestampIsSkippedAndDoesNotBlockTheStore() throws IOException {
        FileKnowledgeGraphStore store = new FileKnowledgeGraphStore(directory);
        store.replace(analysis("wiki", "1", "Beleg"));
        store.replace(analysis("wiki", "2", "Quittung"));
        Path broken;
        try (var files = Files.list(directory.resolve(FileKnowledgeGraphStore.PAGES))) {
            broken = files.filter(file -> {
                try {
                    return Files.readString(file, StandardCharsets.UTF_8).contains("Quittung");
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).findFirst().orElseThrow();
        }
        String json = Files.readString(broken, StandardCharsets.UTF_8);
        Files.writeString(broken, json.replaceFirst("\"analyzedAt\"\\s*:\\s*\"[^\"]*\"",
                "\"analyzedAt\":\"gestern\""), StandardCharsets.UTF_8);

        FileKnowledgeGraphStore reopened = new FileKnowledgeGraphStore(directory);
        assertThat(reopened.all()).extracting(PageStatementAnalysis::key).containsExactly(new PageKey("wiki", "1"));
        reopened.replace(analysis("wiki", "2", "Quittung"));
        assertThat(new FileKnowledgeGraphStore(directory).all()).hasSize(2);
    }

    @Test
    void qualityParagraphAndReviewSurviveARestart() {
        PageStatementAnalysis base = analysis("wiki", "1", "Beleg");
        ParsedStatements.Statement statement = base.result().statements().get(0);
        ParsedStatements.Statement plausible = assessed(statement, statement.id(), 2,
                ParsedStatements.Quality.PLAUSIBLE, List.of(), ParsedStatements.Review.CONFIRMED);
        ParsedStatements.Statement invalid = assessed(statement, statement.id() + "x", 0,
                ParsedStatements.Quality.INVALID, List.of("NO_PARTICIPANT", "UNRESOLVED_PRONOUN"),
                ParsedStatements.Review.UNREVIEWED);
        PageStatementAnalysis analysis = base.withResult(withStatements(base.result(), List.of(plausible, invalid)));
        new FileKnowledgeGraphStore(directory).replace(analysis);

        List<ParsedStatements.Statement> read = new FileKnowledgeGraphStore(directory).find(analysis.key())
                .orElseThrow().result().statements();

        assertThat(read).containsExactly(plausible, invalid);
    }

    @Test
    void featuresSurviveARestartAndOldFilesReadAsUnknown() throws IOException {
        PageStatementAnalysis base = analysis("wiki", "1", "Beleg");
        ParsedStatements.Statement s = base.result().statements().get(0);
        ParsedStatements.Features features = new ParsedStatements.Features(ParsedStatements.Negation.NEGATED,
                ParsedStatements.Modality.SOLL, ParsedStatements.Temporality.GEPLANT);
        ParsedStatements.Statement withFeatures = new ParsedStatements.Statement(s.id(), s.sentence(), s.start(),
                s.end(), s.predicateText(), s.predicateLemma(), s.voice(), s.clause(), s.core(), s.arguments(),
                s.findings(), 0, ParsedStatements.Quality.PLAUSIBLE, List.of(), ParsedStatements.Review.UNREVIEWED,
                features);
        new FileKnowledgeGraphStore(directory).replace(base.withResult(withStatements(base.result(),
                List.of(withFeatures))));

        assertThat(new FileKnowledgeGraphStore(directory).find(base.key()).orElseThrow().result().statements())
                .containsExactly(withFeatures);

        new FileKnowledgeGraphStore(directory).replace(base);
        Path file;
        try (Stream<Path> files = Files.list(directory.resolve(FileKnowledgeGraphStore.PAGES))) {
            file = files.findFirst().orElseThrow();
        }
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).doesNotContain("negation", "modality",
                "temporality");
        assertThat(new FileKnowledgeGraphStore(directory).find(base.key()).orElseThrow().result().statements()
                .get(0).features()).isEqualTo(ParsedStatements.Features.UNKNOWN);
    }

    @Test
    void theArticleOfARoleNounSurvivesARestartAndIsMissingWithoutOne() throws IOException {
        PageStatementAnalysis base = analysis("wiki", "1", "Beleg");
        ParsedStatements.Statement s = base.result().statements().get(0);
        ParsedStatements.Features features = new ParsedStatements.Features(ParsedStatements.Negation.NEGATED,
                ParsedStatements.Modality.NONE, ParsedStatements.Temporality.GILT, ParsedStatements.Article.KEIN);
        ParsedStatements.Statement withArticle = new ParsedStatements.Statement(s.id(), s.sentence(), s.start(),
                s.end(), s.predicateText(), s.predicateLemma(), s.voice(), s.clause(), s.core(), s.arguments(),
                s.findings(), 0, ParsedStatements.Quality.PLAUSIBLE, List.of(), ParsedStatements.Review.UNREVIEWED,
                features);
        new FileKnowledgeGraphStore(directory).replace(base.withResult(withStatements(base.result(),
                List.of(withArticle))));

        assertThat(new FileKnowledgeGraphStore(directory).find(base.key()).orElseThrow().result().statements()
                .get(0).features().article()).isEqualTo(ParsedStatements.Article.KEIN);

        ParsedStatements.Statement without = new ParsedStatements.Statement(s.id(), s.sentence(), s.start(),
                s.end(), s.predicateText(), s.predicateLemma(), s.voice(), s.clause(), s.core(), s.arguments(),
                s.findings(), 0, ParsedStatements.Quality.PLAUSIBLE, List.of(), ParsedStatements.Review.UNREVIEWED,
                new ParsedStatements.Features(ParsedStatements.Negation.AFFIRMED, ParsedStatements.Modality.NONE,
                        ParsedStatements.Temporality.GILT));
        new FileKnowledgeGraphStore(directory).replace(base.withResult(withStatements(base.result(),
                List.of(without))));
        Path file;
        try (Stream<Path> files = Files.list(directory.resolve(FileKnowledgeGraphStore.PAGES))) {
            file = files.findFirst().orElseThrow();
        }
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).doesNotContain("\"article\"");
        assertThat(new FileKnowledgeGraphStore(directory).find(base.key()).orElseThrow().result().statements())
                .containsExactly(without);
    }

    @Test
    void originFlagAndSimplifiedSentenceOfTheStageSurviveARestartAndOriginalsWriteNothing() throws IOException {
        PageStatementAnalysis base = analysis("wiki", "1", "Beleg");
        ParsedStatements.Statement s = base.result().statements().get(0);
        ParsedStatements.Statement fromStage = new ParsedStatements.Statement(s.id(), s.sentence(), s.start(),
                s.end(), s.predicateText(), s.predicateLemma(), s.voice(), s.clause(), s.core(), s.arguments(),
                s.findings(), 0, ParsedStatements.Quality.PLAUSIBLE, List.of(), ParsedStatements.Review.UNREVIEWED,
                ParsedStatements.Features.UNKNOWN, ParsedStatements.Derivation.vorstufe(
                        ParsedStatements.StageFlag.CONDITION, "Die Stelle prüft den Beleg.").withFeaturesFromOriginal()
                        .withSection(new ParsedStatements.Section(0, 2)));
        new FileKnowledgeGraphStore(directory).replace(base.withResult(withStatements(base.result(),
                List.of(fromStage))).withStage("always|fake:m1|prompt-1"));

        ParsedStatements.Statement read = new FileKnowledgeGraphStore(directory).find(base.key()).orElseThrow()
                .result().statements().get(0);
        assertThat(read).isEqualTo(fromStage);
        assertThat(read.derivation().stageFlag()).isEqualTo(ParsedStatements.StageFlag.CONDITION);
        assertThat(read.derivation().featuresFrom()).isEqualTo(ParsedStatements.Origin.ORIGINAL);
        assertThat(read.derivation().section()).as("Abschnitt für den Prüfschritt")
                .isEqualTo(new ParsedStatements.Section(0, 2));
        assertThat(new FileKnowledgeGraphStore(directory).find(base.key()).orElseThrow().stage())
                .isEqualTo("always|fake:m1|prompt-1");

        new FileKnowledgeGraphStore(directory).replace(base);
        Path file;
        try (Stream<Path> files = Files.list(directory.resolve(FileKnowledgeGraphStore.PAGES))) {
            file = files.findFirst().orElseThrow();
        }
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).doesNotContain("origin", "stageFlag",
                "simplifiedSentence", "featuresFrom", "\"stage\"", "\"section\"");
        assertThat(new FileKnowledgeGraphStore(directory).find(base.key()).orElseThrow().result().statements()
                .get(0).derivation()).isEqualTo(ParsedStatements.Derivation.ORIGINAL);
    }

    @Test
    void verdictsAndCorrectionsOfTheCheckSurviveARestart() throws IOException {
        PageStatementAnalysis base = analysis("wiki", "1", "Beleg");
        ParsedStatements.Statement s = base.result().statements().get(0);
        ParsedStatements.Statement checked = s.withCheck(new ParsedStatements.Check(
                ParsedStatements.Verdict.UNVOLLSTAENDIG, "fake:m1|prompt-1", true));
        ParsedStatements.Statement correction = new ParsedStatements.Statement(s.id() + "m", s.sentence(), s.start(),
                s.end(), s.predicateText(), s.predicateLemma(), s.voice(), s.clause(), false, s.arguments(),
                s.findings(), 0, ParsedStatements.Quality.PLAUSIBLE, List.of(), ParsedStatements.Review.UNREVIEWED,
                ParsedStatements.Features.UNKNOWN, ParsedStatements.Derivation.modell("Die Stelle prüft den Beleg.",
                        s.id(), ParsedStatements.Origin.MODELL));
        new FileKnowledgeGraphStore(directory).replace(base.withResult(withStatements(base.result(),
                List.of(checked, correction))));

        List<ParsedStatements.Statement> read = new FileKnowledgeGraphStore(directory).find(base.key())
                .orElseThrow().result().statements();

        assertThat(read).containsExactly(checked, correction);
        assertThat(read.get(0).check().corrected()).isTrue();
        assertThat(read.get(1).derivation().corrects()).isEqualTo(s.id());
    }

    @Test
    void statementsOfTheProgramAnalysisKeepTheirSourceLocationAcrossARestart() throws IOException {
        // ADR 0041, Punkt 7: Beleg einer Aussage aus Programmcode ist der Quellstellenbeleg, nie der Hypothesensatz
        DocumentKey program = DocumentKey.page(new PageKey("ftp-test", "TEST.COBOL(ANTRAG01)"));
        DocumentKey copybook = DocumentKey.page(new PageKey("ftp-test", "TEST.COPY(ANTRAGCP)"));
        SourceLocationEvidence source = new SourceLocationEvidence(program, "p1",
                List.of(new SourceLocationEvidence.CodeSite(program, "p1", 120, 130),
                        new SourceLocationEvidence.CodeSite(copybook, "c1", 12, 12)),
                List.of(new SourceLocationEvidence.ParserFact("CONTROL", "IF KZ-STATUS = 'A'", 0, 125, 125)),
                List.of(SourceLocationEvidence.Dependency.resolved("ANTRAGCP", copybook, "c1"),
                        SourceLocationEvidence.Dependency.missing("DATUMCP")), "cobol-program@3");
        PageStatementAnalysis base = analysis("ftp-test", "TEST.COBOL(ANTRAG01)", "Antrag");
        ParsedStatements.Statement hypothesis = base.result().statements().get(0).withDerivation(
                ParsedStatements.Derivation.programm("Der Sachbearbeiter schließt den Antrag ab.", source));
        new FileKnowledgeGraphStore(directory).replace(base.withResult(withStatements(base.result(),
                List.of(hypothesis))));

        List<ParsedStatements.Statement> read = new FileKnowledgeGraphStore(directory).find(base.key())
                .orElseThrow().result().statements();

        assertThat(read).containsExactly(hypothesis);
        assertThat(read.get(0).derivation().source().fingerprint()).isEqualTo(source.fingerprint());
    }

    @Test
    void aProgramStatementWithoutReadableSourceLocationIsSkipped() throws IOException {
        PageStatementAnalysis base = analysis("wiki", "1", "Beleg");
        new FileKnowledgeGraphStore(directory).replace(base);
        Path file;
        try (Stream<Path> files = Files.list(directory.resolve(FileKnowledgeGraphStore.PAGES))) {
            file = files.findFirst().orElseThrow();
        }
        String json = Files.readString(file, StandardCharsets.UTF_8);
        Files.writeString(file, json.replace("\"review\"", "\"origin\" : \"PROGRAMM\", "
                + "\"simplifiedSentence\" : \"Der Sachbearbeiter prüft den Beleg.\", \"review\""),
                StandardCharsets.UTF_8);

        assertThat(new FileKnowledgeGraphStore(directory).find(base.key()).orElseThrow().result().statements())
                .isEmpty();
    }

    @Test
    void anUnreadableCorrectionIsSkippedAndNeverLoadedAsAnOriginal() throws IOException {
        PageStatementAnalysis base = analysis("wiki", "1", "Beleg");
        new FileKnowledgeGraphStore(directory).replace(base);
        Path file;
        try (Stream<Path> files = Files.list(directory.resolve(FileKnowledgeGraphStore.PAGES))) {
            file = files.findFirst().orElseThrow();
        }
        String json = Files.readString(file, StandardCharsets.UTF_8);
        Files.writeString(file, json.replace("\"review\"", "\"origin\" : \"MODELL\", \"review\""),
                StandardCharsets.UTF_8);

        assertThat(new FileKnowledgeGraphStore(directory).find(base.key()).orElseThrow().result().statements())
                .isEmpty();
    }

    @Test
    void aFileWithoutOrWithUnknownQualityFieldsStillLoads() throws IOException {
        new FileKnowledgeGraphStore(directory).replace(analysis("wiki", "1", "Beleg"));
        Path file;
        try (Stream<Path> files = Files.list(directory.resolve(FileKnowledgeGraphStore.PAGES))) {
            file = files.findFirst().orElseThrow();
        }
        String json = Files.readString(file, StandardCharsets.UTF_8);
        // Stand vor der Qualitätsstufe: keine neuen Felder
        String old = json.replaceAll(",\\s*\"paragraph\"\\s*:\\s*-?\\d+", "")
                .replaceAll(",\\s*\"quality\"\\s*:\\s*\"[A-Z_]+\"", "")
                .replaceAll(",\\s*\"qualityReasons\"\\s*:\\s*\\[[^\\]]*\\]", "")
                .replaceAll(",\\s*\"review\"\\s*:\\s*\"[A-Z_]+\"", "");
        assertThat(old).doesNotContain("quality", "review", "paragraph");
        Files.writeString(file, old, StandardCharsets.UTF_8);

        ParsedStatements.Statement legacy = new FileKnowledgeGraphStore(directory).find(new PageKey("wiki", "1"))
                .orElseThrow().result().statements().get(0);
        assertThat(legacy.quality()).isEqualTo(ParsedStatements.Quality.UNCERTAIN);
        assertThat(legacy.qualityReasons()).containsExactly(ParsedStatements.Statement.NOT_ASSESSED);
        assertThat(legacy.paragraph()).isEqualTo(ParsedStatements.Statement.UNKNOWN_PARAGRAPH);
        assertThat(legacy.review()).isEqualTo(ParsedStatements.Review.UNREVIEWED);

        Files.writeString(file, json.replaceFirst("\"quality\"\\s*:\\s*\"[A-Z_]+\"", "\"quality\":\"VIELLEICHT\"")
                .replaceFirst("\"review\"\\s*:\\s*\"[A-Z_]+\"", "\"review\":\"SPAETER\""), StandardCharsets.UTF_8);
        ParsedStatements.Statement unknown = new FileKnowledgeGraphStore(directory).find(new PageKey("wiki", "1"))
                .orElseThrow().result().statements().get(0);
        assertThat(unknown.quality()).isEqualTo(ParsedStatements.Quality.UNCERTAIN);
        assertThat(unknown.qualityReasons()).containsExactly(ParsedStatements.Statement.NOT_ASSESSED);
        assertThat(unknown.review()).isEqualTo(ParsedStatements.Review.UNREVIEWED);
    }

    @Test
    void removingDeletesTheFile() {
        FileKnowledgeGraphStore store = new FileKnowledgeGraphStore(directory);
        store.replace(analysis("wiki", "1", "Beleg"));
        store.remove(new PageKey("wiki", "1"));
        store.remove(new PageKey("wiki", "2"));

        assertThat(new FileKnowledgeGraphStore(directory).all()).isEmpty();
        assertThat(store.conllu(new PageKey("wiki", "1"))).isEmpty();
    }

    @Test
    void anEmptyDirectoryIsNotCreatedJustByReading() {
        Path missing = directory.resolve("knowledge-graph");

        assertThat(new FileKnowledgeGraphStore(missing).all()).isEmpty();
        assertThat(missing).doesNotExist();
    }

    @Test
    void documentsLieUnderTheirPageWithSeparateFieldsAndThePageStaysWhereItWas() throws IOException {
        // ADR 0026, Punkt 10 und Schlüsselform Punkte 1 und 7
        PageStatementAnalysis page = analysis("wiki", "1", "Beleg");
        DocumentKey attachment = DocumentKey.attachment(page.key(), "att/42?x");
        DocumentKey part = attachment.child(new PartId.Content("a".repeat(64), 1));
        FileKnowledgeGraphStore store = new FileKnowledgeGraphStore(directory);
        store.replace(page);
        store.replace(document(page, attachment, "Anhang"));
        store.replace(document(page, part, "Teil"));

        Path folder = directory.resolve(FileKnowledgeGraphStore.DOCUMENTS)
                .resolve(DocumentKey.page(page.key()).hash());
        assertThat(folder.resolve(attachment.hash() + ".json")).exists();
        assertThat(folder.resolve(part.hash() + ".json")).exists();
        assertThat(directory.resolve(FileKnowledgeGraphStore.PAGES)
                .resolve(FileKnowledgeGraphStore.name(page.key()))).exists();
        tools.jackson.databind.JsonNode json = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(Files.readString(folder.resolve(part.hash() + ".json"), StandardCharsets.UTF_8));
        assertThat(json.path("pageId").asString()).isEqualTo("1");
        assertThat(json.path("attachmentId").asString()).isEqualTo("att/42?x");
        assertThat(json.path("partId").asString()).isEqualTo("sha256:" + "a".repeat(64) + ":1");

        FileKnowledgeGraphStore restarted = new FileKnowledgeGraphStore(directory);
        assertThat(restarted.all()).extracting(PageStatementAnalysis::document)
                .containsExactly(DocumentKey.page(page.key()));
        assertThat(restarted.documents()).extracting(PageStatementAnalysis::document)
                .containsExactlyInAnyOrder(attachment, part);
        assertThat(restarted.find(part).orElseThrow().result().mentions())
                .extracting(ParsedStatements.Mention::text).containsExactly("Sachbearbeiter", "Teil");
        assertThat(restarted.find(page.key()).orElseThrow().ofDocument()).isFalse();
        assertThat(restarted.conllu(attachment)).contains("1\tAnhang\t_\t_\t_\t_\t0\troot\t_\t_\n");
    }

    @Test
    void removingAnAttachmentTakesItsPartsAndRemovingThePageTakesEverything() {
        PageStatementAnalysis page = analysis("wiki", "1", "Beleg");
        DocumentKey attachment = DocumentKey.attachment(page.key(), "42");
        DocumentKey part = attachment.child(new PartId.Content("b".repeat(64), 1));
        DocumentKey other = DocumentKey.attachment(page.key(), "43");
        FileKnowledgeGraphStore store = new FileKnowledgeGraphStore(directory);
        store.replace(page);
        store.replace(document(page, attachment, "Anhang"));
        store.replace(document(page, part, "Teil"));
        store.replace(document(page, other, "Zweiter"));

        store.removeDocument(attachment);

        assertThat(new FileKnowledgeGraphStore(directory).documents()).extracting(PageStatementAnalysis::document)
                .containsExactly(other);
        assertThat(store.find(page.key())).isPresent();

        store.remove(page.key());

        FileKnowledgeGraphStore restarted = new FileKnowledgeGraphStore(directory);
        assertThat(restarted.all()).isEmpty();
        assertThat(restarted.documents()).isEmpty();
        assertThat(directory.resolve(FileKnowledgeGraphStore.DOCUMENTS)
                .resolve(DocumentKey.page(page.key()).hash())).doesNotExist();
    }

    @Test
    void aPageFileWithoutDocumentFieldsIsThePageItself() {
        // Bestehende Ablagen vor ADR 0026: keine Migration, kein Neulauf
        PageStatementAnalysis page = analysis("wiki", "1", "Beleg");
        new FileKnowledgeGraphStore(directory).replace(page);

        PageStatementAnalysis read = new FileKnowledgeGraphStore(directory).find(page.key()).orElseThrow();

        assertThat(read.document()).isEqualTo(DocumentKey.page(page.key()));
        assertThat(FileKnowledgeGraphStore.json(page).has("attachmentId")).isFalse();
        assertThat(FileKnowledgeGraphStore.json(page).has("partId")).isFalse();
    }

    private static PageStatementAnalysis document(PageStatementAnalysis page, DocumentKey document, String noun) {
        PageStatementAnalysis analysis = analysis(page.key().sourceId(), page.key().pageId(), noun);
        return new PageStatementAnalysis(analysis.key(), analysis.spaceKey(), noun, "sha256:" + "c".repeat(64),
                analysis.inputSha256(), analysis.analyzerId(), analysis.analyzerVersion(), analysis.analyzedAt(),
                analysis.result(), document);
    }

    private static PageStatementAnalysis analysis(String sourceId, String pageId, String noun) {
        String doc = sourceId + "/" + pageId + "@0123456789ab";
        ParsedStatements.Mention subject = new ParsedStatements.Mention(doc + "#s0.t1", 0, 0, 14, "Sachbearbeiter",
                "Sachbearbeiter", "Sachbearbeiter", ParsedStatements.MentionKind.NOUN, false, Set.of("Nom"));
        ParsedStatements.Mention object = new ParsedStatements.Mention(doc + "#s0.t3", 0, 27, 27 + noun.length(),
                noun, noun, null, ParsedStatements.MentionKind.NOUN, false, Set.of());
        ParsedStatements.Statement statement = new ParsedStatements.Statement(doc + "#s0.v2", 0, 15, 26,
                "übermittelt", "übermitteln", ParsedStatements.Voice.ACTIVE, ParsedStatements.Clause.MAIN, true,
                List.of(new ParsedStatements.Argument(ParsedStatements.Role.ACTOR, subject.id(), "nsubj", null,
                                ParsedStatements.Priority.CORE, ParsedStatements.Basis.PARSER, true),
                        new ParsedStatements.Argument(ParsedStatements.Role.TIME, object.id(), "nmod", "am",
                                ParsedStatements.Priority.CONTEXT, ParsedStatements.Basis.LEXICON, false)),
                Set.of("TIME_FROM_NOMINAL"));
        ParsedStatements result = new ParsedStatements(List.of(new ParsedStatements.Sentence(0, 0, 40)),
                List.of(subject, object), List.of(statement),
                List.of(new ParsedStatements.Attachment(subject.id() + "→t3", subject.id(), object.id(), "nmod", null,
                        ParsedStatements.AttachmentKind.GENITIVE, ParsedStatements.Basis.CASE_HEURISTIC)),
                List.of("parser=maltparser-1.9.2"), "1\t" + noun + "\t_\t_\t_\t_\t0\troot\t_\t_\n");
        return new PageStatementAnalysis(new PageKey(sourceId, pageId), "DEV", "Seite " + pageId, "3",
                "sha256:0123456789abcdef", "maltparser-de", "1|maltparser-de-gsd-v1.0.0|237885c2",
                Instant.parse("2026-11-09T15:00:00Z"), result);
    }

    private static ParsedStatements.Statement assessed(ParsedStatements.Statement statement, String id, int paragraph,
                                                       ParsedStatements.Quality quality, List<String> reasons,
                                                       ParsedStatements.Review review) {
        return new ParsedStatements.Statement(id, statement.sentence(), statement.start(), statement.end(),
                statement.predicateText(), statement.predicateLemma(), statement.voice(), statement.clause(),
                statement.core(), statement.arguments(), statement.findings(), paragraph, quality, reasons, review);
    }

    private static ParsedStatements withStatements(ParsedStatements result,
                                                   List<ParsedStatements.Statement> statements) {
        return new ParsedStatements(result.sentences(), result.mentions(), statements, result.attachments(),
                result.components(), result.conllu());
    }
}
