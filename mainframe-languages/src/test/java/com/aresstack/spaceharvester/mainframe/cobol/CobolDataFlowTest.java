package com.aresstack.spaceharvester.mainframe.cobol;

import com.aresstack.spaceharvester.mainframe.cobol.CobolDataFlow.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CobolDataFlowTest {
    private static final String HEADER = """
                   IDENTIFICATION DIVISION.
                   PROGRAM-ID. DEMO.
                   DATA DIVISION.
                   WORKING-STORAGE SECTION.
                   01 A PIC 9(3).
                   01 B PIC 9(3).
                   01 C PIC 9(3).
                   01 D PIC 9(3).
            """;

    @Test
    void distinguishesSendingReceivingAndInPlaceArithmeticOperands() {
        var flow = analyze("", """
                MOVE A TO B C
                ADD A TO B C
                COMPUTE D = A + C
                SUBTRACT A FROM B GIVING D
                MULTIPLY A BY B
                DIVIDE A INTO C GIVING D
                IF B > A
                   DISPLAY D
                END-IF
                GOBACK.
                """).dataFlow();
        assertThat(flow.limitations()).isEmpty();
        var move = operation(flow, "MOVE");
        assertThat(move.accesses()).extracting(Access::kind).containsExactly(AccessKind.READ, AccessKind.WRITE, AccessKind.WRITE);
        assertThat(move.transfers()).hasSize(2);
        assertThat(move.transfers()).allSatisfy(transfer -> {
            assertThat(transfer.inputs()).extracting(Operand::text).containsExactly("A");
            assertThat(transfer.inputs().getFirst().kind()).isEqualTo(OperandKind.SYMBOL);
        });
        var add = operation(flow, "ADD");
        assertThat(add.transfers()).hasSize(2);
        assertThat(add.transfers().get(0).inputs()).extracting(Operand::text).containsExactly("A", "B");
        assertThat(add.transfers().get(1).inputs()).extracting(Operand::text).containsExactly("A", "C");
        assertThat(add.accesses()).extracting(Access::kind).containsExactly(AccessKind.READ, AccessKind.READ_WRITE, AccessKind.READ_WRITE);
        assertThat(operation(flow, "COMPUTE").transfers().getFirst().inputs()).extracting(Operand::text).containsExactly("A", "C");
        assertThat(operation(flow, "SUBTRACT").transfers().getFirst().inputs()).extracting(Operand::text).containsExactly("A", "B");
        assertThat(operation(flow, "MULTIPLY").transfers().getFirst().inputs()).extracting(Operand::text).containsExactly("A", "B");
        assertThat(operation(flow, "DIVIDE").transfers().getFirst().inputs()).extracting(Operand::text).containsExactly("A", "C");
        assertThat(operation(flow, "IF").accesses()).extracting(Access::kind).containsOnly(AccessKind.READ);
        assertThat(operation(flow, "DISPLAY").accesses()).extracting(access -> access.operand().text()).containsExactly("D");
        assertThat(move.span().start().line()).isEqualTo(10);
        assertThat(move.controlNode()).isNotBlank();
    }

    @Test
    void keepsQualifiedNamesDistinctAndRejectsAmbiguousReferences() {
        var flow = analyze("""
                01 GRP1.
                   05 ITEM PIC 9.
                01 GRP2.
                   05 ITEM PIC 9.
                """, """
                MOVE ITEM OF GRP1 TO ITEM OF GRP2
                MOVE ITEM TO A
                GOBACK.
                """).dataFlow();
        var moves = flow.operations().stream().filter(op -> op.verb().equals("MOVE")).toList();
        assertThat(moves).hasSize(2);
        var transfer = moves.getFirst().transfers().getFirst();
        assertThat(transfer.inputs().getFirst().kind()).isEqualTo(OperandKind.SYMBOL);
        assertThat(transfer.destination().kind()).isEqualTo(OperandKind.SYMBOL);
        assertThat(transfer.inputs().getFirst().symbolId()).isNotEqualTo(transfer.destination().symbolId());
        assertThat(moves.getLast().transfers().getFirst().inputs().getFirst().kind()).isEqualTo(OperandKind.UNKNOWN);
        assertThat(flow.partial()).isTrue();
    }

    @Test
    void representsExternalArgumentsAndReturnWithoutInventingCalleeEffects() {
        var flow = analyze("", """
                MOVE 42 TO A
                ACCEPT B
                CALL 'OTHER' USING BY REFERENCE A BY CONTENT B
                    BY VALUE C RETURNING D
                GOBACK.
                """).dataFlow();
        assertThat(operation(flow, "MOVE").transfers().getFirst().inputs().getFirst().kind()).isEqualTo(OperandKind.LITERAL);
        assertThat(operation(flow, "ACCEPT").transfers().getFirst().inputs().getFirst().kind()).isEqualTo(OperandKind.EXTERNAL);
        var call = operation(flow, "CALL");
        assertThat(call.accesses()).anyMatch(access -> access.kind() == AccessKind.POSSIBLE_READ_WRITE && access.operand().text().equals("A"));
        assertThat(call.accesses()).anyMatch(access -> access.kind() == AccessKind.READ && access.operand().text().equals("B"));
        assertThat(call.accesses()).anyMatch(access -> access.kind() == AccessKind.READ && access.operand().text().equals("C"));
        assertThat(call.transfers()).singleElement().satisfies(transfer -> {
            assertThat(transfer.kind()).isEqualTo(TransferKind.RETURN_VALUE);
            assertThat(transfer.destination().text()).isEqualTo("D");
            assertThat(transfer.inputs()).extracting(Operand::kind).containsExactly(OperandKind.EXTERNAL);
        });
        assertThat(flow.partial()).isTrue();
    }

    @Test
    void mapsCopybookDefinitionsAndOperationsAndWithholdsIncompleteExpansion() {
        String source = HEADER + "       COPY FIELDS.\n       PROCEDURE DIVISION.\n       COPY LOGIC.\n           GOBACK.\n";
        var analyzer = CobolAnalyzer.proLeap();
        var result = analyzer.analyze(CobolSource.fixed("DEMO", source), (including, name, library) ->
                Optional.of(CobolSource.fixed(name, name.equals("FIELDS") ? "       01 FIELD-X PIC 9.\n"
                        : "           MOVE FIELD-X TO A.\n")));
        var flow = result.dataFlow();
        assertThat(flow.symbols()).anySatisfy(symbol -> {
            assertThat(symbol.name()).isEqualTo("FIELD-X");
            assertThat(symbol.definition().start().source()).isEqualTo("FIELDS");
            assertThat(symbol.definition().start().line()).isEqualTo(1);
        });
        assertThat(operation(flow, "MOVE").span().start().source()).isEqualTo("LOGIC");
        assertThat(operation(flow, "MOVE").transfers().getFirst().inputs().getFirst().kind()).isEqualTo(OperandKind.SYMBOL);
        var missing = analyzer.analyze(CobolSource.fixed("DEMO", source), CopybookResolver.NONE).dataFlow();
        assertThat(missing.partial()).isTrue();
        assertThat(missing.operations()).isEmpty();
    }

    @Test
    void tracesBothBranchDefinitionsAndKillsEarlierWritesAtAnUnconditionalMove() {
        var result = analyze("", """
                MOVE 0 TO A
                IF B = 0
                    MOVE 1 TO A
                ELSE
                    MOVE 2 TO A
                END-IF
                DISPLAY A
                MOVE 3 TO A
                DISPLAY A
                GOBACK.
                """);
        var flow = result.dataFlow();
        var displays = flow.operations().stream().filter(op -> op.verb().equals("DISPLAY")).toList();
        var first = flow.readSites().stream().filter(read -> read.controlNode().equals(displays.getFirst().controlNode()))
                .findFirst().orElseThrow();
        assertThat(CobolReachingDefinitions.candidates(result, first)).extracting(definition -> definition.span().start().line())
                .containsExactlyInAnyOrder(12, 14);
        var second = flow.readSites().stream().filter(read -> read.controlNode().equals(displays.getLast().controlNode()))
                .findFirst().orElseThrow();
        assertThat(CobolReachingDefinitions.candidates(result, second)).singleElement().satisfies(definition -> {
            assertThat(definition.kind()).isEqualTo(DefinitionKind.WRITE);
            assertThat(definition.span().start().line()).isEqualTo(17);
        });
    }

    @Test
    void retainsLoopCarriedWritesAndEntryStateAndDoesNotClaimUnreachableReads() {
        var result = analyze("", """
                PERFORM UNTIL A = 2
                    ADD 1 TO A
                END-PERFORM
                DISPLAY A
                STOP RUN.
                DEAD-PARA.
                    DISPLAY A.
                """);
        var flow = result.dataFlow();
        var displays = flow.operations().stream().filter(op -> op.verb().equals("DISPLAY")).toList();
        var read = flow.readSites().stream().filter(item -> item.controlNode().equals(displays.getFirst().controlNode()))
                .findFirst().orElseThrow();
        assertThat(CobolReachingDefinitions.candidates(result, read)).extracting(Definition::kind)
                .contains(DefinitionKind.ENTRY_STATE, DefinitionKind.WRITE);
        assertThat(flow.readSites()).noneMatch(item -> item.controlNode().equals(displays.getLast().controlNode()));
    }

    private static CobolAnalysis analyze(String declarations, String body) {
        String source = HEADER + fixed(declarations) + "       PROCEDURE DIVISION.\n" + fixed(body);
        return CobolAnalyzer.proLeap().analyze(CobolSource.fixed("DEMO", source), CopybookResolver.NONE);
    }

    private static String fixed(String content) {
        return content.isEmpty() ? "" : content.lines().map(line -> "       " + line)
                .collect(java.util.stream.Collectors.joining("\n")) + "\n";
    }

    private static Operation operation(CobolDataFlow flow, String verb) {
        return flow.operations().stream().filter(op -> op.verb().equals(verb)).findFirst()
                .orElseThrow(() -> new AssertionError("Missing " + verb + ": " + flow.limitations()));
    }
}
