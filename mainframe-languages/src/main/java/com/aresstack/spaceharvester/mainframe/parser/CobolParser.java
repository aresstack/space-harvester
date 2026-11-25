package com.aresstack.spaceharvester.mainframe.parser;

import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalyzer;
import com.aresstack.spaceharvester.mainframe.cobol.CobolSource;
import com.aresstack.spaceharvester.mainframe.cobol.CopybookResolver;
import com.aresstack.spaceharvester.mainframe.model.JclOutlineModel;

import java.util.Objects;

/** Adapt the shared COBOL analysis to the existing outline contract. */
public final class CobolParser {
    private final CobolAnalyzer analyzer;
    private final CopybookResolver copybooks;

    public CobolParser() {
        this(CobolAnalyzer.proLeap(), CopybookResolver.NONE);
    }

    public CobolParser(CobolAnalyzer analyzer, CopybookResolver copybooks) {
        this.analyzer = Objects.requireNonNull(analyzer);
        this.copybooks = Objects.requireNonNull(copybooks);
    }

    public JclOutlineModel parse(String content, String sourceName) {
        CobolSource source = CobolSource.fixed(sourceName, content);
        return new CobolOutlineProjection().project(source, analyzer.analyze(source, copybooks));
    }
}
