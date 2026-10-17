package com.aresstack.spaceharvester.nlp.extraction;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Eine konkrete Aussage: ein Prädikat in einem Satz mit seinen Beteiligten. Jede Aussage hat eine eigene ID, damit
 * Empfänger und Zeit zweier Übermittlungen nie vermischt werden; die Beteiligten verweisen auf Erwähnungen, nicht
 * auf Entitäten.
 *
 * @param id        {@code quelle/dokument@version#s<Satz>.v<Verb>}
 * @param sentence  Satzindex ab 0
 * @param predicate Prädikat
 * @param voice     Aktiv oder Passiv
 * @param clause    Art des Teilsatzes
 * @param arguments Beteiligte, nach {@link Priority} und Textstelle geordnet
 * @param findings  erkannte, aber nicht aufgelöste Besonderheiten
 */
public record Statement(String id, int sentence, Predicate predicate, Voice voice, Clause clause,
                        List<Argument> arguments, Set<Finding> findings) {

    public Statement {
        Objects.requireNonNull(id, "id");
        arguments = List.copyOf(arguments);
        findings = Set.copyOf(findings);
    }

    /** Erster Beteiligter mit {@code role} und Rang {@link Priority#CORE} oder {@code null}. */
    public Argument core(Role role) {
        for (Argument argument : arguments) {
            if (argument.role() == role && argument.priority() == Priority.CORE) {
                return argument;
            }
        }
        return null;
    }

    /** Grammatisches Subjekt (aktiv: {@code ACTOR}, passiv: {@code AFFECTED_OBJECT}) oder {@code null}. */
    public Argument subject() {
        for (Argument argument : arguments) {
            if (argument.relation().startsWith("nsubj")) {
                return argument;
            }
        }
        return null;
    }

    /** Erstes Akkusativobjekt oder {@code null}. */
    public Argument object() {
        for (Argument argument : arguments) {
            if (argument.relation().startsWith("obj")) {
                return argument;
            }
        }
        return null;
    }

    /** Alle Beteiligten mit {@code role}. */
    public List<Argument> withRole(Role role) {
        return arguments.stream().filter(argument -> argument.role() == role).toList();
    }

    public boolean has(Finding finding) {
        return findings.contains(finding);
    }

    /**
     * Alle Beteiligten hängen per Parserkante am Prädikat und keine Zuordnung ist unsicher; Rollen aus der
     * Wortliste ({@link Basis#LEXICON}) zählen als sicher angebunden.
     */
    public boolean certain() {
        return arguments.stream().allMatch(argument -> argument.certain()
                && (argument.basis() == Basis.PARSER || argument.basis() == Basis.LEXICON));
    }

    Statement with(List<Argument> newArguments, Set<Finding> newFindings) {
        List<Argument> ordered = new ArrayList<>(newArguments);
        ordered.sort((a, b) -> a.priority().compareTo(b.priority()));
        Set<Finding> merged = new TreeSet<>(newFindings);
        return new Statement(id, sentence, predicate, voice, clause, ordered, merged);
    }

    public enum Voice {
        ACTIVE,
        PASSIVE
    }

    /** Art des Teilsatzes nach der Relation des Prädikats. */
    public enum Clause {
        /** Hauptsatz ({@code root}, {@code parataxis}). */
        MAIN,
        /** Mit einem anderen Prädikat koordiniert ({@code conj}). */
        COORDINATED,
        /** Nebensatz, Relativsatz, Infinitiv- oder Partizipgruppe. */
        SUBORDINATE
    }
}
