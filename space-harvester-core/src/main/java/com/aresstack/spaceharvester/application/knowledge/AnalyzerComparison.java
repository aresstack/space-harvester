package com.aresstack.spaceharvester.application.knowledge;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.query.CacheMissException;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.terms.TermText;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Abgleich der beiden Aussagenanalysatoren einer Seite (K2, Fahrplan Analyse-Reiter): Stimmt eine Aussage des
 * eingestellten Analysators mit der Lesart des anderen überein? Gerechnet auf Abruf, nichts wird gespeichert, nichts
 * geht an ein Modell.
 *
 * <p>Verglichen wird nur, wenn beide Ablagen die Seite AKTUELL und in derselben Fassung (Revision und Eingabe-Hash)
 * haben, denn die Spannen müssen sich auf denselben Lesetext beziehen; und nur Aussagen der Herkunft
 * {@link ParsedStatements.Origin#ORIGINAL} (ADR 0032), sonst zählte eine Korrektur als Abweichung. Sätze werden über
 * die Überlappung ihrer Zeichenspannen zugeordnet, nicht über ihre Nummer. Die Zuordnung der Aussagen ist eins zu eins:
 * zuerst die beste Überlappung der Prädikate, dann dasselbe Lemma; die nächste Aussage im Satz nur, wenn im
 * überlappenden Satzpaar auf beiden Seiten genau eine Aussage übrig ist. Sonst {@link Agreement#NOT_COMPARABLE}.
 *
 * <p>Abweichend ({@link Agreement#DIFFERS}) ist ein anderes Verblemma (über {@link TermText#normalize}) oder ein
 * Handelnder oder Betroffener ohne Gegenstück: Beteiligte werden über ihre Zeichenspannen eins zu eins zugeordnet,
 * nicht über Wörter; eine leere gegen eine nicht leere Menge weicht ab. Die Wortlaute der {@link Reading} dienen nur
 * der Anzeige.
 */
public final class AnalyzerComparison {

    /** Ergebnis des Abgleichs einer Aussage. */
    public enum Agreement {
        /** Gleiches Verb, gleiche Handelnde und Betroffene. */
        AGREES,
        /** Verb, Handelnde oder Betroffene weichen ab ({@link Comparison#differences()}). */
        DIFFERS,
        /** Nicht vergleichbar: eine Ablage fehlt, ist veraltet, hat eine andere Fassung oder keine Gegenaussage. */
        NOT_COMPARABLE
    }

    /** Was abweicht. */
    public enum Difference {
        VERB,
        ACTOR,
        AFFECTED_OBJECT
    }

    /**
     * Die Lesart einer Aussage in einer Ablage.
     *
     * @param analyzer    die Ablage ({@link AnalyzerStores})
     * @param statementId Kennung der Aussage in ihrer Ablage
     * @param verb        Verblemma, normalisiert
     * @param actors      Handelnde, normalisiert
     * @param affected    Betroffene, normalisiert
     */
    public record Reading(AnalyzerChoice analyzer, String statementId, String verb, Set<String> actors,
                          Set<String> affected) {

        public Reading {
            Objects.requireNonNull(analyzer, "analyzer");
            Objects.requireNonNull(statementId, "statementId");
            actors = Collections.unmodifiableSet(new TreeSet<>(actors));
            affected = Collections.unmodifiableSet(new TreeSet<>(affected));
        }
    }

    /**
     * Abgleich einer Aussage.
     *
     * @param statementId Kennung der Aussage: in der eingestellten Ablage, oder, wenn sie nur in der anderen steht
     *                    ({@code own == null}), dort
     * @param own         Lesart der eingestellten Ablage oder {@code null}, wenn nur die andere eine Aussage hat
     * @param other       Lesart der anderen Ablage oder {@code null} ohne Gegenaussage
     * @param differences was abweicht, nur bei {@link Agreement#DIFFERS} nicht leer
     */
    public record Comparison(String statementId, Agreement agreement, Reading own, Reading other,
                             Set<Difference> differences) {

        public Comparison {
            Objects.requireNonNull(statementId, "statementId");
            Objects.requireNonNull(agreement, "agreement");
            differences = differences.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(differences));
        }
    }

    private final AccessGuard access;
    private final Function<PageKey, Optional<PageSnapshot>> snapshots;
    private final Pages graphs;
    private final Supplier<AnalyzerChoice> selected;

    /** Eine analysierte Seite aus der Ablage eines Analysators, mit Stand und Rechten wie {@link KnowledgeGraph#page}. */
    @FunctionalInterface
    public interface Pages {

        Optional<KnowledgeGraph.AnalysedPage> page(AccessContext caller, AnalyzerChoice analyzer, PageKey key);
    }

    /**
     * @param snapshots Seite im Bestand; ohne sie gibt es nichts zu vergleichen
     * @param graphs    je Ablage die Seite wie {@link KnowledgeGraph#page}
     * @param selected  der eingestellte Analysator ({@link AnalyzerStores#selected()})
     */
    public AnalyzerComparison(AccessGuard access, Function<PageKey, Optional<PageSnapshot>> snapshots,
                              Pages graphs, Supplier<AnalyzerChoice> selected) {
        this.access = Objects.requireNonNull(access, "access");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.graphs = Objects.requireNonNull(graphs, "graphs");
        this.selected = Objects.requireNonNull(selected, "selected");
    }

    /**
     * Der Abgleich einer Aussage der eingestellten Ablage; eine unbekannte Kennung ist {@link Agreement#NOT_COMPARABLE}
     * ohne Lesarten.
     *
     * @throws CacheMissException ohne die Seite im Bestand
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException ohne Leserecht auf die Seite, wie
     *                                                                              {@code StatementAnalysis#read}
     */
    public Comparison comparison(AccessContext caller, PageKey page, String statementId) {
        Objects.requireNonNull(statementId, "statementId");
        Comparison found = comparisons(caller, page).get(statementId);
        return found != null && found.own() != null ? found
                : new Comparison(statementId, Agreement.NOT_COMPARABLE, null, null, Set.of());
    }

    /**
     * Alle Abgleiche einer Seite in der Reihenfolge der Aussagen: jede Aussage der eingestellten Ablage, danach die
     * Aussagen, die nur in der anderen stehen ({@code own == null}, {@link Agreement#NOT_COMPARABLE}).
     *
     * @throws CacheMissException ohne die Seite im Bestand
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException ohne Leserecht auf die Seite
     */
    public Map<String, Comparison> comparisons(AccessContext caller, PageKey page) {
        Objects.requireNonNull(page, "page");
        PageSnapshot snapshot = snapshots.apply(page)
                .orElseThrow(() -> new CacheMissException(page.sourceId(), "Seite nicht im Bestand"));
        access.require(caller, ResourceScope.page(page.sourceId(), snapshot.spaceKey(), page.pageId()),
                AccessKind.CONTENT);
        AnalyzerChoice ownChoice = selected.get() == null ? AnalyzerChoice.PARSER : selected.get();
        AnalyzerChoice otherChoice = ownChoice == AnalyzerChoice.PARSER ? AnalyzerChoice.MODEL : AnalyzerChoice.PARSER;
        Optional<KnowledgeGraph.AnalysedPage> own = graphs.page(caller, ownChoice, page);
        Optional<KnowledgeGraph.AnalysedPage> other = graphs.page(caller, otherChoice, page);
        List<ParsedStatements.Statement> ownStatements = own.map(AnalyzerComparison::originals).orElse(List.of());
        List<ParsedStatements.Statement> otherStatements = other.map(AnalyzerComparison::originals)
                .orElse(List.of());
        Map<String, Comparison> result = new LinkedHashMap<>();
        if (own.isEmpty() || other.isEmpty() || !comparable(own.get(), other.get())) {
            ownStatements.forEach(statement -> result.put(statement.id(), new Comparison(statement.id(),
                    Agreement.NOT_COMPARABLE, reading(ownChoice, own.get().analysis(), statement), null, Set.of())));
            otherStatements.forEach(statement -> result.putIfAbsent(statement.id(), new Comparison(statement.id(),
                    Agreement.NOT_COMPARABLE, null, reading(otherChoice, other.get().analysis(), statement),
                    Set.of())));
            return result;
        }
        ParsedStatements ownResult = own.get().analysis().result();
        ParsedStatements otherResult = other.get().analysis().result();
        Map<String, ParsedStatements.Statement> pairs = pair(ownResult, ownStatements, otherResult, otherStatements);
        Set<String> used = new java.util.HashSet<>();
        for (ParsedStatements.Statement statement : ownStatements) {
            Reading mine = reading(ownChoice, own.get().analysis(), statement);
            ParsedStatements.Statement counterpart = pairs.get(statement.id());
            if (counterpart == null) {
                result.put(statement.id(), new Comparison(statement.id(), Agreement.NOT_COMPARABLE, mine, null,
                        Set.of()));
                continue;
            }
            used.add(counterpart.id());
            Reading theirs = reading(otherChoice, other.get().analysis(), counterpart);
            Set<Difference> differences = EnumSet.noneOf(Difference.class);
            if (!Objects.equals(mine.verb(), theirs.verb())) {
                differences.add(Difference.VERB);
            }
            if (!sameParticipants(statement, ownResult, counterpart, otherResult, ParsedStatements.Role.ACTOR)) {
                differences.add(Difference.ACTOR);
            }
            if (!sameParticipants(statement, ownResult, counterpart, otherResult,
                    ParsedStatements.Role.AFFECTED_OBJECT)) {
                differences.add(Difference.AFFECTED_OBJECT);
            }
            result.put(statement.id(), new Comparison(statement.id(), differences.isEmpty() ? Agreement.AGREES
                    : Agreement.DIFFERS, mine, theirs, differences));
        }
        for (ParsedStatements.Statement statement : otherStatements) {
            if (!used.contains(statement.id())) {
                result.putIfAbsent(statement.id(), new Comparison(statement.id(), Agreement.NOT_COMPARABLE, null,
                        reading(otherChoice, other.get().analysis(), statement), Set.of()));
            }
        }
        return result;
    }

    /** Beide aktuell und dieselbe Fassung der Seite. */
    private static boolean comparable(KnowledgeGraph.AnalysedPage own, KnowledgeGraph.AnalysedPage other) {
        return own.page().state() == PageStatements.State.CURRENT
                && other.page().state() == PageStatements.State.CURRENT
                && Objects.equals(own.analysis().revision(), other.analysis().revision())
                && Objects.equals(own.analysis().inputSha256(), other.analysis().inputSha256());
    }

    private static List<ParsedStatements.Statement> originals(KnowledgeGraph.AnalysedPage page) {
        return page.analysis().result().statements().stream()
                .filter(statement -> statement.derivation().origin() == ParsedStatements.Origin.ORIGINAL).toList();
    }

    /**
     * Eins-zu-eins-Zuordnung je überlappendem Satzpaar: Kandidatenpaare nach Überlappung der Prädikate, dann gleichem
     * Lemma, je Seite höchstens einmal vergeben; zuletzt die einzige übrige Aussage gegen die einzige übrige.
     */
    private static Map<String, ParsedStatements.Statement> pair(ParsedStatements ownResult,
                                                               List<ParsedStatements.Statement> own,
                                                               ParsedStatements otherResult,
                                                               List<ParsedStatements.Statement> other) {
        Map<Integer, ParsedStatements.Sentence> ownSentences = sentences(ownResult);
        Map<Integer, ParsedStatements.Sentence> otherSentences = sentences(otherResult);
        record Candidate(ParsedStatements.Statement mine, ParsedStatements.Statement theirs, int overlap,
                         boolean sameLemma) {
        }
        List<Candidate> candidates = new ArrayList<>();
        for (ParsedStatements.Statement mine : own) {
            ParsedStatements.Sentence a = ownSentences.get(mine.sentence());
            for (ParsedStatements.Statement theirs : other) {
                ParsedStatements.Sentence b = otherSentences.get(theirs.sentence());
                if (a == null || b == null || overlap(a.start(), a.end(), b.start(), b.end()) <= 0) {
                    continue;
                }
                int overlap = overlap(mine.start(), mine.end(), theirs.start(), theirs.end());
                boolean sameLemma = Objects.equals(verb(mine), verb(theirs));
                if (overlap > 0 || sameLemma) {
                    candidates.add(new Candidate(mine, theirs, overlap, sameLemma));
                }
            }
        }
        candidates.sort(Comparator.comparingInt((Candidate candidate) -> -candidate.overlap())
                .thenComparing(candidate -> !candidate.sameLemma())
                .thenComparingInt(candidate -> candidate.mine().start())
                .thenComparingInt(candidate -> candidate.theirs().start()));
        Map<String, ParsedStatements.Statement> pairs = new HashMap<>();
        Set<String> taken = new java.util.HashSet<>();
        for (Candidate candidate : candidates) {
            if (!pairs.containsKey(candidate.mine().id()) && !taken.contains(candidate.theirs().id())) {
                pairs.put(candidate.mine().id(), candidate.theirs());
                taken.add(candidate.theirs().id());
            }
        }
        // Rückfall: im überlappenden Satzpaar auf beiden Seiten genau eine übrige Aussage.
        for (ParsedStatements.Statement mine : own) {
            if (pairs.containsKey(mine.id())) {
                continue;
            }
            ParsedStatements.Sentence a = ownSentences.get(mine.sentence());
            if (a == null) {
                continue;
            }
            List<ParsedStatements.Statement> mineLeft = own.stream().filter(s -> !pairs.containsKey(s.id()))
                    .filter(s -> sameSentence(ownSentences.get(s.sentence()), a)).toList();
            List<ParsedStatements.Statement> theirsLeft = other.stream().filter(s -> !taken.contains(s.id()))
                    .filter(s -> {
                        ParsedStatements.Sentence b = otherSentences.get(s.sentence());
                        return b != null && overlap(a.start(), a.end(), b.start(), b.end()) > 0;
                    }).toList();
            if (mineLeft.size() == 1 && theirsLeft.size() == 1) {
                pairs.put(mine.id(), theirsLeft.getFirst());
                taken.add(theirsLeft.getFirst().id());
            }
        }
        return pairs;
    }

    private static boolean sameSentence(ParsedStatements.Sentence candidate, ParsedStatements.Sentence sentence) {
        return candidate != null && candidate.start() == sentence.start() && candidate.end() == sentence.end();
    }

    private static Map<Integer, ParsedStatements.Sentence> sentences(ParsedStatements result) {
        Map<Integer, ParsedStatements.Sentence> byIndex = new HashMap<>();
        result.sentences().forEach(sentence -> byIndex.put(sentence.index(), sentence));
        return byIndex;
    }

    private static int overlap(int start, int end, int otherStart, int otherEnd) {
        return Math.min(end, otherEnd) - Math.max(start, otherStart);
    }

    private static Reading reading(AnalyzerChoice analyzer, PageStatementAnalysis analysis,
                                   ParsedStatements.Statement statement) {
        Map<String, ParsedStatements.Mention> mentions = new HashMap<>();
        analysis.result().mentions().forEach(mention -> mentions.put(mention.id(), mention));
        return new Reading(analyzer, statement.id(), verb(statement),
                participants(statement, ParsedStatements.Role.ACTOR, mentions),
                participants(statement, ParsedStatements.Role.AFFECTED_OBJECT, mentions));
    }

    /**
     * Beteiligte einer Rolle stimmen überein, wenn sich jede Zeichenspanne eins zu eins mit einer Spanne derselben
     * Rolle auf der anderen Seite überlappt, beste Überlappung zuerst. Nicht über Wörter: Der Parser liefert die
     * Grundform des Kopfs, der KI-Analysator das letzte Wort der Gruppe („des Antrags“, „der Antrag des Bürgers“).
     * {@link #comparable} sichert dieselbe Fassung, die Spannen beziehen sich also auf denselben Lesetext. Ohne
     * auflösbare Erwähnung zählt der normalisierte Wortlaut.
     */
    private static boolean sameParticipants(ParsedStatements.Statement own, ParsedStatements ownResult,
                                            ParsedStatements.Statement other, ParsedStatements otherResult,
                                            ParsedStatements.Role role) {
        List<Part> mine = parts(own, ownResult, role);
        List<Part> theirs = parts(other, otherResult, role);
        if (mine.size() != theirs.size()) {
            return false;
        }
        record Candidate(int mine, int theirs, int overlap) {
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < mine.size(); i++) {
            for (int j = 0; j < theirs.size(); j++) {
                int overlap = mine.get(i).overlap(theirs.get(j));
                if (overlap > 0) {
                    candidates.add(new Candidate(i, j, overlap));
                }
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::overlap).reversed()
                .thenComparingInt(Candidate::mine).thenComparingInt(Candidate::theirs));
        boolean[] mineUsed = new boolean[mine.size()];
        boolean[] theirsUsed = new boolean[theirs.size()];
        int matched = 0;
        for (Candidate candidate : candidates) {
            if (!mineUsed[candidate.mine()] && !theirsUsed[candidate.theirs()]) {
                mineUsed[candidate.mine()] = true;
                theirsUsed[candidate.theirs()] = true;
                matched++;
            }
        }
        return matched == mine.size();
    }

    /** Spanne eines Beteiligten; ohne Erwähnung nur sein normalisierter Wortlaut. */
    private record Part(int start, int end, String text) {

        int overlap(Part other) {
            if (start >= 0 && other.start >= 0) {
                return AnalyzerComparison.overlap(start, end, other.start, other.end);
            }
            return !text.isEmpty() && text.equals(other.text) ? 1 : 0;
        }
    }

    private static List<Part> parts(ParsedStatements.Statement statement, ParsedStatements result,
                                    ParsedStatements.Role role) {
        Map<String, ParsedStatements.Mention> mentions = new HashMap<>();
        result.mentions().forEach(mention -> mentions.put(mention.id(), mention));
        List<Part> parts = new ArrayList<>();
        for (ParsedStatements.Argument argument : statement.arguments()) {
            if (argument.role() != role) {
                continue;
            }
            ParsedStatements.Mention mention = mentions.get(argument.mention());
            if (mention != null && mention.end() > mention.start()) {
                parts.add(new Part(mention.start(), mention.end(), TermText.normalize(mention.text())));
            } else {
                parts.add(new Part(-1, -1, TermText.normalize(mention == null ? argument.mention()
                        : mention.text())));
            }
        }
        return parts;
    }

    private static String verb(ParsedStatements.Statement statement) {
        String lemma = statement.predicateLemma();
        return TermText.normalize(lemma == null || lemma.isBlank() ? statement.predicateText() : lemma);
    }

    private static Set<String> participants(ParsedStatements.Statement statement, ParsedStatements.Role role,
                                            Map<String, ParsedStatements.Mention> mentions) {
        Set<String> found = new TreeSet<>();
        for (ParsedStatements.Argument argument : statement.arguments()) {
            if (argument.role() != role) {
                continue;
            }
            ParsedStatements.Mention mention = mentions.get(argument.mention());
            String text = mention == null ? argument.mention()
                    : mention.key() == null || mention.key().isBlank() ? mention.text() : mention.key();
            String normalized = TermText.normalize(text);
            if (!normalized.isEmpty()) {
                found.add(normalized);
            }
        }
        return found;
    }
}
