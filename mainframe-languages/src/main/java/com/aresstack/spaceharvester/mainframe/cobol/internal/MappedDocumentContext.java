package com.aresstack.spaceharvester.mainframe.cobol.internal;

import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalysis.Position;
import io.proleap.cobol.CobolPreprocessorParser.ReplaceClauseContext;
import io.proleap.cobol.preprocessor.sub.document.impl.CobolDocumentContext;
import io.proleap.cobol.preprocessor.sub.util.TokenUtils;
import org.antlr.v4.runtime.BufferedTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;

import java.util.List;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.regex.Matcher;

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
        clauses = replacements == null ? List.of() : List.copyOf(replacements);
    }

    @Override
    public void replaceReplaceablesByReplacements(BufferedTokenStream tokens) {
        if (clauses.isEmpty()) {
            return;
        }
        MappedCobolText input = mappedText();
        List<TextWord> words = words(input.text());
        List<Rule> rules = new ArrayList<>();
        for (ReplaceClauseContext clause : clauses) {
            String from = replacementText(clause.replaceable(), tokens);
            String to = replacementText(clause.replacement(), tokens);
            if (from == null || from.isBlank() || to == null) {
                continue;
            }
            Mode mode = clause.partialReplaceMode() == null ? Mode.FULL
                    : Mode.valueOf(clause.partialReplaceMode().getText().toUpperCase(java.util.Locale.ROOT));
            List<TextWord> pattern = patternWords(from);
            if (!pattern.isEmpty()) {
                rules.add(new Rule(pattern, to, mode));
            }
        }
        List<MappedCobolText.Replacement> matches = new ArrayList<>();
        for (int index = 0; index < words.size();) {
            Match matched = null;
            for (Rule rule : rules) {
                matched = rule.match(words, index);
                if (matched != null) {
                    break;
                }
            }
            if (matched == null) {
                index++;
            } else {
                matches.add(matched.replacement());
                index = matched.next();
            }
        }
        output = new MappedCobolText.Builder();
        output.append(input.replaceAll(matches));
    }

    // Treat literals as indivisible, including common national and hexadecimal literal prefixes.
    private static final Pattern TEXT_WORD = Pattern.compile(
            "\\*>[^\\r\\n]*|(?i:(?:NX|UX|BX|N|X|G|Z|U|B)?)(?:'(?:''|[^'])*'|\"(?:\"\"|[^\"])*\")|[A-Za-z0-9_$@#-]+|[^\\s]");
    private static final Pattern ORDINARY_WORD = Pattern.compile("[A-Za-z0-9_$@#-]+");

    private enum Mode { FULL, LEADING, TRAILING }

    private record TextWord(String text, int start, int end) {
        boolean isComment() {
            return text.startsWith("*>");
        }

        boolean isSeparator() {
            return text.equals(",") || text.equals(";");
        }

        boolean isOrdinary() {
            return ORDINARY_WORD.matcher(text).matches();
        }

        boolean same(TextWord other) {
            if (isComment() || other.isComment()) {
                return false;
            }
            return text.indexOf('\'') >= 0 || text.indexOf('"') >= 0
                    ? text.equals(other.text) : text.equalsIgnoreCase(other.text);
        }
    }

    private record Match(MappedCobolText.Replacement replacement, int next) { }

    private record Rule(List<TextWord> pattern, String replacement, Mode mode) {
        Rule {
            if (mode != Mode.FULL && (pattern.size() != 1 || !pattern.getFirst().isOrdinary()
                    || !replacement.isEmpty() && !ORDINARY_WORD.matcher(replacement).matches())) {
                throw new IllegalArgumentException("Invalid partial-word replacement");
            }
        }

        Match match(List<TextWord> input, int start) {
            if (mode != Mode.FULL) {
                return matchPartial(input.get(start), start);
            }
            int cursor = start;
            for (int i = 0; i < pattern.size(); i++) {
                if (i > 0) {
                    while (cursor < input.size() && (input.get(cursor).isComment() || input.get(cursor).isSeparator())) {
                        cursor++;
                    }
                }
                if (cursor >= input.size() || !pattern.get(i).same(input.get(cursor))) {
                    return null;
                }
                cursor++;
            }
            return new Match(new MappedCobolText.Replacement(input.get(start).start(),
                    input.get(cursor - 1).end(), replacement), cursor);
        }

        private Match matchPartial(TextWord word, int index) {
            String needle = pattern.getFirst().text();
            int offset = mode == Mode.LEADING ? 0 : word.text().length() - needle.length();
            if (!word.isOrdinary() || offset < 0 || !word.text().regionMatches(true, offset, needle, 0, needle.length())) {
                return null;
            }
            int start = word.start() + offset;
            return new Match(new MappedCobolText.Replacement(start, start + needle.length(), replacement), index + 1);
        }
    }

    private static List<TextWord> patternWords(String text) {
        List<TextWord> result = words(text).stream().filter(word -> !word.isComment()).toList();
        return result.size() == 1 ? result : result.stream().filter(word -> !word.isSeparator()).toList();
    }

    private static List<TextWord> words(String text) {
        List<TextWord> result = new ArrayList<>();
        Matcher matcher = TEXT_WORD.matcher(text);
        while (matcher.find()) {
            result.add(new TextWord(matcher.group(), matcher.start(), matcher.end()));
        }
        return result;
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