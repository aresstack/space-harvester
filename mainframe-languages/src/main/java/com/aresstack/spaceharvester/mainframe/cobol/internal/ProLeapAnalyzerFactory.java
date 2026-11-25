package com.aresstack.spaceharvester.mainframe.cobol.internal;

import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalyzer;

/** Construct the package-private parser adapter through its public interface. */
public final class ProLeapAnalyzerFactory {
    private ProLeapAnalyzerFactory() { }

    public static CobolAnalyzer create() {
        return new ProLeapCobolAnalyzer();
    }
}
