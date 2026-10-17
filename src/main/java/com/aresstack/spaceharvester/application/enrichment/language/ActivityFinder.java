package com.aresstack.spaceharvester.application.enrichment.language;

import com.aresstack.spaceharvester.application.enrichment.language.Activity.Participant;
import com.aresstack.spaceharvester.application.enrichment.language.Activity.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Findet Handlungen in den Teilsätzen eines Satzes, nach festen Regeln der deutschen Satzstellung:
 * <ol>
 *   <li>Klammereinschübe zählen nicht. Teilsätze enden an {@code , ; :} und beginnen neu an Subjunktionen
 *       ({@code dass}, {@code weil}, {@code wenn}, …) und an {@code und}, {@code oder}, {@code aber},
 *       {@code sondern}, wenn direkt danach ein Verb folgt; dann gilt das Subjekt des vorigen Teilsatzes weiter.</li>
 *   <li>Mit Hilfs- oder Modalverb ist das letzte verbartige Wort des Teilsatzes das Vollverb. Eine Form von werden mit
 *       Partizip ist Passiv. Sein oder bleiben mit Partizip oder Adjektiv ist eine Eigenschaft
 *       ({@link PropertyProcessor}), keine Handlung; haben ohne Vollverb ist selbst das Prädikat.</li>
 *   <li>Ohne Hilfsverb ist im Hauptsatz das erste verbartige Wort nach dem ersten Satzglied das finite Verb (Verbzweit),
 *       im Nebensatz das letzte, nach einem vorangestellten Nebensatz das erste. Eine Verbpartikel am Ende des
 *       Hauptsatzes gehört zum Verb ({@code ruft … auf}).</li>
 *   <li>Ein Infinitiv am Ende, der nicht direkt auf das erste Satzglied folgt ({@code Server neu starten},
 *       {@code Bei Problemen den Dienst neu starten}), und ein Verb vor {@code Sie} sind Anweisungen ohne Subjekt.</li>
 *   <li>Beteiligte sind Nominalgruppen und Personalpronomen des Teilsatzes: das Subjekt handelt (im Passiv ist es
 *       betroffen), von- und durch-Gruppen handeln im Passiv, mit-, mittels-, per- und über-Gruppen sind Mittel,
 *       Gruppen mit {@code dem} oder {@code einem} empfangen, Zeitangaben ({@code jede Nacht}) zählen nicht, andere
 *       Gruppen ohne Präposition sind betroffen.</li>
 * </ol>
 */
final class ActivityFinder {

    private static final Set<String> SUBORDINATORS = Set.of(
            "dass", "weil", "wenn", "ob", "falls", "sobald", "nachdem", "bevor", "damit", "sodass", "obwohl",
            "indem", "wobei", "sofern", "soweit", "solange", "ehe", "seitdem");
    private static final Set<String> COORDINATORS = Set.of("und", "oder", "aber", "sondern");
    private static final Set<String> PERSONAL_PRONOUNS = Set.of(
            "ich", "du", "er", "sie", "es", "wir", "man", "ihr", "ihn", "mich", "dich");
    private static final Set<String> DATIVE_PRONOUNS = Set.of("mir", "dir", "ihm", "uns", "euch", "ihnen");
    private static final Set<String> DATIVE_DETERMINERS = Set.of("dem", "einem", "diesem", "jedem", "keinem");
    private static final Set<String> ACTOR_PREPOSITIONS = Set.of("von", "vom", "durch");
    private static final Set<String> INSTRUMENT_PREPOSITIONS = Set.of("mit", "mittels", "per", "über");

    private ActivityFinder() {
    }

    static List<Activity> find(Sentence full) {
        Sentence sentence = withoutParentheses(full);
        List<Activity> activities = new ArrayList<>();
        List<Token> tokens = sentence.tokens();
        int start = 0;
        boolean subordinate = false;
        // Nach einem vorangestellten Nebensatz steht das finite Verb des Hauptsatzes vorn.
        boolean afterSubordinate = false;
        Participant carried = null;
        for (int i = 0; i <= tokens.size(); i++) {
            boolean end = i == tokens.size();
            Token token = end ? null : tokens.get(i);
            boolean boundary = end || token.is(WordClass.PUNCTUATION) && ",;:!?.…".contains(token.text());
            boolean newClause = !end && token.is(WordClass.CONJUNCTION)
                    && (SUBORDINATORS.contains(token.lower()) || COORDINATORS.contains(token.lower())
                    && verbFollows(tokens, i + 1));
            if (boundary || newClause) {
                Activity activity = clause(sentence, start, i, subordinate, carried, afterSubordinate);
                afterSubordinate = subordinate && !newClause;
                carried = null;
                if (activity != null) {
                    activities.add(activity);
                    if (newClause && COORDINATORS.contains(token.lower())) {
                        carried = subject(activity);
                    }
                }
                subordinate = newClause && SUBORDINATORS.contains(token.lower());
                start = i + 1;
            }
        }
        return activities;
    }

    /**
     * Der Satz ohne Klammereinschübe: {@code Die API (Application Programming Interface) liefert JSON} wird wie
     * {@code Die API liefert JSON} gelesen. Fundstellen bleiben die des Originaltexts.
     */
    static Sentence withoutParentheses(Sentence sentence) {
        List<Token> tokens = sentence.tokens();
        int[] index = new int[tokens.size() + 1];
        List<Token> kept = new ArrayList<>();
        int depth = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.punctuation("(")) {
                depth++;
            }
            index[i] = depth == 0 ? kept.size() : -1;
            if (depth == 0) {
                kept.add(token);
            }
            if (token.punctuation(")") && depth > 0) {
                depth--;
            }
        }
        index[tokens.size()] = kept.size();
        if (kept.size() == tokens.size()) {
            return sentence;
        }
        List<NounGroup> groups = new ArrayList<>();
        for (NounGroup group : sentence.nounGroups()) {
            if (index[group.from()] >= 0 && index[group.to() - 1] >= 0) {
                groups.add(new NounGroup(index[group.from()], index[group.termFrom()], index[group.to() - 1] + 1,
                        group.term(), group.kind()));
            }
        }
        return new Sentence(kept, groups);
    }

    private static boolean verbFollows(List<Token> tokens, int index) {
        if (index >= tokens.size()) {
            return false;
        }
        Token next = tokens.get(index);
        return next.is(WordClass.AUXILIARY) || next.is(WordClass.MODAL)
                || next.is(WordClass.OTHER) && Verbs.verbLike(next.lower());
    }

    /** Das handelnde Subjekt, das in einem angereihten Teilsatz weiter gilt, oder {@code null}. */
    private static Participant subject(Activity activity) {
        return activity.passive() ? null : activity.first(Role.ACTOR);
    }

    private static Activity clause(Sentence sentence, int from, int to, boolean subordinate, Participant carried,
                                   boolean verbFirst) {
        if (from >= to) {
            return null;
        }
        List<Token> tokens = sentence.tokens();
        int auxiliary = -1;
        int modal = -1;
        for (int i = from; i < to; i++) {
            if (tokens.get(i).is(WordClass.AUXILIARY) && auxiliary < 0) {
                auxiliary = i;
            } else if (tokens.get(i).is(WordClass.MODAL) && modal < 0) {
                modal = i;
            }
        }
        int finite = auxiliary >= 0 && modal >= 0 ? Math.min(auxiliary, modal) : Math.max(auxiliary, modal);
        int verb;
        boolean instruction = false;
        if (finite >= 0) {
            verb = lastVerb(sentence, from, to, -1);
            if (verb < 0 || verb < finite && !subordinate) {
                if (auxiliary >= 0 && Verbs.HABEN.contains(tokens.get(auxiliary).lower()) && modal < 0) {
                    verb = auxiliary;
                } else {
                    return null;
                }
            }
            if (auxiliary >= 0 && Lexicon.COPULAS.contains(tokens.get(auxiliary).lower()) && modal < 0
                    && !hasPassiveAuxiliary(tokens, from, to)) {
                return null;
            }
        } else if (from + 1 < to && tokens.get(from).is(WordClass.OTHER) && Verbs.verbLike(tokens.get(from).lower())
                && tokens.get(from + 1).text().equals("Sie")) {
            verb = from;
            instruction = true;
        } else if (subordinate) {
            verb = lastVerb(sentence, from, to, -1);
        } else {
            // Im angereihten Teilsatz fehlt das Subjekt; das Verb steht dann vorn.
            verb = firstVerb(sentence, carried != null || verbFirst ? from : from + 1, to);
            if (verb >= 0 && verb == lastWord(tokens, from, to) && Verbs.infinitive(tokens.get(verb).lower())
                    && !secondPosition(sentence, from, verb)) {
                instruction = true;
            } else if (verb < 0) {
                int last = lastWord(tokens, from, to);
                if (last >= 0 && tokens.get(last).is(WordClass.OTHER) && Verbs.infinitive(tokens.get(last).lower())
                        && !inGroup(sentence, last)) {
                    verb = last;
                    instruction = true;
                }
            }
        }
        if (verb < 0) {
            return null;
        }
        boolean passive = auxiliary >= 0 && hasPassiveAuxiliary(tokens, from, to)
                && Verbs.participle(tokens.get(verb).lower());
        Token particle = null;
        if (!subordinate && !instruction && finite < 0) {
            int last = lastWord(tokens, from, to);
            if (last > verb && Lexicon.VERB_PARTICLES.contains(tokens.get(last).lower()) && !inGroup(sentence, last)
                    && (tokens.get(last).is(WordClass.PREPOSITION) || tokens.get(last).is(WordClass.ADVERB)
                    || tokens.get(last).is(WordClass.OTHER))) {
                particle = tokens.get(last);
            }
        }
        List<Token> predicate = new ArrayList<>();
        if (finite >= 0 && finite != verb) {
            predicate.add(tokens.get(finite));
        }
        predicate.add(tokens.get(verb));
        if (particle != null) {
            predicate.add(particle);
        }
        predicate.sort((a, b) -> Integer.compare(a.start(), b.start()));
        boolean negated = false;
        for (int i = from; i < to; i++) {
            negated |= tokens.get(i).is(WordClass.NEGATION) || tokens.get(i).lower().startsWith("kein")
                    && tokens.get(i).is(WordClass.DETERMINER);
        }
        int anchor = finite >= 0 ? finite : verb;
        List<Participant> participants = participants(sentence, from, to, anchor, subordinate, instruction, passive,
                carried);
        return new Activity(sentence, from, to, predicate, Verbs.lemma(tokens.get(verb).text(),
                particle == null ? null : particle.text()), passive, instruction,
                modal >= 0 ? tokens.get(modal).text() : null, negated, participants);
    }

    private static boolean hasPassiveAuxiliary(List<Token> tokens, int from, int to) {
        for (int i = from; i < to; i++) {
            if (tokens.get(i).is(WordClass.AUXILIARY) && Verbs.PASSIVE_AUXILIARIES.contains(tokens.get(i).lower())) {
                return true;
            }
        }
        return false;
    }

    private static List<Participant> participants(Sentence sentence, int from, int to, int anchor,
                                                  boolean subordinate, boolean instruction, boolean passive,
                                                  Participant carried) {
        List<Token> tokens = sentence.tokens();
        List<Participant> found = new ArrayList<>();
        List<Integer> coreStarts = new ArrayList<>();
        List<Participant> core = new ArrayList<>();
        for (int i = from; i < to; i++) {
            Token token = tokens.get(i);
            NounGroup group = sentence.groupAt(i);
            Participant candidate = null;
            int next = i + 1;
            if (group != null && group.to() <= to && Lexicon.TIME_NOUNS.contains(group.normalized())) {
                next = group.to();
            } else if (group != null && group.to() <= to) {
                candidate = new Participant(Role.AFFECTED_OBJECT, group, null);
                next = group.to();
            } else if (token.is(WordClass.PRONOUN) && PERSONAL_PRONOUNS.contains(token.lower())
                    && !(instruction && token.text().equals("Sie"))) {
                candidate = new Participant(Role.AFFECTED_OBJECT, null, token);
            } else if (token.is(WordClass.PRONOUN) && DATIVE_PRONOUNS.contains(token.lower())) {
                candidate = new Participant(Role.RECIPIENT, null, token);
            }
            if (candidate != null) {
                Token before = i > from ? tokens.get(i - 1) : null;
                if (before != null && before.is(WordClass.PREPOSITION)) {
                    if (ACTOR_PREPOSITIONS.contains(before.lower()) && passive) {
                        found.add(new Participant(Role.ACTOR, candidate.group(), candidate.pronoun()));
                    } else if (INSTRUMENT_PREPOSITIONS.contains(before.lower())) {
                        found.add(new Participant(Role.INSTRUMENT, candidate.group(), candidate.pronoun()));
                    }
                } else if (candidate.role() == Role.RECIPIENT || group != null
                        && DATIVE_DETERMINERS.contains(tokens.get(group.from()).lower())) {
                    found.add(new Participant(Role.RECIPIENT, candidate.group(), candidate.pronoun()));
                } else {
                    coreStarts.add(i);
                    core.add(candidate);
                    found.add(candidate);
                }
            }
            i = next - 1;
        }
        if (instruction) {
            return found;
        }
        int subject = -1;
        if (subordinate) {
            subject = core.isEmpty() ? -1 : 0;
        } else {
            for (int k = 0; k < core.size(); k++) {
                Participant participant = core.get(k);
                int end = participant.group() != null ? participant.group().to() : coreStarts.get(k) + 1;
                if (end == anchor) {
                    subject = k;
                }
            }
            if (subject < 0 && carried == null) {
                for (int k = 0; k < core.size() && subject < 0; k++) {
                    if (coreStarts.get(k) > anchor) {
                        subject = k;
                    }
                }
            }
        }
        if (subject >= 0) {
            Participant chosen = core.get(subject);
            found.set(found.indexOf(chosen), new Participant(passive ? Role.AFFECTED_OBJECT : Role.ACTOR,
                    chosen.group(), chosen.pronoun()));
        } else if (carried != null && !passive) {
            found.add(0, carried);
        }
        return found;
    }

    /** Erstes verbartiges Wort ab {@code from}, das kein Adjektiv einer Nominalgruppe ist. */
    private static int firstVerb(Sentence sentence, int from, int to) {
        for (int i = from; i < to; i++) {
            Token token = sentence.tokens().get(i);
            if (token.is(WordClass.OTHER) && Verbs.verbLike(token.lower()) && !inGroup(sentence, i)) {
                return i;
            }
        }
        return -1;
    }

    private static int lastVerb(Sentence sentence, int from, int to, int fallback) {
        for (int i = to - 1; i >= from; i--) {
            Token token = sentence.tokens().get(i);
            if (token.is(WordClass.OTHER) && Verbs.verbLike(token.lower()) && !inGroup(sentence, i)) {
                return i;
            }
        }
        return fallback;
    }

    private static int lastWord(List<Token> tokens, int from, int to) {
        for (int i = to - 1; i >= from; i--) {
            if (!tokens.get(i).is(WordClass.PUNCTUATION)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean inGroup(Sentence sentence, int index) {
        for (NounGroup group : sentence.nounGroups()) {
            if (index >= group.from() && index < group.to()) {
                return true;
            }
        }
        return false;
    }

    /** Das Verb steht direkt nach dem ersten Satzglied: dann ist ein Infinitiv am Ende eher eine Pluralform. */
    private static boolean secondPosition(Sentence sentence, int from, int verb) {
        NounGroup first = sentence.groupAt(from);
        return first != null && first.to() == verb;
    }
}
