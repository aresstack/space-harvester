package com.aresstack.spaceharvester.mainframe.diagram;

import com.aresstack.spaceharvester.mainframe.diagram.OutlineToMermaidConverter.DiagramType;
import com.aresstack.spaceharvester.mainframe.outline.SourceLanguage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Blockbildung für COBOL (Flussdiagramm) und JCL: ohne KI die Fakten, mit KI ausführlich beschrieben. */
class CobolJclBlocksTest {

    private static final String COBOL = """
                   IDENTIFICATION DIVISION.
                   PROGRAM-ID. DEMO.
                   DATA DIVISION.
                   WORKING-STORAGE SECTION.
                   01 WS-A PIC 9(4).
                   01 WS-B PIC 9(4).
                   PROCEDURE DIVISION.
                   MAIN-PARA.
                       MOVE 1 TO WS-A.
                       ADD 2 TO WS-A.
                       MOVE WS-A TO WS-B.
                       IF WS-A > 2
                          DISPLAY 'GROSS'
                       ELSE
                          DISPLAY 'KLEIN'
                       END-IF.
                       PERFORM WORK-PARA.
                       GOBACK.
                   WORK-PARA.
                       COMPUTE WS-B = WS-A * 2.
                       DISPLAY WS-B.
                       EXIT.
            """;

    private static final String JCL = """
            //BUILD    JOB (A),'SORTIEREN'
            //STEP1    EXEC PGM=SORT
            //SORTIN   DD DSN=KUNDEN.ROH,DISP=SHR
            //SORTOUT  DD DSN=KUNDEN.SORTIERT,DISP=(NEW,CATLG)
            //SYSIN    DD *
              SORT FIELDS=(1,10,CH,A)
            /*
            """;

    @AfterEach
    void reset() {
        OutlineToMermaidConverter.blockLabelSummarizer(null);
    }

    @Test
    void compactCobolFlowJoinsSimpleStatementChainsWithoutInventingEdges() {
        String full = cobol(false, false);
        String compact = cobol(true, false);

        assertThat(full).doesNotContain("📦");
        // MOVE, ADD, MOVE werden ein Block, in Quelltextreihenfolge; die Verzweigung bleibt eine Raute
        // Ohne KI nur die Tatsache, kein nacherzählter Code im Knoten
        assertThat(compact).contains("📦 3 Anweisungen (L9–L11)\"]").doesNotContain("\\nMOVE 1 TO WS-A");
        assertThat(compact).contains("IF WS-A");
        assertThat(compact).contains("PERFORM_CALL", "TERMINATE");
        // jede Kante des Blocks führt zu einem Knoten, den es gibt; keine Kante innerhalb des Blocks
        assertThat(compact.lines().filter(l -> l.contains("-->")).count())
                .isLessThan(full.lines().filter(l -> l.contains("-->")).count());
    }

    @Test
    void withAiCobolBlocksAndDecisionsAreDescribedFromTheirLines() {
        RecordingAi ai = new RecordingAi();
        OutlineToMermaidConverter.blockLabelSummarizer(ai);

        String compact = cobol(true, true);

        assertThat(compact).contains("Beschreibung").contains("Frage");
        assertThat(ai.codes).anySatisfy(code -> assertThat(code).contains("MOVE 1 TO WS-A")
                .contains("MOVE WS-A TO WS-B"));
        assertThat(cobol(true, false)).as("das erste Bild fragt keine KI").doesNotContain("Beschreibung");
    }

    @Test
    void blocksOnASharedLineAreDescribedFromTheirOwnStatementsOnly() {
        RecordingAi ai = new RecordingAi();
        OutlineToMermaidConverter.blockLabelSummarizer(ai);
        String program = """
                       IDENTIFICATION DIVISION.
                       PROGRAM-ID. ZEILE.
                       PROCEDURE DIVISION.
                       MAIN-PARA.
                           IF WS-A = 1 MOVE 1 TO WS-B ADD 1 TO WS-B ELSE MOVE 2 TO WS-B ADD 2 TO WS-B END-IF.
                           GOBACK.
                """;

        SourceDiagrams.generate(program, "ZEILE.cbl", Optional.of(SourceLanguage.COBOL), DiagramType.FLOWCHART, 0,
                true, false, null, null, true);

        assertThat(ai.codes).isNotEmpty().allSatisfy(code -> assertThat(code).doesNotContain("IF WS-A")
                .satisfiesAnyOf(c -> assertThat(c).contains("MOVE 1").doesNotContain("MOVE 2"),
                        c -> assertThat(c).contains("MOVE 2").doesNotContain("MOVE 1")));
    }

    @Test
    void separateLinesStillGiveTheModelTheFullSourceLines() {
        RecordingAi ai = new RecordingAi();
        OutlineToMermaidConverter.blockLabelSummarizer(ai);
        cobol(true, true);
        // ganze Quelltextzeilen mit Punkt, nicht nur die Kurztexte der Anweisungen
        assertThat(ai.codes).anySatisfy(code -> assertThat(code).contains("MOVE 1 TO WS-A."));
    }

    @Test
    void withAiJclStepsAreDescribedTogetherWithTheirFiles() {
        RecordingAi ai = new RecordingAi();
        OutlineToMermaidConverter.blockLabelSummarizer(ai);

        String flow = SourceDiagrams.generate(JCL, "SORT.jcl", Optional.of(SourceLanguage.JCL),
                DiagramType.FLOWCHART, 0, false, false, null, null, true).mermaid().orElseThrow();
        String compact = SourceDiagrams.generate(JCL, "SORT.jcl", Optional.of(SourceLanguage.JCL),
                DiagramType.FLOWCHART, 0, true, false, null, null, true).mermaid().orElseThrow();

        assertThat(flow).contains("STEP_STEP1[\"STEP1\\nPGM=SORT\\n① Frage")
                .contains("erklaerung_1[\"① Beschreibung\"]");
        assertThat(compact).contains("Beschreibung");
        // Der Block nach dem Schritt kennt auch die EXEC-Zeile: welches Programm die Dateien benutzt
        assertThat(ai.codes).allSatisfy(code -> assertThat(code).contains("EXEC PGM=SORT").contains("SORTIN"));
        assertThat(ai.purposes).anySatisfy(purpose -> assertThat(purpose).isEqualTo(FlowLabels.STEP_PURPOSE));
    }

    private static String cobol(boolean compact, boolean ai) {
        var result = SourceDiagrams.generate(COBOL, "DEMO.cbl", Optional.of(SourceLanguage.COBOL),
                DiagramType.FLOWCHART, 0, compact, false, null, null, ai);
        assertThat(result.type()).isEqualTo(DiagramType.FLOWCHART);
        assertThat(result.collapsed()).isEqualTo(compact);
        return result.mermaid().orElseThrow();
    }

    private static final class RecordingAi implements BlockLabelSummarizer {
        final List<String> codes = new ArrayList<>();
        final List<String> purposes = new ArrayList<>();

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public String summarize(String code, int maxLength, String purpose) {
            return "Frage";
        }

        @Override
        public String describe(String code, int maxLength, String purpose) {
            codes.add(code);
            purposes.add(purpose);
            return "Beschreibung";
        }
    }
}
