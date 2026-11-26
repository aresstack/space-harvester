package com.aresstack.spaceharvester.mainframe.cobol;

import java.util.List;
import java.util.Map;

/** Expose analysis facts without leaking parser or presentation types. */
public record CobolAnalysis(List<Element> elements, List<ProgramCall> calls,
                            List<SymbolReference> references, List<Diagnostic> diagnostics,
                            boolean semanticAnalysisComplete, CobolControlFlow controlFlow, CobolDataFlow dataFlow) {
    public CobolAnalysis(List<Element> elements, List<ProgramCall> calls,
                         List<SymbolReference> references, List<Diagnostic> diagnostics,
                         boolean semanticAnalysisComplete, CobolControlFlow controlFlow) {
        this(elements, calls, references, diagnostics, semanticAnalysisComplete, controlFlow, CobolDataFlow.EMPTY);
    }
    public CobolAnalysis(List<Element> elements, List<ProgramCall> calls,
                         List<SymbolReference> references, List<Diagnostic> diagnostics,
                         boolean semanticAnalysisComplete) {
        this(elements, calls, references, diagnostics, semanticAnalysisComplete, CobolControlFlow.EMPTY);
    }
    public CobolAnalysis {
        elements = List.copyOf(elements);
        calls = List.copyOf(calls);
        references = List.copyOf(references);
        diagnostics = List.copyOf(diagnostics);
        controlFlow = controlFlow == null ? CobolControlFlow.EMPTY : controlFlow;
        dataFlow = dataFlow == null ? CobolDataFlow.EMPTY : dataFlow;
    }

    public enum Kind {
        PROGRAM_ID, DIVISION, PROCEDURE_DIVISION, SECTION, WORKING_STORAGE, LINKAGE_SECTION,
        FILE_SECTION, SCREEN_SECTION, FILE_DESCRIPTOR, PARAGRAPH, DATA_ITEM,
        LEVEL_01, LEVEL_77, LEVEL_88, COPY_STMT, CALL_STMT, PERFORM_STMT,
        EXEC_SQL, EXEC_SQLIMS, EXEC_CICS
    }

    public enum TargetKind { LITERAL, DYNAMIC }

    /** Use line zero when provenance is unknown; column zero means unavailable. */
    public record Position(String source, int line, int column, boolean debuggingLine) {
        public static final Position UNKNOWN = new Position("", 0, 0);

        public Position(String source, int line, int column) {
            this(source, line, column, false);
        }
    }

    public record Span(Position start, Position end) { }

    public record Element(Kind kind, String name, int parentIndex, Span span,
                          String text, Map<String, String> attributes) {
        public Element {
            attributes = Map.copyOf(attributes);
        }
    }

    public record ProgramCall(TargetKind targetKind, String target, Span span, String text) { }

    public record SymbolReference(String name, Span usage, Span definition) { }

    public enum DiagnosticKind { SYNTAX_ERROR, COPYBOOK, ANALYSIS_ERROR }

    public record Diagnostic(String message, Position position, DiagnosticKind kind) {
        public Diagnostic(String message, Position position) {
            this(message, position, DiagnosticKind.ANALYSIS_ERROR);
        }
    }

    /** Keep all call sites in calls(); deduplicate only for the external-call view. */
    public List<ProgramCall> knownLiteralCalls() {
        Map<String, ProgramCall> known = new java.util.LinkedHashMap<>();
        for (ProgramCall call : calls) {
            if (call.targetKind() == TargetKind.LITERAL && !call.target().isBlank()
                    && !call.span().start().debuggingLine()) {
                known.putIfAbsent(call.target().toUpperCase(java.util.Locale.ROOT), call);
            }
        }
        return List.copyOf(known.values());
    }

    public boolean isPartial() {
        return !diagnostics.isEmpty() || !semanticAnalysisComplete;
    }
}
