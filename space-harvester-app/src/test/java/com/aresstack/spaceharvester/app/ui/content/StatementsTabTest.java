package com.aresstack.spaceharvester.app.ui.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.knowledge.AnalyzerUnavailableException;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import com.aresstack.spaceharvester.application.knowledge.PageStatementAnalysis;
import com.aresstack.spaceharvester.application.knowledge.PageStatements;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Der Reiter „Aussagen“ ohne Parser: Anzeige, Knopf, Abbruch, Seitenwechsel, Sprung (#499). */
class StatementsTabTest {

    static final String TEXT = "Der Sachbearbeiter übermittelt dem Vorgesetzten am Montag den Beleg des Kunden.";
    private static final String DOC = "wiki/1@0123456789ab";

    private final FakeActions actions = new FakeActions();
    private final DeferredRunner runner = new DeferredRunner();
    private final StatementsTab tab = new StatementsTab(() -> actions, runner);

    @Test
    void openingAPageShowsTheStoredStateAndNeverParses() {
        tab.page(page("1"), text());
        runner.runAll();

        assertThat(tab.stateLabel()).isEqualTo("Nicht analysiert");
        assertThat(tab.hintText()).isEqualTo(StatementsTab.NOT_ANALYSED);
        assertThat(tab.analyzeOffered()).isTrue();
        assertThat(actions.analyses).isZero();
    }

    /** Fahrplan A3: der eingestellte KI-Analysator lässt die Seite bewusst aus, gelbe Pille statt Fehlerband. */
    @Test
    void aPageTheModelAnalyzerSkipsShowsAYellowPillWithTheReason() {
        actions.analyzer = com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice.MODEL;
        actions.skipped = Optional.of(
                com.aresstack.spaceharvester.application.knowledge.AnalyzerUnavailableException.Reason.NOT_RELEASED);
        tab.page(page("1"), text());
        runner.runAll();

        assertThat(tab.stateLabel()).isEqualTo(StatementsTab.SKIPPED);
        assertThat(tab.stateTooltip()).contains("nicht für den KI-Dienst freigegeben");
        assertThat(tab.hintText()).isBlank();
        assertThat(tab.lines()).isEmpty();
        assertThat(tab.analyzeLabel()).as("ein neutraler Knopf").isEqualTo(StatementsTab.ANALYZE);
        assertThat(tab.analyzeTooltip()).isEqualTo(StatementsTab.ANALYZE_TIP_MODEL);
    }

    /** Codex-Befund zu #1025: Der KI-Analysator braucht das MaltParser-Modell nicht. */
    @Test
    void theModelAnalyzerCanAnalyseWithoutTheParserModel() {
        actions.analyzer = com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice.MODEL;
        tab.modelStatus(new com.aresstack.spaceharvester.app.ui.ParserModelSetup.Status(
                com.aresstack.spaceharvester.app.ui.ParserModelSetup.State.NOT_INSTALLED, null, false, -1));
        tab.page(page("1"), text());
        runner.runAll();

        assertThat(tab.analyzeOffered()).isTrue();
        assertThat(tab.modelBanner.isVisible()).as("keine Parser-Meldung für den KI-Analysator").isFalse();

        actions.analyzer = com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice.PARSER;
        tab.storeChanged();
        runner.runAll();
        assertThat(tab.analyzeOffered()).isFalse();
        assertThat(tab.modelBanner.isVisible()).isTrue();
    }

    /** Codex-Befund zu #1025: Ein Lauf aus der alten Ablage erscheint nach dem Umschalten nicht unter der neuen. */
    @Test
    void switchingTheStoreDuringAnAnalysisDropsItsResult() {
        tab.page(page("1"), text());
        runner.runAll();
        tab.clickAnalyze();

        tab.storeChanged();
        runner.runAll();

        assertThat(actions.lastCancelled.getAsBoolean()).as("Lauf abgebrochen").isTrue();
        assertThat(tab.stateLabel()).isEqualTo("Nicht analysiert");
        assertThat(tab.analyzeOffered()).isTrue();
    }

    @Test
    void withTheParserTheButtonKeepsItsName() {
        tab.page(page("1"), text());
        runner.runAll();
        assertThat(tab.analyzeLabel()).isEqualTo(StatementsTab.ANALYZE);
        assertThat(tab.analyzeTooltip()).isEqualTo(StatementsTab.ANALYZE_TIP_PARSER);
    }

    @Test
    void theButtonAnalysesAndShowsCoreStatementsFirstWithRolesAndEvidence() {
        tab.page(page("1"), text());
        runner.runAll();

        tab.clickAnalyze();
        assertThat(tab.cancelOffered()).isTrue();
        runner.runAll();

        assertThat(actions.analyses).isEqualTo(1);
        assertThat(tab.stateLabel()).isEqualTo("Aktuell");
        assertThat(tab.cancelOffered()).isFalse();
        List<String> lines = tab.lines();
        assertThat(lines.getFirst()).isEqualTo("Kernaussagen");
        assertThat(lines.get(1)).contains("übermittelt (übermitteln)", "Handelnder: Der Sachbearbeiter",
                "Betroffen: den Beleg", "Empfänger: dem Vorgesetzten", "Zeit: Montag (am, unsicher)",
                "Aktiv · Hauptsatz", "Nominaler Bezug: den Beleg → des Kunden (Genitiv; kein Besitz behauptet)",
                "Zeitangabe hing am Empfänger", "Beleg: „" + TEXT + "“");
        assertThat(lines).contains("Verbindungen");
        assertThat(lines.getLast()).contains("„beleg“ → Belege (handbuch)",
                "Kandidat, keine bestätigte Identität");
    }

    @Test
    void theTrashDeletesTheStoredAnalysisOfThisPageAfterYes() {
        List<String> statuses = new ArrayList<>();
        tab.onStatus(statuses::add);
        tab.page(page("1"), text());
        runner.runAll();
        assertThat(tab.deleteButton().isEnabled()).as("ohne Analyse nichts zu löschen").isFalse();

        actions.stored = analysed();
        tab.reload();
        runner.runAll();
        assertThat(tab.deleteButton().isEnabled()).isTrue();
        assertThat(tab.deleteButton().getToolTipText()).isEqualTo("Aussagen-Analyse dieser Seite löschen");
        List<String> questions = new ArrayList<>();
        tab.confirmDeleteWith(question -> {
            questions.add(question);
            return false;
        });
        tab.deleteButton().doClick();
        runner.runAll();
        assertThat(questions).containsExactly("Wirklich die Aussagen-Analyse dieser Seite samt Anhängen löschen? "
                + "Die Seite bleibt.");
        assertThat(actions.deletions).isEmpty();

        tab.confirmDeleteWith(question -> true);
        tab.deleteButton().doClick();
        runner.runAll();
        assertThat(actions.deletions).containsExactly(page("1").key());
        assertThat(statuses).contains("2 Aussagen-Analysen gelöscht");
        assertThat(tab.stateLabel()).isEqualTo("Nicht analysiert");
        assertThat(tab.deleteButton().isEnabled()).isFalse();
    }

    @Test
    void aClickOnAStatementJumpsToItsSentenceInTheReaderText() {
        List<List<TermsTab.Span>> jumps = new ArrayList<>();
        tab.onJump(jumps::add);
        actions.stored = analysed();
        tab.page(page("1"), text());
        runner.runAll();

        tab.clickFirstEntry();

        assertThat(jumps).containsExactly(List.of(new TermsTab.Span(0, TEXT.length())));
    }

    @Test
    void aStatementOfTheSimplificationStageShowsOriginFlagAndSimplifiedSentenceNextToTheOriginal() {
        ParsedStatements result = analysed().analysis().result();
        ParsedStatements.Statement original = result.statements().getFirst();
        ParsedStatements.Statement staged = new ParsedStatements.Statement(original.id(), original.sentence(),
                original.start(), original.end(), original.predicateText(), original.predicateLemma(),
                original.voice(), original.clause(), original.core(), original.arguments(), original.findings(),
                original.paragraph(), original.quality(), original.qualityReasons(), original.review(),
                original.features(), ParsedStatements.Derivation.vorstufe(ParsedStatements.StageFlag.PLANNING,
                        "Der Sachbearbeiter übermittelt den Beleg."));
        Map<String, ParsedStatements.Mention> mentions = new java.util.LinkedHashMap<>();
        result.mentions().forEach(mention -> mentions.put(mention.id(), mention));

        List<String> body = StatementsTab.describe(staged, result, mentions, TEXT);

        assertThat(body).containsSubsequence(
                "Herkunft: Vorstufe „Sätze vereinfachen“ · Kennzeichen: Planung oder Absicht",
                "Beleg (Originalsatz): „" + TEXT + "“",
                "Vereinfachter Satz: „Der Sachbearbeiter übermittelt den Beleg.“");
        assertThat(StatementsTab.describe(staged, result, mentions, null)).as("veraltet: ohne Beleg, mit Satz")
                .contains("Vereinfachter Satz: „Der Sachbearbeiter übermittelt den Beleg.“")
                .noneMatch(line -> line.startsWith("Beleg"));
        assertThat(StatementsTab.describe(original, result, mentions, TEXT)).as("Original ohne Herkunftszeile")
                .noneMatch(line -> line.startsWith("Herkunft") || line.startsWith("Vereinfachter Satz"));
    }

    @Test
    void aCheckedStatementShowsVerdictAndLoweredStageAndACorrectionItsOrigin() {
        ParsedStatements result = analysed().analysis().result();
        ParsedStatements.Statement first = result.statements().getFirst();
        ParsedStatements.Statement original = new ParsedStatements.Statement(first.id(), first.sentence(),
                first.start(), first.end(), first.predicateText(), first.predicateLemma(), first.voice(),
                first.clause(), first.core(), first.arguments(), first.findings(), first.paragraph(),
                ParsedStatements.Quality.PLAUSIBLE, List.of(), first.review(), first.features(), first.derivation());
        Map<String, ParsedStatements.Mention> mentions = new java.util.LinkedHashMap<>();
        result.mentions().forEach(mention -> mentions.put(mention.id(), mention));

        assertThat(StatementsTab.describe(first.withCheck(new ParsedStatements.Check(
                ParsedStatements.Verdict.FALSCH, "modell|v1", false)), result, mentions, TEXT))
                .as("schon unsicher: das Urteil senkt nichts, also keine Stufenzeile")
                .contains("Urteil des Modells: falsch").noneMatch(line -> line.startsWith("Stufe"));
        assertThat(StatementsTab.describe(original, result, mentions, TEXT)).as("ungeprüft: keine Zeile")
                .noneMatch(line -> line.startsWith(CheckTexts.VERDICT_LABEL) || line.startsWith("Stufe"));
        assertThat(StatementsTab.describe(original.withCheck(new ParsedStatements.Check(
                ParsedStatements.Verdict.STIMMT, "modell|v1", false)), result, mentions, TEXT))
                .contains("Urteil des Modells: stimmt").as("stimmt hebt und senkt nie")
                .noneMatch(line -> line.startsWith("Stufe"));
        assertThat(StatementsTab.describe(original.withCheck(new ParsedStatements.Check(
                ParsedStatements.Verdict.FALSCH, "modell|v1", false)), result, mentions, TEXT))
                .containsSubsequence("Urteil des Modells: falsch", "Stufe: unsicher (vom Parser: plausibel)");
        assertThat(StatementsTab.describe(original.withCheck(new ParsedStatements.Check(
                ParsedStatements.Verdict.UNVOLLSTAENDIG, "modell|v1", true)), result, mentions, TEXT))
                .containsSubsequence("Urteil des Modells: unvollständig (Korrektur ergänzt)",
                        "Stufe: unsicher (vom Parser: plausibel)");
        assertThat(StatementsTab.describe(original.withCheck(new ParsedStatements.Check(
                ParsedStatements.Verdict.UNVOLLSTAENDIG, "modell|v1", false)), result, mentions, TEXT))
                .contains("Urteil des Modells: unvollständig").noneMatch(line -> line.startsWith("Stufe"));

        ParsedStatements.Statement correction = new ParsedStatements.Statement("c1", original.sentence(),
                original.start(), original.end(), original.predicateText(), original.predicateLemma(),
                original.voice(), original.clause(), original.core(), original.arguments(), original.findings(),
                original.paragraph(), original.quality(), original.qualityReasons(), original.review(),
                original.features(), ParsedStatements.Derivation.modell(
                        "Der Sachbearbeiter übermittelt dem Vorgesetzten den Beleg.", original.id(),
                        ParsedStatements.Origin.ORIGINAL));
        assertThat(StatementsTab.describe(correction, result, mentions, TEXT)).containsSubsequence(
                "Herkunft: Korrektur des Modells", "Beleg (Originalsatz): „" + TEXT + "“",
                "Satz des Modells: „Der Sachbearbeiter übermittelt dem Vorgesetzten den Beleg.“")
                .noneMatch(line -> line.startsWith("Vereinfachter Satz"));
    }

    @Test
    void anUncertainStatementIsMarkedAsProposalWithOriginAndCheckStatusAndStaysVisible() {
        actions.stored = analysed();
        tab.page(page("1"), text());
        runner.runAll();

        List<String> lines = tab.lines();
        assertThat(lines.getFirst()).isEqualTo("Kernaussagen");
        assertThat(lines.get(1)).as("A1: gekennzeichnet, nicht ausgeblendet")
                .startsWith("Vorschlag · Originalsatz, MaltParser · nicht geprüft | übermittelt (übermitteln)");
        assertThat(tab.copyText()).startsWith("Kernaussagen (1, davon 1 Vorschlag)");
        assertThat(tab.chips()).hasSize(1);
        assertThat(tab.chips().getFirst().getText()).isEqualTo("Herkunft");
    }

    @Test
    void theChipShowsOriginAndCheckDetailsInTheOverlay() {
        ParsedStatements.Statement checked = plausible(analysed().analysis().result().statements().getFirst())
                .withCheck(new ParsedStatements.Check(ParsedStatements.Verdict.FALSCH, "modell|v1", false));
        actions.stored = with(analysed(), checked);
        tab.page(page("1"), text());
        runner.runAll();

        assertThat(tab.lines().get(1)).startsWith("Vorschlag · Originalsatz, MaltParser · vom Prüfschritt verworfen")
                .doesNotContain("Urteil des Modells", "Stufe:");
        tab.chips().getFirst().doClick();

        assertThat(tab.popup().titleText()).isEqualTo("Herkunft und Prüfstatus");
        assertThat(tab.popup().bodyText()).contains("Kennzeichen: Vorschlag",
                "Stufe: unsicher (vom Parser: plausibel)", "Gründe: der Prüfschritt hält die Aussage für falsch",
                "Prüfschritt: vom Prüfschritt verworfen", "Urteil des Modells: falsch", "Urteil von Hand: keins",
                "Herkunft: Originalsatz, MaltParser", "Handelnder „Der Sachbearbeiter“: Parserkante (nsubj), sicher",
                "Zeit „Montag“: Wortliste, unsicher");
    }

    @Test
    void copyingStillDeliversOriginVerdictAndStageThatMovedIntoTheOverlay() {
        ParsedStatements.Statement first = plausible(analysed().analysis().result().statements().getFirst());
        ParsedStatements.Statement staged = first.withDerivation(ParsedStatements.Derivation.vorstufe(
                ParsedStatements.StageFlag.PLANNING, "Der Sachbearbeiter übermittelt den Beleg."))
                .withCheck(new ParsedStatements.Check(ParsedStatements.Verdict.FALSCH, "modell|v1", false));
        actions.stored = with(analysed(), staged);
        tab.page(page("1"), text());
        runner.runAll();

        assertThat(tab.lines().get(1)).as("Auflage AT: die Vorstufe sieht nie wie das Original aus")
                .startsWith("Vorschlag · Vorstufe „Sätze vereinfachen“ (KI), MaltParser · vom Prüfschritt verworfen")
                .doesNotContain("Herkunft:", "Urteil des Modells", "Stufe:");
        assertThat(tab.copyText()).contains(
                "Herkunft: Vorstufe „Sätze vereinfachen“ · Kennzeichen: Planung oder Absicht",
                "Urteil des Modells: falsch", "Stufe: unsicher (vom Parser: plausibel)",
                "Vereinfachter Satz: „Der Sachbearbeiter übermittelt den Beleg.“", "Beleg (Originalsatz): „");
    }

    @Test
    void aPlausibleStatementHasNoMarkerButStillSaysItIsUnchecked() {
        actions.stored = with(analysed(), plausible(analysed().analysis().result().statements().getFirst()));
        tab.page(page("1"), text());
        runner.runAll();

        assertThat(tab.lines().get(1)).startsWith("Originalsatz, MaltParser · nicht geprüft | ");
        assertThat(tab.copyText()).startsWith("Kernaussagen (1)\n");
    }

    private static ParsedStatements.Statement plausible(ParsedStatements.Statement s) {
        return new ParsedStatements.Statement(s.id(), s.sentence(), s.start(), s.end(), s.predicateText(),
                s.predicateLemma(), s.voice(), s.clause(), s.core(), s.arguments(), s.findings(), s.paragraph(),
                ParsedStatements.Quality.PLAUSIBLE, List.of(), s.review(), s.features(), s.derivation());
    }

    private static PageStatements with(PageStatements base, ParsedStatements.Statement statement) {
        PageStatementAnalysis a = base.analysis();
        ParsedStatements r = a.result();
        return new PageStatements(base.state(), base.reason(), new PageStatementAnalysis(a.key(), a.spaceKey(),
                a.title(), a.revision(), a.inputSha256(), a.analyzerId(), a.analyzerVersion(), a.analyzedAt(),
                new ParsedStatements(r.sentences(), r.mentions(), List.of(statement), r.attachments(), r.components(),
                        r.conllu())));
    }

    @Test
    void checkingIsOfferedOnlyForACurrentAnalysisAndSaysWhyItCouldNotRun() {
        List<String> status = new ArrayList<>();
        tab.onStatus(status::add);
        tab.page(page("1"), text());
        runner.runAll();
        assertThat(tab.checkOffered()).as("nicht analysiert").isFalse();

        tab.clickAnalyze();
        runner.runAll();
        assertThat(tab.checkOffered()).isTrue();

        tab.clickCheck();
        assertThat(tab.cancelOffered()).as("Abbrechen wie bei der Analyse").isTrue();
        assertThat(tab.progressText()).isEqualTo(StatementsTab.CHECKING);
        assertThat(tab.analyzeOffered()).as("nie zwei Läufe zugleich").isFalse();
        runner.runAll();
        assertThat(actions.checks).isEqualTo(1);
        assertThat(tab.cancelOffered()).isFalse();
        assertThat(tab.lines().get(1)).contains("vom Prüfschritt verworfen");
        assertThat(status.getLast()).contains(", geprüft: 1 Urteil. Grammatische");

        actions.checkUnavailable = Optional.of(
                com.aresstack.spaceharvester.application.check.CheckUnavailableException.Reason.NOT_RELEASED);
        tab.clickCheck();
        runner.runAll();
        assertThat(actions.checks).as("ohne Freigabe geht nichts an das Modell").isEqualTo(1);
        assertThat(status.getLast()).isEqualTo(CheckTexts.NOT_RELEASED);
        assertThat(tab.lines().get(1)).as("die Aussagen bleiben stehen").contains("vom Prüfschritt verworfen");
        actions.checkUnavailable = Optional.of(
                com.aresstack.spaceharvester.application.check.CheckUnavailableException.Reason.NO_MODEL);
        tab.clickCheck();
        runner.runAll();
        assertThat(status.getLast()).isEqualTo(CheckTexts.NO_MODEL);
        assertThat(tab.checkOffered()).isTrue();
    }

    @Test
    void thePageSaysWhetherTheStageWasUsedAndWhyAWithdrawnOneIsStale() {
        PageStatements plain = analysed();
        assertThat(StatementsTab.hint(plain)).contains(", ohne Vorstufe. Grammatische Zuordnung");
        PageStatements staged = new PageStatements(PageStatements.State.CURRENT, null,
                plain.analysis().withStage("where-needed|gemma4:12b|prompt-1"));
        assertThat(StatementsTab.hint(staged))
                .contains(", mit Vorstufe „Sätze vereinfachen“ (nur wo nötig, gemma4:12b prompt-1). Grammatische");
        assertThat(StatementsTab.stage("always|m|prompt-1")).startsWith("mit Vorstufe „Sätze vereinfachen“ (immer, ");
        PageStatements withdrawn = new PageStatements(PageStatements.State.STALE, PageStatements.STAGE_WITHDRAWN,
                plain.analysis().withStage(
                        com.aresstack.spaceharvester.application.knowledge.PageStatementAnalysis.STAGE_WITHDRAWN));
        assertThat(StatementsTab.hint(withdrawn)).startsWith(StatementsTab.STAGE_WITHDRAWN)
                .contains("KI-Freigabe", "neu ohne Vorstufe rechnen");
    }

    @Test
    void theStageRunsOnlyWhenTickedAndSaysWhyItCouldNot() {
        List<String> status = new ArrayList<>();
        tab.onStatus(status::add);
        tab.page(page("1"), text());
        runner.runAll();

        tab.clickAnalyze();
        runner.runAll();
        assertThat(actions.simplified).as("ohne Haken keine Vorstufe").isZero();

        tab.simplify(true);
        tab.clickAnalyze();
        runner.runAll();
        assertThat(actions.simplified).isEqualTo(1);
        assertThat(status.getLast()).doesNotContain("lief nicht");

        actions.stageUnavailable = Optional.of("NOT_RELEASED");
        tab.clickAnalyze();
        runner.runAll();
        assertThat(actions.simplified).isEqualTo(2);
        assertThat(status.getLast()).endsWith(StatementsTab.SIMPLIFY_NOT_RELEASED);
        assertThat(StatementsTab.stageNote("NO_MODEL")).isEqualTo(StatementsTab.SIMPLIFY_NO_MODEL);
    }

    @Test
    void aLateResultOfALeftPageIsDiscardedAndTheRunIsCancelled() {
        tab.page(page("1"), text());
        runner.runAll();
        tab.clickAnalyze();
        tab.page(page("2"), text());
        runner.runAll();

        assertThat(actions.lastCancelled.getAsBoolean()).isTrue();
        assertThat(tab.stateLabel()).isEqualTo("Nicht analysiert");
        assertThat(tab.lines()).isEmpty();
        assertThat(tab.analyzeOffered()).isTrue();
    }

    @Test
    void aMissingModelShowsTheInstallHint() {
        actions.failure = new AnalyzerUnavailableException(AnalyzerUnavailableException.Reason.MODEL_MISSING,
                "Kein Parsermodell unter /daten/nlp-model. Zum Installieren …", null);
        tab.page(page("1"), text());
        runner.runAll();

        tab.clickAnalyze();
        runner.runAll();

        assertThat(tab.hintText()).startsWith("Kein Parsermodell unter /daten/nlp-model");
        assertThat(tab.analyzeOffered()).isTrue();
    }

    @Test
    void aStaleAnalysisIsGreyedAndExplained() {
        PageStatements current = analysed();
        actions.stored = new PageStatements(PageStatements.State.STALE, PageStatements.TEXT_CHANGED,
                current.analysis());
        tab.page(page("1"), text());
        runner.runAll();

        assertThat(tab.stateLabel()).isEqualTo("Veraltet");
        assertThat(tab.hintText()).startsWith("Der Text der Seite hat sich seit der Analyse geändert.")
                .endsWith(StatementsTab.NO_EVIDENCE);
        assertThat(tab.lines()).contains("Kernaussagen");
        assertThat(tab.lines()).as("alte Spannen nie aus dem neuen Text schneiden")
                .noneMatch(line -> line.contains("Beleg: „"));
        assertThat(tab.jumpCount()).isZero();
    }

    @Test
    void withSubpagesTheButtonStartsTheGroupOverTheSubtreeAndDoesNotWait() {
        List<String> statuses = new ArrayList<>();
        tab.onStatus(statuses::add);
        tab.withSubpages(() -> true);
        tab.confirmBelowWith(question -> true);
        tab.page(page("1"), text());
        runner.runAll();

        tab.clickAnalyze();
        runner.runAll();

        assertThat(actions.below).containsExactly("1");
        assertThat(actions.belowLimits).as("Sicherheitsgrenze wie im Baum").containsExactly(500);
        assertThat(actions.analyses).as("keine eigene Rechnung im Reiter").isZero();
        assertThat(statuses).contains(StatementsTab.STARTED_BELOW);
        assertThat(actions.belowWatch).as("der Reiter verfolgt die Gruppe").isNotNull();
    }

    /** Wie im Baum: erst zählen und mit „wirklich“ fragen; „Nein“ startet nichts, über der Grenze ohne Grenze. */
    @Test
    void withSubpagesAsksWithTheCountFirstAndNoStartsNothing() {
        List<String> questions = new ArrayList<>();
        tab.withSubpages(() -> true);
        tab.page(page("1"), text());
        runner.runAll();
        tab.confirmBelowWith(question -> {
            questions.add(question);
            return false;
        });

        tab.clickAnalyze();
        runner.runAll();

        assertThat(questions).singleElement().asString().contains("4 Seiten wirklich analysieren?")
                .contains("mit allen Unterseiten");
        assertThat(actions.below).isEmpty();
        assertThat(tab.analyzeOffered()).isTrue();

        actions.belowEstimate = new com.aresstack.spaceharvester.application.knowledge.StatementAnalysisJobs
                .Estimate(null, 500, true, false);
        tab.confirmBelowWith(question -> true);
        tab.clickAnalyze();
        runner.runAll();
        assertThat(actions.belowLimits).as("alle ausdrücklich bestätigt").containsExactly(0);
    }

    /** UX (D3): „gestartet“ bleibt nicht stehen; das Ende der Gruppe ersetzt es, „Aufträge“ ist ein Link. */
    @Test
    void theEndOfTheGroupReplacesStartedWithTheResultAndJobsIsALink() {
        List<com.aresstack.spaceharvester.app.ui.controls.StatusNote> notes = new ArrayList<>();
        List<String> opened = new ArrayList<>();
        tab.onNote(notes::add);
        tab.onShowJob(opened::add);
        tab.withSubpages(() -> true);
        tab.confirmBelowWith(question -> true);
        tab.page(page("1"), text());
        runner.runAll();
        tab.clickAnalyze();
        runner.runAll();

        com.aresstack.spaceharvester.app.ui.controls.StatusNote started = notes.get(notes.size() - 1);
        assertThat(started.text()).isEqualTo(StatementsTab.STARTED_BELOW);
        assertThat(started.link()).isEqualTo("Aufträge");
        started.action().run();
        assertThat(opened).as("öffnet den Auftrag in der Auftragsverwaltung").containsExactly("job-1");

        actions.belowWatch.accept(group(part(com.aresstack.spaceharvester.application.jobs.JobState.COMPLETED,
                new com.aresstack.spaceharvester.application.jobs.JobProgress(12, 12, 3, 0))));
        assertThat(actions.outcomesRead).as("Auftragsereignisse nie auf dem EDT").isZero();
        runner.runAll(); // Ergebnis abseits des EDT, dann liest der Reiter die Ablage neu

        assertThat(actions.outcomesRead).isEqualTo(1);
        assertThat(actions.belowWatchClosed).as("fertig: nicht weiter verfolgen").isTrue();

        com.aresstack.spaceharvester.app.ui.controls.StatusNote result = notes.get(notes.size() - 1);
        assertThat(result.text()).startsWith("Analyse der Seite samt Unterseiten fertig: 12 Seiten, davon 3 neu");
        assertThat(result.tone()).isEqualTo(com.aresstack.spaceharvester.app.ui.controls.StatusNote.Tone.PLAIN);
        assertThat(result.link()).isEqualTo("Aufträge");
    }

    @Test
    void aGroupEndingAfterThePageChangedLeavesTheNewPageAlone() {
        List<com.aresstack.spaceharvester.app.ui.controls.StatusNote> notes = new ArrayList<>();
        tab.onNote(notes::add);
        tab.withSubpages(() -> true);
        tab.confirmBelowWith(question -> true);
        tab.page(page("1"), text());
        runner.runAll();
        tab.clickAnalyze();
        runner.runAll();
        Consumer<com.aresstack.spaceharvester.application.background.BackgroundGroup> first = actions.belowWatch;

        tab.page(page("2"), text());
        runner.runAll();
        int before = notes.size();
        first.accept(group(part(com.aresstack.spaceharvester.application.jobs.JobState.COMPLETED,
                new com.aresstack.spaceharvester.application.jobs.JobProgress(4, 4, 4, 0))));
        runner.runAll();

        assertThat(actions.belowWatchClosed).as("Seitenwechsel beendet das Verfolgen").isTrue();
        assertThat(notes).hasSize(before);
    }

    /** AT zu #1059: dieselbe Seite erneut anzeigen (Klick im Baum, Ablage umschalten, Neuladen) verfolgt weiter. */
    @Test
    void showingTheSamePageAgainKeepsFollowingTheGroup() {
        List<com.aresstack.spaceharvester.app.ui.controls.StatusNote> notes = new ArrayList<>();
        tab.onNote(notes::add);
        tab.withSubpages(() -> true);
        tab.confirmBelowWith(question -> true);
        tab.page(page("1"), text());
        runner.runAll();
        tab.clickAnalyze();
        runner.runAll();

        tab.pending(new PageKey("wiki", "1"));
        tab.page(page("1"), text());
        runner.runAll();
        tab.reload();
        runner.runAll();

        assertThat(actions.belowWatchClosed).isFalse();
        actions.belowWatch.accept(group(part(com.aresstack.spaceharvester.application.jobs.JobState.COMPLETED,
                new com.aresstack.spaceharvester.application.jobs.JobProgress(2, 2, 2, 0))));
        runner.runAll();
        assertThat(notes.get(notes.size() - 1).text()).startsWith("Analyse der Seite samt Unterseiten fertig: 2 Seiten");
    }

    /** AT zu #1059: Fortschritt und Abbrechen im Reiter; nach dem Anhalten gelb und weiter verfolgt, bis fertig. */
    @Test
    void theGroupShowsProgressCanBeCancelledInTheTabAndIsFollowedPastAPause() {
        List<com.aresstack.spaceharvester.app.ui.controls.StatusNote> notes = new ArrayList<>();
        tab.onNote(notes::add);
        tab.withSubpages(() -> true);
        tab.confirmBelowWith(question -> true);
        tab.page(page("1"), text());
        runner.runAll();
        tab.clickAnalyze();
        runner.runAll();

        actions.belowWatch.accept(group(part(com.aresstack.spaceharvester.application.jobs.JobState.RUNNING,
                new com.aresstack.spaceharvester.application.jobs.JobProgress(10, 4, 4, 0))));
        assertThat(tab.progressText()).isEqualTo("Analyse der Seite samt Unterseiten: 4 von 10 Seiten …");
        assertThat(tab.cancelOffered()).isTrue();
        assertThat(tab.analyzeOffered()).as("nie zwei Läufe zugleich").isFalse();

        actions.belowWatch.accept(group(part(com.aresstack.spaceharvester.application.jobs.JobState.PAUSED,
                new com.aresstack.spaceharvester.application.jobs.JobProgress(10, 5, 5, 0))));
        assertThat(notes.get(notes.size() - 1).tone())
                .isEqualTo(com.aresstack.spaceharvester.app.ui.controls.StatusNote.Tone.WARNING);
        assertThat(tab.cancelOffered()).isFalse();
        assertThat(actions.belowWatchClosed).as("angehalten: weiter verfolgen").isFalse();

        actions.belowWatch.accept(group(part(com.aresstack.spaceharvester.application.jobs.JobState.RUNNING,
                new com.aresstack.spaceharvester.application.jobs.JobProgress(10, 6, 6, 0))));
        assertThat(notes.get(notes.size() - 1).text()).isEqualTo(StatementsTab.STARTED_BELOW);
        tab.clickCancel();
        runner.runAll();
        assertThat(actions.cancelledGroups).containsExactly("g-1");

        actions.belowWatch.accept(group(part(com.aresstack.spaceharvester.application.jobs.JobState.CANCELLED,
                new com.aresstack.spaceharvester.application.jobs.JobProgress(10, 6, 6, 0))));
        runner.runAll();
        assertThat(notes.get(notes.size() - 1).text()).startsWith("Analyse der Seite samt Unterseiten abgebrochen nach "
                + "6 Seiten");
        assertThat(tab.cancelOffered()).isFalse();
        assertThat(tab.analyzeOffered()).isTrue();
    }

    /** Review zu #1059: ohne gezeigte Seite kein „Abbrechen“, das die Gruppe einer anderen Seite träfe. */
    @Test
    void whileAnotherPageLoadsTheGroupOffersNoCancelAndItsPageGetsItBack() {
        tab.withSubpages(() -> true);
        tab.confirmBelowWith(question -> true);
        tab.page(page("1"), text());
        runner.runAll();
        tab.clickAnalyze();
        runner.runAll();
        actions.belowWatch.accept(group(part(com.aresstack.spaceharvester.application.jobs.JobState.QUEUED,
                com.aresstack.spaceharvester.application.jobs.JobProgress.NONE)));
        assertThat(tab.cancelOffered()).isTrue();

        tab.pending(new PageKey("wiki", "2"));
        assertThat(tab.cancelOffered()).isFalse();
        assertThat(tab.progressText()).isEqualTo(" ");
        tab.clickCancel();
        tab.unavailable();
        assertThat(tab.cancelOffered()).isFalse();
        runner.runAll();
        assertThat(actions.cancelledGroups).isEmpty();

        tab.page(page("1"), text());
        runner.runAll();
        assertThat(tab.cancelOffered()).as("dieselbe Seite zeigt die wartende Gruppe wieder").isTrue();
        assertThat(tab.analyzeOffered()).isFalse();
        tab.reload();
        runner.runAll();
        assertThat(tab.analyzeOffered()).as("Neuladen gibt „Analysieren“ neben der Gruppe nicht frei").isFalse();
    }

    private static com.aresstack.spaceharvester.application.background.BackgroundGroup group(
            com.aresstack.spaceharvester.application.jobs.JobStatus... parts) {
        return new com.aresstack.spaceharvester.application.background.BackgroundGroup("g-1",
                com.aresstack.spaceharvester.application.knowledge.StatementAnalysisJobs.KIND, "Aussagen", List.of(parts));
    }

    private static com.aresstack.spaceharvester.application.jobs.JobStatus part(
            com.aresstack.spaceharvester.application.jobs.JobState state,
            com.aresstack.spaceharvester.application.jobs.JobProgress progress) {
        return new com.aresstack.spaceharvester.application.jobs.JobStatus("job-1",
                com.aresstack.spaceharvester.application.jobs.JobType.BACKGROUND, "handbuch", "OPS", "app", state,
                progress, null, false, Instant.EPOCH, null, null, "1", null,
                Map.of(com.aresstack.spaceharvester.application.background.BackgroundJobs.GROUP, "g-1"), false, false,
                null);
    }

    @Test
    void skippedPagesAreYellowWithANumberAndOnlyErrorsAreRed() {
        Runnable open = () -> { };
        var omitted = StatementsTab.belowResult(new StatementsTab.BelowOutcome(10, 7, 0, 3,
                StatementsTab.BelowOutcome.End.DONE), open);
        assertThat(omitted.tone()).isEqualTo(com.aresstack.spaceharvester.app.ui.controls.StatusNote.Tone.WARNING);
        assertThat(omitted.text()).contains("10 Seiten").contains("3 Seiten bewusst ausgelassen");

        var failed = StatementsTab.belowResult(new StatementsTab.BelowOutcome(10, 9, 1, 0,
                StatementsTab.BelowOutcome.End.DONE), open);
        assertThat(failed.tone()).isEqualTo(com.aresstack.spaceharvester.app.ui.controls.StatusNote.Tone.ERROR);
        assertThat(failed.text()).contains("1 Seite mit Fehler");

        assertThat(StatementsTab.belowResult(new StatementsTab.BelowOutcome(2, 0, 0, 0,
                StatementsTab.BelowOutcome.End.FAILED), open).tone())
                .isEqualTo(com.aresstack.spaceharvester.app.ui.controls.StatusNote.Tone.ERROR);
        assertThat(StatementsTab.belowResult(new StatementsTab.BelowOutcome(2, 2, 0, 0,
                StatementsTab.BelowOutcome.End.CANCELLED), open).tone())
                .isEqualTo(com.aresstack.spaceharvester.app.ui.controls.StatusNote.Tone.PLAIN);
        var paused = StatementsTab.belowResult(new StatementsTab.BelowOutcome(2, 2, 0, 0,
                StatementsTab.BelowOutcome.End.PAUSED), open);
        assertThat(paused.tone()).isEqualTo(com.aresstack.spaceharvester.app.ui.controls.StatusNote.Tone.WARNING);
        assertThat(paused.link()).isEqualTo("Aufträge");
    }

    @Test
    void cancelReachesTheCoreJobThroughTheCancellation() {
        tab.page(page("1"), text());
        runner.runAll();
        tab.clickAnalyze();
        tab.clickCancel();
        runner.runAll();

        assertThat(actions.lastCancelled).isInstanceOf(com.aresstack.spaceharvester.app.runtime.Cancellation.class);
        assertThat(actions.lastCancelled.getAsBoolean()).isTrue();
    }

    @Test
    void cancelKeepsAnalyseLockedUntilTheRunHasReallyEnded() {
        tab.page(page("1"), text());
        runner.runAll();
        tab.clickAnalyze();

        tab.clickCancel();

        assertThat(tab.analyzeOffered()).as("kein zweiter Lauf, solange der erste noch rechnet").isFalse();
        assertThat(tab.progressText()).isEqualTo("Wird abgebrochen …");
        runner.runAll();
        assertThat(tab.analyzeOffered()).isTrue();
        assertThat(tab.progressText()).startsWith("Abgebrochen");
    }

    /** Angelo (Screenshot 09.11.): Belegtexte waren rechts abgeschnitten, dazu ein waagerechter Balken. */
    @Test
    void longEvidenceWrapsInsteadOfWideningTheContent() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            tab.page(page("1"), text());
            runner.runAll();
            tab.render(analysed(), List.of());
            // Schmaler als der Belegsatz: der Text muss umbrechen.
            tab.setSize(360, 400);
            tab.addNotify();
            tab.validate();
            tab.validate();
            javax.swing.JScrollPane scroll = (javax.swing.JScrollPane) java.util.Arrays.stream(tab.analyzeOffer().getComponents())
                    .filter(javax.swing.JScrollPane.class::isInstance).findFirst().orElseThrow();
            assertThat(scroll.getViewport().getView().getWidth())
                    .as("der Inhalt folgt der Breite des Sichtfensters")
                    .isEqualTo(scroll.getViewport().getWidth());
            assertThat(scroll.getHorizontalScrollBar().isVisible()).isFalse();
        });
    }

    /** Codex zu #566: Beim Seitenwechsel kopierte der Knopf noch die Aussagen der vorigen Seite. */
    @Test
    void leavingThePageEmptiesTheClipboardText() {
        tab.page(page("1"), text());
        runner.runAll();
        tab.render(analysed(), List.of());
        assertThat(tab.copyText()).isNotBlank();

        tab.pending(new PageKey("wiki", "2"));
        assertThat(tab.copyText()).isEmpty();
    }

    @Test
    void reloadingTheSamePageKeepsARunningAnalysis() {
        tab.page(page("1"), text());
        runner.runAll();
        tab.clickAnalyze();

        tab.pending(new PageKey("wiki", "1"));
        tab.page(page("1"), text());
        runner.runAll();

        assertThat(actions.lastCancelled.getAsBoolean()).isFalse();
        assertThat(tab.stateLabel()).isEqualTo("Aktuell");
    }

    @Test
    void aChangedTextOfTheSamePageCancelsTheRunningAnalysis() {
        List<String> analysed = new ArrayList<>();
        tab.onAnalysed(() -> analysed.add("x"));
        tab.page(page("1"), text());
        runner.runAll();
        tab.clickAnalyze();

        tab.page(page("1"), new PageText("test/v1", "Ganz anderer Text.", List.of()));
        runner.runAll();

        assertThat(actions.lastCancelled.getAsBoolean()).isTrue();
        assertThat(tab.stateLabel()).isEqualTo("Nicht analysiert");
        assertThat(analysed).isEmpty();

        tab.clickAnalyze();
        runner.runAll();
        assertThat(analysed).as("neue Analyse gemeldet, etwa für den Graph-Reiter").hasSize(1);
    }

    @Test
    void loadingAndFailedPagesClaimNothingAboutTheAnalysis() {
        tab.pending(new PageKey("wiki", "1"));
        assertThat(tab.hintText()).isEqualTo(StatementsTab.LOADING_PAGE);
        assertThat(tab.stateLabel()).isBlank();
        assertThat(tab.analyzeOffered()).isFalse();

        tab.unavailable();
        assertThat(tab.hintText()).isEqualTo(StatementsTab.PAGE_UNAVAILABLE);
    }

    @Test
    void aNewAnalysisReplacesOpenSyntaxDetails() {
        actions.stored = analysed();
        tab.page(page("1"), text());
        runner.runAll();
        tab.clickSyntax();
        runner.runAll();
        assertThat(tab.syntaxText()).isEqualTo("1\tDer\n");

        actions.syntax = "1\tNeu\n";
        tab.clickAnalyze();
        runner.runAll();

        assertThat(tab.syntaxText()).isEqualTo("1\tNeu\n");
    }

    @Test
    void withoutAnalyzerTheButtonIsDisabled() {
        actions.available = false;
        tab.page(page("1"), text());
        runner.runAll();

        assertThat(tab.analyzeOffered()).isFalse();
        assertThat(tab.hintText()).isEqualTo(StatementsTab.NOT_AVAILABLE);
    }

    static PageStatements analysed() {
        ParsedStatements.Mention subject = mention("t2", 0, 18, "Der Sachbearbeiter", "Sachbearbeiter");
        ParsedStatements.Mention recipient = mention("t5", 31, 47, "dem Vorgesetzten", "Vorgesetzte");
        ParsedStatements.Mention time = mention("t8", 51, 57, "Montag", "Montag");
        ParsedStatements.Mention object = mention("t10", 58, 67, "den Beleg", "Beleg");
        ParsedStatements.Mention customer = mention("t12", 68, 78, "des Kunden", "Kunde");
        ParsedStatements.Statement statement = new ParsedStatements.Statement(DOC + "#s1.v3", 0, 19, 30,
                "übermittelt", "übermitteln", ParsedStatements.Voice.ACTIVE, ParsedStatements.Clause.MAIN, true,
                List.of(argument(ParsedStatements.Role.ACTOR, subject, "nsubj", null, true),
                        argument(ParsedStatements.Role.AFFECTED_OBJECT, object, "obj", null, true),
                        argument(ParsedStatements.Role.RECIPIENT, recipient, "obl:arg", null, true),
                        new ParsedStatements.Argument(ParsedStatements.Role.TIME, time.id(), "nmod", "am",
                                ParsedStatements.Priority.CONTEXT, ParsedStatements.Basis.LEXICON, false)),
                Set.of("TIME_FROM_NOMINAL"));
        ParsedStatements result = new ParsedStatements(List.of(new ParsedStatements.Sentence(0, 0, TEXT.length())),
                List.of(subject, recipient, time, object, customer), List.of(statement),
                List.of(new ParsedStatements.Attachment(object.id() + "→t12", object.id(), customer.id(), "nmod",
                        null, ParsedStatements.AttachmentKind.GENITIVE, ParsedStatements.Basis.PARSER)),
                List.of("dependencies=maltparser 1.9.2"), null);
        return new PageStatements(PageStatements.State.CURRENT, null, new PageStatementAnalysis(
                new PageKey("wiki", "1"), "DEV", "Seite 1", "3", "sha256:0123456789abcdef", "maltparser-de",
                "1|maltparser-de-gsd-v1.0.0|237885c25519|rules-1", Instant.parse("2026-11-09T15:00:00Z"), result));
    }

    private static ParsedStatements.Mention mention(String token, int start, int end, String text, String lemma) {
        return new ParsedStatements.Mention(DOC + "#s1." + token, 0, start, end, text, lemma, lemma,
                ParsedStatements.MentionKind.NOUN, false, Set.of());
    }

    private static ParsedStatements.Argument argument(ParsedStatements.Role role, ParsedStatements.Mention mention,
                                                      String relation, String marker, boolean certain) {
        return new ParsedStatements.Argument(role, mention.id(), relation, marker,
                role == ParsedStatements.Role.RECIPIENT ? ParsedStatements.Priority.SUPPLEMENTARY
                        : ParsedStatements.Priority.CORE, ParsedStatements.Basis.PARSER, certain);
    }

    private static PageText text() {
        return new PageText("test/v1", TEXT, List.of());
    }

    private static PageSnapshot page(String id) {
        PageMetadata metadata = new PageMetadata(id, "confluence:wiki/page/" + id, "Seite " + id, "DEV", null, "3",
                Instant.parse("2026-11-01T10:00:00Z"), URI.create("https://wiki.example.org/" + id),
                PageMetadata.sorted(Map.of()));
        return PageSnapshot.of("wiki", metadata, new NativePayload(TEXT.getBytes(StandardCharsets.UTF_8),
                "text/plain", "test-text"), Instant.parse("2026-11-09T10:00:00Z"));
    }

    private static final class FakeActions implements StatementsTab.Actions {

        PageStatements stored = PageStatements.missing(PageStatements.NOT_ANALYSED);
        boolean available = true;
        RuntimeException failure;
        int analyses;
        BooleanSupplier lastCancelled = () -> false;
        int simplified;
        Optional<String> stageUnavailable = Optional.empty();
        int checks;
        final List<PageKey> deletions = new ArrayList<>();
        Optional<com.aresstack.spaceharvester.application.check.CheckUnavailableException.Reason> checkUnavailable =
                Optional.empty();
        com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice analyzer =
                com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice.PARSER;
        Optional<com.aresstack.spaceharvester.application.knowledge.AnalyzerUnavailableException.Reason> skipped =
                Optional.empty();

        @Override
        public com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice analyzer() {
            return analyzer;
        }

        @Override
        public Optional<com.aresstack.spaceharvester.application.knowledge.AnalyzerUnavailableException.Reason>
                skipped(PageKey key) {
            return skipped;
        }

        @Override
        public int deleteAnalysis(PageKey key) {
            deletions.add(key);
            int count = stored.analysis() == null ? 0 : 2;
            stored = PageStatements.missing(PageStatements.NOT_ANALYSED);
            return count;
        }

        @Override
        public Optional<com.aresstack.spaceharvester.application.check.CheckUnavailableException.Reason>
                checkUnavailable(PageKey key) {
            return checkUnavailable;
        }

        @Override
        public PageStatements checkStatements(PageSnapshot page, BooleanSupplier cancelled) {
            checks++;
            ParsedStatements result = stored.analysis().result();
            List<ParsedStatements.Statement> judged = new ArrayList<>(result.statements());
            judged.set(0, judged.getFirst().withCheck(new ParsedStatements.Check(ParsedStatements.Verdict.FALSCH,
                    "schein-modell|v1", false)));
            stored = new PageStatements(PageStatements.State.CURRENT, null, stored.analysis().withResult(
                    new ParsedStatements(result.sentences(), result.mentions(), judged, result.attachments(),
                            result.components(),
                            result.conllu())));
            return stored;
        }

        @Override
        public PageStatements analyzeSimplified(PageSnapshot page, BooleanSupplier cancelled) {
            simplified++;
            return analyzeStatements(page, cancelled);
        }

        @Override
        public Optional<String> simplificationUnavailable(PageKey key) {
            return stageUnavailable;
        }

        @Override
        public PageStatements statements(PageSnapshot page) {
            return stored;
        }

        @Override
        public boolean statementAnalysisAvailable() {
            return available;
        }

        @Override
        public List<KnowledgeGraph.Link> statementLinks(PageKey key) {
            if (stored.analysis() == null) {
                return List.of();
            }
            return List.of(new KnowledgeGraph.Link("beleg", KnowledgeGraph.Match.LEXICAL, List.of(DOC + "#s1.t10"),
                    new KnowledgeGraph.PageRef(new PageKey("handbuch", "7"), "OPS", "Belege",
                            PageStatements.State.CURRENT, null), List.of("handbuch/7@abc#s1.t4")));
        }

        final List<String> below = new ArrayList<>();
        /** Verfolgt die Gruppe, wie der Core sie meldet. */
        Consumer<com.aresstack.spaceharvester.application.background.BackgroundGroup> belowWatch;
        boolean belowWatchClosed;
        int outcomesRead;
        final List<String> cancelledGroups = new ArrayList<>();

        final List<Integer> belowLimits = new ArrayList<>();
        com.aresstack.spaceharvester.application.knowledge.StatementAnalysisJobs.Estimate belowEstimate;

        @Override
        public com.aresstack.spaceharvester.application.knowledge.StatementAnalysisJobs.Estimate estimateBelow(
                PageSnapshot page, int limit) {
            return belowEstimate != null ? belowEstimate
                    : new com.aresstack.spaceharvester.application.knowledge.StatementAnalysisJobs.Estimate(null, 4,
                    false, false);
        }

        @Override
        public com.aresstack.spaceharvester.application.jobs.JobStatus startBelow(PageSnapshot page,
                                                                                  boolean withStage, int maxPages) {
            below.add(page.key().pageId());
            belowLimits.add(maxPages);
            return new com.aresstack.spaceharvester.application.jobs.JobStatus("job-" + page.key().pageId(),
                    com.aresstack.spaceharvester.application.jobs.JobType.BACKGROUND, "handbuch", "OPS", "app",
                    com.aresstack.spaceharvester.application.jobs.JobState.QUEUED,
                    com.aresstack.spaceharvester.application.jobs.JobProgress.NONE, null, false, Instant.EPOCH, null,
                    null, page.key().pageId(), null,
                    Map.of(com.aresstack.spaceharvester.application.background.BackgroundJobs.GROUP, "g-1"), false,
                    false, null);
        }

        @Override
        public AutoCloseable watchBelow(com.aresstack.spaceharvester.application.jobs.JobStatus started,
                Consumer<com.aresstack.spaceharvester.application.background.BackgroundGroup> listener) {
            belowWatch = listener;
            belowWatchClosed = false;
            return () -> belowWatchClosed = true;
        }

        @Override
        public StatementsTab.BelowOutcome belowOutcome(
                com.aresstack.spaceharvester.application.background.BackgroundGroup group) {
            assertThat(javax.swing.SwingUtilities.isEventDispatchThread()).isFalse();
            outcomesRead++;
            return StatementsTab.BelowOutcome.of(group, 0);
        }

        @Override
        public void cancelBelow(String groupId) {
            cancelledGroups.add(groupId);
        }

        @Override
        public PageStatements analyzeStatements(PageSnapshot page, BooleanSupplier cancelled) {
            analyses++;
            lastCancelled = cancelled;
            if (failure != null) {
                throw failure;
            }
            if (cancelled.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException(); // wie der Parser zwischen zwei Sätzen
            }
            PageStatements result = analysed();
            if (!cancelled.getAsBoolean()) {
                stored = result;
            }
            return result;
        }

        String syntax = "1\tDer\n";

        @Override
        public Optional<String> syntaxDetails(PageSnapshot page) {
            return Optional.of(syntax);
        }
    }

    /** Hält Hintergrundarbeit zurück, bis der Test sie ausführt (wie späte Ergebnisse nach Seitenwechsel). */
    private static final class DeferredRunner implements StatementsTab.Runner {

        private final List<Runnable> pending = new ArrayList<>();

        @Override
        public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
            pending.add(() -> {
                T result;
                try {
                    result = work.call();
                } catch (Throwable e) {
                    onFailure.accept(e);
                    return;
                }
                onSuccess.accept(result);
            });
        }

        void runAll() {
            while (!pending.isEmpty()) {
                pending.removeFirst().run();
            }
        }
    }

    @Test
    void featuresShowOnlyWhatDeviatesFromTheDefault() {
        assertThat(StatementsTab.features(ParsedStatements.Features.UNKNOWN)).isEmpty();
        assertThat(StatementsTab.features(new ParsedStatements.Features(ParsedStatements.Negation.AFFIRMED,
                ParsedStatements.Modality.NONE, ParsedStatements.Temporality.GILT))).isEmpty();
        assertThat(StatementsTab.features(new ParsedStatements.Features(ParsedStatements.Negation.NEGATED,
                ParsedStatements.Modality.SOLL, ParsedStatements.Temporality.GEPLANT)))
                .isEqualTo("verneint · soll · geplant");
    }
}
