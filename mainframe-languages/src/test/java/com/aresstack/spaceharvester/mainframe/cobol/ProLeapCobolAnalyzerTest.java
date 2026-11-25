package com.aresstack.spaceharvester.mainframe.cobol;

import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalysis.*;
import com.aresstack.spaceharvester.mainframe.model.JclElement;
import com.aresstack.spaceharvester.mainframe.model.JclElementType;
import com.aresstack.spaceharvester.mainframe.parser.CobolParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ProLeapCobolAnalyzerTest {
    private final CobolAnalyzer analyzer = CobolAnalyzer.proLeap();

    private static final String SOURCE = """
                   IDENTIFICATION DIVISION.
                   PROGRAM-ID.
                       DEMO.
                   DATA DIVISION.
                   WORKING-STORAGE SECTION.
                   01 CUSTOMER.
                      05 WS-ADDRESS.
                         10 CITY PIC X(20).
                      05 WS-STATUS PIC X.
                         88 ACTIVE VALUE 'A'.
                         88 INACTIVE VALUE 'I'.
                   77 WS-PROGRAM PIC X(8).
                   PROCEDURE DIVISION.
                   1000-MAIN.
                       MOVE 'A' TO WS-STATUS.
                       CALL 'PROG01' CALL 'PROG02'.
                       CALL WS-PROGRAM.
                       DISPLAY "CALL PGM003".
                       PERFORM 2000-FIRST THRU 3000-LAST.
                       PERFORM 2 TIMES
                           CALL 'PROG04'
                       END-PERFORM.
                       STOP RUN.
                   2000-FIRST.
                       DISPLAY CITY.
                   3000-LAST.
                       EXIT.
            """;

    @Test
    void analyzesGrammarAndSemanticReferences() {
        CobolAnalysis result = analyze(SOURCE);
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.semanticAnalysisComplete()).isTrue();
        assertThat(result.elements()).filteredOn(element -> element.kind() == Kind.PROGRAM_ID)
                .extracting(Element::name).containsExactly("DEMO");
        assertThat(result.elements()).filteredOn(element -> element.kind() == Kind.PARAGRAPH)
                .extracting(Element::name).containsExactly("1000-MAIN", "2000-FIRST", "3000-LAST");
        assertThat(result.calls()).extracting(ProgramCall::target)
                .containsExactly("PROG01", "PROG02", "WS-PROGRAM", "PROG04");
        assertThat(result.calls()).extracting(ProgramCall::targetKind)
                .containsExactly(TargetKind.LITERAL, TargetKind.LITERAL, TargetKind.DYNAMIC, TargetKind.LITERAL);
        assertThat(result.calls().get(0).span().start()).isEqualTo(new Position("DEMO.cbl", 16, 12));
        assertThat(result.calls().get(1).span().start().line()).isEqualTo(16);
        assertThat(result.references()).filteredOn(reference -> reference.name().equals("WS-STATUS"))
                .anySatisfy(reference -> {
                    assertThat(reference.definition().start().line()).isEqualTo(9);
                    assertThat(reference.usage().start().line()).isEqualTo(15);
                });
        assertThat(result.elements()).filteredOn(element -> element.kind() == Kind.PERFORM_STMT)
                .singleElement().satisfies(element -> {
                    assertThat(element.name()).isEqualTo("2000-FIRST");
                    assertThat(element.attributes()).containsEntry("THRU", "3000-LAST");
                });
    }

    @Test
    void retainsCompleteDataHierarchyAndCorrectParents() {
        CobolAnalysis result = analyze(SOURCE);
        assertThat(parentOf(result, "WS-ADDRESS")).isEqualTo("CUSTOMER");
        assertThat(parentOf(result, "CITY")).isEqualTo("WS-ADDRESS");
        assertThat(parentOf(result, "ACTIVE")).isEqualTo("WS-STATUS");
        assertThat(parentOf(result, "INACTIVE")).isEqualTo("WS-STATUS");
        assertThat(element(result, "CITY").attributes()).containsEntry("PIC", "X(20)").containsEntry("LEVEL", "10");
        assertThat(element(result, "ACTIVE").attributes()).containsEntry("VALUE", "'A'");
        assertThat(parentOf(result, "WS-PROGRAM")).isEqualTo("WORKING-STORAGE SECTION");
    }

    @Test
    void keepsContinuationAndPhysicalSourceLineNumbers() {
        String source = """
                       IDENTIFICATION DIVISION.
                       PROGRAM-ID. DEMO.
                       PROCEDURE DIVISION.
                           CALL 'PROG
                      -    '01'.
                      *    CALL 'IGNORED'.
                      /    CALL 'ALSOIGNORED'.
                           CALL
                              'PROG02'.
                           STOP RUN.
                """;
        CobolAnalysis result = analyze(source);
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.calls()).extracting(ProgramCall::target).containsExactly("PROG01", "PROG02");
        assertThat(result.calls().get(0).span().start().line()).isEqualTo(4);
        assertThat(result.calls().get(0).span().end().line()).isEqualTo(5);
        assertThat(result.calls().get(1).span().start().line()).isEqualTo(8);
        assertThat(result.calls().get(1).span().end().line()).isEqualTo(9);
    }

    @Test
    void expandsCopybookReplacingAndKeepsBothSourceIdentities() {
        String source = """
                       IDENTIFICATION DIVISION.
                       PROGRAM-ID. DEMO.
                       DATA DIVISION.
                       WORKING-STORAGE SECTION.
                           COPY CUSTDATA
                               REPLACING ==CUST-NAME== BY ==WS-NAME==.
                       PROCEDURE DIVISION.
                           MOVE 'A' TO WS-NAME.
                           CALL 'PROG01'.
                           STOP RUN.
                """;
        String copy = """
                       01 CUSTOMER.
                          05 CUST-NAME PIC X(10).
                """;
        CopybookResolver resolver = (including, name, library) -> name.equals("CUSTDATA")
                ? Optional.of(CobolSource.fixed("LIB/CUSTDATA", copy)) : Optional.empty();
        CobolSource input = CobolSource.fixed("DEMO.cbl", source);
        CobolAnalysis result = analyzer.analyze(input, resolver);
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.semanticAnalysisComplete()).isTrue();
        assertThat(element(result, "WS-NAME").span().start().source()).isEqualTo("LIB/CUSTDATA");
        assertThat(element(result, "WS-NAME").span().start().line()).isEqualTo(2);
        assertThat(parentOf(result, "WS-NAME")).isEqualTo("CUSTOMER");
        assertThat(element(result, "CUSTDATA").span().start().line()).isEqualTo(5);
        assertThat(result.calls().getFirst().span().start()).isEqualTo(new Position("DEMO.cbl", 9, 12));
        assertThat(result.references()).filteredOn(reference -> reference.name().equals("WS-NAME"))
                .anySatisfy(reference -> {
                    assertThat(reference.usage().start().line()).isEqualTo(8);
                    assertThat(reference.definition().start().source()).isEqualTo("LIB/CUSTDATA");
                });
        var outline = new CobolParser(analyzer, resolver).parse(source, "DEMO.cbl");
        assertThat(outline.getElements()).filteredOn(element -> element.getName().equals("WS-NAME"))
                .singleElement().satisfies(element -> {
                    assertThat(element.getLineNumber()).isZero();
                    assertThat(element.getParameter("SOURCE")).isEqualTo("LIB/CUSTDATA");
                });
        assertThat(outline.getElements()).filteredOn(element -> element.getName().equals("PROG01"))
                .singleElement().satisfies(element -> assertThat(element.getLineNumber()).isEqualTo(9));
    }

    @Test
    void missingCopybookIsDiagnosedWithoutLosingSubsequentCalls() {
        String source = """
                       IDENTIFICATION DIVISION.
                       PROGRAM-ID. DEMO.
                       DATA DIVISION.
                       WORKING-STORAGE SECTION.
                           COPY MISSING.
                       PROCEDURE DIVISION.
                           CALL 'PGM'.
                           STOP RUN.
                """;
        CobolAnalysis result = analyze(source);
        assertThat(result.isPartial()).isTrue();
        assertThat(result.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.message()).contains("Copybook not found: MISSING");
            assertThat(diagnostic.position().line()).isEqualTo(5);
        });
        assertThat(result.calls()).singleElement().satisfies(call -> {
            assertThat(call.target()).isEqualTo("PGM");
            assertThat(call.span().start().line()).isEqualTo(7);
        });
        assertThat(element(result, "MISSING").kind()).isEqualTo(Kind.COPY_STMT);
    }

    @Test
    void recognizesEmbeddedSqlAndCicsWithoutMisclassifyingTheirText() {
        String source = """
                       IDENTIFICATION DIVISION.
                       PROGRAM-ID. DEMO.
                       PROCEDURE DIVISION.
                           EXEC SQL
                               SELECT 'CALL PGM' FROM SYSIBM.SYSDUMMY1
                           END-EXEC.
                           EXEC CICS
                               LINK PROGRAM('CICSPGM')
                           END-EXEC.
                           CALL 'ACTUAL'.
                           STOP RUN.
                """;
        CobolAnalysis result = analyze(source);
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.elements()).filteredOn(element -> element.kind() == Kind.EXEC_SQL
                || element.kind() == Kind.EXEC_CICS).extracting(Element::kind)
                .containsExactly(Kind.EXEC_SQL, Kind.EXEC_CICS);
        assertThat(result.calls()).extracting(ProgramCall::target).containsExactly("ACTUAL");
        assertThat(result.calls().getFirst().span().start().line()).isEqualTo(10);
        assertThat(element(result, "EXEC SQL").text()).contains("SELECT").doesNotContain("*>EXECSQL");
        assertThat(element(result, "EXEC CICS").text()).contains("LINK PROGRAM").doesNotContain("*>EXECCICS");
    }

    @Test
    void reportsIncompleteEditorSource() {
        CobolAnalysis result = analyze("""
                       IDENTIFICATION DIVISION.
                       PROGRAM-ID. DEMO.
                       PROCEDURE DIVISION.
                           CALL
                """);
        assertThat(result.isPartial()).isTrue();
        assertThat(result.diagnostics()).isNotEmpty();
        assertThat(result.calls()).isEmpty();
    }

    @Test
    void keepsDynamicCallsOutOfTheStaticOutlineCallType() {
        var outline = new CobolParser().parse(SOURCE, "DEMO.cbl");
        assertThat(outline.getElements()).filteredOn(element -> element.getType() == JclElementType.CALL_STMT)
                .extracting(JclElement::getName).containsExactly("PROG01", "PROG02", "PROG04");
        assertThat(outline.getElements()).filteredOn(element -> element.getType() == JclElementType.DYNAMIC_CALL_STMT)
                .extracting(JclElement::getName).containsExactly("WS-PROGRAM");
    }

    @Test
    void distinguishesDebuggingLinesFromUnconditionalCalls() {
        CobolAnalysis result = analyze("""
                       IDENTIFICATION DIVISION.
                       PROGRAM-ID. DEMO.
                       PROCEDURE DIVISION.
                      D    CALL 'DEBUGPGM'.
                           CALL 'ACTUAL'.
                           STOP RUN.
                """);
        assertThat(result.calls()).extracting(ProgramCall::target).containsExactly("DEBUGPGM", "ACTUAL");
        assertThat(result.calls().getFirst().span().start().debuggingLine()).isTrue();
        assertThat(result.knownLiteralCalls()).extracting(ProgramCall::target).containsExactly("ACTUAL");
    }

    @Test
    void missingCopybookDoesNotDiscardLocalReferences() {
        CobolAnalysis result = analyze("""
                       IDENTIFICATION DIVISION.
                       PROGRAM-ID. DEMO.
                       DATA DIVISION.
                       WORKING-STORAGE SECTION.
                       01 WS-COUNT PIC 9.
                           COPY MISSING.
                       PROCEDURE DIVISION.
                           MOVE 1 TO WS-COUNT.
                           STOP RUN.
                """);
        assertThat(result.isPartial()).isTrue();
        assertThat(result.semanticAnalysisComplete()).isFalse();
        assertThat(result.references()).anySatisfy(reference -> {
            assertThat(reference.name()).isEqualTo("WS-COUNT");
            assertThat(reference.definition().start().line()).isEqualTo(5);
            assertThat(reference.usage().start().line()).isEqualTo(8);
        });
    }

    @Test
    void replacesWholeWordsWithoutChangingCaseInsensitiveNeighbors() {
        String source = """
                       IDENTIFICATION DIVISION.
                       PROGRAM-ID. DEMO.
                       DATA DIVISION.
                       WORKING-STORAGE SECTION.
                           COPY CPY REPLACING id BY WS-ID.
                       PROCEDURE DIVISION.
                           STOP RUN.
                """;
        String copy = """
                       01 ID PIC X.
                       01 VALID-FLAG PIC X.
                """;
        CobolAnalysis result = analyzer.analyze(CobolSource.fixed("DEMO.cbl", source),
                (including, name, library) -> Optional.of(CobolSource.fixed("CPY", copy)));
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.elements()).extracting(Element::name).contains("WS-ID", "VALID-FLAG");
    }

    @Test
    void replacementDoesNotRewriteLiteralsOrCascadeIntoAnotherRule() {
        String source = """
                       IDENTIFICATION DIVISION.
                       PROGRAM-ID. DEMO.
                       DATA DIVISION.
                       WORKING-STORAGE SECTION.
                           COPY CPY REPLACING ==OLD-NAME== BY ==NEW-NAME==
                               ==NEW-NAME== BY ==FINAL-NAME==.
                       PROCEDURE DIVISION.
                           STOP RUN.
                """;
        String copy = """
                       01 OLD-NAME PIC X(8) VALUE 'OLD-NAME'.
                       01 NEW-NAME PIC X(8).
                """;
        CobolAnalysis result = analyzer.analyze(CobolSource.fixed("DEMO.cbl", source),
                (including, name, library) -> Optional.of(CobolSource.fixed("CPY", copy)));
        assertThat(result.diagnostics()).isEmpty();
        assertThat(element(result, "NEW-NAME").attributes()).containsEntry("VALUE", "'OLD-NAME'");
        assertThat(element(result, "FINAL-NAME").kind()).isEqualTo(Kind.LEVEL_01);
    }

    @Test
    void replaceOffEndsReplacementScope() {
        CobolAnalysis result = analyze("""
                       IDENTIFICATION DIVISION.
                       PROGRAM-ID. DEMO.
                       DATA DIVISION.
                       WORKING-STORAGE SECTION.
                           REPLACE ==OLD-NAME== BY ==NEW-NAME==.
                       01 OLD-NAME PIC X.
                           REPLACE OFF.
                       01 OLD-NAME PIC X.
                       PROCEDURE DIVISION.
                           STOP RUN.
                """);
        assertThat(result.diagnostics()).isEmpty();
        assertThat(element(result, "NEW-NAME").span().start().line()).isEqualTo(6);
        assertThat(element(result, "OLD-NAME").span().start().line()).isEqualTo(8);
    }

    @Test
    void supportsLeadingTrailingAndEmptyReplacementOperands() {
        String source = """
                       IDENTIFICATION DIVISION.
                       PROGRAM-ID. DEMO.
                       DATA DIVISION.
                       WORKING-STORAGE SECTION.
                           COPY CPY REPLACING LEADING ==PRE-== BY ====
                               TRAILING ==-OLD== BY ==-NEW==.
                       PROCEDURE DIVISION.
                           STOP RUN.
                """;
        String copy = """
                       01 PRE-WS-COUNT PIC 9.
                       01 FIELD-OLD PIC X.
                       01 WS-OTHER PIC X(8) VALUE 'PRE-OLD'.
                """;
        CobolAnalysis result = analyzer.analyze(CobolSource.fixed("DEMO.cbl", source),
                (including, name, library) -> Optional.of(CobolSource.fixed("CPY", copy)));
        assertThat(result.diagnostics()).isEmpty();
        assertThat(element(result, "WS-COUNT").span().start().source()).isEqualTo("CPY");
        assertThat(element(result, "FIELD-NEW").span().start().line()).isEqualTo(2);
        assertThat(element(result, "WS-OTHER").attributes()).containsEntry("VALUE", "'PRE-OLD'");
    }

    @Test
    void supportsMultipleReplacementClausesOnSameLineAndTags() {
        String source = """
                       IDENTIFICATION DIVISION.
                       PROGRAM-ID. DEMO.
                       DATA DIVISION.
                       WORKING-STORAGE SECTION.
                           COPY CPY REPLACING ==:TAG:== BY ==WS== ==X== BY ==9==.
                       PROCEDURE DIVISION.
                           STOP RUN.
                """;
        String copy = "       01 :TAG:-COUNT PIC X.\n";
        CobolAnalysis result = analyzer.analyze(CobolSource.fixed("DEMO.cbl", source),
                (including, name, library) -> Optional.of(CobolSource.fixed("CPY", copy)));
        assertThat(result.diagnostics()).isEmpty();
        assertThat(element(result, "WS-COUNT").attributes()).containsEntry("PIC", "9");
    }

    @Test
    void supportsPartialWordsInReplaceStatement() {
        CobolAnalysis result = analyze("""
                       IDENTIFICATION DIVISION.
                       PROGRAM-ID. DEMO.
                       DATA DIVISION.
                       WORKING-STORAGE SECTION.
                           REPLACE LEADING ==XX-== BY ====.
                       01 XX-WS-COUNT PIC 9.
                           REPLACE OFF.
                       01 XX-WS-COUNT PIC 9.
                       PROCEDURE DIVISION.
                           STOP RUN.
                """);
        assertThat(result.diagnostics()).isEmpty();
        assertThat(element(result, "WS-COUNT").span().start().line()).isEqualTo(6);
        assertThat(element(result, "XX-WS-COUNT").span().start().line()).isEqualTo(8);
    }

    @Test
    void analyzesLargeFixedFormatProgram() {
        StringBuilder source = new StringBuilder("""
                       IDENTIFICATION DIVISION.
                       PROGRAM-ID. LARGE-PGM.
                       DATA DIVISION.
                       WORKING-STORAGE SECTION.
                       01 WS-COUNT PIC 9(8).
                       PROCEDURE DIVISION.
                """);
        for (int i = 0; i < 49992; i++) {
            source.append("           ADD 1 TO WS-COUNT.\n");
        }
        source.append("           CALL 'LASTPGM'.\n           STOP RUN.\n");
        long start = System.nanoTime();
        CobolAnalysis result = analyze(source.toString());
        System.out.println("50,000-line analysis: " + (System.nanoTime() - start) / 1_000_000 + " ms");
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.semanticAnalysisComplete()).isTrue();
        assertThat(result.references()).hasSizeGreaterThanOrEqualTo(49992);
        assertThat(result.calls().getFirst().span().start().line()).isEqualTo(49999);
    }

    private CobolAnalysis analyze(String content) {
        return analyzer.analyze(CobolSource.fixed("DEMO.cbl", content), CopybookResolver.NONE);
    }

    private Element element(CobolAnalysis result, String name) {
        return result.elements().stream().filter(element -> element.name().equals(name))
                .findFirst().orElseThrow();
    }

    private String parentOf(CobolAnalysis result, String name) {
        return result.elements().get(element(result, name).parentIndex()).name();
    }
}