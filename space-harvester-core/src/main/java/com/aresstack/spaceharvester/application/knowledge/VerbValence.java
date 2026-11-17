package com.aresstack.spaceharvester.application.knowledge;

import com.aresstack.spaceharvester.application.access.AccessContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Valenz aus dem Bestand (ADR 0032): je Verb-Grundform, welche Kernrollen in den gespeicherten Aussagen vorkommen und wie
 * oft (Rahmen). Zweck: Grundlage für spätere Hinweise auf unvollständige Aussagen und für das Verhalten der Begriffe
 * (FL5, FL7).
 *
 * <p>Reine Auswertung: rechnet nur auf Anfrage über die gespeicherten Analysen der Seiten, die der Aufrufer lesen darf
 * ({@link KnowledgeGraph#analysed(AccessContext)}), ohne Modell und ohne Auftrag im Hintergrund, abbrechbar. Sie ändert
 * nichts: keine Rückwirkung auf {@code StatementQuality}, Stufen oder gespeicherte Analysen.
 *
 * <p>Was zählt:
 * <ul>
 *   <li>nur Aussagen der Stufe {@link ParsedStatements.Quality#PLAUSIBLE} auf aktuellen (nicht veralteten) Analysen;</li>
 *   <li>keine Zustandsaussagen ({@link ParsedStatements.Statement#state()}): ihr Prädikat ist kein Verb;</li>
 *   <li>Aktiv und Passiv getrennt (im Passiv fehlt der Handelnde regelmäßig);</li>
 *   <li>Aussagen aus der Vorstufe „Sätze vereinfachen“ ({@link ParsedStatements.Origin#VORSTUFE}) getrennt vom
 *       Original, je eigener Rahmen;</li>
 *   <li>Kernrollen Handelnder, Betroffenes, Empfänger, Präpositionalobjekt (mit Präposition); sicher heißt
 *       Parserkante oder Wortliste und sicher angebunden, alle übrigen stehen getrennt als heuristisch.</li>
 * </ul>
 */
public final class VerbValence {

    /** Rollen, die einen Rahmen bilden. */
    private static final Set<ParsedStatements.Role> FRAME_ROLES = Set.of(ParsedStatements.Role.ACTOR,
            ParsedStatements.Role.AFFECTED_OBJECT, ParsedStatements.Role.RECIPIENT,
            ParsedStatements.Role.PREPOSITIONAL_OBJECT);
    /** Belegstellen je Rahmen höchstens. */
    public static final int MAX_EVIDENCE = 3;

    private VerbValence() {
    }

    /**
     * Ergebnis.
     *
     * @param verbs              Verben nach Häufigkeit absteigend, dann Grundform
     * @param pages              ausgewertete Seiten und Dokumente
     * @param stalePages         übersprungene veraltete Analysen
     * @param statements         gezählte Aussagen
     * @param notPlausible       übersprungene Aussagen unter PLAUSIBLE
     * @param states             übersprungene Zustandsaussagen
     */
    public record Result(List<Verb> verbs, int pages, int stalePages, int statements, int notPlausible, int states) {

        public Result {
            verbs = List.copyOf(verbs);
        }

        /** Das Verb mit dieser Grundform oder {@code null}. */
        public Verb verb(String lemma) {
            return verbs.stream().filter(v -> v.lemma().equals(lemma)).findFirst().orElse(null);
        }
    }

    /**
     * Ein Verb mit seinen Rahmen.
     *
     * @param lemma  Grundform in Kleinschreibung
     * @param count  gezählte Aussagen
     * @param frames Rahmen nach Häufigkeit absteigend
     */
    public record Verb(String lemma, int count, List<Frame> frames) {

        public Verb {
            frames = List.copyOf(frames);
        }
    }

    /**
     * Ein Rahmen: welche Kernrollen eine Aussage des Verbs besetzt.
     *
     * @param voice      Aktiv oder Passiv
     * @param origin     Original oder Vorstufe
     * @param roles      sicher besetzte Rollen ({@code ACTOR}, {@code AFFECTED_OBJECT}, {@code RECIPIENT},
     *                   {@code PREPOSITIONAL_OBJECT:auf}), leer = keine
     * @param heuristic  nur heuristisch oder unsicher besetzte Rollen, getrennt
     * @param count      Aussagen mit genau diesem Rahmen
     * @param evidence   bis zu {@link #MAX_EVIDENCE} Aussage-IDs als Belege
     */
    public record Frame(ParsedStatements.Voice voice, ParsedStatements.Origin origin, SortedSet<String> roles,
                        SortedSet<String> heuristic, int count, List<String> evidence) {

        public Frame {
            roles = java.util.Collections.unmodifiableSortedSet(new TreeSet<>(roles));
            heuristic = java.util.Collections.unmodifiableSortedSet(new TreeSet<>(heuristic));
            evidence = List.copyOf(evidence);
        }
    }

    /** Über alle Seiten, die {@code caller} lesen darf. */
    public static Result of(KnowledgeGraph graph, AccessContext caller, BooleanSupplier cancelled) {
        return of(graph.analysed(caller), cancelled);
    }

    /**
     * Über die gegebenen sichtbaren Seiten.
     *
     * @throws CancellationException sobald {@code cancelled} zutrifft (je Seite geprüft)
     */
    public static Result of(Collection<KnowledgeGraph.AnalysedPage> pages, BooleanSupplier cancelled) {
        Objects.requireNonNull(cancelled, "cancelled");
        Map<String, Map<FrameKey, Counter>> byVerb = new TreeMap<>();
        int counted = 0;
        int stale = 0;
        int statements = 0;
        int notPlausible = 0;
        int states = 0;
        for (KnowledgeGraph.AnalysedPage page : pages) {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException();
            }
            if (page.page().state() != PageStatements.State.CURRENT) {
                stale++;
                continue;
            }
            counted++;
            for (ParsedStatements.Statement statement : page.analysis().result().statements()) {
                if (statement.state()) {
                    states++;
                    continue;
                }
                if (statement.effectiveQuality() != ParsedStatements.Quality.PLAUSIBLE) {
                    notPlausible++;
                    continue;
                }
                String lemma = statement.predicateLemma() == null ? statement.predicateText()
                        : statement.predicateLemma();
                lemma = lemma.toLowerCase(Locale.ROOT);
                SortedSet<String> roles = new TreeSet<>();
                SortedSet<String> heuristic = new TreeSet<>();
                for (ParsedStatements.Argument argument : statement.arguments()) {
                    if (!FRAME_ROLES.contains(argument.role())) {
                        continue;
                    }
                    String name = argument.role() == ParsedStatements.Role.PREPOSITIONAL_OBJECT
                            && argument.marker() != null
                            ? argument.role().name() + ":" + argument.marker().toLowerCase(Locale.ROOT)
                            : argument.role().name();
                    boolean certain = argument.certain() && (argument.basis() == ParsedStatements.Basis.PARSER
                            || argument.basis() == ParsedStatements.Basis.LEXICON);
                    (certain ? roles : heuristic).add(name);
                }
                heuristic.removeAll(roles);
                FrameKey key = new FrameKey(statement.voice(), statement.derivation().origin(), roles, heuristic);
                Counter counter = byVerb.computeIfAbsent(lemma, l -> new LinkedHashMap<>())
                        .computeIfAbsent(key, k -> new Counter());
                counter.count++;
                if (counter.evidence.size() < MAX_EVIDENCE) {
                    counter.evidence.add(statement.id());
                }
                statements++;
            }
        }
        List<Verb> verbs = new ArrayList<>();
        byVerb.forEach((lemma, frames) -> {
            List<Frame> list = new ArrayList<>();
            frames.forEach((key, counter) -> list.add(new Frame(key.voice(), key.origin(), key.roles(),
                    key.heuristic(), counter.count, counter.evidence)));
            list.sort(Comparator.comparingInt(Frame::count).reversed());
            verbs.add(new Verb(lemma, list.stream().mapToInt(Frame::count).sum(), list));
        });
        verbs.sort(Comparator.comparingInt(Verb::count).reversed().thenComparing(Verb::lemma));
        return new Result(verbs, counted, stale, statements, notPlausible, states);
    }

    private record FrameKey(ParsedStatements.Voice voice, ParsedStatements.Origin origin, SortedSet<String> roles,
                            SortedSet<String> heuristic) {
    }

    private static final class Counter {
        int count;
        final List<String> evidence = new ArrayList<>();
    }
}
