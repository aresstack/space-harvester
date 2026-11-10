package com.aresstack.spaceharvester.nlp.extraction;

import com.aresstack.spaceharvester.nlp.syntax.Sentence;
import com.aresstack.spaceharvester.nlp.syntax.Token;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Deterministische Qualitätsstufe zwischen Extraktion und Graph-Projektion (#499, Nachtrag Ursachenmatrix zu ADR
 * 0024). Bewertet nur, wie gut eine Aussage maschinell abgeleitet ist, nie ob sie fachlich stimmt; keine
 * Prozentwerte. Nichts wird gelöscht: jede Aussage bleibt mit Stufe und Gründen erhalten.
 * <ul>
 *   <li>{@link Level#INVALID}: erkannte Fehlkonstruktion, keine verwendbare Relation (kein Beteiligter,
 *       Handelnder ist dieselbe Erwähnung wie der Betroffene).</li>
 *   <li>{@link Level#UNCERTAIN}: verwertbarer Kandidat mit ungeklärter Zuordnung der Kernbeteiligten (Heuristik,
 *       Kasuskonflikt, offenes Pronomen, Rollenkonflikt, Artikel als Beteiligter, aktiv ohne Handelnden). Hinweise zu
 *       ergänzenden Rollen (Zeit, Dativ) mindern die Stufe nicht.</li>
 *   <li>{@link Level#PLAUSIBLE}: aus Parserkanten (oder Koordination ohne Teilsatzgrenze, Wortliste) ausreichend
 *       gestützt.</li>
 * </ul>
 */
public final class StatementQuality {

    /** Stufe der Extraktionsqualität. */
    public enum Level {
        PLAUSIBLE,
        UNCERTAIN,
        INVALID
    }

    /**
     * Ergebnis der Bewertung.
     *
     * @param level   Stufe
     * @param reasons Gründe alphabetisch ({@code HEURISTIC_PARTICIPANT}, {@code NO_PARTICIPANT} …), leer bei
     *                {@link Level#PLAUSIBLE}
     */
    public record Assessment(Level level, List<String> reasons) {

        public Assessment {
            Objects.requireNonNull(level, "level");
            reasons = List.copyOf(new TreeSet<>(reasons));
        }
    }

    /**
     * Hinweise, die die Zuordnung der Kernbeteiligten offen lassen. Hinweise zu ergänzenden Rollen (Zeit, Dativ,
     * Umstand) betreffen die Kernrelation nicht und mindern die Stufe nicht.
     */
    private static final Set<Finding> UNCERTAIN_FINDINGS = Set.of(Finding.CASE_CONFLICT,
            Finding.HEURISTIC_PARTICIPANT, Finding.SUBJECT_FROM_LABEL);
    /** Rollen, die eine Aussage zur Relation machen. */
    private static final Set<Role> PARTICIPANT_ROLES = Set.of(Role.ACTOR, Role.AFFECTED_OBJECT, Role.RECIPIENT,
            Role.PREPOSITIONAL_OBJECT);

    private StatementQuality() {
    }

    /** Wie {@link #assess(Map, ObjectCentricAnalysis, Statement)} mit einer eigenen Nachschlagetabelle (Tests). */
    public static Assessment assess(ObjectCentricAnalysis analysis, Statement statement) {
        return assess(mentions(analysis), analysis, statement);
    }

    /** Die Erwähnungen nach ID, einmal je Analyse für {@link #assess(Map, ObjectCentricAnalysis, Statement)}. */
    public static Map<String, Mention> mentions(ObjectCentricAnalysis analysis) {
        Map<String, Mention> byId = new HashMap<>();
        for (Mention mention : analysis.mentions()) {
            byId.put(mention.id(), mention);
        }
        return byId;
    }

    /**
     * Bewertet eine Aussage.
     *
     * @param mentions Erwähnungen der Analyse nach ID ({@link #mentions(ObjectCentricAnalysis)})
     */
    public static Assessment assess(Map<String, Mention> mentions, ObjectCentricAnalysis analysis,
                                    Statement statement) {
        Set<String> invalid = new TreeSet<>();
        Set<String> uncertain = new TreeSet<>();
        if (statement.arguments().stream().noneMatch(argument -> PARTICIPANT_ROLES.contains(argument.role()))) {
            invalid.add("NO_PARTICIPANT");
        }
        Argument actor = statement.core(Role.ACTOR);
        Argument object = statement.core(Role.AFFECTED_OBJECT);
        if (actor != null && object != null && actor.mention().equals(object.mention())) {
            // Nur dieselbe Erwähnung; gleiche Grundform ist erlaubt („Er genehmigt ihn“, „Konnektor verbindet
            // Konnektoren“).
            invalid.add("SELF_RELATION");
        }
        for (Finding finding : statement.findings()) {
            if (UNCERTAIN_FINDINGS.contains(finding)) {
                uncertain.add(finding.name());
            }
        }
        List<Argument> core = new ArrayList<>();
        for (Argument argument : statement.arguments()) {
            if (argument.priority() == Priority.CORE) {
                core.add(argument);
            }
        }
        for (Argument argument : core) {
            Mention mention = mentions.get(argument.mention());
            if (mention == null) {
                uncertain.add("UNCERTAIN_PARTICIPANT");
                continue;
            }
            if (!supported(argument, mention)) {
                uncertain.add("UNCERTAIN_PARTICIPANT");
            }
            if (mention.resolution() == Resolution.UNRESOLVED) {
                uncertain.add("UNRESOLVED_PRONOUN");
            }
            Sentence sentence = analysis.document().sentences().get(mention.sentence());
            Token head = sentence.token(mention.head());
            if (head.hasUpos("DET", "PUNCT")) {
                uncertain.add("DETERMINER_AS_PARTICIPANT");
            }
        }
        if (core.stream().filter(argument -> argument.role() == Role.ACTOR).count() > 1
                || core.stream().filter(argument -> argument.role() == Role.AFFECTED_OBJECT
                && !"obj+conj".equals(argument.relation())).count() > 1) {
            uncertain.add("ROLE_CONFLICT");
        }
        if (statement.voice() == Statement.Voice.ACTIVE && actor == null && invalid.isEmpty()) {
            uncertain.add("NO_ACTOR");
        }
        if (!invalid.isEmpty()) {
            invalid.addAll(uncertain);
            return new Assessment(Level.INVALID, List.copyOf(invalid));
        }
        return uncertain.isEmpty() ? new Assessment(Level.PLAUSIBLE, List.of())
                : new Assessment(Level.UNCERTAIN, List.copyOf(uncertain));
    }

    /**
     * Ob ein Kernbeteiligter gestützt ist: sichere Parserkante, Wortliste, oder Übernahme aus der Reihung ohne
     * Teilsatzgrenze (Regel E), solange der bekannte Kasus der Subjektrolle nicht widerspricht (ein übernommenes
     * Subjekt, das nur Akkusativ sein kann, ist nicht gestützt).
     */
    private static boolean supported(Argument argument, Mention mention) {
        return switch (argument.basis()) {
            case PARSER -> argument.certain();
            case LEXICON -> true;
            case COORDINATION -> !argument.relation().startsWith("nsubj")
                    || mention.cases().isEmpty() || mention.cases().contains("Nom");
            default -> false;
        };
    }
}
