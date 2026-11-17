package com.aresstack.spaceharvester.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Argument;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Basis;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Origin;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Priority;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Quality;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Role;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Voice;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentTestPages;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

/** Valenz aus dem Bestand (ADR 0032) an erfundenen Aussagen. */
class VerbValenceTest {

    private static int ids;

    private static Argument argument(Role role, String marker, Basis basis, boolean certain) {
        return new Argument(role, "m" + (ids++), "rel", marker, Priority.CORE, basis, certain);
    }

    private static Argument sure(Role role) {
        return argument(role, null, Basis.PARSER, true);
    }

    private static ParsedStatements.Statement statement(String lemma, Voice voice, Quality quality,
                                                        ParsedStatements.Derivation derivation, Set<String> findings,
                                                        Argument... arguments) {
        return new ParsedStatements.Statement("doc#s1.v" + (ids++), 0, 0, 1, lemma, lemma, voice,
                ParsedStatements.Clause.MAIN, true, List.of(arguments), findings, 0, quality, List.of(),
                ParsedStatements.Review.UNREVIEWED, ParsedStatements.Features.UNKNOWN, derivation);
    }

    private static ParsedStatements.Statement active(String lemma, Argument... arguments) {
        return statement(lemma, Voice.ACTIVE, Quality.PLAUSIBLE, null, Set.of(), arguments);
    }

    private static KnowledgeGraph.AnalysedPage page(String id, PageStatements.State state,
                                                   List<ParsedStatements.Statement> statements) {
        PageKey key = new PageKey("wiki", id);
        ParsedStatements result = new ParsedStatements(List.of(), List.of(), statements, List.of(), List.of(), null);
        PageStatementAnalysis analysis = new PageStatementAnalysis(key, "DEV", "Seite " + id, "1",
                "sha256:" + "a".repeat(64), "fake", "1", Instant.EPOCH, result);
        return new KnowledgeGraph.AnalysedPage(new KnowledgeGraph.PageRef(key, "DEV", "Seite " + id, state,
                state == PageStatements.State.CURRENT ? null : "text-changed"), analysis);
    }

    @Test
    void framesSeparateVoiceOriginAndHeuristicRolesAndSkipStatesAndWeakStatements() {
        List<ParsedStatements.Statement> statements = List.of(
                active("prüfen", sure(Role.ACTOR), sure(Role.AFFECTED_OBJECT)),
                active("prüfen", sure(Role.ACTOR), sure(Role.AFFECTED_OBJECT)),
                active("prüfen", sure(Role.ACTOR), argument(Role.AFFECTED_OBJECT, null, Basis.COORDINATION, false)),
                statement("prüfen", Voice.PASSIVE, Quality.PLAUSIBLE, null, Set.of(), sure(Role.AFFECTED_OBJECT)),
                statement("prüfen", Voice.ACTIVE, Quality.PLAUSIBLE, ParsedStatements.Derivation.vorstufe(
                        ParsedStatements.StageFlag.FACT, "Der Techniker prüft die Pumpe."), Set.of(),
                        sure(Role.ACTOR), sure(Role.AFFECTED_OBJECT)),
                active("warten", sure(Role.ACTOR), argument(Role.PREPOSITIONAL_OBJECT, "Auf", Basis.PARSER, true),
                        argument(Role.TIME, null, Basis.PARSER, true)),
                statement("prüfen", Voice.ACTIVE, Quality.UNCERTAIN, null, Set.of(), sure(Role.ACTOR)),
                statement("defekt", Voice.ACTIVE, Quality.PLAUSIBLE, null,
                        Set.of(ParsedStatements.Statement.STATE_PROPERTY), sure(Role.BEARER)));
        List<KnowledgeGraph.AnalysedPage> pages = List.of(page("1", PageStatements.State.CURRENT, statements),
                page("2", PageStatements.State.STALE, List.of(active("senden", sure(Role.ACTOR)))));

        VerbValence.Result result = VerbValence.of(pages, () -> false);

        assertThat(result.pages()).isEqualTo(1);
        assertThat(result.stalePages()).isEqualTo(1);
        assertThat(result.states()).as("Zustand ist kein Verb").isEqualTo(1);
        assertThat(result.notPlausible()).isEqualTo(1);
        assertThat(result.statements()).isEqualTo(6);
        assertThat(result.verb("defekt")).isNull();
        assertThat(result.verb("senden")).as("veraltete Analyse").isNull();
        VerbValence.Verb pruefen = result.verb("prüfen");
        assertThat(pruefen.count()).isEqualTo(5);
        assertThat(pruefen.frames()).extracting(VerbValence.Frame::voice, VerbValence.Frame::origin,
                        f -> String.join("+", f.roles()), f -> String.join("+", f.heuristic()), VerbValence.Frame::count)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(Voice.ACTIVE, Origin.ORIGINAL, "ACTOR+AFFECTED_OBJECT", "",
                                2),
                        org.assertj.core.groups.Tuple.tuple(Voice.ACTIVE, Origin.ORIGINAL, "ACTOR", "AFFECTED_OBJECT",
                                1),
                        org.assertj.core.groups.Tuple.tuple(Voice.PASSIVE, Origin.ORIGINAL, "AFFECTED_OBJECT", "", 1),
                        org.assertj.core.groups.Tuple.tuple(Voice.ACTIVE, Origin.VORSTUFE, "ACTOR+AFFECTED_OBJECT", "",
                                1));
        assertThat(pruefen.frames().get(0).evidence()).hasSize(2);
        assertThat(result.verb("warten").frames().get(0).roles()).containsExactly("ACTOR",
                "PREPOSITIONAL_OBJECT:auf");
    }

    @Test
    void itCanBeCancelled() {
        List<KnowledgeGraph.AnalysedPage> pages = List.of(page("1", PageStatements.State.CURRENT, List.of()));
        assertThatThrownBy(() -> VerbValence.of(pages, () -> true)).isInstanceOf(CancellationException.class);
    }

    /** Laufzeit an erzeugten Analysen (Zahl für den PR): 5000 Seiten mit je 40 Aussagen. */
    @Test
    void severalThousandPagesAreCountedQuickly() {
        String[] verbs = {"prüfen", "senden", "genehmigen", "übermitteln", "erfassen", "buchen", "zahlen", "ablehnen"};
        List<KnowledgeGraph.AnalysedPage> pages = new ArrayList<>();
        for (int p = 0; p < 5000; p++) {
            List<ParsedStatements.Statement> statements = new ArrayList<>();
            for (int s = 0; s < 40; s++) {
                statements.add(s % 3 == 0 ? statement(verbs[(p + s) % verbs.length], Voice.PASSIVE, Quality.PLAUSIBLE,
                        null, Set.of(), sure(Role.AFFECTED_OBJECT))
                        : active(verbs[(p + s) % verbs.length], sure(Role.ACTOR), sure(Role.AFFECTED_OBJECT),
                        argument(Role.RECIPIENT, null, Basis.CASE_HEURISTIC, false)));
            }
            pages.add(page(Integer.toString(p), PageStatements.State.CURRENT, statements));
        }
        long start = System.nanoTime();
        VerbValence.Result result = VerbValence.of(pages, () -> false);
        long millis = (System.nanoTime() - start) / 1_000_000;
        System.out.println("Valenz: " + result.pages() + " Seiten, " + result.statements() + " Aussagen in " + millis
                + " ms");
        assertThat(result.statements()).isEqualTo(200_000);
        assertThat(result.verbs()).hasSize(verbs.length);
    }

    /**
     * Laufzeit über den ganzen Weg aus dem Bestand (Zahl für den PR): 3000 mit dem Ersatzanalysator erzeugte und
     * gespeicherte Analysen mit je 20 Aussagen, gelesen über {@link KnowledgeGraph#analysed(AccessContext)} samt
     * Leserecht und Stand.
     */
    @Test
    void fromStoredAnalysesOfSeveralThousandPages() {
        AccessContext caller = AccessContext.technicalClient("desktop");
        InMemoryKnowledgeGraphStore store = new InMemoryKnowledgeGraphStore();
        FakeStatementAnalyzer parser = new FakeStatementAnalyzer();
        AccessGuard access = new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW);
        Map<PageKey, PageSnapshot> cache = new HashMap<>();
        StatementAnalysis statements = new StatementAnalysis(
                snapshot -> Optional.of(StatementAnalysisTest.text(snapshot)), store, access, () -> parser,
                StatementAnalysisTest.clock());
        String text = "Der Sachbearbeiter prüft den Beleg. Die Kasse sendet den Antrag. ".repeat(10);
        for (int p = 0; p < 3000; p++) {
            PageSnapshot snapshot = EnrichmentTestPages.text("wiki", Integer.toString(p), "1", text);
            cache.put(snapshot.key(), snapshot);
            statements.analyze(caller, snapshot, () -> false);
        }
        KnowledgeGraph graph = new KnowledgeGraph(store, access, key -> Optional.ofNullable(cache.get(key)),
                snapshot -> Optional.of(StatementAnalysisTest.text(snapshot)), statements,
                (c, term, sourceId) -> List.of());
        for (String run : List.of("erster Aufruf", "zweiter Aufruf")) {
            long start = System.nanoTime();
            VerbValence.Result result = VerbValence.of(graph, caller, () -> false);
            long millis = (System.nanoTime() - start) / 1_000_000;
            System.out.println("Valenz aus dem Bestand, " + run + ": " + result.pages() + " Seiten, "
                    + result.statements() + " Aussagen in " + millis + " ms");
            assertThat(result.pages()).isEqualTo(3000);
            assertThat(result.verb("prüft").count()).as("Ersatzanalysator: Grundform = Wortform").isEqualTo(30_000);
        }
    }
}
