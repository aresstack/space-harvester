package com.aresstack.spaceharvester.app.ui.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Herkunft und Prüfstatus einer Aussage in Worten (Fahrplan Analyse-Reiter, Paket A1). */
class StatementProvenanceTest {

    @Test
    void uncertainIsAProposalAndInvalidStaysVisibleWithItsWordNeverAsFact() {
        assertThat(StatementProvenance.marker(statement(ParsedStatements.Quality.PLAUSIBLE,
                ParsedStatements.Review.UNREVIEWED))).isNull();
        assertThat(StatementProvenance.marker(statement(ParsedStatements.Quality.UNCERTAIN,
                ParsedStatements.Review.UNREVIEWED))).isEqualTo("Vorschlag");
        assertThat(StatementProvenance.marker(statement(ParsedStatements.Quality.INVALID,
                ParsedStatements.Review.UNREVIEWED))).isEqualTo("ungültig");
        assertThat(StatementProvenance.flagged(statement(ParsedStatements.Quality.INVALID,
                ParsedStatements.Review.UNREVIEWED))).isTrue();
    }

    @Test
    void aJudgementByHandGoesFirst() {
        assertThat(StatementProvenance.marker(statement(ParsedStatements.Quality.UNCERTAIN,
                ParsedStatements.Review.CONFIRMED))).isEqualTo("von Hand bestätigt");
        assertThat(StatementProvenance.marker(statement(ParsedStatements.Quality.PLAUSIBLE,
                ParsedStatements.Review.REJECTED))).isEqualTo("von Hand verworfen");
        assertThat(StatementProvenance.details(statement(ParsedStatements.Quality.PLAUSIBLE,
                ParsedStatements.Review.REJECTED), null, null)).contains("Urteil von Hand: verworfen");
    }

    @Test
    void theLineAlwaysNamesOriginAnalyzerAndCheckStatus() {
        ParsedStatements.Statement plain = statement(ParsedStatements.Quality.PLAUSIBLE,
                ParsedStatements.Review.UNREVIEWED);
        assertThat(StatementProvenance.line(plain, "MaltParser")).isEqualTo("Originalsatz, MaltParser · nicht geprüft");
        assertThat(StatementProvenance.line(plain.withCheck(new ParsedStatements.Check(
                ParsedStatements.Verdict.STIMMT, "m|1", false)), null)).isEqualTo("Originalsatz · vom Prüfschritt "
                + "bestätigt");
        assertThat(StatementProvenance.line(plain.withCheck(new ParsedStatements.Check(
                ParsedStatements.Verdict.UNVOLLSTAENDIG, "m|1", true)), null))
                .isEqualTo("Vorschlag · Originalsatz · vom Prüfschritt als unvollständig eingestuft, Korrektur "
                        + "ergänzt");
        assertThat(StatementProvenance.line(plain.withDerivation(ParsedStatements.Derivation.modell("Satz.", "x",
                ParsedStatements.Origin.ORIGINAL)), "MaltParser"))
                .isEqualTo("Korrektur des Modells (KI), MaltParser · nicht geprüft");
        assertThat(StatementProvenance.analyzer("maltparser-de")).isEqualTo("MaltParser");
        assertThat(StatementProvenance.analyzer("anderer")).isEqualTo("anderer");
        assertThat(StatementProvenance.analyzer(null)).isNull();
    }

    @Test
    void aParticipantInferredFromTheCaseIsShownAsSuchNextToTheReason() {
        ParsedStatements.Mention actor = new ParsedStatements.Mention("wiki/1@h#s0.t1", 0, 0, 3, "Amt", "Amt", "Amt",
                ParsedStatements.MentionKind.NOUN, false, Set.of());
        ParsedStatements.Mention object = new ParsedStatements.Mention("wiki/1@h#s0.t3", 0, 10, 16, "Antrag",
                "Antrag", "Antrag", ParsedStatements.MentionKind.NOUN, false, Set.of());
        ParsedStatements.Statement statement = new ParsedStatements.Statement("wiki/1@h#s0.v2", 0, 4, 9, "prüft",
                "prüfen", ParsedStatements.Voice.ACTIVE, ParsedStatements.Clause.MAIN, true, List.of(
                new ParsedStatements.Argument(ParsedStatements.Role.ACTOR, actor.id(), "nsubj", null,
                        ParsedStatements.Priority.CORE, ParsedStatements.Basis.PARSER, true),
                new ParsedStatements.Argument(ParsedStatements.Role.AFFECTED_OBJECT, object.id(), "obj?", null,
                        ParsedStatements.Priority.CORE, ParsedStatements.Basis.CASE_HEURISTIC, false)),
                Set.of("HEURISTIC_PARTICIPANT"), 0, ParsedStatements.Quality.UNCERTAIN,
                List.of("HEURISTIC_PARTICIPANT"), ParsedStatements.Review.UNREVIEWED);

        String details = StatementProvenance.details(statement, "MaltParser",
                java.util.Map.of(actor.id(), actor, object.id(), object));

        assertThat(details).contains("Kennzeichen: Vorschlag", "Gründe: Beteiligter aus dem Kasus geschlossen",
                "Handelnder „Amt“: Parserkante (nsubj), sicher",
                "Betroffen „Antrag“: aus dem Kasus geschlossen, unsicher");
    }

    @Test
    void reasonsAreWordsAndUnknownCodesStayReadable() {
        assertThat(StatementProvenance.reason("HEURISTIC_PARTICIPANT")).isEqualTo("Beteiligter aus dem Kasus "
                + "geschlossen");
        assertThat(StatementProvenance.reason("NEU_X")).isEqualTo("NEU_X");
    }

    @Test
    void aProgramStatementCarriesItsInsightLevelAndIsNeverConfirmedOnStaleEvidence() {
        var program = com.aresstack.spaceharvester.application.assets.DocumentKey.page(
                new com.aresstack.spaceharvester.application.snapshot.PageKey("ftp", "ANTRAG01"));
        var copybook = com.aresstack.spaceharvester.application.assets.DocumentKey.page(
                new com.aresstack.spaceharvester.application.snapshot.PageKey("ftp", "ANTRAGCP"));
        var site = new com.aresstack.spaceharvester.application.evidence.SourceLocationEvidence.CodeSite(program,
                "p1", 120, 121);
        var evidence = new com.aresstack.spaceharvester.application.evidence.SourceLocationEvidence(program, "p1",
                List.of(site, new com.aresstack.spaceharvester.application.evidence.SourceLocationEvidence.CodeSite(
                        copybook, "c1", 3, 3)),
                List.of(new com.aresstack.spaceharvester.application.evidence.SourceLocationEvidence.ParserFact(
                        "CONTROL", "IF KZ-STATUS = 'A'", 0, 120, 120)),
                List.of(com.aresstack.spaceharvester.application.evidence.SourceLocationEvidence.Dependency.resolved(
                                "ANTRAGCP", copybook, "c1"),
                        com.aresstack.spaceharvester.application.evidence.SourceLocationEvidence.Dependency.missing(
                                "FEHLT")), "cobol-program@5");
        var derivation = ParsedStatements.Derivation.programm("Der Sachbearbeiter prüft den Antrag.", evidence);
        var open = statement(ParsedStatements.Quality.PLAUSIBLE, ParsedStatements.Review.UNREVIEWED)
                .withDerivation(derivation);
        var confirmed = statement(ParsedStatements.Quality.PLAUSIBLE, ParsedStatements.Review.CONFIRMED)
                .withDerivation(derivation);

        assertThat(StatementProvenance.line(open, null, false))
                .isEqualTo("Hypothese · Programmanalyse (KI-Hypothese, Ist-Zustand) · nicht geprüft");
        assertThat(StatementProvenance.marker(confirmed, false)).isEqualTo("von Hand bestätigt");
        assertThat(StatementProvenance.marker(confirmed, true)).isEqualTo("Hypothese, Bestätigung veraltet");
        assertThat(StatementProvenance.details(confirmed, null, null, true))
                .contains("Erkenntnisebene: Hypothese, Bestätigung veraltet")
                .contains("Bezug: Ist-Zustand des Programms, keine geltende Soll-Regel")
                .contains("Hypothese: „Der Sachbearbeiter prüft den Antrag.“")
                .contains("Quellstellen: ANTRAG01 Zeilen 120–121; ANTRAGCP Zeile 3")
                .contains("Parserfakt (CONTROL, Zeile 120): „IF KZ-STATUS = 'A'“")
                .contains("Abhängigkeiten: ANTRAGCP, FEHLT (nicht gefunden)")
                .contains("Programmstand: p1 · Programmanalyse: cobol-program@5");
    }

    private static ParsedStatements.Statement statement(ParsedStatements.Quality quality,
                                                        ParsedStatements.Review review) {
        return new ParsedStatements.Statement("wiki/1@h#s0.v1", 0, 0, 5, "prüft", "prüfen",
                ParsedStatements.Voice.ACTIVE, ParsedStatements.Clause.MAIN, true, List.of(), Set.of(),
                0, quality, quality == ParsedStatements.Quality.PLAUSIBLE ? List.of() : List.of("NO_ACTOR"), review);
    }
}
