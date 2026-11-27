package com.aresstack.spaceharvester.app.runtime.program;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.enrichment.PageText;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Gliederung von Großrechner-Quelltext als Überschriften; erfundene Beispiele wie im OutlineTest. */
class ProgramOutlineTextProjectorTest {

    @Test
    void jclJobAndStepsBecomeHeadingsAndDdsDoNot() {
        String jcl = """
                //BEISPJOB JOB (ACCT),'TESTLAUF',CLASS=A,MSGCLASS=X
                //SCHRITT1 EXEC PGM=IEFBR14
                //EINGABE  DD DSN=TEST.DATEN.EINGABE,DISP=SHR
                //SCHRITT2 EXEC PGM=SORT
                //SYSIN    DD *
                """;

        List<PageText.Section> sections = ProgramOutlineTextProjector.sections("BEISPJOB.jcl", jcl);

        assertThat(sections).extracting(PageText.Section::heading)
                .containsExactly("BEISPJOB", "SCHRITT1", "SCHRITT2");
        assertThat(sections.get(0).level()).isEqualTo(1);
        assertThat(sections.get(1).level()).isEqualTo(2);
        assertThat(jcl.substring(sections.get(1).start(), sections.get(1).end()))
                .startsWith("//SCHRITT1").contains("EINGABE").doesNotContain("SCHRITT2");
        assertThat(sections.get(0).end()).isEqualTo(jcl.length());
    }

    @Test
    void cobolParagraphsSitBelowTheirDivision() {
        String cobol = """
                       IDENTIFICATION DIVISION.
                       PROGRAM-ID. BEISPIEL.
                       DATA DIVISION.
                       WORKING-STORAGE SECTION.
                       01  WS-ZAEHLER        PIC 9(4) VALUE 0.
                       PROCEDURE DIVISION.
                       HAUPT.
                           PERFORM VERARBEITUNG.
                           STOP RUN.
                       VERARBEITUNG.
                           ADD 1 TO WS-ZAEHLER.
                """;

        List<PageText.Section> sections = ProgramOutlineTextProjector.sections("BEISPIEL.cbl", cobol);

        assertThat(sections).extracting(PageText.Section::level).contains(1, 2, 3);
        assertThat(sections.stream().filter(s -> s.level() == 3).map(PageText.Section::heading))
                .contains("HAUPT", "VERARBEITUNG");
        assertThat(sections).allSatisfy(s -> assertThat(s.end()).isGreaterThanOrEqualTo(s.start()));
    }

    @Test
    void naturalSubroutineIsAHeadingAndUnknownTextHasNone() {
        String natural = """
                DEFINE DATA
                LOCAL
                1 #ZAEHLER (N4)
                END-DEFINE
                PERFORM AUSGABE
                DEFINE SUBROUTINE AUSGABE
                  WRITE #ZAEHLER
                END-SUBROUTINE
                END
                """;

        assertThat(ProgramOutlineTextProjector.sections("BEISPIEL.nsp", natural))
                .extracting(PageText.Section::heading).contains("AUSGABE");
        assertThat(ProgramOutlineTextProjector.sections("notiz.txt", "nur ein Satz ohne Code")).isEmpty();
    }
}
