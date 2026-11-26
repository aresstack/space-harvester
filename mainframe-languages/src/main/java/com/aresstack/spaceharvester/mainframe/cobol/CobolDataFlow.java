package com.aresstack.spaceharvester.mainframe.cobol;

import java.util.List;

/** Source-mapped local data effects, not a proof of values at runtime. */
public record CobolDataFlow(List<Symbol> symbols, List<Operation> operations, List<Limitation> limitations,
                            List<ReadSite> readSites) {
    public static final CobolDataFlow EMPTY = new CobolDataFlow(List.of(), List.of(), List.of(), List.of());

    public CobolDataFlow(List<Symbol> symbols, List<Operation> operations, List<Limitation> limitations) {
        this(symbols, operations, limitations, List.of());
    }

    public CobolDataFlow {
        symbols = List.copyOf(symbols);
        operations = List.copyOf(operations);
        limitations = List.copyOf(limitations);
        readSites = readSites == null ? List.of() : List.copyOf(readSites);
    }

    public boolean partial() { return !limitations.isEmpty(); }

    public enum AccessKind { READ, WRITE, READ_WRITE, ADDRESS_READ, POSSIBLE_READ_WRITE, UNKNOWN }
    public enum OperandKind { SYMBOL, LITERAL, EXTERNAL, UNKNOWN }
    public enum TransferKind { MOVE, COMPUTE, INITIALIZE, INPUT, RETURN_VALUE, ITERATION }

    public record Symbol(String id, String program, String name, String qualifiedName,
                         CobolAnalysis.Span definition, boolean complexStorage) { }
    public record Operand(OperandKind kind, String text, String symbolId, CobolAnalysis.Span span) { }
    public record Access(AccessKind kind, Operand operand) { }
    /** Inputs affect the destination value if the operation succeeds; no path feasibility claim. */
    public record Transfer(TransferKind kind, List<Operand> inputs, Operand destination) {
        public Transfer { inputs = List.copyOf(inputs); }
    }
    /** Control node is empty when there is no unique matching CFG node. */
    public record Operation(String program, String controlNode, String verb, CobolAnalysis.Span span,
                            List<Access> accesses, List<Transfer> transfers, boolean incomplete) {
        public Operation {
            accesses = List.copyOf(accesses);
            transfers = List.copyOf(transfers);
        }
    }
    public enum DefinitionKind { ENTRY_STATE, WRITE, POSSIBLE_WRITE, UNKNOWN_EFFECT }
    public record Definition(DefinitionKind kind, String controlNode, CobolAnalysis.Span span) { }

    /** Store one read site; expand candidate definitions on demand from the same persisted graph. */
    public record ReadSite(String program, String controlNode, Operand operand) { }

    public record Limitation(String message, CobolAnalysis.Position position) { }
}
