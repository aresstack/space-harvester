package com.aresstack.spaceharvester.nlp.extraction;

import com.aresstack.spaceharvester.nlp.syntax.Sentence;
import com.aresstack.spaceharvester.nlp.syntax.Token;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Merkmale einer Aussage aus den Wörtern ihres Satzes (ADR 0032, FL2): Verneinung, Verbindlichkeit und Zeitbezug.
 * Deterministisch, nur allgemeines Deutsch, keine Fachwörter; was der Satz nicht sagt, bleibt beim Standard (nicht
 * verneint, ohne Verbindlichkeit, gilt). Satzübergreifendes („Diese Überlegung wurde verworfen“ für den Satz davor)
 * ist Sache einer späteren Auswertung.
 */
public final class StatementFeatures {

    /** Verbindlichkeit nach dem Modalverb. */
    public enum Modality {
        NONE,
        MUSS,
        SOLL,
        KANN,
        DARF
    }

    /** Zeitbezug der Aussage. */
    public enum Temporality {
        /** Gilt (Präsens, „aktuell“, „derzeit“), auch wenn der Satz nichts sagt. */
        GILT,
        /** Geplant oder beabsichtigt („künftig“, Futur, „soll“ mit Zeitangabe). */
        GEPLANT,
        /** Früher (Präteritum von Hilfsverb oder Kopula, „damals“, „früher“). */
        FRUEHER,
        /** Im selben Satz als verworfen oder entfallen bezeichnet. */
        VERWORFEN
    }

    /**
     * Ergebnis.
     *
     * @param negated     die Aussage ist verneint („erfüllt Auflagen nicht“, „ist noch nicht abgestimmt“)
     * @param modality    Verbindlichkeit
     * @param temporality Zeitbezug
     */
    public record Features(boolean negated, Modality modality, Temporality temporality) {

        public Features {
            Objects.requireNonNull(modality, "modality");
            Objects.requireNonNull(temporality, "temporality");
        }
    }

    private static final Set<String> NEGATIONS = Set.of("nicht", "nie", "niemals", "nimmer");
    private static final Set<String> NEGATING_DETERMINERS = Set.of("kein", "keine", "keinen", "keinem", "keiner",
            "keines");
    private static final Map<String, Modality> MODALS = Map.of("müssen", Modality.MUSS, "sollen", Modality.SOLL,
            "können", Modality.KANN, "dürfen", Modality.DARF);
    /** Formen der Modalverben, falls die Grundform fehlt oder abweicht (Konjunktiv II). */
    private static final Map<String, Modality> MODAL_FORMS = modalForms();
    /** Präteritum der Hilfsverben und der Kopula. */
    private static final Set<String> PAST_AUXILIARIES = Set.of("war", "warst", "waren", "wart", "wurde", "wurdest",
            "wurden", "wurdet", "hatte", "hattest", "hatten", "hattet");
    private static final Set<String> PAST_WORDS = Set.of("früher", "damals", "ehemals", "einst", "seinerzeit",
            "vormals", "ursprünglich");
    private static final Set<String> FUTURE_WORDS = Set.of("künftig", "zukünftig", "später", "demnächst",
            "perspektivisch", "langfristig", "mittelfristig");
    private static final Set<String> CURRENT_WORDS = Set.of("aktuell", "derzeit", "zurzeit", "momentan", "heute",
            "gegenwärtig", "inzwischen", "mittlerweile");
    private static final Set<String> DISCARDED_WORDS = Set.of("verworfen", "entfällt", "entfallen", "eingestellt",
            "abgelöst", "aufgegeben", "gestrichen", "überholt", "hinfällig", "obsolet");

    /** Relationen, unter denen ein zu-Infinitiv die Merkmale seines regierenden Prädikats teilt. */
    private static final Set<String> INFINITIVE_GROUPS = Set.of("ccomp", "advcl", "csubj");
    private static final Set<String> PURPOSE_MARKERS = Set.of("um", "ohne", "statt", "anstatt");

    private StatementFeatures() {
    }

    /** Wie {@link #assess(Map, ObjectCentricAnalysis, Statement)} mit eigener Nachschlagetabelle. */
    public static Features assess(ObjectCentricAnalysis analysis, Statement statement) {
        return assess(StatementQuality.mentions(analysis), analysis, statement);
    }

    public static Features assess(Map<String, Mention> mentions, ObjectCentricAnalysis analysis,
                                  Statement statement) {
        Sentence sentence = analysis.evidence(statement);
        Token verb = sentence.token(statement.predicate().verb());
        List<Token> scope = scope(sentence, verb);
        return new Features(negated(mentions, sentence, statement, scope), modality(sentence, verb, scope),
                temporality(sentence, verb, scope));
    }

    /** „um/ohne/statt … zu“: eigene Absicht, Merkmale des regierenden Prädikats gelten nicht. */
    private static boolean purpose(Sentence sentence, Token verb) {
        return sentence.dependents(verb.id()).stream()
                .anyMatch(t -> "mark".equals(t.baseRelation()) && PURPOSE_MARKERS.contains(lower(t.form())));
    }

    /** Das Prädikat, seine direkten Abhängigen und – bei Infinitiv- oder Partizipgruppe – das regierende Prädikat. */
    private static List<Token> scope(Sentence sentence, Token verb) {
        List<Token> scope = new ArrayList<>();
        scope.add(verb);
        scope.addAll(sentence.dependents(verb.id()));
        Token governor = sentence.head(verb);
        String relation = verb.baseRelation();
        if (governor != null && ("xcomp".equals(relation) || INFINITIVE_GROUPS.contains(relation)
                && PrimaryRelationExtraction.zuInfinitive(sentence, verb) && !purpose(sentence, verb))) {
            scope.add(governor);
            scope.addAll(sentence.dependents(governor.id()).stream()
                    .filter(t -> !"punct".equals(t.deprel()) && t.id() != verb.id()).toList());
        }
        return scope;
    }

    private static boolean negated(Map<String, Mention> mentions, Sentence sentence, Statement statement,
                                   List<Token> scope) {
        for (Token token : scope) {
            String form = lower(token.form());
            if (NEGATIONS.contains(form) && ("advmod".equals(token.baseRelation()) || token.hasUpos("PART"))
                    && !notOnly(sentence, token)) {
                return true;
            }
        }
        for (Argument argument : statement.arguments()) {
            if (argument.priority() != Priority.CORE) {
                continue;
            }
            Mention mention = mentions.get(argument.mention());
            if (mention == null || mention.kind() == Mention.Kind.CLAUSE) {
                continue;
            }
            for (Token determiner : sentence.dependents(mention.head())) {
                if ("det".equals(determiner.baseRelation()) && NEGATING_DETERMINERS.contains(lower(determiner.form()))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** „nicht nur … sondern auch“ ist eine Aufzählung, keine Verneinung. */
    private static boolean notOnly(Sentence sentence, Token negation) {
        return negation.id() < sentence.tokens().size()
                && "nur".equalsIgnoreCase(sentence.token(negation.id() + 1).form());
    }

    private static Modality modality(Sentence sentence, Token verb, List<Token> scope) {
        for (Token token : scope) {
            if (!"aux".equals(token.deprel()) && token != verb) {
                continue;
            }
            Modality modality = token.lemma() == null ? null : MODALS.get(lower(token.lemma()));
            if (modality == null) {
                modality = MODAL_FORMS.get(lower(token.form()));
            }
            if (modality != null && token != verb) {
                return modality;
            }
        }
        // „ist … vorzulegen“, „hat … einzureichen“: sein/haben mit zu-Infinitiv ist eine Pflicht
        if (PrimaryRelationExtraction.zuInfinitive(sentence, verb)) {
            for (Token dependent : sentence.dependents(verb.id())) {
                String lemma = dependent.lemma() == null ? "" : lower(dependent.lemma());
                if (("aux".equals(dependent.deprel()) || "aux:pass".equals(dependent.deprel()))
                        && ("sein".equals(lemma) || "haben".equals(lemma))) {
                    return Modality.MUSS;
                }
            }
        }
        return Modality.NONE;
    }

    private static Temporality temporality(Sentence sentence, Token verb, List<Token> scope) {
        boolean past = false;
        boolean future = false;
        boolean current = false;
        boolean discarded = false;
        boolean timeExpression = false;
        Modality modality = modality(sentence, verb, scope);
        for (Token token : scope) {
            String form = lower(token.form());
            String relation = token.baseRelation();
            boolean auxiliary = "aux".equals(relation) || "cop".equals(relation);
            if (auxiliary && PAST_AUXILIARIES.contains(form)) {
                past = true;
            }
            if (PAST_WORDS.contains(form)) {
                past = true;
            }
            if (FUTURE_WORDS.contains(form)) {
                future = true;
            }
            if (CURRENT_WORDS.contains(form)) {
                current = true;
            }
            if (DISCARDED_WORDS.contains(form) && token != verb) {
                discarded = true;
            }
            if (("obl".equals(relation) || "advmod".equals(relation) || "nmod".equals(relation))
                    && (token.hasUpos("NUM") || SupplementaryRoles.TIME_WORDS.contains(lower(
                    token.lemma() == null ? token.form() : token.lemma())) || hasNumber(sentence, token))) {
                timeExpression = true;
            }
            // Futur: „wird … prüfen“ (werden als aux mit Infinitiv, kein Passiv)
            if ("aux".equals(token.deprel()) && token.lemma() != null && "werden".equals(lower(token.lemma()))
                    && verb.form().equalsIgnoreCase(verb.lemma() == null ? "" : verb.lemma())) {
                future = true;
            }
        }
        if (discarded) {
            return Temporality.VERWORFEN;
        }
        if (past) {
            return Temporality.FRUEHER;
        }
        if (future || modality == Modality.SOLL && timeExpression && !current) {
            return Temporality.GEPLANT;
        }
        return Temporality.GILT;
    }

    private static boolean hasNumber(Sentence sentence, Token token) {
        return sentence.dependents(token.id()).stream().anyMatch(t -> t.hasUpos("NUM"));
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static Map<String, Modality> modalForms() {
        Map<String, Modality> forms = new HashMap<>();
        for (String form : List.of("muss", "musst", "müssen", "müsst", "musste", "mussten", "müsste", "müssten")) {
            forms.put(form, Modality.MUSS);
        }
        for (String form : List.of("soll", "sollst", "sollen", "sollt", "sollte", "sollten")) {
            forms.put(form, Modality.SOLL);
        }
        for (String form : List.of("kann", "kannst", "können", "könnt", "konnte", "konnten", "könnte", "könnten")) {
            forms.put(form, Modality.KANN);
        }
        for (String form : List.of("darf", "darfst", "dürfen", "dürft", "durfte", "durften", "dürfte", "dürften")) {
            forms.put(form, Modality.DARF);
        }
        return Map.copyOf(forms);
    }
}
