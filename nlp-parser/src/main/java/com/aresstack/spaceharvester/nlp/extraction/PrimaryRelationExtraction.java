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
 *       Grundform = Partikel + Grundform des Verbs ({@code an} + {@code rufen}). Eine {@code compound:prt}-Kante
 *       zählt nur, wenn das Token in der rechten Satzklammer stehen kann ({@link #separableParticle}); sonst ist es
 *       eine falsch angehängte Präposition („Zugriff auf und Austausch von …“) und gehört nicht zum Prädikat.</li>
 *   <li>Passiv ({@code aux:pass} oder {@code nsubj:pass}): das Subjekt ist {@link Role#AFFECTED_OBJECT}.</li>
 *   <li>Koordiniertes Prädikat ({@code conj}) ohne eigenes Subjekt übernimmt das Subjekt des Bezugsprädikats,
 *       markiert als {@link Basis#COORDINATION} und unsicher; ist es ein {@code nsubj:pass}, gilt auch das
 *       übernehmende Prädikat als Passiv ({@code werden geprüft und genehmigt}).</li>
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
    /**
     * Abhängige des Verbs, die hinter der rechten Satzklammer stehen dürfen (Nachfeld, weitere Teilsätze); sie
     * schließen eine Partikel nicht ein.
     */
    private static final Set<String> AFTER_THE_BRACKET = Set.of("punct", "conj", "cc", "parataxis", "ccomp",
            "advcl", "xcomp", "csubj", "acl", "discourse", "vocative", "dep");
    /** Teile einer Phrase, die im Nachfeld stehen dürfen. */
    private static final Set<String> CLAUSAL_INSIDE = Set.of("acl", "advcl", "ccomp", "xcomp", "csubj",
            "parataxis", "punct");
    /** Wortarten, mit denen eine gereihte Einheit ein eigener Satz ist. */
    private static final Set<String> CLAUSE_HEADS = Set.of("VERB", "AUX", "PUNCT");
    /** STTS-Tags einer Präposition; eine so getaggte „Partikel“ ist keine. */
    private static final Set<String> PREPOSITION_TAGS = Set.of("APPR", "APPRART", "APPO", "APZR");

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
        Token subject = firstOf(sentence, verb.id(), "nsubj", "nsubj:pass");
        Token inherited = null;
        if (subject == null) {
            inherited = coordinatedSubject(sentence, verb);
            if (inherited == null && crossesClauseInChain(sentence, verb)) {
                findings.add(Finding.COORDINATION_ACROSS_CLAUSE);
            }
        }
        List<Token> auxiliaries = auxiliaries(sentence, verb);
        if (sentence.dependents(verb.id()).stream().anyMatch(t -> auxiliary(t) && !auxiliaries.contains(t))) {
            findings.add(Finding.AUXILIARY_OUTSIDE_CLAUSE);
        }
        // Geteiltes Passiv (werden geprüft und genehmigt): Hilfsverb und Subjekt hängen nur am ersten Prädikat.
        boolean passive = auxiliaries.stream().anyMatch(t -> "aux:pass".equals(t.deprel()))
                || subject != null && "nsubj:pass".equals(subject.deprel())
                || inherited != null && "nsubj:pass".equals(inherited.deprel());
        if (passive) {
            findings.add(Finding.PASSIVE);
        }
        Clause clause = clause(sentence, verb);
        if (clause == Clause.SUBORDINATE) {
            findings.add(Finding.SUBORDINATE_CLAUSE);
        }
        List<Argument> arguments = new ArrayList<>();
        if (subject != null) {
            Mention mention = mentions.at(document, sentence, subject);
            arguments.add(new Argument(passive ? Role.AFFECTED_OBJECT : Role.ACTOR, mention.id(), subject.deprel(),
                    null, Priority.CORE, Basis.PARSER, true));
        } else {
            if (inherited != null) {
                Mention mention = mentions.at(document, sentence, inherited);
                arguments.add(new Argument(passive ? Role.AFFECTED_OBJECT : Role.ACTOR, mention.id(),
                        inherited.deprel(), null, Priority.CORE, Basis.COORDINATION, false));
                findings.add(Finding.SUBJECT_FROM_COORDINATION);
            } else {
                findings.add(Finding.NO_SUBJECT);
            }
        }
        List<Token> objects = new ArrayList<>();
        for (Token object : sentence.dependents(verb.id(), "obj")) {
            if (reflexive(object)) {
                findings.add(Finding.REFLEXIVE);
            } else {
                objects.add(object);
            }
        }
        if (objects.isEmpty() && subject != null && !passive) {
            // Zwei Subjekte am selben Verb sind ein Parserfehler („hat sich die Vision gesetzt“): das spätere ist
            // wahrscheinlich das Objekt, aber nur als unsichere Heuristik.
            for (Token second : sentence.dependents(verb.id(), "nsubj")) {
                if (second.id() > subject.id() && !reflexive(second) && "nsubj".equals(second.deprel())) {
                    Mention mention = mentions.at(document, sentence, second);
                    arguments.add(new Argument(Role.AFFECTED_OBJECT, mention.id(), second.deprel(), null,
                            Priority.CORE, Basis.HEURISTIC, false));
                    findings.add(Finding.HEURISTIC_PARTICIPANT);
                    break;
                }
            }
        }
        for (Token object : objects) {
            arguments.add(object(document, mentions, sentence, object, "obj"));
            for (Token conjunct : sentence.dependents(object.id(), "conj")) {
                if (conjunct.hasUpos("NOUN", "PROPN", "PRON")) {
                    arguments.add(object(document, mentions, sentence, conjunct, "obj+conj"));
                    findings.add(Finding.COORDINATED_OBJECTS);
                }
            }
        }
        if (objects.isEmpty() && arguments.stream().noneMatch(a -> a.role() == Role.AFFECTED_OBJECT
                && a.basis() == Basis.HEURISTIC)) {
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

    /**
     * Subjekt des Prädikats, mit dem {@code verb} koordiniert ist, entlang der {@code conj}-Kette. Beginnt zwischen
     * den gereihten Prädikaten ein Teilsatz mit eigenem Subjekt (Relativsatz ohne schließendes Komma, „stellt … bereit,
     * die … verwaltet und ausgewertet werden“), gehört das spätere Prädikat zu diesem Teilsatz: keine Übernahme.
     */
    private static Token coordinatedSubject(Sentence sentence, Token verb) {
        Token current = verb;
        while ("conj".equals(current.baseRelation())) {
            Token head = sentence.head(current);
            if (head == null || !head.hasUpos("VERB", "AUX") || opensClauseBetween(sentence, head, current)) {
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

    /** Ob die Übernahme entlang der {@code conj}-Kette an einer Teilsatzgrenze endete. */
    private static boolean crossesClauseInChain(Sentence sentence, Token verb) {
        Token current = verb;
        while ("conj".equals(current.baseRelation())) {
            Token head = sentence.head(current);
            if (head == null) {
                return false;
            }
            if (opensClauseBetween(sentence, head, current)) {
                return true;
            }
            current = head;
        }
        return false;
    }

    /**
     * Ob nach dem letzten Komma zwischen {@code head} und dem späteren {@code conjunct} ein Subjekt oder Einleitewort
     * eines dritten Prädikats steht, also ein Teilsatz beginnt, in dem {@code conjunct} liegt. Ein geschlossener
     * Einschub („prüft den Antrag, den der Kunde einreicht, und genehmigt ihn“) endet mit einem Komma und stört nicht.
     */
    private static boolean opensClauseBetween(Sentence sentence, Token head, Token conjunct) {
        int comma = -1;
        for (int id = head.id() + 1; id < conjunct.id(); id++) {
            if (",".equals(sentence.token(id).form())) {
                comma = id;
            }
        }
        if (comma < 0) {
            return false;
        }
        for (int id = comma + 1; id < conjunct.id(); id++) {
            Token token = sentence.token(id);
            boolean opener = "mark".equals(token.deprel()) || "nsubj".equals(token.deprel())
                    || "nsubj:pass".equals(token.deprel());
            if (opener && token.head() != head.id() && token.head() != conjunct.id()) {
                return true;
            }
        }
        return false;
    }

    /** Reflexivpronomen {@code sich}: nie ein eigener Beteiligter, nie Subjekt oder betroffenes Objekt. */
    static boolean reflexive(Token token) {
        return "sich".equalsIgnoreCase(token.form());
    }

    private static boolean auxiliary(Token token) {
        return "aux".equals(token.deprel()) || "aux:pass".equals(token.deprel());
    }

    /**
     * Die Hilfsverben des Prädikats. Ein Hilfsverb vor dem Verb (linke Satzklammer) zählt wie vom Parser angehängt;
     * eines dahinter nur im Verbalkomplex, also wenn dazwischen nur Verben, Konjunktionen, Kommas und direkte
     * Abhängige dieser Verben stehen (ohne Komma dazwischen auch mittelbare) („ausgewertet werden“, „geprüft und genehmigt wird“). Ein Hilfsverb jenseits einer
     * Teilsatzgrenze („stellt … bereit, die … ausgewertet werden“) gehört dem Verb direkt davor.
     */
    static List<Token> auxiliaries(Sentence sentence, Token verb) {
        List<Token> result = new ArrayList<>();
        for (Token dependent : sentence.dependents(verb.id())) {
            if (auxiliary(dependent) && inVerbCluster(sentence, verb, dependent)) {
                result.add(dependent);
            }
        }
        if (verb.id() < sentence.tokens().size()) {
            Token next = sentence.token(verb.id() + 1);
            Token host = sentence.head(next);
            if (auxiliary(next) && host != null && host.id() != verb.id() && !inVerbCluster(sentence, host, next)) {
                result.add(next);
            }
        }
        return result;
    }

    private static boolean inVerbCluster(Sentence sentence, Token verb, Token auxiliary) {
        if (auxiliary.id() < verb.id()) {
            return true;
        }
        boolean strict = false;
        for (int id = verb.id() + 1; id < auxiliary.id(); id++) {
            Token between = sentence.token(id);
            strict |= ",".equals(between.form());
            // „gesammelt hat und … verwaltet werden“: das Verb hat sein Hilfsverb schon, kein geteiltes.
            strict |= auxiliary(between) && between.head() == verb.id();
        }
        for (int id = verb.id() + 1; id < auxiliary.id(); id++) {
            Token between = sentence.token(id);
            if (between.hasUpos("VERB", "AUX", "CCONJ") || ",".equals(between.form())) {
                continue;
            }
            Token head = sentence.head(between);
            boolean ofAVerbBetween = head != null && head.id() > verb.id() && head.id() < auxiliary.id()
                    && head.hasUpos("VERB", "AUX");
            // Ohne Komma zählen auch mittelbar Abhängige der mit dem Verb gereihten Verben (conj).
            if (!ofAVerbBetween && (strict || !underAConjunctBetween(sentence, between, verb, auxiliary.id()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Ob {@code token} (auch mittelbar, etwa ein Artikel über sein Nomen) von einem mit {@code verb} gereihten Verb
     * ({@code conj}, auch über mehrere Glieder) zwischen {@code verb} und {@code to} abhängt, ohne den Bereich dabei
     * zu verlassen („gearbeitet und der Antrag geprüft wird“). Nur ohne Komma dazwischen und nur über gereihte Verben:
     * Ein Relativ- oder Adverbialsatz („gesammelt, mit denen gearbeitet wird“; ohne Komma „abgelehnt weil … gelagert
     * werden“) hängt nicht als {@code conj} am Verb.
     */
    private static boolean underAConjunctBetween(Sentence sentence, Token token, Token verb, int to) {
        Token head = sentence.head(token);
        // Jeder Schritt bleibt im Bereich; mehr Schritte als Wörter darin gibt es in einem Baum nicht.
        for (int steps = to - verb.id(); head != null && head.id() > verb.id() && head.id() < to && steps > 0;
             steps--) {
            if (head.hasUpos("VERB", "AUX")) {
                return conjunctOf(sentence, head, verb);
            }
            head = sentence.head(head);
        }
        return false;
    }

    /** Ob {@code candidate} über eine Kette von {@code conj}-Kanten an {@code verb} hängt. */
    private static boolean conjunctOf(Sentence sentence, Token candidate, Token verb) {
        Token current = candidate;
        for (int steps = sentence.tokens().size(); current != null && "conj".equals(current.deprel()) && steps > 0;
             steps--) {
            if (current.head() == verb.id()) {
                return true;
            }
            current = sentence.head(current);
        }
        return false;
    }

    private static Token firstOf(Sentence sentence, int head, String... relations) {
        for (Token dependent : sentence.dependents(head)) {
            if (reflexive(dependent)) {
                continue;
            }
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
        auxiliaries(sentence, verb).forEach(auxiliary -> ids.add(auxiliary.id()));
        for (Token dependent : sentence.dependents(verb.id())) {
            String relation = dependent.deprel();
            if ("compound:prt".equals(relation) && separableParticle(sentence, verb, dependent)) {
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

    /**
     * Ob {@code particle} ({@code compound:prt} am Verb) wirklich eine abgetrennte Verbpartikel ist. Die steht in der
     * rechten Satzklammer hinter dem Verb, also nie
     * <ul>
     *   <li>als Präposition getaggt (STTS {@code APPR}, {@code APPRART}, …),</li>
     *   <li>mitten in einer Phrase eines anderen Beteiligten des Verbs (Wörter dieser Phrase davor und danach),</li>
     *   <li>vor einer Reihung, deren Kopf kein Verb ist („auf und Austausch“); „ruft … an und reicht … ein“ und
     *       „ruft an und sie antwortet“ bleiben.</li>
     * </ul>
     * Der Parser hängt eine Präposition, deren Nomen per Koordination fehlt, sonst gern als Partikel an.
     */
    static boolean separableParticle(Sentence sentence, Token verb, Token particle) {
        if (particle.xpos() != null && PREPOSITION_TAGS.contains(particle.xpos())) {
            return false;
        }
        if (particle.id() < verb.id()) {
            return false;
        }
        for (Token dependent : sentence.dependents(verb.id())) {
            if (dependent.id() == particle.id() || AFTER_THE_BRACKET.contains(dependent.deprel())
                    || AFTER_THE_BRACKET.contains(dependent.baseRelation())) {
                continue;
            }
            int[] span = span(sentence, dependent.id());
            if (span[0] < particle.id() && particle.id() < span[1]) {
                return false;
            }
        }
        int size = sentence.tokens().size();
        if (particle.id() + 1 <= size && "CCONJ".equals(sentence.token(particle.id() + 1).upos())) {
            // Was wird gereiht? Der Kopf der Konjunktion: ein Verb („an und sie antwortet“) lässt die Partikel stehen,
            // ein Nomen („auf und Austausch“) nicht.
            Token conjunction = sentence.token(particle.id() + 1);
            Token coordinated = conjunction.head() >= 1 && conjunction.head() <= size
                    ? sentence.token(conjunction.head()) : null;
            if (coordinated == null && particle.id() + 2 <= size) {
                coordinated = sentence.token(particle.id() + 2);
            }
            return coordinated == null || CLAUSE_HEADS.contains(coordinated.upos());
        }
        return true;
    }

    /**
     * Kleinste und größte Token-ID der Phrase von {@code root}, ohne Teilsätze und Satzzeichen darin: ein
     * Relativsatz am Objekt darf hinter der Partikel stehen („ruft den Kunden an, der …“).
     */
    private static int[] span(Sentence sentence, int root) {
        int[] span = {root, root};
        for (Token dependent : sentence.dependents(root)) {
            if (CLAUSAL_INSIDE.contains(dependent.baseRelation())) {
                continue;
            }
            int[] child = span(sentence, dependent.id());
            span[0] = Math.min(span[0], child[0]);
            span[1] = Math.max(span[1], child[1]);
        }
        return span;
    }
}
