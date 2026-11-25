package com.aresstack.spaceharvester.mainframe.cobol.internal;

import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalysis;
import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalysis.Diagnostic;
import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalysis.Element;
import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalysis.Position;
import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalyzer;
import com.aresstack.spaceharvester.mainframe.cobol.CobolSource;
import com.aresstack.spaceharvester.mainframe.cobol.CopybookResolver;
import io.proleap.cobol.CobolLexer;
import io.proleap.cobol.CobolParser;
import io.proleap.cobol.asg.metamodel.Program;
import io.proleap.cobol.asg.metamodel.impl.ProgramImpl;
import io.proleap.cobol.asg.runner.impl.CobolParserRunnerImpl;
import io.proleap.cobol.asg.visitor.impl.CobolCompilationUnitVisitorImpl;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import java.util.ArrayList;
import java.util.List;

/** Parse once and project ProLeap's syntax and semantic facts onto our own model. */
final class ProLeapCobolAnalyzer implements CobolAnalyzer {
    @Override
    public CobolAnalysis analyze(CobolSource source, CopybookResolver copybooks) {
        if (source.content().isBlank()) {
            return new CobolAnalysis(List.of(), List.of(), List.of(), List.of(), true);
        }
        List<Diagnostic> diagnostics = new ArrayList<>();
        List<Element> copyReferences = new ArrayList<>();
        MappedCobolText prepared;
        try {
            prepared = new SourceMappedPreprocessor(copybooks, diagnostics, copyReferences).prepare(source);
        } catch (RuntimeException error) {
            diagnostics.add(new Diagnostic("COBOL preprocessing failed: " + error.getMessage(), Position.UNKNOWN));
            return new CobolAnalysis(copyReferences, List.of(), List.of(), diagnostics, false);
        }

        CobolDiagnostics errorListener = new CobolDiagnostics(prepared, diagnostics);
        CobolLexer lexer = new CobolLexer(CharStreams.fromString(prepared.text()));
        lexer.removeErrorListeners();
        lexer.addErrorListener(errorListener);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        CobolParser parser = new CobolParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(errorListener);
        CobolParser.StartRuleContext tree = parser.startRule();

        Program program = buildSemanticModel(source, prepared, tokens, tree, diagnostics);
        CobolStructureVisitor visitor = new CobolStructureVisitor(prepared, tokens, program);
        try {
            visitor.visit(tree);
        } catch (RuntimeException error) {
            diagnostics.add(new Diagnostic("COBOL structure analysis incomplete: " + error.getMessage(), Position.UNKNOWN));
        }
        List<Element> elements = new ArrayList<>(visitor.elements());
        elements.addAll(copyReferences);
        return new CobolAnalysis(elements, visitor.calls(), visitor.references(), diagnostics,
                program != null && diagnostics.isEmpty());
    }

    private Program buildSemanticModel(CobolSource source, MappedCobolText prepared, CommonTokenStream tokens,
                                       CobolParser.StartRuleContext tree, List<Diagnostic> diagnostics) {
        // Do not treat a recovered or incomplete syntax tree as a complete semantic graph.
        if (diagnostics.stream().anyMatch(d -> d.kind() == CobolAnalysis.DiagnosticKind.SYNTAX_ERROR)) {
            return null;
        }
        try {
            Program program = new ProgramImpl();
            String name = source.name().isBlank() ? "COBOL" : source.name();
            new CobolCompilationUnitVisitorImpl(name, prepared.text().lines().toList(), tokens, program).visit(tree);
            new SemanticRunner().build(program);
            return program;
        } catch (RuntimeException error) {
            diagnostics.add(new Diagnostic("COBOL semantic analysis incomplete: " + error.getMessage(), Position.UNKNOWN));
            return null;
        }
    }

    private static final class SemanticRunner extends CobolParserRunnerImpl {
        void build(Program program) {
            analyze(program);
        }
    }
}
