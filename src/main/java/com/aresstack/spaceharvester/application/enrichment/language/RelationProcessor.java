package com.aresstack.spaceharvester.application.enrichment.language;

import com.aresstack.spaceharvester.application.enrichment.EnrichmentOutput;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentProcessor;
import com.aresstack.spaceharvester.application.enrichment.JsonText;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Relationen, die der Text selbst ausspricht, als maschinell abgeleitete Hinweise:
 * <ul>
 *   <li>{@code spo}: Subjekt–Prädikat–Objekt aus jeder Handlung ({@link ActivityFinder}) mit Handelndem und
 *       betroffenem Objekt, beide keine Pronomen; Prädikat ist die vorgeschlagene Grundform ({@code Die Sachbearbeitung erstellt den
 *       Antrag}: sachbearbeitung – erstellen – antrag).</li>
 *   <li>{@code is_a}: Begriff, Form von sein, unbestimmter Artikel oder Plural ohne Artikel, Begriff
 *       ({@code Ein Antrag ist ein Dokument}).</li>
 *   <li>{@code part_of}: {@code X ist (ein) Teil/Bestandteil von/des Y}, {@code X gehört zu Y}.</li>
 *   <li>{@code has_part}: {@code X enthält/umfasst/beinhaltet Y}, {@code X besteht aus Y}, auch mit gereihten
 *       Objekten.</li>
 *   <li>{@code synonym}: Begriff mit Klammereinschub, der nur aus einem Begriff besteht, wenn eine Seite ein Akronym
 *       ist oder {@code auch}, {@code kurz}, {@code alias}, {@code genannt} oder {@code abgekürzt} dabeisteht
 *       ({@code API (Application Programming Interface)}).</li>
 *   <li>{@code before}: zwei Handlungen in zeitlicher Folge, nur bei einem Signalwort im Text ({@code danach},
 *       {@code anschließend}, {@code dann}, {@code daraufhin}, {@code abschließend}, {@code zuletzt},
 *       {@code schließlich}, {@code später} beziehen sich auf die vorige Handlung desselben Abschnitts;
 *       {@code bevor} und {@code nachdem} verbinden Haupt- und Nebensatz).</li>
 * </ul>
 * Verneinte Aussagen tragen {@code "negated":true}. Nichts wird über Seiten hinweg zusammengeführt, Pronomen werden
 * nicht aufgelöst; eine Relation gilt nur für ihren Satz.
 *
 * <p>Ergebnis {@code {"relations":[{"type","subject","subjectNormalized","predicate","object","objectNormalized",
 * "negated","cue","section","charStart","charEnd"}]}} nach Fundstelle, höchstens {@value #MAX_RELATIONS} Einträge.
 */
public class RelationProcessor implements EnrichmentProcessor {

    static final int MAX_RELATIONS = 1000;

    private static final Set<String> HAS_PART_VERBS = Set.of(
            "enthält", "enthalten", "umfasst", "umfassen", "beinhaltet", "beinhalten");
    private static final Set<String> CONSIST_VERBS = Set.of("besteht", "bestehen");
    private static final Set<String> BELONG_VERBS = Set.of("gehört", "gehören");
    private static final Set<String> PART_NOUNS = Set.of("teil", "bestandteil", "teile", "bestandteile");
    private static final Set<String> INDEFINITE = Set.of("ein", "eine", "einen", "einer", "eines", "kein", "keine",
            "keinen", "keiner", "keines");
    private static final Set<String> PLURAL_COPULAS = Set.of("sind", "waren", "seien", "wären");
    private static final Set<String> SYNONYM_CUES = Set.of("auch", "kurz", "alias", "genannt", "abgekürzt");
    private static final Set<String> SEQUENCE_CUES = Set.of(
            "danach", "anschließend", "dann", "daraufhin", "abschließend", "zuletzt", "schließlich", "später");

    @Override
    public String id() {
        return "relations";
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public EnrichmentOutput process(PageText text) {
        GermanText analysis = GermanText.analyze(text);
        List<Relation> found = new ArrayList<>();
        Activity previous = null;
        String previousSection = null;
        for (Sentence sentence : analysis.sentences()) {
            terms(sentence, found);
            synonyms(sentence, found);
            List<Activity> activities = ActivityFinder.find(sentence);
            for (int i = 0; i < activities.size(); i++) {
                Activity activity = activities.get(i);
                spo(activity, found);
                String section = analysis.sectionAt(activity.charStart());
                Token conjunction = activity.from() > 0 ? activity.sentence().tokens().get(activity.from() - 1)
                        : null;
                Token cue = cue(activity);
                if (cue != null && previous != null && java.util.Objects.equals(section, previousSection)) {
                    found.add(before(previous, activity, cue));
                } else if (conjunction != null && conjunction.lower().equals("bevor") && i > 0) {
                    found.add(before(activities.get(i - 1), activity, conjunction));
                } else if (conjunction != null && conjunction.lower().equals("nachdem")) {
                    Activity other = i + 1 < activities.size() ? activities.get(i + 1) : i > 0
                            ? activities.get(i - 1) : null;
                    if (other != null) {
                        found.add(before(activity, other, conjunction));
                    }
                }
                previous = activity;
                previousSection = section;
            }
        }
        found.sort(Comparator.comparingInt(Relation::start).thenComparingInt(Relation::end)
                .thenComparing(Relation::type));
        List<String> warnings = new ArrayList<>(analysis.warnings());
        if (found.size() > MAX_RELATIONS) {
            warnings.add("Nur die ersten " + MAX_RELATIONS + " von " + found.size() + " Relationen");
        }
        StringBuilder json = new StringBuilder("{\"relations\":[");
        for (int i = 0; i < Math.min(found.size(), MAX_RELATIONS); i++) {
            Relation relation = found.get(i);
            String section = analysis.sectionAt(relation.start());
            json.append(i == 0 ? "" : ",")
                    .append("{\"type\":").append(JsonText.quote(relation.type()))
                    .append(",\"subject\":").append(JsonText.quote(relation.subject()))
                    .append(",\"subjectNormalized\":").append(JsonText.quote(Terms.normalize(relation.subject())))
                    .append(",\"predicate\":").append(JsonText.quote(relation.predicate()))
                    .append(",\"object\":").append(JsonText.quote(relation.object()))
                    .append(",\"objectNormalized\":").append(JsonText.quote(Terms.normalize(relation.object())))
                    .append(",\"negated\":").append(relation.negated())
                    .append(",\"cue\":").append(relation.cue() == null ? "null" : JsonText.quote(relation.cue()))
                    .append(",\"section\":").append(section == null ? "null" : JsonText.quote(section))
                    .append(",\"charStart\":").append(relation.start())
                    .append(",\"charEnd\":").append(relation.end()).append('}');
        }
        return new EnrichmentOutput(json.append("]}").toString(), warnings);
    }

    private static void spo(Activity activity, List<Relation> found) {
        Activity.Participant actor = activity.first(Activity.Role.ACTOR);
        Activity.Participant object = activity.first(Activity.Role.AFFECTED_OBJECT);
        if (actor != null && object != null && !activity.instruction() && actor.group() != null
                && object.group() != null) {
            found.add(new Relation("spo", actor.term(), activity.lemma(), object.term(), activity.negated(), null,
                    activity.charStart(), activity.charEnd()));
        }
    }

    private static Relation before(Activity first, Activity then, Token cue) {
        return new Relation("before", first.lemma(), "before", then.lemma(), false, cue.text(),
                Math.min(first.charStart(), then.charStart()), Math.max(first.charEnd(), then.charEnd()));
    }

    /** Ein Signalwort der zeitlichen Folge im Teilsatz der Handlung. */
    private static Token cue(Activity activity) {
        for (int i = activity.from(); i < activity.to(); i++) {
            Token token = activity.sentence().tokens().get(i);
            if (token.is(WordClass.ADVERB) && SEQUENCE_CUES.contains(token.lower())) {
                return token;
            }
        }
        return null;
    }

    /** is_a, part_of und has_part nach Kopula oder Verb direkt hinter einem Begriff. */
    private static void terms(Sentence sentence, List<Relation> found) {
        List<Token> tokens = sentence.tokens();
        for (NounGroup subject : sentence.nounGroups()) {
            int k = subject.to();
            if (k >= tokens.size()) {
                continue;
            }
            Token verb = tokens.get(k);
            String lower = verb.lower();
            if (verb.is(WordClass.AUXILIARY) && Lexicon.COPULAS.contains(lower)) {
                copula(sentence, subject, k, PLURAL_COPULAS.contains(lower), found);
            } else if (HAS_PART_VERBS.contains(lower)) {
                objects(sentence, subject, "has_part", k + 1, false, found);
            } else if (CONSIST_VERBS.contains(lower) && k + 1 < tokens.size()
                    && tokens.get(k + 1).lower().equals("aus")) {
                objects(sentence, subject, "has_part", k + 2, false, found);
            } else if (BELONG_VERBS.contains(lower)) {
                int j = skipAdverbs(tokens, k + 1);
                boolean negated = j > k + 1 && negatedBetween(tokens, k + 1, j);
                if (j < tokens.size() && (tokens.get(j).lower().equals("zu"))) {
                    objects(sentence, subject, "part_of", j + 1, negated, found);
                } else if (j < tokens.size() && (tokens.get(j).lower().equals("zum")
                        || tokens.get(j).lower().equals("zur"))) {
                    NounGroup whole = sentence.groupAt(j + 1);
                    if (whole != null) {
                        found.add(relation("part_of", subject, whole, negated, sentence));
                    }
                }
            }
        }
    }

    private static void copula(Sentence sentence, NounGroup subject, int k, boolean plural, List<Relation> found) {
        List<Token> tokens = sentence.tokens();
        int j = skipAdverbs(tokens, k + 1);
        boolean negated = negatedBetween(tokens, k + 1, j);
        NounGroup object = sentence.groupAt(j);
        if (object == null) {
            return;
        }
        Token first = tokens.get(object.from());
        boolean indefinite = first.is(WordClass.DETERMINER) && INDEFINITE.contains(first.lower());
        negated |= first.lower().startsWith("kein");
        boolean bare = object.from() == object.termFrom();
        if (PART_NOUNS.contains(object.normalized())) {
            int next = object.to();
            if (next < tokens.size() && (tokens.get(next).lower().equals("von") || tokens.get(next).lower()
                    .equals("vom"))) {
                next++;
            }
            NounGroup whole = sentence.groupAt(next);
            if (whole != null) {
                found.add(relation("part_of", subject, whole, negated, sentence));
            }
        } else if (indefinite || plural && bare) {
            found.add(relation("is_a", subject, object, negated, sentence));
        }
    }

    /** Ein Objekt oder mehrere, mit {@code und}, {@code oder}, {@code sowie} oder Komma gereiht. */
    private static void objects(Sentence sentence, NounGroup subject, String type, int index, boolean negated,
                                List<Relation> found) {
        List<Token> tokens = sentence.tokens();
        int i = skipAdverbs(tokens, index);
        negated |= negatedBetween(tokens, index, i);
        while (i < tokens.size()) {
            int start = i;
            while (i < tokens.size() && i - start < 2 && sentence.groupAt(i) == null
                    && (tokens.get(i).is(WordClass.NUMBER) || tokens.get(i).is(WordClass.OTHER))) {
                i++;
            }
            NounGroup object = sentence.groupAt(i);
            if (object == null) {
                return;
            }
            found.add(relation(type, subject, object, negated, sentence));
            i = object.to();
            if (i < tokens.size() && (tokens.get(i).punctuation(",") || tokens.get(i).is(WordClass.CONJUNCTION)
                    && Set.of("und", "oder", "sowie").contains(tokens.get(i).lower()))) {
                i++;
            } else {
                return;
            }
        }
    }

    /** {@code X (Y)}, {@code X (auch Y)}, {@code X (kurz: Y)}, {@code X (auch Y genannt)}. */
    private static void synonyms(Sentence sentence, List<Relation> found) {
        List<Token> tokens = sentence.tokens();
        for (NounGroup left : sentence.nounGroups()) {
            int i = left.to();
            if (i >= tokens.size() || !tokens.get(i).punctuation("(")) {
                continue;
            }
            i++;
            boolean cue = false;
            while (i < tokens.size() && SYNONYM_CUES.contains(tokens.get(i).lower())) {
                cue = true;
                i++;
            }
            if (i < tokens.size() && tokens.get(i).punctuation(":")) {
                i++;
            }
            NounGroup right = sentence.groupAt(i);
            if (right == null) {
                continue;
            }
            int close = right.to();
            while (close < tokens.size() && SYNONYM_CUES.contains(tokens.get(close).lower())) {
                cue = true;
                close++;
            }
            if (close < tokens.size() && tokens.get(close).punctuation(")")
                    && (cue || left.kind().equals("acronym") || right.kind().equals("acronym"))) {
                found.add(new Relation("synonym", left.term(), "synonym", right.term(), false, null,
                        left.termStart(sentence), tokens.get(close).end()));
            }
        }
    }

    private static int skipAdverbs(List<Token> tokens, int index) {
        int i = index;
        while (i < tokens.size() && (tokens.get(i).is(WordClass.ADVERB) || tokens.get(i).is(WordClass.NEGATION))) {
            i++;
        }
        return i;
    }

    private static boolean negatedBetween(List<Token> tokens, int from, int to) {
        for (int i = from; i < to; i++) {
            if (tokens.get(i).is(WordClass.NEGATION)) {
                return true;
            }
        }
        return false;
    }

    private static Relation relation(String type, NounGroup subject, NounGroup object, boolean negated,
                                     Sentence sentence) {
        return new Relation(type, subject.term(), type, object.term(), negated, null, subject.charStart(sentence),
                object.charEnd(sentence));
    }

    private record Relation(String type, String subject, String predicate, String object, boolean negated,
                            String cue, int start, int end) {
    }
}
