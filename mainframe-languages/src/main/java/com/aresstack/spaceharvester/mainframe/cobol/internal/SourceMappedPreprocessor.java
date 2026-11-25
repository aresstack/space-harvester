package com.aresstack.spaceharvester.mainframe.cobol.internal;

import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalysis.Diagnostic;
import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalysis.Element;
import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalysis.Kind;
import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalysis.Position;
import com.aresstack.spaceharvester.mainframe.cobol.CobolSource;
import com.aresstack.spaceharvester.mainframe.cobol.CopybookResolver;
import io.proleap.cobol.CobolPreprocessorLexer;
import io.proleap.cobol.CobolPreprocessorParser;
import io.proleap.cobol.asg.params.impl.CobolParserParamsImpl;
import io.proleap.cobol.preprocessor.CobolPreprocessor.CobolSourceFormatEnum;
import io.proleap.cobol.preprocessor.impl.CobolPreprocessorImpl;
import io.proleap.cobol.preprocessor.sub.CobolLine;
import io.proleap.cobol.preprocessor.sub.CobolLineTypeEnum;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

final class SourceMappedPreprocessor extends CobolPreprocessorImpl {
    private final CopybookResolver resolver;
    private final List<Diagnostic> diagnostics;
    private final List<Element> copyReferences;
    private final Deque<String> activeSources = new ArrayDeque<>();

    SourceMappedPreprocessor(CopybookResolver resolver, List<Diagnostic> diagnostics, List<Element> copyReferences) {
        this.resolver = resolver;
        this.diagnostics = diagnostics;
        this.copyReferences = copyReferences;
    }

    MappedCobolText prepare(CobolSource source) {
        if (activeSources.contains(source.name()) || activeSources.size() >= 64) {
            diagnostics.add(new Diagnostic("Recursive or excessively nested COPY: " + source.name(), Position.UNKNOWN));
            return new MappedCobolText.Builder().build();
        }
        activeSources.push(source.name());
        try {
            var params = new CobolParserParamsImpl();
            params.setFormat(CobolSourceFormatEnum.valueOf(source.format().name()));
            MappedCobolText normalized = normalize(source, params);
            return processDocument(source, normalized, params);
        } finally {
            activeSources.pop();
        }
    }

    private MappedCobolText normalize(CobolSource source, CobolParserParamsImpl params) {
        List<CobolLine> lines = rewriteLines(readLines(source.content(), params));
        MappedCobolText.Builder output = new MappedCobolText.Builder();
        for (CobolLine line : lines) {
            int number = line.getNumber() + 1;
            Position origin = new Position(source.name(), number, 0, line.getType() == CobolLineTypeEnum.DEBUG);
            if (line.getType() != CobolLineTypeEnum.CONTINUATION) {
                if (number > 1) {
                    output.append("\n", origin);
                }
                output.append(line.getBlankSequenceArea() + line.getIndicatorArea(), origin);
            }
            appendContent(output, source, line);
        }
        return output.build();
    }

    private void appendContent(MappedCobolText.Builder output, CobolSource source, CobolLine line) {
        String content = line.getContentArea();
        String original = line.getContentAreaOriginal();
        int offset = original.indexOf(content);
        int firstColumn = source.format() == CobolSource.Format.TANDEM ? 2 : 8;
        boolean debugging = line.getType() == CobolLineTypeEnum.DEBUG;
        if (offset < 0 || offset != original.lastIndexOf(content)) {
            output.append(content, new Position(source.name(), line.getNumber() + 1, 0, debugging));
            return;
        }
        output.appendLineContent(content,
                new Position(source.name(), line.getNumber() + 1, firstColumn + offset, debugging));
    }

    private MappedCobolText processDocument(CobolSource source, MappedCobolText normalized,
                                           CobolParserParamsImpl params) {
        CobolDiagnostics errors = new CobolDiagnostics(normalized, diagnostics);
        CobolPreprocessorLexer lexer = new CobolPreprocessorLexer(CharStreams.fromString(normalized.text()));
        lexer.removeErrorListeners();
        lexer.addErrorListener(errors);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        CobolPreprocessorParser parser = new CobolPreprocessorParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(errors);
        var tree = parser.startRule();
        SourceMappedDocumentListener listener = new SourceMappedDocumentListener(
                params, tokens, normalized, source, resolver, this::prepare, diagnostics, copyReferences);
        ParseTreeWalker.DEFAULT.walk(listener, tree);
        return listener.result();
    }
}
