package com.aresstack.spaceharvester.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.passages.SentenceSegmenter;
import com.aresstack.spaceharvester.source.KnowledgeTextSegmentation;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

/** Erfundene Sätze; das Modell ist ein Fake mit fester Antwort. */
class ModelStatementAnalyzerTest {

    static final SentenceSegmenter SENTENCES = SentenceSegmenter.splitter(KnowledgeTextSegmentation.defaults());

    @Test
    void aStatementKeepsOnlyParticipantsThatStandLiterallyInTheSentence() {
        FakeStepModel model = new FakeStepModel(input -> """
                {"satz": 1, "aussagen": [{"verb": "pflegen", "handelnder": "Die Redaktion", "betroffenes": "die Stammdaten", "empfaenger": null, "verneint": false, "verbindlichkeit": "MUSS", "zeitbezug": "GILT"}]}
                """);
        List<StatementAnalyzer.Costs> costs = new ArrayList<>();

        ParsedStatements result = analyzer(model).analyze("doc", "Die Redaktion muss die Stammdaten pflegen.",
                () -> false, costs::add);

        assertThat(model.inputs).containsExactly("1 Die Redaktion muss die Stammdaten pflegen.\n");
        assertThat(result.statements()).singleElement().satisfies(statement -> {
            assertThat(statement.predicateLemma()).isEqualTo("pflegen");
            assertThat(statement.predicateText()).isEqualTo("pflegen");
            assertThat(statement.core()).isTrue();
            assertThat(statement.quality()).isEqualTo(ParsedStatements.Quality.PLAUSIBLE);
            assertThat(statement.features().modality()).isEqualTo(ParsedStatements.Modality.MUSS);
            assertThat(statement.features().negation()).isEqualTo(ParsedStatements.Negation.AFFIRMED);
            assertThat(statement.arguments()).extracting(ParsedStatements.Argument::basis)
                    .containsOnly(ParsedStatements.Basis.MODEL);
            assertThat(statement.arguments()).extracting(ParsedStatements.Argument::role)
                    .containsExactly(ParsedStatements.Role.ACTOR, ParsedStatements.Role.AFFECTED_OBJECT);
        });
        assertThat(result.mentions()).extracting(ParsedStatements.Mention::text)
                .containsExactly("Die Redaktion", "die Stammdaten");
        assertThat(result.components()).containsExactly("analyzer=language-model",
                "model=statement-model:fake-chat@0000", "prompt=1");
        assertThat(costs).containsExactly(new StatementAnalyzer.Costs(1, 1, 0, 0, 0));
    }

    @Test
    void anInventedParticipantDropsTheStatementAndASentenceWithoutLineHasNoStatement() {
        FakeStepModel model = new FakeStepModel(input -> """
                {"satz": 1, "aussagen": [{"verb": "prüfen", "handelnder": "Das Amt", "betroffenes": "den Bescheid"}]}
                Gerne helfe ich weiter!
                {"satz": 7, "aussagen": []}
                """);
        List<StatementAnalyzer.Costs> costs = new ArrayList<>();

        ParsedStatements result = analyzer(model).analyze("doc",
                "Die Stelle prüft den Antrag. Der Termin ist im Mai.", () -> false, costs::add);

        assertThat(result.sentences()).hasSize(2);
        assertThat(result.statements()).isEmpty();
        // zwei Zeilen verworfen (Text, fremde Nummer), eine Aussage verworfen, zwei Sätze ohne Aussage
        assertThat(costs).containsExactly(new StatementAnalyzer.Costs(2, 1, 2, 1, 2));
    }

    @Test
    void objectsBehindAPrepositionFallAwayAndTheStatementStays() {
        FakeStepModel model = new FakeStepModel(input -> """
                {"satz": 1, "aussagen": [{"verb": "erstellen", "handelnder": "Die Stelle", "betroffenes": "den Bescheid", "empfaenger": "den Antragsteller"}]}
                """);

        ParsedStatements result = analyzer(model).analyze("doc",
                "Die Stelle erstellt den Bescheid für den Antragsteller.", () -> false);

        assertThat(result.statements()).singleElement().satisfies(statement -> assertThat(statement.arguments())
                .extracting(ParsedStatements.Argument::role)
                .containsExactly(ParsedStatements.Role.ACTOR, ParsedStatements.Role.AFFECTED_OBJECT));
    }

    @Test
    void aClauseParticipantPointsToAnotherStatementOfTheSameSentence() {
        FakeStepModel model = new FakeStepModel(input -> """
                {"satz": 1, "aussagen": [{"verb": "entscheiden", "handelnder": "Das Referat", "betroffenes": "§fördern"}, {"verb": "fördern", "handelnder": "der Bund", "betroffenes": "das Vorhaben"}]}
                {"satz": 2, "aussagen": [{"verb": "wissen", "handelnder": "Die Stelle", "betroffenes": "§zahlen"}]}
                """);

        ParsedStatements result = analyzer(model).analyze("doc",
                "Das Referat entscheidet, ob der Bund das Vorhaben fördert. Die Stelle weiß es.", () -> false);

        assertThat(result.statements()).extracting(ParsedStatements.Statement::predicateLemma)
                .containsExactly("entscheiden", "fördern");
        ParsedStatements.Statement decide = result.statements().get(0);
        String clause = decide.arguments().get(1).mention();
        assertThat(result.mentions()).filteredOn(mention -> mention.id().equals(clause)).singleElement()
                .satisfies(mention -> assertThat(mention.kind()).isEqualTo(ParsedStatements.MentionKind.CLAUSE));
    }

    @Test
    void aStateStatementBecomesABearerWithAFinding() {
        FakeStepModel model = new FakeStepModel(input -> """
                {"satz": 1, "aussagen": [{"verb": "zuständig", "handelnder": "Das Referat"}]}
                """);

        ParsedStatements result = analyzer(model).analyze("doc", "Das Referat ist zuständig.", () -> false);

        assertThat(result.statements()).singleElement().satisfies(statement -> {
            assertThat(statement.findings()).containsExactly(ParsedStatements.Statement.STATE_PROPERTY);
            assertThat(statement.arguments()).extracting(ParsedStatements.Argument::role)
                    .containsExactly(ParsedStatements.Role.BEARER);
            assertThat(statement.core()).isFalse();
        });
    }

    @Test
    void withoutModelTheAnalyzerIsUnavailableAndNothingIsSent() {
        FakeStepModel model = new FakeStepModel(input -> "");
        model.available = false;
        ModelStatementAnalyzer analyzer = analyzer(model);

        assertThat(analyzer.ready()).isFalse();
        assertThat(analyzer.remote()).isTrue();
        assertThatThrownBy(() -> analyzer.analyze("doc", "Die Stelle prüft.", () -> false))
                .isInstanceOfSatisfying(AnalyzerUnavailableException.class, e -> assertThat(e.reason())
                        .isEqualTo(AnalyzerUnavailableException.Reason.NO_AI_MODEL));
        assertThat(model.inputs).isEmpty();
    }

    @Test
    void manySentencesGoOutInBlocksAndACancelStops() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < ModelStatementAnalyzer.MAX_LINES + 3; i++) {
            text.append("Die Stelle prüft den Antrag ").append(i).append(".\n");
        }
        FakeStepModel model = new FakeStepModel(input -> "");
        List<StatementAnalyzer.Costs> costs = new ArrayList<>();

        analyzer(model).analyze("doc", text.toString(), () -> false, costs::add);

        assertThat(model.inputs).hasSize(2);
        assertThat(model.inputs.get(1)).startsWith("1 Die Stelle prüft den Antrag 40.");
        assertThat(costs.get(0).noStatement()).isEqualTo(ModelStatementAnalyzer.MAX_LINES + 3);
        assertThatThrownBy(() -> analyzer(model).analyze("doc", text.toString(), () -> true))
                .isInstanceOf(CancellationException.class);
    }

    @Test
    void theVersionCarriesModelAndPrompt() {
        FakeStepModel model = new FakeStepModel(input -> "");
        ModelStatementAnalyzer analyzer = analyzer(model);

        assertThat(analyzer.id()).isEqualTo(ModelStatementAnalyzer.ID);
        assertThat(analyzer.version()).isEqualTo("statement-model:fake-chat@0000|prompt-1|abgleich-2");
        assertThat(ModelStatementAnalyzer.instructions()).contains("satz").contains("aussagen");
    }

    @Test
    void theLiteralCheckIgnoresCaseAndAMissingArticle() {
        assertThat(ModelStatementAnalyzer.find("Die Stelle prüft den Antrag.", "der Antrag")).containsExactly(21, 27);
        assertThat(ModelStatementAnalyzer.find("Die Stelle prüft den Antrag.", "die Anträge")).isNull();
        assertThat(ModelStatementAnalyzer.find("Die Stelle prüft den Antrag.", "den antrag")).containsExactly(17, 27);
        assertThat(ModelStatementAnalyzer.find("Die Stelle prüft Anträge.", "die Anträge")).containsExactly(17, 24);
        assertThat(ModelStatementAnalyzer.find("Die Stellenleitung prüft.", "Stelle")).isNull();
    }

    /** Codex zu #1023: fällt das innerste Ziel einer Kette weg, fallen alle, die darauf zeigen. */
    @Test
    void aClauseChainWithAMissingInnermostTargetFallsAwayCompletely() {
        FakeStepModel model = new FakeStepModel(input -> """
                {"satz": 1, "aussagen": [{"verb": "entscheiden", "handelnder": "Die Leitung", "betroffenes": "§prüfen"}, {"verb": "prüfen", "handelnder": "die Stelle", "betroffenes": "§fördern"}, {"verb": "fördern", "handelnder": "die Kommission"}]}
                """);
        List<StatementAnalyzer.Costs> costs = new ArrayList<>();

        ParsedStatements result = analyzer(model).analyze("doc",
                "Die Leitung entscheidet, ob die Stelle prüft, ob der Bund fördert.", () -> false, costs::add);

        assertThat(result.statements()).isEmpty();
        assertThat(costs).containsExactly(new StatementAnalyzer.Costs(1, 1, 0, 3, 1));
    }

    @Test
    void aCompleteClauseChainKeepsEveryLink() {
        FakeStepModel model = new FakeStepModel(input -> """
                {"satz": 1, "aussagen": [{"verb": "entscheiden", "handelnder": "Die Leitung", "betroffenes": "§prüfen"}, {"verb": "prüfen", "handelnder": "die Stelle", "betroffenes": "§fördern"}, {"verb": "fördern", "handelnder": "der Bund"}]}
                """);

        ParsedStatements result = analyzer(model).analyze("doc",
                "Die Leitung entscheidet, ob die Stelle prüft, ob der Bund fördert.", () -> false);

        assertThat(result.statements()).extracting(ParsedStatements.Statement::predicateLemma)
                .containsExactly("entscheiden", "prüfen", "fördern");
        for (ParsedStatements.Statement statement : result.statements().subList(0, 2)) {
            assertThat(statement.arguments()).filteredOn(a -> a.role() == ParsedStatements.Role.AFFECTED_OBJECT)
                    .singleElement().satisfies(a -> assertThat(result.mentions()).filteredOn(m -> m.id()
                            .equals(a.mention())).singleElement().extracting(ParsedStatements.Mention::kind)
                            .isEqualTo(ParsedStatements.MentionKind.CLAUSE));
        }
    }

    /** Codex zu #1023: „Sie“ am Satzanfang ist ein ungelöstes Pronomen, kein Substantiv. */
    @Test
    void aCapitalisedPronounAtTheStartStaysAnUnresolvedPronoun() {
        FakeStepModel model = new FakeStepModel(input -> """
                {"satz": 1, "aussagen": [{"verb": "prüfen", "handelnder": "Sie", "betroffenes": "Anträge"}]}
                """);

        ParsedStatements result = analyzer(model).analyze("doc", "Sie prüft Anträge.", () -> false);

        assertThat(result.mentions()).filteredOn(m -> m.text().equals("Sie")).singleElement().satisfies(m -> {
            assertThat(m.kind()).isEqualTo(ParsedStatements.MentionKind.PRONOUN);
            assertThat(m.unresolved()).isTrue();
        });
        assertThat(result.mentions()).filteredOn(m -> m.text().equals("Anträge")).singleElement().satisfies(m -> {
            assertThat(m.kind()).isEqualTo(ParsedStatements.MentionKind.NOUN);
            assertThat(m.unresolved()).isFalse();
        });
    }

    /** Codex zu #1023: Die Suche nach einer Präposition geht nicht über Verben und Pronomen hinweg. */
    @Test
    void thePrepositionScanStopsAtVerbsAndPronouns() {
        String text = "Für sie erstellt er Bescheide.";
        assertThat(ModelStatementAnalyzer.behindPreposition(text, text.indexOf("Bescheide"))).isFalse();
        String np = "Die Stelle erstellt den Bescheid für den neuen Antragsteller.";
        assertThat(ModelStatementAnalyzer.behindPreposition(np, np.indexOf("Antragsteller"))).isTrue();
        String beim = "Das Amt fragt beim Antragsteller nach.";
        assertThat(ModelStatementAnalyzer.behindPreposition(beim, beim.indexOf("Antragsteller"))).isTrue();
        String direct = "Die Stelle erstellt den Bescheid.";
        assertThat(ModelStatementAnalyzer.behindPreposition(direct, direct.indexOf("den Bescheid"))).isFalse();
    }

    /** Codex zu #1023: Entzug der Freigabe zwischen zwei Abschnitten schickt keinen weiteren Abschnitt. */
    @Test
    void withdrawingTheReleaseStopsBeforeTheNextRequest() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < ModelStatementAnalyzer.MAX_LINES + 5; i++) {
            text.append("Die Stelle prüft den Antrag. ");
        }
        boolean[] released = {true};
        FakeStepModel counting = new FakeStepModel(input -> {
            released[0] = false; // nach dem ersten Aufruf entzogen
            return "{\"satz\": 1, \"aussagen\": []}";
        });

        assertThatThrownBy(() -> analyzer(counting).analyze("doc", text.toString(), () -> false,
                () -> released[0], costs -> { }))
                .isInstanceOf(AnalyzerUnavailableException.class)
                .extracting(e -> ((AnalyzerUnavailableException) e).reason())
                .isEqualTo(AnalyzerUnavailableException.Reason.NOT_RELEASED);
        assertThat(counting.inputs).hasSize(1);
    }

    static ModelStatementAnalyzer analyzer(FakeStepModel model) {
        return new ModelStatementAnalyzer(model, SENTENCES);
    }
}
