package com.aresstack.spaceharvester.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.assets.DocumentKey;
import com.aresstack.spaceharvester.application.evidence.SourceLocationEvidence;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Programmaussagen zählen als Ist-Zustand mit eigener Stelle in Fachsicht und Verben (ADR 0032, Fortschreibung
 * 26.11.2026); Vorstufe und Korrektur zählen weiter nie. Erfundenes Programm ANTRAG01.
 */
class ProgramStatementCountingTest {

    private static final DocumentKey PROGRAM = DocumentKey.page(new PageKey("ftp", "ANTRAG01"));

    @Test
    void programStatementsCountWithTheirOwnSiteButStageAndCorrectionsStillDoNot() {
        var program = statement().withDerivation(ParsedStatements.Derivation.programm(
                "Der Sachbearbeiter prüft den Antrag.", new SourceLocationEvidence(PROGRAM, "p1",
                        List.of(new SourceLocationEvidence.CodeSite(PROGRAM, "p1", 3, 4)), List.of(), List.of(),
                        "cobol-program@5")));
        var correction = statement().withDerivation(ParsedStatements.Derivation.modell("Satz.", "x",
                ParsedStatements.Origin.ORIGINAL));

        assertThat(DomainTermGraph.counts(statement())).isTrue();
        assertThat(DomainTermGraph.counts(program)).isTrue();
        assertThat(VerbForms.counts(program)).isTrue();
        assertThat(DomainTermGraph.counts(correction)).isFalse();
        assertThat(VerbForms.counts(correction)).isFalse();
    }

    private static ParsedStatements.Statement statement() {
        return new ParsedStatements.Statement("ftp/ANTRAG01@h#s0.v1", 0, 0, 5, "prüft", "prüfen",
                ParsedStatements.Voice.ACTIVE, ParsedStatements.Clause.MAIN, true, List.of(), Set.of(), 0,
                ParsedStatements.Quality.PLAUSIBLE, List.of(), ParsedStatements.Review.UNREVIEWED);
    }
}
