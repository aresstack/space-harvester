package com.aresstack.spaceharvester.app.nlp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.knowledge.AnalyzerUnavailableException;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.nlp.malt.ExpectedModel;
import com.aresstack.spaceharvester.nlp.malt.TestModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Der Desktop-Adapter mit dem kleinen Testmodell aus {@code :nlp-parser} (nicht dem GSD-Modell): Laden erst beim
 * ersten Aufruf, klare Fehler ohne Download, Abbildung auf das Core-Modell mit Spannen im Lesetext.
 */
class MaltStatementAnalyzerTest {

    private static final String TEXT =
            "Der Sachbearbeiter übermittelt dem Vorgesetzten am Montag den Beleg des Kunden.";
    private static final String DOC = "wiki/123@0123456789ab";

    @TempDir
    Path empty;

    @Test
    void theVersionNamesModelAndRulesWithoutLoadingAnything() {
        MaltStatementAnalyzer analyzer = new MaltStatementAnalyzer(empty, TestModel.EXPECTED);

        assertThat(analyzer.version()).isEqualTo("1|maltparser-de-test-v0.1.0|43cc823cf1a3|rules-1");
        assertThat(analyzer.id()).isEqualTo("maltparser-de");
        assertThat(analyzer.loaded()).isFalse();
    }

    @Test
    void aMissingModelGivesAnInstallHintAndNoFallback() {
        MaltStatementAnalyzer analyzer = new MaltStatementAnalyzer(empty.resolve("nlp-model"), TestModel.EXPECTED);

        assertThatThrownBy(() -> analyzer.analyze(DOC, TEXT, () -> false))
                .isInstanceOfSatisfying(AnalyzerUnavailableException.class, e -> {
                    assertThat(e.reason()).isEqualTo(AnalyzerUnavailableException.Reason.MODEL_MISSING);
                    assertThat(e.getMessage()).contains(empty.resolve("nlp-model").toString())
                            .contains("maltparser-de-test-v0.1.0.zip").contains("Installieren")
                            .doesNotContain("Sachbearbeiter");
                });
        assertThat(analyzer.loaded()).isFalse();
        assertThat(empty.resolve("nlp-model")).doesNotExist();
    }

    @Test
    void aModelWithAnotherChecksumIsRejected() {
        ExpectedModel other = new ExpectedModel("maltparser-de-test", "0.1.0", "maltparser-de-test", "0".repeat(64));
        MaltStatementAnalyzer analyzer = new MaltStatementAnalyzer(TestModel.directory(), other);

        assertThatThrownBy(() -> analyzer.analyze(DOC, TEXT, () -> false))
                .isInstanceOfSatisfying(AnalyzerUnavailableException.class, e -> assertThat(e.reason())
                        .isEqualTo(AnalyzerUnavailableException.Reason.MODEL_INVALID));
    }

    @Test
    void aBrokenModelFileIsRejected() throws Exception {
        Path copy = Files.createDirectory(empty.resolve("kaputt"));
        Files.copy(TestModel.directory().resolve("model-manifest.json"), copy.resolve("model-manifest.json"));
        Files.writeString(copy.resolve("maltparser-de-test.mco"), "kein Modell");
        MaltStatementAnalyzer analyzer = new MaltStatementAnalyzer(copy, TestModel.EXPECTED);

        assertThatThrownBy(() -> analyzer.analyze(DOC, TEXT, () -> false))
                .isInstanceOfSatisfying(AnalyzerUnavailableException.class, e -> assertThat(e.reason())
                        .isEqualTo(AnalyzerUnavailableException.Reason.MODEL_INVALID));
    }

    @Test
    void theReferenceSentenceBecomesACoreStatementWithEvidenceInTheReaderText() {
        MaltStatementAnalyzer analyzer = new MaltStatementAnalyzer(TestModel.directory(), TestModel.EXPECTED);

        ParsedStatements result = analyzer.analyze(DOC, TEXT, () -> false);

        assertThat(analyzer.loaded()).isTrue();
        assertThat(result.mentions()).allSatisfy(mention -> {
            assertThat(mention.id()).startsWith(DOC + "#s1.t");
            assertThat(TEXT.substring(mention.start(), mention.end())).isEqualTo(mention.text());
        });
        ParsedStatements.Statement core = result.statements().stream().filter(ParsedStatements.Statement::core)
                .findFirst().orElseThrow();
        assertThat(core.id()).startsWith(DOC + "#s1.v");
        assertThat(core.predicateLemma()).isEqualTo("übermitteln");
        assertThat(TEXT.substring(core.start(), core.end())).isEqualTo("übermittelt");
        assertThat(text(result, core, ParsedStatements.Role.ACTOR)).isEqualTo("Der Sachbearbeiter");
        assertThat(text(result, core, ParsedStatements.Role.AFFECTED_OBJECT)).isEqualTo("den Beleg");
        assertThat(result.conllu()).contains("\tSachbearbeiter\t");
        assertThat(result.components()).anySatisfy(component -> assertThat(component)
                .startsWith("dependencies=maltparser 1.9.2 maltparser-de-test 0.1.0 sha256:43cc823c"));
        assertThat(result.attachments()).anySatisfy(attachment -> {
            assertThat(attachment.kind()).isEqualTo(ParsedStatements.AttachmentKind.GENITIVE);
            assertThat(mention(result, attachment.dependent()).text()).isEqualTo("des Kunden");
        });
    }

    @Test
    void aCancelStopsBetweenSentences() {
        MaltStatementAnalyzer analyzer = new MaltStatementAnalyzer(TestModel.directory(), TestModel.EXPECTED);

        assertThatThrownBy(() -> analyzer.analyze(DOC, TEXT + " Er prüft den Antrag.", () -> true))
                .isInstanceOf(CancellationException.class);
    }

    @Test
    void theDefaultDirectoryLiesBelowTheDataDirectory() {
        assertThat(MaltStatementAnalyzer.defaultDirectory(Path.of("daten")))
                .isEqualTo(Path.of("daten", "nlp-model", "maltparser-de-gsd-v1.0.0"));
        assertThat(MaltStatementAnalyzer.pinned(empty)).get()
                .satisfies(analyzer -> assertThat(analyzer.version()).startsWith("1|maltparser-de-gsd-v1.0.0|237885c25519|"));
    }

    private static String text(ParsedStatements result, ParsedStatements.Statement statement,
                               ParsedStatements.Role role) {
        return mention(result, statement.withRole(role).getFirst().mention()).text();
    }

    private static ParsedStatements.Mention mention(ParsedStatements result, String id) {
        return result.mentions().stream().filter(mention -> mention.id().equals(id)).findFirst().orElseThrow();
    }
}
