package com.aresstack.spaceharvester.mainframe.diagram;

import com.aresstack.spaceharvester.mainframe.diagram.OutlineToMermaidConverter.DiagramType;
import com.aresstack.spaceharvester.mainframe.model.JclElement;
import com.aresstack.spaceharvester.mainframe.model.JclElementType;
import com.aresstack.spaceharvester.mainframe.model.JclOutlineModel;
import com.aresstack.spaceharvester.mainframe.outline.SourceLanguage;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Diagramme aus echtem Quelltext: Ablauf mit Bedingungen, Natural-Aufrufziele, ER mit teils aufgelösten DDMs. */
class SourceDiagramsTest {

    @Test
    void consecutiveConditionsKeepTheirOwnSteps() {
        JclOutlineModel model = new JclOutlineModel();
        model.setLanguage(JclOutlineModel.Language.JCL);
        model.addElement(new JclElement(JclElementType.JOB, "BUILD", 1, "//BUILD JOB"));
        model.addElement(new JclElement(JclElementType.IF, "RC = 0", 2, "// IF (RC = 0) THEN"));
        model.addElement(new JclElement(JclElementType.EXEC, "STEPA", 3, "//STEPA EXEC PGM=PROGA"));
        model.addElement(new JclElement(JclElementType.ENDIF, null, 4, "// ENDIF"));
        model.addElement(new JclElement(JclElementType.IF, "RC > 4", 5, "// IF (RC > 4) THEN"));
        model.addElement(new JclElement(JclElementType.EXEC, "STEPB", 6, "//STEPB EXEC PGM=PROGB"));
        model.addElement(new JclElement(JclElementType.ENDIF, null, 7, "// ENDIF"));
        String flow = OutlineToMermaidConverter.convert(model, DiagramType.FLOWCHART);
        // Jeder Schritt hängt am „Ja“ seiner eigenen Bedingung und kommt genau einmal vor
        assertThat(flow).contains("IF_2 -->|Ja| STEP_STEPA").contains("IF_5 -->|Ja| STEP_STEPB");
        assertThat(flow.split("STEP_STEPB\\[", -1)).hasSize(2);
        assertThat(flow).doesNotContain("STEP_STEPB_2");
    }

    @Test
    void naturalCallTargetsAreDeclaredWithTheirName() {
        String natural = """
                DEFINE DATA LOCAL
                1 #A (A10)
                END-DEFINE
                CALLNAT 'SUB-PROG' #A
                CALLNAT 'SUB-PROG' #A
                END
                """;
        String structure = SourceDiagrams.generate(natural, "MAIN.NSP", Optional.of(SourceLanguage.NATURAL),
                DiagramType.STRUCTURE, 0, false, false, null).mermaid().orElseThrow();
        assertThat(structure).contains("EXT_SUB_PROG>\"SUB-PROG\"]");
        assertThat(structure).doesNotContain("EXT_SUB_PROG_2");
    }

    @Test
    void unresolvedDdmsStayAsPlaceholders() {
        String natural = """
                DEFINE DATA LOCAL
                1 EMP VIEW OF EMPLOYEES
                  2 NAME
                1 VEH VIEW OF VEHICLES
                  2 MAKE
                END-DEFINE
                READ EMP BY NAME
                END-READ
                END
                """;
        String er = SourceDiagrams.generate(natural, "MAIN.NSP", Optional.of(SourceLanguage.NATURAL),
                DiagramType.ER_DIAGRAM, 0, false, false,
                name -> name.equals("EMPLOYEES") ? DdmErDiagramTest.EMPLOYEES_DDM : null).mermaid().orElseThrow();
        assertThat(er).startsWith("erDiagram").contains("EMPLOYEES {").contains("VEHICLES {");
    }

    @Test
    void resolvedProcedureCopybookCallsBelongToIncludingParagraph() {
        String cobol = """
                       IDENTIFICATION DIVISION.
                       PROGRAM-ID. DEMO.
                       PROCEDURE DIVISION.
                       MAIN-PARA.
                           COPY PROCBOOK.
                           STOP RUN.
                       EXIT-PARA.
                           EXIT.
                """;
        var source = com.aresstack.spaceharvester.mainframe.cobol.CobolSource.fixed("DEMO.cbl", cobol);
        var analysis = com.aresstack.spaceharvester.mainframe.cobol.CobolAnalyzer.proLeap().analyze(source,
                (including, name, library) -> Optional.of(
                        com.aresstack.spaceharvester.mainframe.cobol.CobolSource.fixed("PROCBOOK",
                                "           CALL 'SUBPGM'.\n")));
        assertThat(analysis.diagnostics()).isEmpty();
        var outline = com.aresstack.spaceharvester.mainframe.outline.Outline.fromCobolAnalysis(source, analysis);
        String diagram = SourceDiagrams.generate(cobol, "DEMO.cbl", Optional.of(SourceLanguage.COBOL),
                DiagramType.STRUCTURE, 0, false, false, null, outline).mermaid().orElseThrow();
        assertThat(diagram).contains("PARA_MAIN_PARA ==>|CALL| CALL_SUBPGM")
                .doesNotContain("PARA_EXIT_PARA ==>|CALL| CALL_SUBPGM")
                .contains("PROCEDURE DIVISION");
    }

    @Test
    void cobolDoesNotInventExecutionOrderOrStopRun() {
        String cobol = """
                       IDENTIFICATION DIVISION.
                       PROGRAM-ID. DEMO.
                       PROCEDURE DIVISION.
                       MAIN-PARA.
                           PERFORM WORK-PARA.
                           GOBACK.
                       UNUSED-PARA.
                           DISPLAY 'UNUSED'.
                       WORK-PARA.
                           CALL 'BOOKING'.
                           EXIT.
                """;
        // The flowchart is the parser's static control flow: GOBACK terminates, UNUSED-PARA is not reached from MAIN-PARA.
        var flow = SourceDiagrams.generate(cobol, "DEMO.cbl", Optional.of(SourceLanguage.COBOL),
                DiagramType.FLOWCHART, 0, false, false, null);
        assertThat(flow.type()).isEqualTo(DiagramType.FLOWCHART);
        assertThat(flow.mermaid().orElseThrow()).startsWith("flowchart TD").contains("PERFORM_CALL", "TERMINATE")
                .doesNotContain("STOP RUN");
        // Without a complete graph (unresolved COPY) the structure is shown instead of an invented order.
        var unresolved = SourceDiagrams.generate(cobol.replace("GOBACK.", "COPY MISSING.\n               GOBACK."),
                "DEMO.cbl", Optional.of(SourceLanguage.COBOL), DiagramType.FLOWCHART, 0, false, false, null);
        assertThat(unresolved.type()).isEqualTo(DiagramType.STRUCTURE);
        for (DiagramType type : new DiagramType[]{DiagramType.STRUCTURE, DiagramType.SEQUENCE}) {
            for (boolean compact : new boolean[]{false, true}) {
                var diagram = SourceDiagrams.generate(cobol, "DEMO.cbl", Optional.of(SourceLanguage.COBOL),
                        type, 0, compact, false, null);
                assertThat(diagram.type()).isEqualTo(DiagramType.STRUCTURE);
                String mermaid = diagram.mermaid().orElseThrow();
                assertThat(mermaid).doesNotContain("STOP RUN", "PARA_MAIN_PARA --> PARA_UNUSED_PARA",
                        "PARA_UNUSED_PARA --> PARA_WORK_PARA", "sequenceDiagram");
                if (!compact) {
                    assertThat(mermaid).contains("PARA_MAIN_PARA -.->|PERFORM| PARA_WORK_PARA",
                            "PARA_WORK_PARA ==>|CALL| CALL_BOOKING");
                }
            }
        }
    }

    @Test
    void explicitDetailsOverrideAutomaticCollapse() {
        StringBuilder jcl = new StringBuilder("//BIG      JOB (ACCT),'BAU',CLASS=A\n");
        for (int i = 1; i <= 40; i++) {
            jcl.append(String.format("//STEP%-4d EXEC PGM=PROG%d%n", i, i));
        }
        var auto = SourceDiagrams.generate(jcl.toString(), "big.jcl", Optional.of(SourceLanguage.JCL),
                DiagramType.STRUCTURE, 0, false, true, null);
        var details = SourceDiagrams.generate(jcl.toString(), "big.jcl", Optional.of(SourceLanguage.JCL),
                DiagramType.STRUCTURE, 0, false, false, null);
        assertThat(auto.collapsed()).isTrue();
        assertThat(details.collapsed()).isFalse();
    }
}
