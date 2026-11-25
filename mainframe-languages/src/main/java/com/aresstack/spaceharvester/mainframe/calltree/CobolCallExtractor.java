package com.aresstack.spaceharvester.mainframe.calltree;

import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalysis;
import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalyzer;
import com.aresstack.spaceharvester.mainframe.cobol.CobolSource;
import com.aresstack.spaceharvester.mainframe.cobol.CopybookResolver;

import java.util.List;

/** Extract only known literal program targets from the shared COBOL analysis. */
final class CobolCallExtractor implements CallExtractor {
    private final CobolAnalyzer analyzer;
    private final CopybookResolver copybooks;

    CobolCallExtractor() {
        this(CobolAnalyzer.proLeap(), CopybookResolver.NONE);
    }

    CobolCallExtractor(CobolAnalyzer analyzer, CopybookResolver copybooks) {
        this.analyzer = analyzer;
        this.copybooks = copybooks;
    }

    @Override
    public List<ExternalCall> extractExternalCalls(String sourceCode, String sourceName) {
        CobolSource source = CobolSource.fixed(sourceName, sourceCode);
        CobolAnalysis analysis = analyzer.analyze(source, copybooks);
        return analysis.knownLiteralCalls().stream().map(call -> {
            var position = call.span().start();
            int line = source.name().equals(position.source()) ? position.line() : 0;
            return new ExternalCall(call.target(), "CALL", line, call.text());
        }).toList();
    }

    @Override
    public SourceLanguage getLanguage() {
        return SourceLanguage.COBOL;
    }
}
