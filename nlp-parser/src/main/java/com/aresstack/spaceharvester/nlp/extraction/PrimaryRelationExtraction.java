package com.aresstack.spaceharvester.nlp.extraction;

import com.aresstack.spaceharvester.nlp.extraction.Statement.Clause;
import com.aresstack.spaceharvester.nlp.extraction.Statement.Voice;
import com.aresstack.spaceharvester.nlp.syntax.ParsedDocument;
import com.aresstack.spaceharvester.nlp.syntax.Sentence;
import com.aresstack.spaceharvester.nlp.syntax.Token;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Kernregel: je Vollverb eine Aussage mit grammatischem Subjekt ({@code nsubj}, im Passiv {@code nsubj:pass}) und
 * Akkusativobjekt ({@code obj}), nur aus Parserkanten.
 * <ul>
 *   <li>Prädikat = Vollverb mit Hilfsverben ({@code aux}, {@code aux:pass}) und Partikel ({@code compound:prt});
 *       Grundform = Partikel + Grundform des Verbs ({@code an} + {@code rufen}).</li>
 *   <li>Passiv ({@code aux:pass} oder {@code nsubj:pass}): das Subjekt ist {@link Role#AFFECTED_OBJECT}.</li>
 *   <li>Koordiniertes Prädikat ({@code conj}) ohne eigenes Subjekt übernimmt das Subjekt des Bezugsprädikats,
 *       markiert als {@link Basis#COORDINATION} und unsicher.</li>
 *   <li>Teilsätze ({@code advcl}, {@code ccomp}, {@code acl}, {@code xcomp}, {@code csubj}) werden erkannt und als
 *       {@link Clause#SUBORDINATE} markiert.</li>
 *   <li>Koordinierte Objekte ({@code obj} + {@code conj}) werden je eigener Beteiligter.</li>
 * </ul>
 */
final class PrimaryRelationExtraction {

    /** Relationen, unter denen ein Verb kein eigenes Prädikat ist. */
    private static final Set<String> NOT_A_PREDICATE = Set.of("aux", "cop", "compound", "amod", "det", "case",
            "mark", "fixed", "flat");
    private static final Set<String> SUBORDINATE = Set.of("advcl", "ccomp", "acl", "xcomp", "csubj");

    private PrimaryRelationExtraction() {
    }

    static List<Statement> extract(ParsedDocument document, Mentions mentions) {
        List<Statement> statements = new ArrayList<>();
        for (Sentence sentence : document.sentences()) {
            for (Token token : sentence.tokens()) {
                if (token.hasUpos("VERB") && token.parsed() && !NOT_A_PREDICATE.contains(token.baseRelation())) {
                    statements.add(statement(document, mentions, sentence, token));
                }
            }
        }
        return statements;
    }

    static String id(ParsedDocument document, int sentence, int verb) {
        return document.ref().key() + "#s" + (sentence + 1) + ".v" + verb;
    }

    private static Statement statement(ParsedDocument document, Mentions mentions, Sentence sentence, Token verb) {
        Set<Finding> findings = EnumSet.noneOf(Finding.class);
        boolean passive = !sentence.dependents(verb.id(), "aux:pass").isEmpty()
                || !sentence.dependents(verb.id(), "nsubj:pass").isEmpty();
        if (passive) {
            findings.add(Finding.PASSIVE);
        }
        Clause clause = clause(sentence, verb);
        if (clause == Clause.SUBORDINATE) {
            findings.add(Finding.SUBORDINATE_CLAUSE);
        }
        List<Argument> arguments = new ArrayList<>();
        Token subject = firstOf(sentence, verb.id(), "nsubj", "nsubj:pass");
        if (subject != null) {
            Mention mention = mentions.at(document, sentence, subject);
            arguments.add(new Argument(passive ? Role.AFFECTED_OBJECT : Role.ACTOR, mention.id(), subject.deprel(),
                    null, Priority.CORE, Basis.PARSER, true));
        } else {
            Token inherited = coordinatedSubject(sentence, verb);
            if (inherited != null) {
                Mention mention = mentions.at(document, sentence, inherited);
                arguments.add(new Argument(passive ? Role.AFFECTED_OBJECT : Role.ACTOR, mention.id(),
                        inherited.deprel(), null, Priority.CORE, Basis.COORDINATION, false));
                findings.add(Finding.SUBJECT_FROM_COORDINATION);
            } else {
                findings.add(Finding.NO_SUBJECT);
            }
        }
        List<Token> objects = sentence.dependents(verb.id(), "obj");
        for (Token object : objects) {
            arguments.add(object(document, mentions, sentence, object, "obj"));
            for (Token conjunct : sentence.dependents(object.id(), "conj")) {
                if (conjunct.hasUpos("NOUN", "PROPN", "PRON")) {
                    arguments.add(object(document, mentions, sentence, conjunct, "obj+conj"));
                    findings.add(Finding.COORDINATED_OBJECTS);
                }
            }
        }
        if (objects.isEmpty()) {
            findings.add(Finding.NO_ACCUSATIVE_OBJECT);
        }
        Statement statement = new Statement(id(document, sentence.index(), verb.id()), sentence.index(),
                predicate(document, sentence, verb), passive ? Voice.PASSIVE : Voice.ACTIVE, clause, List.of(),
                Set.of());
        return statement.with(arguments, withPronouns(mentions, arguments, findings));
    }

    private static Argument object(ParsedDocument document, Mentions mentions, Sentence sentence, Token token,
                                   String relation) {
        Mention mention = mentions.at(document, sentence, token);
        return new Argument(Role.AFFECTED_OBJECT, mention.id(), relation, null, Priority.CORE, Basis.PARSER, true);
    }

    /** {@code findings} plus {@link Finding#UNRESOLVED_PRONOUN}, wenn ein Beteiligter ein offenes Pronomen ist. */
    static Set<Finding> withPronouns(Mentions mentions, List<Argument> arguments, Set<Finding> findings) {
        Set<Finding> result = EnumSet.noneOf(Finding.class);
        result.addAll(findings);
        for (Argument argument : arguments) {
            if (mentions.get(argument.mention()).resolution() == Resolution.UNRESOLVED) {
                result.add(Finding.UNRESOLVED_PRONOUN);
            }
        }
        return result;
    }

    private static Clause clause(Sentence sentence, Token verb) {
        Token current = verb;
        boolean coordinated = false;
        while (current != null) {
            String relation = current.baseRelation();
            if (SUBORDINATE.contains(relation)) {
                return Clause.SUBORDINATE;
            }
            if (!"conj".equals(relation)) {
                return coordinated ? Clause.COORDINATED : Clause.MAIN;
            }
            coordinated = true;
            current = sentence.head(current);
        }
        return coordinated ? Clause.COORDINATED : Clause.MAIN;
    }

    /** Subjekt des Prädikats, mit dem {@code verb} koordiniert ist, entlang der {@code conj}-Kette. */
    private static Token coordinatedSubject(Sentence sentence, Token verb) {
        Token current = verb;
        while ("conj".equals(current.baseRelation())) {
            Token head = sentence.head(current);
            if (head == null || !head.hasUpos("VERB", "AUX")) {
                return null;
            }
            Token subject = firstOf(sentence, head.id(), "nsubj", "nsubj:pass");
            if (subject != null) {
                return subject;
            }
            current = head;
        }
        return null;
    }

    private static Token firstOf(Sentence sentence, int head, String... relations) {
        for (Token dependent : sentence.dependents(head)) {
            for (String relation : relations) {
                if (relation.equals(dependent.deprel())) {
                    return dependent;
                }
            }
        }
        return null;
    }

    private static Predicate predicate(ParsedDocument document, Sentence sentence, Token verb) {
        Set<Integer> ids = new TreeSet<>();
        ids.add(verb.id());
        Token particle = null;
        for (Token dependent : sentence.dependents(verb.id())) {
            String relation = dependent.deprel();
            if ("aux".equals(relation) || "aux:pass".equals(relation)) {
                ids.add(dependent.id());
            } else if ("compound:prt".equals(relation)) {
                ids.add(dependent.id());
                particle = dependent;
            }
        }
        StringBuilder text = new StringBuilder();
        int previous = -1;
        for (int id : ids) {
            Token token = sentence.token(id);
            if (previous >= 0) {
                text.append(id == previous + 1 ? " " : " … ");
            }
            text.append(token.form());
            previous = id;
        }
        String lemma = verb.lemma();
        if (lemma != null && particle != null) {
            String prefix = particle.form().toLowerCase(Locale.ROOT);
            lemma = lemma.toLowerCase(Locale.ROOT).startsWith(prefix) ? lemma : prefix + lemma;
        }
        return new Predicate(verb.id(), new ArrayList<>(ids), text.toString(), lemma);
    }
}
