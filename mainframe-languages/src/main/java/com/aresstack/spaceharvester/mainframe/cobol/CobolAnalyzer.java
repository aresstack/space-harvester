package com.aresstack.spaceharvester.mainframe.cobol;

/** Analyze COBOL independently of any editor or source transport. */
@FunctionalInterface
public interface CobolAnalyzer {
    CobolAnalysis analyze(CobolSource source, CopybookResolver copybooks);

    static CobolAnalyzer proLeap() {
        return com.aresstack.spaceharvester.mainframe.cobol.internal.ProLeapAnalyzerFactory.create();
    }
}
