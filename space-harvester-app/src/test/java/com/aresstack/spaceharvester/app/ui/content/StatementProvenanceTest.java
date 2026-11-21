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
    void reasonsAreWordsAndUnknownCodesStayReadable() {
        assertThat(StatementProvenance.reason("HEURISTIC_PARTICIPANT")).isEqualTo("Beteiligter aus dem Kasus "
                + "geschlossen");
        assertThat(StatementProvenance.reason("NEU_X")).isEqualTo("NEU_X");
    }

    private static ParsedStatements.Statement statement(ParsedStatements.Quality quality,
                                                        ParsedStatements.Review review) {
        return new ParsedStatements.Statement("wiki/1@h#s0.v1", 0, 0, 5, "prüft", "prüfen",
                ParsedStatements.Voice.ACTIVE, ParsedStatements.Clause.MAIN, true, List.of(), Set.of(),
                0, quality, quality == ParsedStatements.Quality.PLAUSIBLE ? List.of() : List.of("NO_ACTOR"), review);
    }
}
