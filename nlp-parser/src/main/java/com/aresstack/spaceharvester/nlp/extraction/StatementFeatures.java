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
        /** Früher: Präteritum und Plusquamperfekt im Indikativ (Hilfsverb, Kopula, Vollverb), „damals“, „früher“; Perfekt und Konjunktiv II nie. */
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
     * @param article     Begleiter des Rollennomens einer Zustandsaussage ({@link Finding#STATE_ROLE_NOUN}),
     *                    {@code null} bei allen anderen Aussagen
     */
    public record Features(boolean negated, Modality modality, Temporality temporality, Article article) {

        public Features {
            Objects.requireNonNull(modality, "modality");
            Objects.requireNonNull(temporality, "temporality");
        }

        /** Ohne Artikel (Handlungen und Eigenschaften). */
        public Features(boolean negated, Modality modality, Temporality temporality) {
            this(negated, modality, temporality, null);
        }
    }

    /**
     * Begleiter des Rollennomens einer Zustandsaussage („ist ein/der/kein Ansprechpartner“, ADR 0032): ein neutrales
     * Merkmal, das der Parser nicht deutet. {@link #KEIN} ändert nichts an der Verneinung.
     */
    public enum Article {
        /** ein, eine, einen, einem, einer, eines. */
        UNBESTIMMT,
        /** der, die, das, den, dem, des. */
        BESTIMMT,
        /** Kein Begleiter, auch Mehrzahl ohne Artikel („sind Ansprechpartner“). */
        OHNE,
        /** kein, keine, keinen, keinem, keiner, keines. */
        KEIN,
        /** Jeder andere Begleiter (unser, ihr, dieser, jeder …). */
        SONSTIGER
    }

    private static final Set<String> INDEFINITE = Set.of("ein", "eine", "einen", "einem", "einer", "eines");
    private static final Set<String> DEFINITE = Set.of("der", "die", "das", "den", "dem", "des");

    private static final Set<String> NEGATIONS = Set.of("nicht", "nie", "niemals", "nimmer");
    private static final Set<String> NEGATING_DETERMINERS = Set.of("kein", "keine", "keinen", "keinem", "keiner",
            "keines");
    private static final Map<String, Modality> MODALS = Map.of("müssen", Modality.MUSS, "sollen", Modality.SOLL,
            "können", Modality.KANN, "dürfen", Modality.DARF);
    /** Formen der Modalverben, falls die Grundform fehlt oder abweicht (Konjunktiv II). */
    private static final Map<String, Modality> MODAL_FORMS = modalForms();
    /** Präteritum der Hilfsverben, der Kopula und von müssen/können/dürfen („sollte“ ist meist Konjunktiv). */
    private static final Set<String> PAST_AUXILIARIES = Set.of("war", "warst", "waren", "wart", "wurde", "wurdest",
            "wurden", "wurdet", "hatte", "hattest", "hatten", "hattet", "musste", "musstest", "mussten", "musstet",
            "konnte", "konntest", "konnten", "konntet", "durfte", "durftest", "durften", "durftet");
    private static final Set<String> PAST_WORDS = Set.of("früher", "damals", "ehemals", "einst", "seinerzeit",
            "vormals", "ursprünglich");
    private static final Set<String> FUTURE_WORDS = Set.of("künftig", "zukünftig", "später", "demnächst",
            "perspektivisch", "langfristig", "mittelfristig");
    private static final Set<String> CURRENT_WORDS = Set.of("aktuell", "derzeit", "zurzeit", "momentan", "heute",
            "gegenwärtig", "inzwischen", "mittlerweile");
    private static final Set<String> DISCARDED_WORDS = Set.of("verworfen", "entfällt", "entfallen",
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
                temporality(sentence, verb, scope), statement.has(Finding.STATE_ROLE_NOUN) ? article(sentence, verb)
                : null);
    }

    /** Der erste Begleiter ({@code det}) am Rollennomen; ohne einen {@link Article#OHNE}. */
    static Article article(Sentence sentence, Token noun) {
        // Präposition am Nomen („im“, „beim“, „zur“ …): nie ein Begleiter im Sinn des Merkmals (AT, rules-14)
        if (!sentence.dependents(noun.id(), "case").isEmpty()) {
            return Article.SONSTIGER;
        }
        Token determiner = sentence.dependents(noun.id()).stream()
                .filter(t -> "det".equals(t.baseRelation()) && t.id() < noun.id()).findFirst().orElse(null);
        if (determiner == null) {
            return Article.OHNE;
        }
        String form = lower(determiner.form());
        if (INDEFINITE.contains(form)) {
            return Article.UNBESTIMMT;
        }
        if (DEFINITE.contains(form)) {
            return Article.BESTIMMT;
        }
        return NEGATING_DETERMINERS.contains(form) ? Article.KEIN : Article.SONSTIGER;
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
        if (statement.has(Finding.STATE_ROLE_NOUN) && article(sentence, sentence.token(statement.predicate().verb()))
                == Article.KEIN) {
            return true; // „ist keine Förderung“: verneint, der Begleiter KEIN steht zusätzlich als Merkmal
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
            // Futur: „wird … prüfen“ (werden als aux mit Infinitiv, kein Passiv); „würde“ ist Konjunktiv II
            if ("aux".equals(token.deprel()) && token.lemma() != null && "werden".equals(lower(token.lemma()))
                    && !CONDITIONAL_FORMS.contains(form)
                    && verb.form().equalsIgnoreCase(verb.lemma() == null ? "" : verb.lemma())) {
                future = true;
            }
        }
        past |= preterite(sentence, verb);
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

    /** Konjunktiv II von werden („würde … ablehnen“): weder Futur noch Präteritum. */
    private static final Set<String> CONDITIONAL_FORMS = Set.of("würde", "würdest", "würden", "würdet");
    /** Präteritum starker und gemischter Verben nach Grundform ({@code praeteritum.tsv}). */
    private static final Map<String, String> STRONG_PRETERITE = strongPreterite();

    /**
     * Ob das Vollverb selbst im Präteritum Indikativ steht („prüfte“, „zahlte … aus“, „gab … zurück“): ohne Hilfsverb,
     * schwach an der Endung {@code -te}/{@code -ete} am Stamm der Grundform, stark und gemischt nach {@code
     * praeteritum.tsv}, auch mit Vorsilbe („vergab“). Ist die Grundform nur aus der Wortform erzeugt, bleibt es beim
     * Standard (nicht raten). Schwacher Konjunktiv II ist am Wort nicht vom Präteritum zu unterscheiden; starker
     * Konjunktiv II („gäbe“) und Hilfs-/Modalverben im Konjunktiv II („wäre“, „könnte“) sind nie Präteritum.
     */
    static boolean preterite(Sentence sentence, Token verb) {
        if (!verb.hasUpos("VERB") || verb.lemma() == null || "Form".equals(verb.feats().get(Token.LEMMA_FROM))
                || sentence.dependents(verb.id()).stream().anyMatch(t -> "aux".equals(t.baseRelation()))) {
            return false;
        }
        String form = lower(verb.form());
        String lemma = lower(verb.lemma());
        if (form.equals(lemma)) {
            return false;
        }
        String stem = lemma.endsWith("en") ? lemma.substring(0, lemma.length() - 2)
                : lemma.endsWith("n") ? lemma.substring(0, lemma.length() - 1) : null;
        if (stem != null && stem.length() >= 2) {
            for (String ending : List.of("te", "test", "ten", "tet", "ete", "etest", "eten", "etet")) {
                if (form.equals(stem + ending)) {
                    return true;
                }
            }
        }
        for (Map.Entry<String, String> entry : STRONG_PRETERITE.entrySet()) {
            String base = entry.getKey();
            if (!lemma.endsWith(base)) {
                continue;
            }
            String prefix = lemma.substring(0, lemma.length() - base.length());
            String past = entry.getValue();
            for (String ending : past.endsWith("e") ? List.of("", "st", "n", "t") : List.of("", "st", "en", "t")) {
                if (form.equals(prefix + past + ending) || form.equals(past + ending)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Map<String, String> strongPreterite() {
        Map<String, String> result = new HashMap<>();
        try (java.io.InputStream input = StatementFeatures.class.getResourceAsStream("praeteritum.tsv")) {
            if (input == null) {
                throw new IllegalStateException("praeteritum.tsv fehlt");
            }
            for (String line : new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .split("\\R")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] fields = line.split("\t");
                result.put(fields[0].strip(), fields[1].strip());
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return Map.copyOf(result);
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
