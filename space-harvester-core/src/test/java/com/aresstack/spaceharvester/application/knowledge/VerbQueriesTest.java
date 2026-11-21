package com.aresstack.spaceharvester.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Quality;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Verzeichnis der Verben (K1) an erfundenen Aussagen. */
class VerbQueriesTest {

    private static int ids;

    private static ParsedStatements.Statement statement(String text, String lemma, Quality quality,
                                                        ParsedStatements.Review review, Set<String> findings) {
        return new ParsedStatements.Statement("doc#s1.v" + (ids++), ids, 3, 9, text, lemma,
                ParsedStatements.Voice.ACTIVE, ParsedStatements.Clause.MAIN, true, List.of(), findings, 0, quality,
                List.of(), review, ParsedStatements.Features.UNKNOWN);
    }

    private static ParsedStatements.Statement verb(String text, String lemma) {
        return statement(text, lemma, Quality.PLAUSIBLE, ParsedStatements.Review.UNREVIEWED, Set.of());
    }

    private static KnowledgeGraph.AnalysedPage page(String id, PageStatements.State state,
                                                   ParsedStatements.Statement... statements) {
        PageKey key = new PageKey("wiki", id);
        ParsedStatements result = new ParsedStatements(List.of(), List.of(), List.of(statements), List.of(),
                List.of(), null);
        PageStatementAnalysis analysis = new PageStatementAnalysis(key, "DEV", "Seite " + id, "1",
                "sha256:" + "a".repeat(64), "fake", "1", Instant.EPOCH, result);
        return new KnowledgeGraph.AnalysedPage(new KnowledgeGraph.PageRef(key, "DEV", "Seite " + id, state,
                state == PageStatements.State.CURRENT ? null : "text-changed"), analysis);
    }

    private final List<KnowledgeGraph.AnalysedPage> pages = List.of(
            page("1", PageStatements.State.CURRENT, verb("prüft", "prüfen"), verb("prüfte", "Prüfen"),
                    verb("bucht", "buchen"), verb("verschickt", null),
                    statement("ist", "defekt", Quality.PLAUSIBLE, ParsedStatements.Review.UNREVIEWED,
                            Set.of(ParsedStatements.Statement.STATE_PROPERTY)),
                    statement("zahlt", "zahlen", Quality.INVALID, ParsedStatements.Review.UNREVIEWED, Set.of()),
                    statement("lehnt", "ablehnen", Quality.PLAUSIBLE, ParsedStatements.Review.REJECTED, Set.of())),
            page("2", PageStatements.State.CURRENT, verb("prüfen", "prüfen"), verb("erfasst", "erfassen")),
            page("3", PageStatements.State.STALE, verb("sendet", "senden")),
            page("9", PageStatements.State.CURRENT, verb("prüft", "prüfen")));

    private final VerbQueries queries = new VerbQueries(caller -> pages);

    private static PageSet within(String... ids) {
        return PageSet.of(Map.of("wiki", List.of(ids)));
    }

    @Test
    void countsStatementsAndPagesPerLemmaOnlyInsideTheSetAndOnlyCountedVerbs() {
        VerbQueries.VerbList list = queries.verbs(null, within("1", "2", "3", "4"), null, 50, null);

        assertThat(list.verbs()).extracting(VerbQueries.Verb::lemma, VerbQueries.Verb::statements,
                        VerbQueries.Verb::pages, VerbQueries.Verb::fromText)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("prüfen", 3, 2, false),
                        org.assertj.core.groups.Tuple.tuple("buchen", 1, 1, false),
                        org.assertj.core.groups.Tuple.tuple("erfassen", 1, 1, false),
                        org.assertj.core.groups.Tuple.tuple("verschickt", 1, 1, true));
        assertThat(list.analysedPages()).isEqualTo(2);
        assertThat(list.stalePages()).isEqualTo(1);
        assertThat(list.unanalysed()).as("Seite 4 hat keine Analyse").isEqualTo(1);
        assertThat(list.total()).isEqualTo(4);
        assertThat(list.nextCursor()).isNull();
    }

    @Test
    void filtersAndPages() {
        VerbQueries.VerbList first = queries.verbs(null, within("1", "2"), null, 2, null);
        assertThat(first.verbs()).extracting(VerbQueries.Verb::lemma).containsExactly("prüfen", "buchen");
        VerbQueries.VerbList second = queries.verbs(null, within("1", "2"), null, 2, first.nextCursor());
        assertThat(second.verbs()).extracting(VerbQueries.Verb::lemma).containsExactly("erfassen", "verschickt");
        assertThat(second.nextCursor()).isNull();

        assertThat(queries.verbs(null, within("1", "2"), " FASS", 10, null).verbs())
                .extracting(VerbQueries.Verb::lemma).containsExactly("erfassen");
    }

    @Test
    void occurrencesNameThePageAndTheStatement() {
        List<VerbQueries.Occurrence> found = queries.occurrences(null, within("1", "2", "3"), "prüfen", false, 10);
        assertThat(found).extracting(o -> o.page().key().pageId(), VerbQueries.Occurrence::predicateText)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("1", "prüft"),
                        org.assertj.core.groups.Tuple.tuple("1", "prüfte"),
                        org.assertj.core.groups.Tuple.tuple("2", "prüfen"));
        assertThat(queries.occurrences(null, within("1"), "verschickt", true, 10)).hasSize(1);
        assertThat(queries.occurrences(null, within("1"), "verschickt", false, 10)).isEmpty();
    }

    @Test
    void lemmaIsTrimmedLowerCaseAndFallsBackToText() {
        assertThat(VerbForms.lemma(verb("Prüft", " Prüfen "))).isEqualTo("prüfen");
        assertThat(VerbForms.lemma(verb("Verschickt", " "))).isEqualTo("verschickt");
        assertThat(VerbForms.fromText(verb("Verschickt", null))).isTrue();
    }

    @Test
    void onPageListsOwnVerbsWithTheirStatementsAndNothingWithoutAnalysis() {
        List<VerbQueries.PageVerb> verbs = queries.onPage(null, new PageKey("wiki", "1")).verbs();
        assertThat(verbs).extracting(v -> v.verb().lemma(), v -> v.verb().statements(), v -> v.occurrences().size())
                .containsExactly(org.assertj.core.groups.Tuple.tuple("prüfen", 2, 2),
                        org.assertj.core.groups.Tuple.tuple("buchen", 1, 1),
                        org.assertj.core.groups.Tuple.tuple("verschickt", 1, 1));
        VerbQueries.PageVerbs stale = queries.onPage(null, new PageKey("wiki", "3"));
        assertThat(stale.stale()).as("veraltet ist nicht dasselbe wie keine Verben").isTrue();
        assertThat(stale.verbs()).isEmpty();
        assertThat(queries.onPage(null, new PageKey("wiki", "1")).stale()).isFalse();
        assertThat(queries.onPage(null, new PageKey("wiki", "4"))).as("ohne Analyse").isNull();
    }

    @Test
    void withoutSetAllVisiblePagesCount() {
        VerbQueries.VerbList all = queries.verbs(null, null, "prüf", 10, null);
        assertThat(all.verbs()).extracting(VerbQueries.Verb::statements).containsExactly(4);
        assertThat(all.unanalysed()).isZero();
    }

    @Test
    void onlyOriginalStatementsCountNotTheirStageOrModelCopies() {
        ParsedStatements.Statement original = verb("prüft", "prüfen");
        ParsedStatements.Statement model = verb("prüft", "prüfen").withDerivation(new ParsedStatements.Derivation(
                ParsedStatements.Origin.MODELL, ParsedStatements.StageFlag.NONE, "Das Amt prüft den Antrag.",
                ParsedStatements.Origin.MODELL, original.id()));
        ParsedStatements.Statement stage = verb("prüft", "prüfen").withDerivation(ParsedStatements.Derivation
                .vorstufe(ParsedStatements.StageFlag.FACT, "Das Amt prüft."));
        VerbQueries corrected = new VerbQueries(caller -> List.of(page("5", PageStatements.State.CURRENT, original,
                model, stage)));

        assertThat(corrected.verbs(null, null, null, 10, null).verbs()).singleElement()
                .satisfies(verb -> assertThat(verb.statements()).isEqualTo(1));
        assertThat(corrected.occurrences(null, null, "prüfen", false, 10)).hasSize(1);
        assertThat(corrected.onPage(null, new PageKey("wiki", "5")).verbs()).singleElement()
                .satisfies(verb -> assertThat(verb.occurrences()).hasSize(1));
    }
}
