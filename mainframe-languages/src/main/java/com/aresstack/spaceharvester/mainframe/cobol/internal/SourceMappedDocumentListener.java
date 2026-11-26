package com.aresstack.spaceharvester.mainframe.cobol.internal;

import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalysis.*;
import com.aresstack.spaceharvester.mainframe.cobol.CobolSource;
import com.aresstack.spaceharvester.mainframe.cobol.CopybookResolver;
import io.proleap.cobol.CobolPreprocessorLexer;
import io.proleap.cobol.CobolPreprocessorParser.*;
import io.proleap.cobol.asg.params.CobolParserParams;
import io.proleap.cobol.preprocessor.CobolPreprocessor;
import io.proleap.cobol.preprocessor.sub.CobolLine;
import io.proleap.cobol.preprocessor.sub.document.impl.CobolDocumentParserListenerImpl;
import io.proleap.cobol.preprocessor.sub.util.TokenUtils;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** Reuse ProLeap directive parsing while preserving original source positions. */
final class SourceMappedDocumentListener extends CobolDocumentParserListenerImpl {
    private final Deque<MappedDocumentContext> contexts = new ArrayDeque<>();
    private final CobolParserParams params;
    private final CommonTokenStream tokens;
    private final MappedCobolText input;
    private final CobolSource source;
    private final CopybookResolver resolver;
    private final Function<CobolSource, MappedCobolText> prepareCopybook;
    private final List<Diagnostic> diagnostics;
    private final List<Element> copyReferences;

    SourceMappedDocumentListener(CobolParserParams params, CommonTokenStream tokens,
                                 MappedCobolText input, CobolSource source, CopybookResolver resolver,
                                 Function<CobolSource, MappedCobolText> prepareCopybook,
                                 List<Diagnostic> diagnostics, List<Element> copyReferences) {
        super(params, tokens);
        this.params = params;
        this.tokens = tokens;
        this.input = input;
        this.source = source;
        this.resolver = resolver;
        this.prepareCopybook = prepareCopybook;
        this.diagnostics = diagnostics;
        this.copyReferences = copyReferences;
        push();
    }

    @Override
    public MappedDocumentContext context() {
        return contexts.peek();
    }

    @Override
    protected MappedDocumentContext pop() {
        return contexts.pop();
    }

    @Override
    protected MappedDocumentContext push() {
        MappedDocumentContext context = new MappedDocumentContext((clause, message) ->
                diagnostics.add(new Diagnostic("Unsupported replacement clause: " + message,
                        input.at(clause.getStart()), DiagnosticKind.COPYBOOK)));
        contexts.push(context);
        return context;
    }

    MappedCobolText result() {
        return context().mappedText();
    }

    @Override
    public void visitTerminal(TerminalNode node) {
        Token token = node.getSymbol();
        if (token.getTokenIndex() < 0) {
            return;
        }
        List<Token> hidden = tokens.getHiddenTokensToLeft(token.getTokenIndex(), CobolPreprocessorLexer.HIDDEN);
        if (hidden != null) {
            hidden.forEach(value -> context().appendToken(input, value));
        }
        if (token.getType() != Token.EOF) {
            context().appendToken(input, token);
        }
    }

    @Override
    public void exitCopyStatement(CopyStatementContext ctx) {
        pop();
        CopySourceContext target = ctx.copySource();
        if (target == null) {
            return;
        }
        String name = unquote(target.literal() != null ? target.literal().getText()
                : target.cobolWord() != null ? target.cobolWord().getText()
                : target.filename() != null ? target.filename().getText() : "");
        String library = target.copyLibrary() == null ? "" : unquote(target.copyLibrary().getText());
        Span span = input.span(ctx.getStart(), ctx.getStop());
        Optional<CobolSource> copybook = resolve(name, library, span.start());
        Map<String, String> attributes = new java.util.LinkedHashMap<>();
        attributes.put("COPYBOOK", name);
        attributes.put("LIBRARY", library);
        copybook.ifPresent(resolved -> attributes.put("RESOLVED_SOURCE", resolved.name()));
        copyReferences.add(new Element(Kind.COPY_STMT, name, -1, span,
                TokenUtils.getTextIncludingHiddenTokens(ctx, tokens).strip(), attributes));
        if (copybook.isEmpty()) {
            // Keep the remaining program analyzable and report the missing dependency.
            context().append("\n", span.start());
            return;
        }
        push();
        context().storeReplaceablesAndReplacements(ctx.replacingPhrase().stream()
                .flatMap(phrase -> phrase.replaceClause().stream()).toList());
        try {
            context().append(prepareCopybook.apply(copybook.get()));
            context().append("\n", span.end());
            context().replaceReplaceablesByReplacements(tokens);
        } catch (RuntimeException error) {
            diagnostics.add(new Diagnostic("Cannot preprocess COPY " + name + ": " + error.getMessage(), span.start()));
        }
        MappedCobolText expanded = pop().mappedText();
        context().append(expanded);
    }

    private Optional<CobolSource> resolve(String name, String library, Position position) {
        try {
            Optional<CobolSource> result = resolver.resolve(source, name, library);
            if (result.isEmpty()) {
                diagnostics.add(new Diagnostic("Copybook not found: " + name, position, DiagnosticKind.COPYBOOK));
            }
            return result;
        } catch (IOException | RuntimeException error) {
            diagnostics.add(new Diagnostic("Cannot resolve COPY " + name + ": " + error.getMessage(), position, DiagnosticKind.COPYBOOK));
            return Optional.empty();
        }
    }

    @Override
    public void exitReplaceArea(ReplaceAreaContext ctx) {
        if (ctx.replaceByStatement() != null) {
            context().storeReplaceablesAndReplacements(ctx.replaceByStatement().replaceClause());
            context().replaceReplaceablesByReplacements(tokens);
        }
        MappedCobolText replaced = pop().mappedText();
        context().append(replaced);
    }

    @Override
    public void exitExecCicsStatement(ExecCicsStatementContext ctx) {
        embedded(ctx, CobolPreprocessor.EXEC_CICS_TAG);
    }

    @Override
    public void exitExecSqlStatement(ExecSqlStatementContext ctx) {
        embedded(ctx, CobolPreprocessor.EXEC_SQL_TAG);
    }

    @Override
    public void exitExecSqlImsStatement(ExecSqlImsStatementContext ctx) {
        embedded(ctx, CobolPreprocessor.EXEC_SQLIMS_TAG);
    }

    private void embedded(ParserRuleContext ctx, String tag) {
        pop();
        String original = TokenUtils.getTextIncludingHiddenTokens(ctx, tokens);
        String prefix = CobolLine.createBlankSequenceArea(params.getFormat()) + tag;
        context().append(buildLines(original, prefix), input.at(ctx.getStart()));
    }

    static String unquote(String value) {
        if (value.length() >= 2 && ((value.startsWith("'") && value.endsWith("'"))
                || (value.startsWith("\"") && value.endsWith("\"")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
