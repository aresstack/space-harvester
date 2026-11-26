package com.aresstack.spaceharvester.application.check;

import com.aresstack.spaceharvester.application.assets.DocumentKey;
import com.aresstack.spaceharvester.application.evidence.SourceLocationEvidence;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Aussagen der Programmanalyse gehen nie in den Prüfschritt: Ihr Beleg ist eine Quellstelle, kein Satz. */
class CheckScopeProgramTest {

    @Test
    void programStatementsAreNeverChecked() {
        var program = DocumentKey.page(new PageKey("ftp-test", "TEST.COBOL(ANTRAG01)"));
        var source = new SourceLocationEvidence(program, "p1",
                List.of(new SourceLocationEvidence.CodeSite(program, "p1", 10, 12)), List.of(), List.of(), "v1");
        var statement = new ParsedStatements.Statement("ftp-test/TEST.COBOL(ANTRAG01)@abc#s0.v1", 0, 0, 10,
                "prüft", "prüfen", ParsedStatements.Voice.ACTIVE, ParsedStatements.Clause.MAIN, true, List.of(),
                Set.of()).withDerivation(ParsedStatements.Derivation.programm("Die Stelle prüft den Antrag.", source));

        assertThat(CheckScope.ALL.includes(statement)).isFalse();
        assertThat(CheckScope.UNCERTAIN_AND_STAGE.includes(statement)).isFalse();
    }
}
