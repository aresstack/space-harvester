package com.aresstack.spaceharvester.application.standing;

import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Mögliche Widersprüche und Ablösungen in einer Status-quo-Sicht (ADR 0032, FL6). Reine Auswertung über die Einträge
 * der Sicht, ohne zweiten Durchlauf über die Analysen; speichert nichts und ändert nie einen Stand. Setzt ein Mensch
 * eine der Aussagen auf überholt, verschwindet der Hinweis von selbst.
 *
 * <p><b>Möglicher Widerspruch</b>: sichere Aussagen derselben {@link Situation Sachlage}, die beide gelten, mit gleicher
 * bestimmter Verbindlichkeit, eine bejaht, eine verneint. <b>Ablösung</b> (nur ein Verweis, kein Konflikt): eine
 * geltende und eine überholte Aussage, entweder derselben Sachlage mit anderer Verneinung
 * ({@link ReplacementKind#NEGATION}) oder auf derselben Seite mit gleichem Prädikat und Betroffenem, aber anderem
 * Handelnden ({@link ReplacementKind#ACTOR}).
 *
 * <p>Nie verglichen: Aussagen aus veralteten Analysen, unsichere Aussagen, Aussagen ohne vollständige Sachlage, Zustandswechsel, Nebensätze, Aussagen mit
 * Vorstufen-Kennzeichen Bedingung (dort hängt die Verneinung an einer Bedingung) und zwei Aussagen aus demselben Satz
 * derselben Seite (Original und Vorstufe beschreiben denselben Satz).
 *
 * @param contradictions mögliche Widersprüche, je Sachlage und Verbindlichkeit einer, meiste Seiten zuerst
 * @param replacements   Ablösungen, je Art und Sachlage eine, meiste Seiten zuerst
 */
public record StatementConflicts(List<PossibleContradiction> contradictions, List<Replacement> replacements) {

    public StatementConflicts {
        contradictions = List.copyOf(contradictions);
        replacements = List.copyOf(replacements);
    }

    /** Art einer Ablösung. */
    public enum ReplacementKind {
        /** Dieselbe Sachlage, früher anders verneint als jetzt. */
        NEGATION,
        /** Dieselbe Seite, gleiches Prädikat und Betroffenes, früher ein anderer Handelnder. */
        ACTOR
    }

    /**
     * Ein möglicher Widerspruch: ein Hinweis für den Menschen.
     *
     * @param affirmed bejahte Aussagen, mindestens eine
     * @param negated  verneinte Aussagen, mindestens eine
     */
    public record PossibleContradiction(Situation situation, ParsedStatements.Modality modality,
                                        List<StatusQuoEntry> affirmed, List<StatusQuoEntry> negated) {

        public PossibleContradiction {
            Objects.requireNonNull(situation, "situation");
            Objects.requireNonNull(modality, "modality");
            affirmed = List.copyOf(affirmed);
            negated = List.copyOf(negated);
        }

        /** Beteiligte Seiten bzw. Dokumente in der Reihenfolge der Sicht. */
        public List<KnowledgeGraph.PageRef> pages() {
            return StatementConflicts.pages(affirmed, negated);
        }
    }

    /**
     * Eine Ablösung: Die geltenden Aussagen verweisen auf die überholten. Kein Konflikt, keine Zahl im Stand.
     *
     * @param predicate Grundform des Prädikats
     * @param object    Schlüssel des Betroffenen oder leer
     * @param current   geltende sichere Aussagen, mindestens eine
     * @param outdated  überholte sichere Aussagen, mindestens eine
     */
    public record Replacement(ReplacementKind kind, String predicate, String object, List<StatusQuoEntry> current,
                              List<StatusQuoEntry> outdated) {

        public Replacement {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(predicate, "predicate");
            Objects.requireNonNull(object, "object");
            current = List.copyOf(current);
            outdated = List.copyOf(outdated);
        }

        /** Beteiligte Seiten bzw. Dokumente in der Reihenfolge der Sicht. */
        public List<KnowledgeGraph.PageRef> pages() {
            return StatementConflicts.pages(current, outdated);
        }
    }

    /** Wertet die Sicht aus. */
    public static StatementConflicts of(StatusQuoView view) {
        Objects.requireNonNull(view, "view");
        Map<Situation, List<StatusQuoEntry>> current = new LinkedHashMap<>();
        Map<Situation, List<StatusQuoEntry>> outdated = new LinkedHashMap<>();
        Map<List<Object>, List<StatusQuoEntry>> currentActions = new LinkedHashMap<>();
        Map<List<Object>, List<StatusQuoEntry>> outdatedActions = new LinkedHashMap<>();
        for (StatusQuoEntry entry : view.entries()) {
            if (!comparable(entry)) {
                continue;
            }
            Standing standing = entry.standing().standing();
            boolean gilt = standing == Standing.GILT;
            if (!gilt && standing != Standing.UEBERHOLT) {
                continue;
            }
            Situation situation = entry.situation();
            (gilt ? current : outdated).computeIfAbsent(situation, ignored -> new ArrayList<>()).add(entry);
            if (situation.kind() == StatementKind.ACTION && !situation.object().isEmpty()) {
                List<Object> place = List.of(entry.page().key(), String.valueOf(entry.page().document()),
                        situation.predicate(), situation.object());
                (gilt ? currentActions : outdatedActions).computeIfAbsent(place, ignored -> new ArrayList<>())
                        .add(entry);
            }
        }
        List<PossibleContradiction> contradictions = new ArrayList<>();
        current.forEach((situation, entries) -> {
            Map<ParsedStatements.Modality, List<StatusQuoEntry>> byModality = new LinkedHashMap<>();
            entries.forEach(entry -> byModality.computeIfAbsent(entry.modality(), ignored -> new ArrayList<>())
                    .add(entry));
            byModality.forEach((modality, group) -> {
                if (modality == ParsedStatements.Modality.UNKNOWN) {
                    return;
                }
                List<StatusQuoEntry> affirmed = with(group, ParsedStatements.Negation.AFFIRMED);
                List<StatusQuoEntry> negated = with(group, ParsedStatements.Negation.NEGATED);
                pair(affirmed, negated, StatusQuoEntry::negation, (a, b) ->
                        contradictions.add(new PossibleContradiction(situation, modality, a, b)));
            });
        });
        List<Replacement> replacements = new ArrayList<>();
        current.forEach((situation, now) -> {
            List<StatusQuoEntry> before = outdated.get(situation);
            if (before != null) {
                pair(determined(now), determined(before), StatusQuoEntry::negation, (a, b) ->
                        replacements.add(new Replacement(ReplacementKind.NEGATION, situation.predicate(),
                                situation.object(), a, b)));
            }
        });
        currentActions.forEach((place, now) -> {
            List<StatusQuoEntry> before = outdatedActions.get(place);
            if (before != null) {
                pair(now, before, entry -> entry.situation().subject(), (a, b) ->
                        replacements.add(new Replacement(ReplacementKind.ACTOR, (String) place.get(2),
                                (String) place.get(3), a, b)));
            }
        });
        contradictions.sort(Comparator.comparingInt((PossibleContradiction c) -> -c.pages().size()));
        replacements.sort(Comparator.comparingInt((Replacement r) -> -r.pages().size()));
        return new StatementConflicts(contradictions, replacements);
    }

    /** Ob die Aussage verglichen wird. */
    static boolean comparable(StatusQuoEntry entry) {
        return !entry.uncertain() && !entry.analysisStale() && entry.situation() != null && !entry.stateChange()
                && entry.statement().clause() != ParsedStatements.Clause.SUBORDINATE
                && entry.stageFlag() != ParsedStatements.StageFlag.CONDITION;
    }

    /**
     * Behält aus beiden Seiten nur Einträge mit mindestens einem Partner auf der anderen Seite: andere Klasse
     * ({@code kind}) und nicht derselbe Satz derselben Seite; meldet, wenn etwas bleibt. Gezählt statt paarweise
     * verglichen, damit große Gruppen linear bleiben.
     */
    private static void pair(List<StatusQuoEntry> left, List<StatusQuoEntry> right,
                             Function<StatusQuoEntry, Object> kind, BiConsumer<List<StatusQuoEntry>,
            List<StatusQuoEntry>> found) {
        if (left.isEmpty() || right.isEmpty()) {
            return;
        }
        List<StatusQuoEntry> keptLeft = partnered(left, right, kind);
        if (!keptLeft.isEmpty()) {
            found.accept(keptLeft, partnered(right, left, kind));
        }
    }

    private static List<StatusQuoEntry> partnered(List<StatusQuoEntry> mine, List<StatusQuoEntry> theirs,
                                                  Function<StatusQuoEntry, Object> kind) {
        Map<Object, Integer> byKind = new HashMap<>();
        Map<Object, Integer> bySentence = new HashMap<>();
        Map<List<Object>, Integer> byBoth = new HashMap<>();
        for (StatusQuoEntry other : theirs) {
            Object k = kind.apply(other);
            Object sentence = sentence(other);
            byKind.merge(k, 1, Integer::sum);
            bySentence.merge(sentence, 1, Integer::sum);
            byBoth.merge(Arrays.asList(k, sentence), 1, Integer::sum);
        }
        List<StatusQuoEntry> kept = new ArrayList<>();
        for (StatusQuoEntry entry : mine) {
            Object k = kind.apply(entry);
            Object sentence = sentence(entry);
            int partners = theirs.size() - byKind.getOrDefault(k, 0) - bySentence.getOrDefault(sentence, 0)
                    + byBoth.getOrDefault(Arrays.asList(k, sentence), 0);
            if (partners > 0) {
                kept.add(entry);
            }
        }
        return kept;
    }

    /** Derselbe Satz derselben Seite bzw. desselben Dokuments. */
    private static Object sentence(StatusQuoEntry entry) {
        return Arrays.asList(entry.page().key(), entry.page().document(), entry.statement().sentence());
    }

    private static List<StatusQuoEntry> with(List<StatusQuoEntry> entries, ParsedStatements.Negation negation) {
        return entries.stream().filter(entry -> entry.negation() == negation).toList();
    }

    private static List<StatusQuoEntry> determined(List<StatusQuoEntry> entries) {
        return entries.stream().filter(entry -> entry.negation() != ParsedStatements.Negation.UNKNOWN).toList();
    }

    private static List<KnowledgeGraph.PageRef> pages(List<StatusQuoEntry> first, List<StatusQuoEntry> second) {
        Set<KnowledgeGraph.PageRef> pages = new LinkedHashSet<>();
        first.forEach(entry -> pages.add(entry.page()));
        second.forEach(entry -> pages.add(entry.page()));
        return List.copyOf(pages);
    }
}
