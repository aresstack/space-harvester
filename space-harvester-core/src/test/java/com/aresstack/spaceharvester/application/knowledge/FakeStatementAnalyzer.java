package com.aresstack.spaceharvester.application.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    /** Läuft nach der Vorstufe, vor dem Parsen. */
    Runnable afterStage = () -> { };
    /** Herkunft aller Aussagen; {@code null} = Original. */
    ParsedStatements.Derivation derivation;
    /** Begleiter des Rollennomens an allen Aussagen (ADR 0032); {@code null} = keiner. */
    ParsedStatements.Article article;
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
                        pronoun ? Set.of("UNRESOLVED_PRONOUN") : Set.of(), 0,
                        pronoun ? ParsedStatements.Quality.UNCERTAIN : ParsedStatements.Quality.PLAUSIBLE,
                        pronoun ? List.of("UNRESOLVED_PRONOUN") : List.of(), ParsedStatements.Review.UNREVIEWED,
                        // Merkmale nur, wenn der Satz „nicht“ enthält; sonst unbekannt wie bei älteren Analysen
                        text.substring(start, end).contains(" nicht") ? new ParsedStatements.Features(
                                ParsedStatements.Negation.NEGATED, ParsedStatements.Modality.NONE,
                                ParsedStatements.Temporality.GILT, article) : article != null
                                ? new ParsedStatements.Features(ParsedStatements.Negation.AFFIRMED,
                                        ParsedStatements.Modality.NONE, ParsedStatements.Temporality.GILT, article)
                                : ParsedStatements.Features.UNKNOWN,
                        derivation));
            }
            start = end;
            index++;
        }
        return new ParsedStatements(sentences, mentions, statements, attachments, List.of("parser=fake"),
                conllu.toString());
    }

    /** Antworten der Vorstufe aus dem letzten Aufruf mit Vorstufe (Satzindex → vereinfachte Sätze). */
    final List<java.util.Map<Integer, List<com.aresstack.spaceharvester.application.simplification.SimplifiedSentence>>>
            stages = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Mit Vorstufe: jeder Satz bis {@code .} wird gewählt, alle in einem Abschnitt; das Ergebnis bleibt das Original. */
    @Override
    public ParsedStatements analyze(String documentId, String text, BooleanSupplier cancelled,
                                    Simplification simplification) {
        List<com.aresstack.spaceharvester.application.simplification.SimplificationSection.Sentence> chosen =
                new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = text.indexOf('.', start);
            end = end < 0 ? text.length() : end + 1;
            String sentence = text.substring(start, end).strip();
            if (!sentence.isEmpty()) {
                chosen.add(new com.aresstack.spaceharvester.application.simplification.SimplificationSection.Sentence(
                        chosen.size(), sentence));
            }
            start = end;
        }
        Map<Integer, List<com.aresstack.spaceharvester.application.simplification.SimplifiedSentence>> answer =
                simplification.simplify(chosen.isEmpty() ? List.of() : List.of(
                new com.aresstack.spaceharvester.application.simplification.SimplificationSection(List.of(), chosen)));
        stages.add(answer);
        afterStage.run();
        return analyze(documentId, text, cancelled);
    }
}
