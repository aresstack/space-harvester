package com.aresstack.spaceharvester.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessDeniedException;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.knowledge.AnalyzerComparison.Agreement;
import com.aresstack.spaceharvester.application.knowledge.AnalyzerComparison.Comparison;
import com.aresstack.spaceharvester.application.knowledge.AnalyzerComparison.Difference;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Argument;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Basis;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Mention;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.MentionKind;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Priority;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Role;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Sentence;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Statement;
import com.aresstack.spaceharvester.application.query.CacheMissException;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * K2: Abgleich der beiden Aussagenanalysatoren an erfundenen Aussagen. Text der Seite (Spannen):
 * „Die Prüfstelle bewertet den Antrag. Das Amt versendet den Bescheid und archiviert die Akte. Der Bearbeiter legt die
 * Frist fest und prüft die Unterlagen des Bürgers.“ Beteiligte liegen an ihrer Stelle in diesem Text; „Text|Grundform“
 * gibt dem Parser eine Grundform.
 */
class AnalyzerComparisonTest {

    private static final AccessContext READER = AccessContext.technicalClient("leser");
    private static final PageKey PAGE = new PageKey("wiki", "7");
    private static final String SHA = "sha256:" + "b".repeat(64);
    private static final String TEXT = "Die Prüfstelle bewertet den Antrag. Das Amt versendet den Bescheid und "
            + "archiviert die Akte. Der Bearbeiter legt die Frist fest und prüft die Unterlagen des Bürgers.";

    private final Set<String> denied = new HashSet<>();
    private final AccessGuard access = new AccessGuard((context, scope, kind) ->
            kind == AccessKind.CONTENT && denied.contains(scope.pageId()) ? AccessDecision.DENY : AccessDecision.ALLOW);
    private final Map<AnalyzerChoice, KnowledgeGraph.AnalysedPage> stored = new EnumMap<>(AnalyzerChoice.class);
    private AnalyzerChoice selected = AnalyzerChoice.PARSER;
    private final PageSnapshot snapshot = mock(PageSnapshot.class);
    private final AnalyzerComparison comparison;

    AnalyzerComparisonTest() {
        when(snapshot.spaceKey()).thenReturn("DEV");
        when(snapshot.key()).thenReturn(PAGE);
        comparison = new AnalyzerComparison(access, key -> key.equals(PAGE) ? Optional.of(snapshot) : Optional.empty(),
                (caller, analyzer, key) -> key.equals(PAGE) ? Optional.ofNullable(stored.get(analyzer))
                        : Optional.empty(), () -> selected);
    }

    /** Eine Aussage: Prädikat an [start, end), Handelnde und Betroffene als Erwähnungen. */
    private record Spec(String id, int sentence, int start, int end, String lemma, List<String> actors,
                        List<String> affected, ParsedStatements.Origin origin) {

        Spec(String id, int sentence, int start, int end, String lemma, List<String> actors, List<String> affected) {
            this(id, sentence, start, end, lemma, actors, affected, ParsedStatements.Origin.ORIGINAL);
        }
    }

    private static Spec spec(String id, int sentence, int start, int end, String lemma, List<String> actors,
                             List<String> affected) {
        return new Spec(id, sentence, start, end, lemma, actors, affected);
    }

    private void store(AnalyzerChoice analyzer, PageStatements.State state, String revision,
                       List<Sentence> sentences, Spec... specs) {
        List<Mention> mentions = new ArrayList<>();
        List<Statement> statements = new ArrayList<>();
        for (Spec spec : specs) {
            List<Argument> arguments = new ArrayList<>();
            for (String actor : spec.actors()) {
                arguments.add(argument(Role.ACTOR, mention(mentions, analyzer, spec, actor)));
            }
            for (String affected : spec.affected()) {
                arguments.add(argument(Role.AFFECTED_OBJECT, mention(mentions, analyzer, spec, affected)));
            }
            Statement statement = new Statement(spec.id(), spec.sentence(), spec.start(), spec.end(), spec.lemma(),
                    spec.lemma(), ParsedStatements.Voice.ACTIVE, ParsedStatements.Clause.MAIN, true, arguments,
                    Set.of(), 0, ParsedStatements.Quality.PLAUSIBLE, List.of(), ParsedStatements.Review.UNREVIEWED,
                    ParsedStatements.Features.UNKNOWN);
            if (spec.origin() != ParsedStatements.Origin.ORIGINAL) {
                statement = statement.withDerivation(new ParsedStatements.Derivation(spec.origin(),
                        ParsedStatements.StageFlag.NONE, "Korrigiert.", spec.origin(), "x"));
            }
            statements.add(statement);
        }
        ParsedStatements result = new ParsedStatements(sentences, mentions, statements, List.of(), List.of(), null);
        String analyzerId = analyzer == AnalyzerChoice.MODEL ? ModelStatementAnalyzer.ID : "maltparser";
        PageStatementAnalysis analysis = new PageStatementAnalysis(PAGE, "DEV", "Seite 7", revision, SHA,
                analyzerId, "1", Instant.EPOCH, result);
        stored.put(analyzer, new KnowledgeGraph.AnalysedPage(new KnowledgeGraph.PageRef(PAGE, "DEV", "Seite 7",
                state, state == PageStatements.State.CURRENT ? null : "text-changed"), analysis));
    }

    /**
     * Erwähnung an ihrer Stelle im Text. Der Parser trägt die Grundform des Kopfs, der KI-Analysator wie
     * {@code ModelStatementAnalyzer} keine Grundform und das letzte Wort der Gruppe als Kopf.
     */
    private static String mention(List<Mention> mentions, AnalyzerChoice analyzer, Spec spec, String participant) {
        String id = spec.id() + ".m" + mentions.size();
        String[] parts = participant.split("\\|");
        String text = parts[0];
        int start = TEXT.toLowerCase(java.util.Locale.ROOT).indexOf(text.toLowerCase(java.util.Locale.ROOT));
        assertThat(start).as("„%s“ steht im Text", text).isNotNegative();
        String[] words = text.split(" ");
        String head = words[words.length - 1];
        String lemma = analyzer == AnalyzerChoice.MODEL ? null : parts.length > 1 ? parts[1] : head;
        mentions.add(new Mention(id, spec.sentence(), start, start + text.length(), text, head, lemma,
                MentionKind.NOUN, false, Set.of()));
        return id;
    }

    private static Argument argument(Role role, String mention) {
        return new Argument(role, mention, "rel", null, Priority.CORE, Basis.PARSER, true);
    }

    /** Die Satzgrenzen des Parsers; der KI-Analysator zählt hier von 1 an, dieselben Spannen. */
    private static List<Sentence> parserSentences() {
        return List.of(new Sentence(0, 0, 36), new Sentence(1, 37, 91), new Sentence(2, 92, 164));
    }

    private static List<Sentence> modelSentences() {
        return List.of(new Sentence(1, 0, 36), new Sentence(2, 37, 91), new Sentence(3, 92, 164));
    }

    @Test
    void sameVerbAndParticipantsAgreeEvenWithOtherSentenceNumbers() {
        store(AnalyzerChoice.PARSER, PageStatements.State.CURRENT, "3", parserSentences(),
                spec("p1", 0, 15, 23, "bewerten", List.of("Prüfstelle"), List.of("Antrag")));
        store(AnalyzerChoice.MODEL, PageStatements.State.CURRENT, "3", modelSentences(),
                spec("m1", 1, 15, 23, "Bewerten", List.of("prüfstelle"), List.of("Antrag")));

        Comparison found = comparison.comparison(READER, PAGE, "p1");

        assertThat(found.agreement()).isEqualTo(Agreement.AGREES);
        assertThat(found.own().analyzer()).isEqualTo(AnalyzerChoice.PARSER);
        assertThat(found.other().analyzer()).isEqualTo(AnalyzerChoice.MODEL);
        assertThat(found.other().statementId()).isEqualTo("m1");
        assertThat(found.differences()).isEmpty();
    }

    @Test
    void anotherVerbOrActorDiffersAndNamesWhat() {
        store(AnalyzerChoice.PARSER, PageStatements.State.CURRENT, "3", parserSentences(),
                spec("p1", 0, 15, 23, "bewerten", List.of("Prüfstelle"), List.of("Antrag")),
                spec("p2", 1, 45, 54, "versenden", List.of("Amt"), List.of("Bescheid")));
        store(AnalyzerChoice.MODEL, PageStatements.State.CURRENT, "3", modelSentences(),
                spec("m1", 1, 15, 23, "prüfen", List.of("Prüfstelle"), List.of("Antrag")),
                spec("m2", 2, 45, 54, "versenden", List.of(), List.of("Bescheid")));

        Map<String, Comparison> all = comparison.comparisons(READER, PAGE);

        assertThat(all.get("p1").agreement()).isEqualTo(Agreement.DIFFERS);
        assertThat(all.get("p1").differences()).containsExactly(Difference.VERB);
        assertThat(all.get("p1").other().verb()).isEqualTo("prüfen");
        assertThat(all.get("p2").differences()).as("leer gegen nicht leer weicht ab")
                .containsExactly(Difference.ACTOR);
    }

    @Test
    void aStaleStoreOrAnotherRevisionIsNotComparable() {
        store(AnalyzerChoice.PARSER, PageStatements.State.CURRENT, "3", parserSentences(),
                spec("p1", 0, 15, 23, "bewerten", List.of("Prüfstelle"), List.of("Antrag")));
        store(AnalyzerChoice.MODEL, PageStatements.State.STALE, "3", modelSentences(),
                spec("m1", 1, 15, 23, "bewerten", List.of("Prüfstelle"), List.of("Antrag")));
        assertThat(comparison.comparison(READER, PAGE, "p1").agreement()).isEqualTo(Agreement.NOT_COMPARABLE);

        store(AnalyzerChoice.MODEL, PageStatements.State.CURRENT, "4", modelSentences(),
                spec("m1", 1, 15, 23, "bewerten", List.of("Prüfstelle"), List.of("Antrag")));
        Comparison other = comparison.comparison(READER, PAGE, "p1");
        assertThat(other.agreement()).as("andere Fassung der Seite").isEqualTo(Agreement.NOT_COMPARABLE);
        assertThat(other.own()).isNotNull();
        assertThat(other.other()).isNull();

        stored.remove(AnalyzerChoice.MODEL);
        assertThat(comparison.comparison(READER, PAGE, "p1").agreement()).isEqualTo(Agreement.NOT_COMPARABLE);
    }

    @Test
    void aSentenceWithoutStatementInTheOtherStoreIsNotComparable() {
        store(AnalyzerChoice.PARSER, PageStatements.State.CURRENT, "3", parserSentences(),
                spec("p1", 0, 15, 23, "bewerten", List.of("Prüfstelle"), List.of("Antrag")),
                spec("p2", 1, 45, 54, "versenden", List.of("Amt"), List.of("Bescheid")));
        store(AnalyzerChoice.MODEL, PageStatements.State.CURRENT, "3", modelSentences(),
                spec("m1", 1, 15, 23, "bewerten", List.of("Prüfstelle"), List.of("Antrag")));

        assertThat(comparison.comparison(READER, PAGE, "p2").agreement()).isEqualTo(Agreement.NOT_COMPARABLE);
        assertThat(comparison.comparison(READER, PAGE, "p1").agreement()).isEqualTo(Agreement.AGREES);
    }

    @Test
    void pairingIsOneToOneAndTheFallbackNeedsExactlyOneLeftOnEachSide() {
        // Zwei Aussagen im zweiten Satz gegen eine: nur die mit überlappendem Prädikat bekommt sie.
        store(AnalyzerChoice.PARSER, PageStatements.State.CURRENT, "3", parserSentences(),
                spec("p2", 1, 45, 54, "versenden", List.of("Amt"), List.of("Bescheid")),
                spec("p3", 1, 77, 87, "archivieren", List.of("Amt"), List.of("Akte")));
        store(AnalyzerChoice.MODEL, PageStatements.State.CURRENT, "3", modelSentences(),
                spec("m2", 2, 45, 54, "verschicken", List.of("Amt"), List.of("Bescheid")));

        Map<String, Comparison> all = comparison.comparisons(READER, PAGE);

        assertThat(all.get("p2").other().statementId()).isEqualTo("m2");
        assertThat(all.get("p2").differences()).containsExactly(Difference.VERB);
        assertThat(all.get("p3").agreement()).as("m2 ist schon vergeben").isEqualTo(Agreement.NOT_COMPARABLE);
        assertThat(all.get("p3").other()).isNull();
    }

    @Test
    void theFallbackPairsTheOnlyRemainingStatementsOfASentence() {
        store(AnalyzerChoice.PARSER, PageStatements.State.CURRENT, "3", parserSentences(),
                spec("p1", 0, 15, 23, "bewerten", List.of("Prüfstelle"), List.of("Antrag")));
        store(AnalyzerChoice.MODEL, PageStatements.State.CURRENT, "3", modelSentences(),
                spec("m1", 1, 4, 14, "einschätzen", List.of("Prüfstelle"), List.of("Antrag")));

        Comparison found = comparison.comparison(READER, PAGE, "p1");

        assertThat(found.agreement()).isEqualTo(Agreement.DIFFERS);
        assertThat(found.other().statementId()).isEqualTo("m1");
    }

    @Test
    void modelCopiesAreIgnoredAndStatementsOnlyInTheOtherStoreComeLast() {
        store(AnalyzerChoice.PARSER, PageStatements.State.CURRENT, "3", parserSentences(),
                spec("p1", 0, 15, 23, "bewerten", List.of("Prüfstelle"), List.of("Antrag")),
                new Spec("p1k", 0, 15, 23, "begutachten", List.of("Prüfstelle"), List.of("Antrag"),
                        ParsedStatements.Origin.MODELL));
        store(AnalyzerChoice.MODEL, PageStatements.State.CURRENT, "3", modelSentences(),
                spec("m1", 1, 15, 23, "bewerten", List.of("Prüfstelle"), List.of("Antrag")),
                spec("m3", 2, 77, 87, "archivieren", List.of("Amt"), List.of("Akte")));

        Map<String, Comparison> all = comparison.comparisons(READER, PAGE);

        assertThat(all).doesNotContainKey("p1k");
        assertThat(all.get("p1").agreement()).isEqualTo(Agreement.AGREES);
        assertThat(List.copyOf(all.keySet())).containsExactly("p1", "m3");
        assertThat(all.get("m3").own()).isNull();
        assertThat(all.get("m3").other().verb()).isEqualTo("archivieren");
        assertThat(all.get("m3").agreement()).isEqualTo(Agreement.NOT_COMPARABLE);
    }

    @Test
    void ownIsTheSelectedStore() {
        store(AnalyzerChoice.PARSER, PageStatements.State.CURRENT, "3", parserSentences(),
                spec("p1", 0, 15, 23, "bewerten", List.of("Prüfstelle"), List.of("Antrag")));
        store(AnalyzerChoice.MODEL, PageStatements.State.CURRENT, "3", modelSentences(),
                spec("m1", 1, 15, 23, "bewerten", List.of("Prüfstelle"), List.of("Antrag")));
        selected = AnalyzerChoice.MODEL;

        Comparison found = comparison.comparison(READER, PAGE, "m1");

        assertThat(found.own().analyzer()).isEqualTo(AnalyzerChoice.MODEL);
        assertThat(found.other().statementId()).isEqualTo("p1");
        assertThat(comparison.comparison(READER, PAGE, "p1").own()).as("keine Aussage der eingestellten Ablage")
                .isNull();
    }

    @Test
    void participantsAreMatchedBySpanNotByWord() {
        // Gebeugte Form gegen Grundform; Gruppe mit Genitivattribut (KI-Kopf „Bürgers“) gegen nur den Kopf.
        store(AnalyzerChoice.PARSER, PageStatements.State.CURRENT, "3", parserSentences(),
                spec("p4", 2, 131, 136, "prüfen", List.of("Bearbeiter"), List.of("Unterlagen|Unterlage")));
        store(AnalyzerChoice.MODEL, PageStatements.State.CURRENT, "3", modelSentences(),
                spec("m4", 3, 131, 136, "prüfen", List.of("Der Bearbeiter"),
                        List.of("die Unterlagen des Bürgers")));

        Comparison found = comparison.comparison(READER, PAGE, "p4");

        assertThat(found.agreement()).isEqualTo(Agreement.AGREES);
        assertThat(found.differences()).isEmpty();
        assertThat(found.own().affected()).as("Anzeige bleibt beim Wortlaut").containsExactly("unterlage");
        assertThat(found.other().affected()).containsExactly("bürgers");
    }

    @Test
    void aParticipantAtAnotherSpanDiffers() {
        store(AnalyzerChoice.PARSER, PageStatements.State.CURRENT, "3", parserSentences(),
                spec("p2", 1, 45, 54, "versenden", List.of("Amt"), List.of("Bescheid")));
        store(AnalyzerChoice.MODEL, PageStatements.State.CURRENT, "3", modelSentences(),
                spec("m2", 2, 45, 54, "versenden", List.of("Bescheid"), List.of("Bescheid")));

        assertThat(comparison.comparison(READER, PAGE, "p2").differences()).containsExactly(Difference.ACTOR);
    }

    @Test
    void separableVerbsWithDifferentLemmasCountAsAnotherVerb() {
        // Beobachtungspunkt des Architekten: „legt … fest“. Hält das heutige Verhalten fest; die Regel folgt.
        store(AnalyzerChoice.PARSER, PageStatements.State.CURRENT, "3", parserSentences(),
                spec("p5", 2, 107, 111, "festlegen", List.of("Bearbeiter"), List.of("Frist")));
        store(AnalyzerChoice.MODEL, PageStatements.State.CURRENT, "3", modelSentences(),
                spec("m5", 3, 107, 111, "legen", List.of("Der Bearbeiter"), List.of("die Frist")));

        Comparison found = comparison.comparison(READER, PAGE, "p5");

        assertThat(found.other().statementId()).isEqualTo("m5");
        assertThat(found.differences()).containsExactly(Difference.VERB);
    }

    @Test
    void withoutReadRightTheSameExceptionAsReadingTheStatements() {
        store(AnalyzerChoice.PARSER, PageStatements.State.CURRENT, "3", parserSentences(),
                spec("p1", 0, 15, 23, "bewerten", List.of("Prüfstelle"), List.of("Antrag")));
        denied.add("7");

        assertThatThrownBy(() -> comparison.comparisons(READER, PAGE)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> comparison.comparison(READER, new PageKey("wiki", "8"), "p1"))
                .isInstanceOf(CacheMissException.class);
    }
}
