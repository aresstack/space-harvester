package com.aresstack.spaceharvester.mainframe.outline;

import com.aresstack.spaceharvester.mainframe.model.JclElement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OutlineTest {

    static final String JCL = """
            //BEISPJOB JOB (ACCT),'TESTLAUF',CLASS=A,MSGCLASS=X
            //* erfundener Beispieljob
            //SCHRITT1 EXEC PGM=IEFBR14
            //EINGABE  DD DSN=TEST.DATEN.EINGABE,DISP=SHR
            //SCHRITT2 EXEC PGM=SORT
            //SYSIN    DD *
              SORT FIELDS=(1,10,CH,A)
            /*
            """;

    static final String COBOL = """
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

    static final String NATURAL = """
            DEFINE DATA
            LOCAL
            1 #ZAEHLER (N4)
            END-DEFINE
            CALLNAT 'UNTERPRG' #ZAEHLER
            PERFORM AUSGABE
            DEFINE SUBROUTINE AUSGABE
              WRITE #ZAEHLER
            END-SUBROUTINE
            END
            """;

    @Test
    void jclIsGroupedLikeMainframeMate() {
        Outline outline = Outline.parse(SourceLanguage.JCL, JCL, "BEISPJOB.jcl");

        assertThat(outline.isEmpty()).isFalse();
        List<String> labels = outline.groups(Outline.ALL).stream().map(Outline.Group::label).toList();
        assertThat(labels).startsWith("Jobs", "Steps", "DDs");
        assertThat(outline.status()).isEqualTo("1 Jobs, 2 Steps, " + outline.model().getElementCount() + " Elemente");
        assertThat(outline.filters()).containsExactly("Alle", "Jobs", "Steps (EXEC)", "DDs", "Prozeduren");
        List<JclElement> steps = outline.groups("Steps (EXEC)").get(0).elements();
        assertThat(steps).extracting(JclElement::getName).containsExactly("SCHRITT1", "SCHRITT2");
        assertThat(steps.get(0).getLineNumber()).isEqualTo(3);
    }

    @Test
    void cobolHasDivisionsAndParagraphs() {
        Outline outline = Outline.parse(SourceLanguage.COBOL, COBOL, "BEISPIEL.cbl");

        assertThat(outline.model().getDivisions()).isNotEmpty();
        assertThat(outline.groups("Paragraphs").get(0).elements()).extracting(JclElement::getName)
                .contains("HAUPT", "VERARBEITUNG");
    }

    @Test
    void naturalHasSubroutinesAndCalls() {
        Outline outline = Outline.parse(SourceLanguage.NATURAL, NATURAL, "BEISPIEL.NSP");

        assertThat(outline.model().getSubroutines()).isNotEmpty();
        assertThat(outline.model().getNaturalCalls()).isNotEmpty();
        assertThat(outline.title()).isEqualTo("Natural-Gliederung");
    }

    @Test
    void detectsLanguageByExtensionThenContent() {
        assertThat(SourceLanguage.detect("LAUF.jcl", "")).contains(SourceLanguage.JCL);
        assertThat(SourceLanguage.detect("PRG.cbl", "")).contains(SourceLanguage.COBOL);
        assertThat(SourceLanguage.detect("PRG.NSN", "")).contains(SourceLanguage.NATURAL);
        assertThat(SourceLanguage.detect("FELDER.NSD", "")).contains(SourceLanguage.DDM);
        assertThat(SourceLanguage.detect("ohne-endung", JCL)).contains(SourceLanguage.JCL);
        assertThat(SourceLanguage.detect("ohne-endung", COBOL)).contains(SourceLanguage.COBOL);
        assertThat(SourceLanguage.detect("ohne-endung", NATURAL)).contains(SourceLanguage.NATURAL);
        assertThat(SourceLanguage.detect("notiz.txt", "Nur ein Satz.")).isEmpty();
    }
}
