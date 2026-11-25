package com.aresstack.spaceharvester.mainframe.cobol.internal;

import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalysis.Diagnostic;
import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalysis.Position;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;

import java.util.List;

final class CobolDiagnostics extends BaseErrorListener {
    private final MappedCobolText source;
    private final List<Diagnostic> diagnostics;

    CobolDiagnostics(MappedCobolText source, List<Diagnostic> diagnostics) {
        this.source = source;
        this.diagnostics = diagnostics;
    }

    @Override
    public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                            int charPositionInLine, String message, RecognitionException cause) {
        Position position = offendingSymbol instanceof Token token ? source.at(token) : Position.UNKNOWN;
        diagnostics.add(new Diagnostic(message, position,
                com.aresstack.spaceharvester.mainframe.cobol.CobolAnalysis.DiagnosticKind.SYNTAX_ERROR));
    }
}
