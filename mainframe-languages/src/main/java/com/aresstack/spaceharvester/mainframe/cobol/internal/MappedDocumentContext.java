package com.aresstack.spaceharvester.mainframe.cobol.internal;

import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalysis.Position;
import io.proleap.cobol.CobolPreprocessorParser.ReplaceClauseContext;
import io.proleap.cobol.preprocessor.sub.document.impl.CobolDocumentContext;
import io.proleap.cobol.preprocessor.sub.util.TokenUtils;
import org.antlr.v4.runtime.BufferedTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.Arrays;

/**
 * Preserve provenance while applying the replacement semantics of the MIT-licensed
 * ProLeap CobolReplacementMapping (see proleap-parser/LICENSE).
 */
final class MappedDocumentContext extends CobolDocumentContext {
    private MappedCobolText.Builder output = new MappedCobolText.Builder();
    private List<ReplaceClauseContext> clauses = List.of();

    void appendToken(MappedCobolText source, org.antlr.v4.runtime.Token token) {
        source.appendTokenTo(output, token);
    }

    void append(MappedCobolText text) {
        output.append(text);
    }

    void append(String text, Position origin) {
        output.append(text, origin);
    }

    MappedCobolText mappedText() {
        return output.build();
    }

    @Override
    public String read() {
        return mappedText().text();
    }

    @Override
    public void write(String text) {
        append(text, Position.UNKNOWN);
    }

    @Override
    public void storeReplaceablesAndReplacements(List<ReplaceClauseContext> replacements) {
        clauses = replacements == null ? List.of() : replacements.stream()
                .sorted(Comparator.comparingInt((ReplaceClauseContext c) -> c.replaceable().getText().length()).reversed())
                .toList();
    }

    @Override
    public void replaceReplaceablesByReplacements(BufferedTokenStream tokens) {
        MappedCobolText result = mappedText();
        for (ReplaceClauseContext clause : clauses) {
            String from = replacementText(clause.replaceable(), tokens);
            String to = replacementText(clause.replacement(), tokens);
            if (from == null || from.isBlank() || to == null) {
                continue;
            }
            String expression = Arrays.stream(from.split("\\s+"))
                    .map(Pattern::quote).collect(Collectors.joining("[\\r\\n\\s]+"));
            boolean word = clause.replaceable().cobolWord() != null;
            if (word) {
                expression = "(?<![A-Za-z0-9_-])" + expression + "(?![A-Za-z0-9_-])";
            }
            result = result.replace(Pattern.compile(expression, word ? Pattern.CASE_INSENSITIVE : 0), to);
        }
        output = new MappedCobolText.Builder();
        output.append(result);
    }

    private String replacementText(ParserRuleContext context, BufferedTokenStream tokens) {
        if (context == null) {
            return null;
        }
        String text = TokenUtils.getTextIncludingHiddenTokens(context, tokens).trim();
        if (text.startsWith("==") && text.endsWith("==") && text.length() >= 4) {
            return text.substring(2, text.length() - 2).trim();
        }
        return text;
    }
}
