package com.aresstack.spaceharvester.mainframe.parser;

import java.util.List;
import java.util.Map;

/** Persist explicit JCL declarations, not inferred runtime execution or file access. */
public record JclAnalysis(String source, List<Statement> statements, List<Dependency> dependencies,
                          List<Diagnostic> diagnostics) {
    public JclAnalysis {
        statements = List.copyOf(statements);
        dependencies = List.copyOf(dependencies);
        diagnostics = List.copyOf(diagnostics);
    }

    public boolean partial() { return !diagnostics.isEmpty(); }

    public enum Kind { PROGRAM, PROCEDURE, INCLUDE, PROCEDURE_LIBRARY, DATASET, DATASET_REFERENCE,
        TEMPORARY_DATASET, DUMMY, SYSOUT, INSTREAM, PATH }
    public enum Resolution { LITERAL, SYMBOLIC, LOCAL_DECLARATION, UNRESOLVED, REFERENCE }

    public record Guard(int ifLine, String expression, boolean otherwise) { }
    public record Statement(int line, int endLine, String name, String operation, String operands,
                            Map<String, String> parameters, List<String> positional,
                            int jobLine, int procedureLine, int stepLine, int concatenationLine,
                            List<Guard> guards, boolean complete) {
        public Statement {
            parameters = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(parameters));
            positional = List.copyOf(positional);
            guards = List.copyOf(guards);
        }
    }
    public record Dependency(Kind kind, Resolution resolution, String target, int line, int stepLine,
                             String ddName, String disposition, int declarationLine) { }
    public record Diagnostic(int line, String message) { }
}
