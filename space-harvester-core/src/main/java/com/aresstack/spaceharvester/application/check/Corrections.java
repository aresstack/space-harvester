package com.aresstack.spaceharvester.application.check;

import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.simplification.SentenceSimplifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Korrekturen des Prüfschritts (ADR 0032, FL4): Eine Korrektur ist ein einfacher Hauptsatz wie eine Zeile der Vorstufe
 * und geht denselben Weg: Wortabgleich ({@link StatementCheck#parse}), parsen mit dem Satzanalysator, dann hier prüfen
 * und zusammenführen. Das Modell liefert nie Rollen; Rollen und Stufe kommen aus dem Parse der Korrektur.
 *
 * <p>Eine Korrektur vervollständigt, sie tauscht nichts aus ({@link #accept}):
 * <ul>
 *     <li>Verb: Der Parse hat eine Aussage zum selben Verb (oder Zustand) wie die korrigierte Aussage.</li>
 *     <li>Verneinung: gleich der korrigierten Aussage, soweit beide bekannt; eine fehlende oder überzählige Verneinung
 *     ist kein „unvollständig“, sondern „falsch“.</li>
 *     <li>Handelnder: Hat die korrigierte Aussage einen Handelnden oder Träger, hat die Korrektur einen mit demselben
 *     Kopf; ohne einen darf sie ihn ergänzen.</li>
 *     <li>Beteiligte: Jeder steht wörtlich oder als Form desselben Stamms im Originalsatz, und mindestens einer ist
 *     neu.</li>
 * </ul>
 *
 * <p>Zusammenführen ({@link #merge}): Die korrigierte Aussage bleibt stehen, ihr Urteil senkt die wirksame Stufe. Die
 * Korrektur kommt als eigene Aussage mit Herkunft {@link ParsedStatements.Origin#MODELL} hinzu, zeigt mit Spannen und
 * Satz auf den Originalsatz und mit {@link ParsedStatements.Derivation#corrects()} auf die korrigierte Aussage. Ihre
 * Merkmale stammen aus dem Original, sobald es im selben Satz eine Aussage zum selben Verb hat, sonst aus ihrem Parse.
 */
public final class Corrections {

    private Corrections() {
    }

    /** Warum eine geparste Korrektur nicht übernommen wird; {@link #ACCEPTED}, wenn sie gilt. */
    public enum Outcome {
        ACCEPTED,
        /** Kein Verb oder Zustand wie in der korrigierten Aussage. */
        OTHER_VERB,
        /** Verneinung anders als in der korrigierten Aussage. */
        NEGATION,
        /** Handelnder oder Träger fehlt oder ist ein anderer. */
        ACTOR,
        /** Ein Beteiligter steht nicht im Originalsatz. */
        WORDS,
        /** Kein Beteiligter kommt hinzu. */
        NOTHING_NEW
    }

    /**
     * Was {@link #accept} von einer Aussage braucht; Prüfstand und Auftrag bilden ihre Aussagen darauf ab.
     *
     * @param lemma        Grundform des Verbs oder Adjektiv/Nomen des Zustands
     * @param negation     Verneinung
     * @param actorHeads   Grundformen (sonst Wortlaut) der Köpfe von Handelndem und Träger, klein geschrieben
     * @param participants Wortlaut aller Beteiligten
     */
    public record View(String lemma, ParsedStatements.Negation negation, Set<String> actorHeads,
                       List<String> participants) {

        public View {
            Objects.requireNonNull(lemma, "lemma");
            negation = negation == null ? ParsedStatements.Negation.UNKNOWN : negation;
            actorHeads = Set.copyOf(actorHeads);
            participants = List.copyOf(participants);
        }

        /** Eine Aussage einer Analyse. */
        public static View of(ParsedStatements.Statement statement, Map<String, ParsedStatements.Mention> mentions) {
            Set<String> heads = new HashSet<>();
            List<String> participants = new ArrayList<>();
            for (ParsedStatements.Argument argument : statement.arguments()) {
                ParsedStatements.Mention mention = mentions.get(argument.mention());
                if (mention == null) {
                    continue;
                }
                participants.add(mention.text());
                if (argument.role() == ParsedStatements.Role.ACTOR || argument.role() == ParsedStatements.Role.BEARER) {
                    String head = mention.lemma() != null ? mention.lemma()
                            : mention.headForm() != null ? mention.headForm() : mention.text();
                    heads.add(head.toLowerCase(Locale.GERMAN));
                }
            }
            String lemma = statement.predicateLemma() != null ? statement.predicateLemma() : statement.predicateText();
            return new View(lemma, statement.features().negation(), heads, participants);
        }
    }

    /** Ob zwei Aussagen dasselbe Verb (oder denselben Zustand) haben, ohne Groß- und Kleinschreibung. */
    public static boolean sameVerb(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    /**
     * Prüft eine geparste Korrektur gegen die korrigierte Aussage und den Originalsatz.
     *
     * @param corrected  die korrigierte Aussage
     * @param candidate  die Aussage aus dem Parse der Korrektur zum selben Verb; {@code null}, wenn es keine gab
     * @param sentence   der Originalsatz
     */
    public static Outcome accept(View corrected, View candidate, String sentence) {
        if (candidate == null || !sameVerb(corrected.lemma(), candidate.lemma())) {
            return Outcome.OTHER_VERB;
        }
        if (corrected.negation() != ParsedStatements.Negation.UNKNOWN
                && candidate.negation() != ParsedStatements.Negation.UNKNOWN
                && corrected.negation() != candidate.negation()) {
            return Outcome.NEGATION;
        }
        if (!corrected.actorHeads().isEmpty()
                && candidate.actorHeads().stream().noneMatch(corrected.actorHeads()::contains)) {
            return Outcome.ACTOR;
        }
        for (String participant : candidate.participants()) {
            if (!SentenceSimplifier.derivable(sentence, participant)) {
                return Outcome.WORDS;
            }
        }
        Set<String> known = new HashSet<>();
        corrected.participants().forEach(text -> known.add(normal(text)));
        if (candidate.participants().stream().allMatch(text -> known.contains(normal(text)))) {
            return Outcome.NOTHING_NEW;
        }
        return Outcome.ACCEPTED;
    }

    private static String normal(String text) {
        return text.replaceAll("\\s+", " ").strip().toLowerCase(Locale.GERMAN);
    }

    /** Eine Korrektur zum Zusammenführen: die korrigierte Aussage, der Satz des Modells und sein Parse. */
    public record Line(ParsedStatements.Statement corrected, String correction) {

        public Line {
            Objects.requireNonNull(corrected, "corrected");
            Objects.requireNonNull(correction, "correction");
        }
    }

    /**
     * Ergebnis von {@link #merge}.
     *
     * @param result    die Analyse mit den übernommenen Korrekturen als Aussagen samt ihren Erwähnungen
     * @param corrected IDs der Aussagen, deren Korrektur übernommen wurde
     * @param rejected  je Grund die Zahl der nicht übernommenen Korrekturen
     */
    public record Merged(ParsedStatements result, Set<String> corrected, Map<Outcome, Integer> rejected) {
    }

    /**
     * Führt geparste Korrekturen mit der Analyse zusammen.
     *
     * @param base       die Analyse ohne Urteile und Korrekturen
     * @param documentId Präfix der IDs der Analyse
     * @param text       der Text der Analyse (für den Originalsatz)
     * @param lines      die Korrekturen in der Reihenfolge, in der sie geparst wurden, eine je Zeile
     * @param parsed     der Parse aller Korrekturen, eine Zeile je Korrektur, mit dem Präfix {@code parsedId}
     * @param lineStarts Beginn jeder Zeile im geparsten Text
     * @param parsedId   Präfix der IDs im Parse der Korrekturen
     */
    public static Merged merge(ParsedStatements base, String documentId, String text, List<Line> lines,
                               ParsedStatements parsed, int[] lineStarts, String parsedId) {
        Map<String, ParsedStatements.Mention> baseMentions = mentions(base);
        Map<String, ParsedStatements.Mention> parsedMentions = mentions(parsed);
        Map<Integer, ParsedStatements.Sentence> sentences = new HashMap<>();
        base.sentences().forEach(sentence -> sentences.put(sentence.index(), sentence));
        Map<Integer, List<ParsedStatements.Statement>> byLine = new HashMap<>();
        Map<Integer, Integer> parsedSentenceLine = new HashMap<>();
        for (ParsedStatements.Sentence sentence : parsed.sentences()) {
            parsedSentenceLine.put(sentence.index(), lineOf(lineStarts, sentence.start()));
        }
        for (ParsedStatements.Statement statement : parsed.statements()) {
            Integer line = parsedSentenceLine.get(statement.sentence());
            if (line != null) {
                byLine.computeIfAbsent(line, key -> new ArrayList<>()).add(statement);
            }
        }
        List<ParsedStatements.Statement> statements = new ArrayList<>(base.statements());
        List<ParsedStatements.Mention> mentions = new ArrayList<>(base.mentions());
        Set<String> corrected = new HashSet<>();
        Map<Outcome, Integer> rejected = new java.util.EnumMap<>(Outcome.class);
        for (int index = 0; index < lines.size(); index++) {
            Line line = lines.get(index);
            ParsedStatements.Statement target = line.corrected();
            ParsedStatements.Sentence original = sentences.get(target.sentence());
            if (original == null || corrected.contains(target.id())) {
                rejected.merge(Outcome.OTHER_VERB, 1, Integer::sum);
                continue;
            }
            String sentenceText = text.substring(Math.min(original.start(), text.length()),
                    Math.min(original.end(), text.length()));
            View targetView = View.of(target, baseMentions);
            ParsedStatements.Statement candidate = byLine.getOrDefault(index, List.of()).stream()
                    .filter(statement -> sameVerb(targetView.lemma(), lemma(statement))).findFirst().orElse(null);
            Outcome outcome = accept(targetView, candidate == null ? null : View.of(candidate, parsedMentions),
                    sentenceText);
            if (outcome != Outcome.ACCEPTED) {
                rejected.merge(outcome, 1, Integer::sum);
                continue;
            }
            corrected.add(target.id());
            // IDs: Präfix der Analyse, Originalsatz, „m“ und Nummer der Korrektur
            String to = documentId + "#s" + (target.sentence() + 1) + ".m" + (index + 1) + ".";
            int parsedSentence = candidate.sentence();
            String from = parsedId + "#s" + (parsedSentence + 1) + ".";
            Map<String, String> renamed = new HashMap<>();
            List<ParsedStatements.Argument> arguments = new ArrayList<>();
            for (ParsedStatements.Argument argument : candidate.arguments()) {
                String id = rename(argument.mention(), from, to);
                renamed.put(argument.mention(), id);
                arguments.add(new ParsedStatements.Argument(argument.role(), id, argument.relation(),
                        argument.marker(), argument.priority(), argument.basis(), argument.certain()));
            }
            for (Map.Entry<String, String> entry : renamed.entrySet()) {
                ParsedStatements.Mention mention = parsedMentions.get(entry.getKey());
                if (mention != null) {
                    mentions.add(new ParsedStatements.Mention(entry.getValue(), target.sentence(), original.start(),
                            original.end(), mention.text(), mention.headForm(), mention.lemma(), mention.kind(),
                            mention.unresolved(), mention.cases()));
                }
            }
            ParsedStatements.Statement fromOriginal = base.statements().stream()
                    .filter(statement -> statement.sentence() == target.sentence()
                            && statement.derivation().origin() == ParsedStatements.Origin.ORIGINAL
                            && statement.features().known() && sameVerb(lemma(statement), lemma(candidate)))
                    .findFirst().orElse(null);
            ParsedStatements.Features features = fromOriginal != null ? fromOriginal.features()
                    : candidate.features();
            ParsedStatements.Origin featuresFrom = fromOriginal != null ? ParsedStatements.Origin.ORIGINAL
                    : ParsedStatements.Origin.MODELL;
            statements.add(new ParsedStatements.Statement(rename(candidate.id(), from, to), target.sentence(),
                    original.start(), original.end(), candidate.predicateText(), candidate.predicateLemma(),
                    candidate.voice(), candidate.clause(), false, arguments, candidate.findings(), target.paragraph(),
                    candidate.quality(), candidate.qualityReasons(), ParsedStatements.Review.UNREVIEWED, features,
                    ParsedStatements.Derivation.modell(line.correction(), target.id(), featuresFrom)));
        }
        ParsedStatements result = new ParsedStatements(base.sentences(), mentions, statements, base.attachments(),
                base.components(), base.conllu());
        return new Merged(result, Set.copyOf(corrected), Map.copyOf(rejected));
    }

    private static String lemma(ParsedStatements.Statement statement) {
        return statement.predicateLemma() != null ? statement.predicateLemma() : statement.predicateText();
    }

    private static Map<String, ParsedStatements.Mention> mentions(ParsedStatements result) {
        Map<String, ParsedStatements.Mention> mentions = new HashMap<>();
        result.mentions().forEach(mention -> mentions.put(mention.id(), mention));
        return mentions;
    }

    private static int lineOf(int[] lineStarts, int offset) {
        int line = 0;
        while (line + 1 < lineStarts.length && lineStarts[line + 1] <= offset) {
            line++;
        }
        return line;
    }

    private static String rename(String id, String from, String to) {
        return id != null && id.startsWith(from) ? to + id.substring(from.length()) : to + id;
    }
}
