package com.aresstack.spaceharvester.nlp.extraction;

import com.aresstack.spaceharvester.nlp.extraction.Statement.Voice;
import com.aresstack.spaceharvester.nlp.syntax.ParsedDocument;
import com.aresstack.spaceharvester.nlp.syntax.Sentence;
import com.aresstack.spaceharvester.nlp.syntax.Token;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Kasus-Rückfall, eigene Regel neben den Parserkanten und abschaltbar ({@link ExtractionSettings#caseFallback()}):
 * <ol>
 *   <li>Fehlt das Subjekt oder das Akkusativobjekt und hängt am Prädikat eine nominale Gruppe ohne Funktion
 *       ({@code dep}), deren Kasus eindeutig Nominativ bzw. Akkusativ ist, wird sie als Subjekt ({@code nsubj?})
 *       bzw. Objekt ({@code obj?}) vorgeschlagen: {@link Basis#CASE_HEURISTIC}, unsicher, nie Teil der Kernsicht.</li>
 *   <li>Widerspricht ein bekannter Kasus einer Parserkante (Subjekt ohne möglichen Nominativ, Objekt ohne möglichen
 *       Akkusativ), bleibt die Kante bestehen; die Zuordnung wird unsicher und {@link Finding#CASE_CONFLICT}.</li>
 * </ol>
 */
final class CaseFallback {

    private CaseFallback() {
    }

    static Statement apply(ParsedDocument document, Mentions mentions, Statement statement) {
        Sentence sentence = document.sentences().get(statement.sentence());
        List<Argument> arguments = new ArrayList<>();
        Set<Finding> findings = EnumSet.noneOf(Finding.class);
        findings.addAll(statement.findings());
        for (Argument argument : statement.arguments()) {
            Mention mention = mentions.get(argument.mention());
            String expected = argument.basis() != Basis.PARSER ? null
                    : argument.relation().startsWith("nsubj:pass") || argument.relation().equals("nsubj")
                    ? CaseHints.NOM : argument.relation().startsWith("obj") ? CaseHints.ACC : null;
            if (expected != null && !mention.cases().isEmpty() && !mention.cases().contains(expected)) {
                arguments.add(argument.uncertain());
                findings.add(Finding.CASE_CONFLICT);
            } else {
                arguments.add(argument);
            }
        }
        boolean passive = statement.voice() == Voice.PASSIVE;
        if (findings.contains(Finding.NO_SUBJECT)) {
            Mention candidate = unassigned(document, mentions, sentence, statement, CaseHints.NOM);
            if (candidate != null) {
                arguments.add(new Argument(passive ? Role.AFFECTED_OBJECT : Role.ACTOR, candidate.id(), "nsubj?",
                        null, Priority.CORE, Basis.CASE_HEURISTIC, false));
                findings.remove(Finding.NO_SUBJECT);
                findings.add(Finding.HEURISTIC_PARTICIPANT);
            }
        }
        if (findings.contains(Finding.NO_ACCUSATIVE_OBJECT) && !passive) {
            Mention candidate = unassigned(document, mentions, sentence, statement, CaseHints.ACC);
            if (candidate != null) {
                arguments.add(new Argument(Role.AFFECTED_OBJECT, candidate.id(), "obj?", null, Priority.CORE,
                        Basis.CASE_HEURISTIC, false));
                findings.remove(Finding.NO_ACCUSATIVE_OBJECT);
                findings.add(Finding.HEURISTIC_PARTICIPANT);
            }
        }
        return statement.with(arguments, PrimaryRelationExtraction.withPronouns(mentions, arguments, findings));
    }

    /** Erste nominale {@code dep}-Gruppe am Prädikat mit eindeutigem Kasus {@code wanted}, die noch keine Rolle hat. */
    private static Mention unassigned(ParsedDocument document, Mentions mentions, Sentence sentence,
                                      Statement statement, String wanted) {
        for (Token dependent : sentence.dependents(statement.predicate().verb())) {
            if (!"dep".equals(dependent.deprel()) || !dependent.hasUpos("NOUN", "PROPN", "PRON")) {
                continue;
            }
            Mention mention = mentions.at(document, sentence, dependent);
            boolean taken = statement.arguments().stream()
                    .anyMatch(argument -> argument.mention().equals(mention.id()));
            if (!taken && wanted.equals(mention.uniqueCase())) {
                return mention;
            }
        }
        return null;
    }
}
