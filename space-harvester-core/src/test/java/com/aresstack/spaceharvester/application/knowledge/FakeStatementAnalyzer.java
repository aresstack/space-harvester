package com.aresstack.spaceharvester.application.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regelbasierter Ersatz für MaltParser in Core-Tests: je Satz (bis {@code .}) großgeschriebene Wörter außer Artikeln
 * als Erwähnungen, {@code er}/{@code sie}/{@code ihn} als offene Pronomen, das erste Wort aus {@link #VERBS} als
 * Prädikat; erste Erwähnung handelt, letzte ist betroffen, eine Erwähnung nach {@code des} hängt nominal an der
 * vorherigen.
 */
final class FakeStatementAnalyzer implements StatementAnalyzer {

    static final Set<String> VERBS = Set.of("übermittelt", "prüft", "sendet", "genehmigt");
    private static final Set<String> ARTICLES = Set.of("Der", "Die", "Das", "Dem", "Den", "Des", "Ein", "Eine");
    private static final Set<String> PRONOUNS = Set.of("er", "sie", "ihn");
    private static final Pattern WORD = Pattern.compile("\\p{L}+");

    final AtomicInteger calls = new AtomicInteger();
    String version = "1|fake-model|abc";
    RuntimeException failure;
    /** Bricht nach so vielen Sätzen ab (über den Abbruch-Schalter des Aufrufers), -1 = nie. */
    int cancelAfterSentences = -1;
    /** Liefert eine Erwähnung außerhalb des Texts. */
    boolean broken;

    @Override
    public String id() {
        return "fake";
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public ParsedStatements analyze(String documentId, String text, BooleanSupplier cancelled) {
        calls.incrementAndGet();
        if (failure != null) {
            throw failure;
        }
        List<ParsedStatements.Sentence> sentences = new ArrayList<>();
        List<ParsedStatements.Mention> mentions = new ArrayList<>();
        List<ParsedStatements.Statement> statements = new ArrayList<>();
        List<ParsedStatements.Attachment> attachments = new ArrayList<>();
        StringBuilder conllu = new StringBuilder();
        int start = 0;
        int index = 0;
        while (start < text.length()) {
            int end = text.indexOf('.', start);
            end = end < 0 ? text.length() : end + 1;
            if (cancelled.getAsBoolean() || index == cancelAfterSentences) {
                throw new CancellationException();
            }
            sentences.add(new ParsedStatements.Sentence(index, start, end));
            List<ParsedStatements.Mention> own = new ArrayList<>();
            int verbStart = -1;
            String verb = null;
            boolean genitive = false;
            Matcher words = WORD.matcher(text).region(start, end);
            int token = 0;
            while (words.find()) {
                token++;
                String word = words.group();
                conllu.append(token).append('\t').append(word).append("\t_\t_\t_\t_\t0\tdep\t_\t_\n");
                if (VERBS.contains(word) && verb == null) {
                    verb = word;
                    verbStart = words.start();
                } else if (PRONOUNS.contains(word)) {
                    own.add(new ParsedStatements.Mention(documentId + "#s" + index + ".t" + token, index,
                            words.start(), words.end(), word, word, word, ParsedStatements.MentionKind.PRONOUN, true,
                            Set.of()));
                } else if ("des".equals(word)) {
                    genitive = true;
                } else if (Character.isUpperCase(word.charAt(0)) && !ARTICLES.contains(word)) {
                    ParsedStatements.Mention mention = new ParsedStatements.Mention(
                            documentId + "#s" + index + ".t" + token, index, words.start(),
                            broken ? text.length() + 5 : words.end(), word, word, word,
                            ParsedStatements.MentionKind.NOUN, false, Set.of());
                    if (genitive && !own.isEmpty()) {
                        attachments.add(new ParsedStatements.Attachment(own.getLast().id() + "→t" + token,
                                own.getLast().id(), mention.id(), "nmod", null,
                                ParsedStatements.AttachmentKind.GENITIVE, ParsedStatements.Basis.PARSER));
                    }
                    genitive = false;
                    own.add(mention);
                }
            }
            conllu.append('\n');
            mentions.addAll(own);
            if (verb != null && own.size() >= 2) {
                List<ParsedStatements.Argument> arguments = List.of(
                        new ParsedStatements.Argument(ParsedStatements.Role.ACTOR, own.getFirst().id(), "nsubj", null,
                                ParsedStatements.Priority.CORE, ParsedStatements.Basis.PARSER, true),
                        new ParsedStatements.Argument(ParsedStatements.Role.AFFECTED_OBJECT, own.getLast().id(), "obj",
                                null, ParsedStatements.Priority.CORE, ParsedStatements.Basis.PARSER, true));
                boolean pronoun = own.getFirst().unresolved() || own.getLast().unresolved();
                statements.add(new ParsedStatements.Statement(documentId + "#s" + index + ".v1", index, verbStart,
                        verbStart + verb.length(), verb, verb, ParsedStatements.Voice.ACTIVE,
                        ParsedStatements.Clause.MAIN, true, arguments,
                        pronoun ? Set.of("UNRESOLVED_PRONOUN") : Set.of()));
            }
            start = end;
            index++;
        }
        return new ParsedStatements(sentences, mentions, statements, attachments, List.of("parser=fake"),
                conllu.toString());
    }
}
