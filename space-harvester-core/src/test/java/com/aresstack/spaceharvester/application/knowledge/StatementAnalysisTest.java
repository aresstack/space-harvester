package com.aresstack.spaceharvester.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessDeniedException;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentTestPages;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class StatementAnalysisTest {

    static final String REFERENCE = "Der Sachbearbeiter übermittelt dem Vorgesetzten den Beleg des Kunden.";
    private static final AccessContext CALLER = AccessContext.technicalClient("desktop");

    private final InMemoryKnowledgeGraphStore store = new InMemoryKnowledgeGraphStore();
    private final FakeStatementAnalyzer parser = new FakeStatementAnalyzer();
    private final AtomicReference<StatementAnalyzer> configured = new AtomicReference<>(parser);
    private AccessGuard access = new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW);

    @Test
    void onlySoftLineBreaksMidSentenceReachTheAnalyzerAsSpaces() {
        String file = String.join("\n",
                "Kurzbeschreibung",
                "Das Programm übernimmt die Stammdaten aus dem Altverfahren und",
                "verarbeitet sie gemeinsam mit den Bewegungsdaten, die der Kunde,",
                "Antragsteller oder Vertreter über das Portal einreicht.",
                "Einleitung",
                "==========",
                "a) Der Server prüft die Daten",
                "b) Der Client sendet die Antwort",
                "– Der Server prüft",
                "– Der Client sendet",
                "Betreff: Antrag",
                "Von: Amt",
                "name,wert",
                "anna,3");
        String analyzed = StatementAnalysis.analyzerText(new PageText("file-text/v1", file, List.of()));
        assertThat(analyzed).hasSameSizeAs(file).isEqualTo(file
                .replace("Altverfahren und\nverarbeitet", "Altverfahren und verarbeitet")
                .replace("Kunde,\nAntragsteller", "Kunde, Antragsteller"));
        assertThat(StatementAnalysis.analyzerText(new PageText("file-text/v1", "Das Programm\nverarbeitet die Daten.",
                List.of()))).as("kurze Zeilen sind kein Umbruch an der Zeilenbreite, im Zweifel bleibt die Grenze")
                .isEqualTo("Das Programm\nverarbeitet die Daten.");

        String log = "benutzer anna meldet sich am server mit ihrem konto an\n".repeat(30);
        assertThat(StatementAnalysis.analyzerText(new PageText("file-text/v1", log, List.of())))
                .as("kleingeschriebene Datensätze beginnen keine Verbindung").isEqualTo(log);
        String prose = "Der Antrag wird von der zuständigen Stelle geprüft und dann\n"
                + "anschließend von der zuständigen Stelle bearbeitet und dann\n".repeat(30);
        assertThat(StatementAnalysis.analyzerText(new PageText("file-text/v1", prose, List.of())).split("\n"))
                .as("höchstens " + StatementAnalysis.MAX_JOINED_LINES + " Zeilen je Satz")
                .hasSizeGreaterThan(1).allMatch(line -> line.length() <= StatementAnalysis.MAX_JOINED_LINES * 61);

        for (String structured : List.of(
                "{\n  \"name\": \"Max Mustermann\",\n  \"ort\": \"Berlin\"\n}",
                "name, ort, status\nanna, berlin, aktiv\nbernd, hamburg, inaktiv",
                "Müller, Hans, Berlin,\nSchmidt, Anna, Hamburg,",
                "spring:\n  datasource: jdbc\n  username: admin",
                "app.name = Space Harvester\napp.version = 1.0",
                "Tabelle1\n\tMüller Hans\tBerlin\tja\n\tnein\tSchmidt Anna",
                "java.lang.NullPointerException: Cannot invoke foo()\n\tat com.foo.Bar.baz(Bar.java:12)",
                "a) die Anschrift,\nb) das Geburtsdatum,\nc) die Staatsangehörigkeit.",
                "– Kopie des Ausweises,\n– aktuelle Meldebescheinigung",
                "An: Herr Müller, Frau Schmidt,\nCc: Referat Z 3",
                "Siehe https://example.org/a b\nund weiter",
                "aschmidt;Anna Schmidt;Referat Z 3;Berlin Mitte\nbmeier;Bernd Meier;Referat Z 4;Hamburg Nord",
                "Glossar\nbackend serverseitiger Teil der Anwendung\nfrontend clientseitiger Teil der Anwendung",
                "Unterstützte Systeme\nmacOS wird ab Version 12 unterstützt.")) {
            assertThat(StatementAnalysis.analyzerText(new PageText("file-text/v1", structured, List.of())))
                    .as(structured).isEqualTo(structured);
        }

        String document = "Kurzbeschreibung\nDas Vorhaben führt Daten zusammen.\nKomponenten\nDer Server verarbeitet die\nAnfragen.";
        assertThat(StatementAnalysis.analyzerText(new PageText("file-document-text/v1", document, List.of())))
                .as("Überschriften und Absätze ohne Leerzeile bleiben getrennt").isEqualTo(document);

        String confluence = "Kurzbeschreibung\nDas Vorhaben führt Daten zusammen.";
        assertThat(StatementAnalysis.analyzerText(new PageText("confluence-storage-text/v1", confluence, List.of())))
                .as("eine Confluence-Projektion trennt Blöcke je Zeile").isEqualTo(confluence);
    }

    @Test
    void readingNeverParsesAndAnAnalysisIsReadBackWithoutRecompute() {
        PageSnapshot page = page("1", "3", REFERENCE);
        StatementAnalysis analysis = analysis();

        assertThat(analysis.read(CALLER, page)).isEqualTo(PageStatements.missing(PageStatements.NOT_ANALYSED));
        assertThat(parser.calls).hasValue(0);

        PageStatements analysed = analysis.analyze(CALLER, page, () -> false);
        PageStatements again = analysis.read(CALLER, page);

        assertThat(parser.calls).hasValue(1);
        assertThat(analysed.state()).isEqualTo(PageStatements.State.CURRENT);
        assertThat(again.state()).isEqualTo(PageStatements.State.CURRENT);
        PageStatementAnalysis stored = again.analysis();
        assertThat(stored.inputSha256()).isEqualTo(text(page).inputSha256());
        assertThat(stored.analyzerVersion()).isEqualTo("1|fake-model|abc");
        assertThat(stored.revision()).isEqualTo("3");
        assertThat(stored.analyzedAt()).isEqualTo(Instant.parse("2026-11-09T15:00:00Z"));
        assertThat(stored.result().conllu()).as("CoNLL-U nur über die Syntaxdetails").isNull();
        assertThat(analysis.syntaxDetails(CALLER, page)).get().asString().contains("Sachbearbeiter");
        assertThat(stored.result().statements()).singleElement().satisfies(statement -> {
            assertThat(statement.predicateText()).isEqualTo("übermittelt");
            assertThat(statement.core()).isTrue();
        });
        assertThat(stored.result().attachments()).singleElement()
                .satisfies(attachment -> assertThat(attachment.kind())
                        .isEqualTo(ParsedStatements.AttachmentKind.GENITIVE));
    }

    @Test
    void aChangedTextMakesTheAnalysisStaleWithoutRecomputing() {
        StatementAnalysis analysis = analysis();
        analysis.analyze(CALLER, page("1", "3", REFERENCE), () -> false);

        PageStatements read = analysis.read(CALLER, page("1", "4", REFERENCE + " Den Antrag prüft er."));

        assertThat(read.state()).isEqualTo(PageStatements.State.STALE);
        assertThat(read.reason()).isEqualTo(PageStatements.TEXT_CHANGED);
        assertThat(parser.calls).hasValue(1);
    }

    @Test
    void anotherModelMakesTheAnalysisStaleAndAClickRenewsIt() {
        StatementAnalysis analysis = analysis();
        PageSnapshot page = page("1", "3", REFERENCE);
        analysis.analyze(CALLER, page, () -> false);

        parser.version = "1|fake-model|def";

        assertThat(analysis.read(CALLER, page).reason()).isEqualTo(PageStatements.ANALYZER_CHANGED);
        assertThat(analysis.analyze(CALLER, page, () -> false).state()).isEqualTo(PageStatements.State.CURRENT);
        assertThat(analysis.read(CALLER, page).analysis().analyzerVersion()).isEqualTo("1|fake-model|def");
    }

    @Test
    void aCancelledOrFailedRunKeepsThePreviousAnalysis() {
        StatementAnalysis analysis = analysis();
        PageSnapshot page = page("1", "3", REFERENCE);
        analysis.analyze(CALLER, page, () -> false);
        PageStatementAnalysis before = store.find(page.key()).orElseThrow();
        PageSnapshot changed = page("1", "4", REFERENCE + " Der Kunde prüft den Beleg.");

        parser.cancelAfterSentences = 1;
        assertThatThrownBy(() -> analysis.analyze(CALLER, changed, () -> false))
                .isInstanceOf(CancellationException.class);
        parser.cancelAfterSentences = -1;
        assertThatThrownBy(() -> analysis.analyze(CALLER, changed, () -> true))
                .isInstanceOf(CancellationException.class);
        parser.failure = new AnalyzerUnavailableException(AnalyzerUnavailableException.Reason.MODEL_INVALID,
                "Prüfsumme passt nicht", null);
        assertThatThrownBy(() -> analysis.analyze(CALLER, changed, () -> false))
                .isInstanceOf(AnalyzerUnavailableException.class);
        parser.failure = null;
        parser.broken = true;
        assertThatThrownBy(() -> analysis.analyze(CALLER, changed, () -> false))
                .isInstanceOf(IllegalStateException.class);

        assertThat(store.find(page.key())).contains(before);
        assertThat(analysis.read(CALLER, changed).reason()).isEqualTo(PageStatements.TEXT_CHANGED);
    }

    @Test
    void withoutAnalyzerTheStoredAnalysisStaysReadableAndAClickExplainsWhy() {
        StatementAnalysis analysis = analysis();
        PageSnapshot page = page("1", "3", REFERENCE);
        analysis.analyze(CALLER, page, () -> false);
        configured.set(null);

        assertThat(analysis.available()).isFalse();
        assertThat(analysis.read(CALLER, page).state()).isEqualTo(PageStatements.State.CURRENT);
        assertThatThrownBy(() -> analysis.analyze(CALLER, page, () -> false))
                .isInstanceOfSatisfying(AnalyzerUnavailableException.class, e -> assertThat(e.reason())
                        .isEqualTo(AnalyzerUnavailableException.Reason.NOT_CONFIGURED));
    }

    @Test
    void withoutReadRightNothingIsParsedOrShown() {
        StatementAnalysis analysis = analysis();
        PageSnapshot page = page("1", "3", REFERENCE);
        analysis.analyze(CALLER, page, () -> false);
        access = new AccessGuard((context, scope, kind) -> AccessDecision.DENY);
        StatementAnalysis denied = analysis();

        assertThatThrownBy(() -> denied.read(CALLER, page)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> denied.syntaxDetails(CALLER, page)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> denied.analyze(CALLER, page, () -> false))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(parser.calls).hasValue(1);
    }

    @Test
    void theDefaultGraphShowsOnlyPlausibleStatementsAndKeepsTheOthersAvailable() {
        StatementAnalysis analysis = analysis();
        PageSnapshot page = page("1", "3", REFERENCE + " Den Antrag prüft er.");
        analysis.analyze(CALLER, page, () -> false);
        PageStatementAnalysis stored = store.find(page.key()).orElseThrow();
        assertThat(stored.result().statements()).extracting(ParsedStatements.Statement::quality)
                .containsExactly(ParsedStatements.Quality.PLAUSIBLE, ParsedStatements.Quality.UNCERTAIN);

        assertThat(PageGraph.of(stored).nodes()).filteredOn(node -> node.kind() == PageGraph.NodeKind.STATEMENT)
                .extracting(PageGraph.Node::label).containsExactly("übermittelt");
        assertThat(PageGraph.of(stored, java.util.EnumSet.allOf(ParsedStatements.Quality.class)).nodes())
                .filteredOn(node -> node.kind() == PageGraph.NodeKind.STATEMENT).hasSize(2);
        // Erwähnungen bleiben vollständig: sie sind Text, keine Relation
        assertThat(PageGraph.of(stored).nodes()).extracting(PageGraph.Node::label).contains("Antrag");
    }

    @Test
    void repeatingTheAnalysisGivesTheSameGraphWithoutDuplicates() {
        StatementAnalysis analysis = analysis();
        PageSnapshot page = page("1", "3", REFERENCE);
        analysis.analyze(CALLER, page, () -> false);
        PageGraph first = PageGraph.of(store.find(page.key()).orElseThrow());

        analysis.analyze(CALLER, page, () -> false);
        PageGraph second = PageGraph.of(store.find(page.key()).orElseThrow());

        assertThat(store.all()).hasSize(1);
        assertThat(second).isEqualTo(first);
        assertThat(second.nodes()).extracting(PageGraph.Node::id).doesNotHaveDuplicates();
        assertThat(second.edges()).filteredOn(edge -> edge.kind() == PageGraph.EdgeKind.ACTOR)
                .singleElement().satisfies(edge -> assertThat(edge.evidence()).isEqualTo("s0"));
        assertThat(second.edges()).filteredOn(edge -> edge.kind() == PageGraph.EdgeKind.NOMINAL)
                .singleElement().satisfies(edge -> assertThat(edge.relation()).isEqualTo("nmod"));
    }

    @Test
    void aPageWithoutTextIsNotParsed() {
        StatementAnalysis analysis = new StatementAnalysis(snapshot -> Optional.empty(), store, access,
                configured::get, clock());

        assertThat(analysis.analyze(CALLER, page("1", "3", REFERENCE), () -> false))
                .isEqualTo(PageStatements.missing(PageStatements.NO_TEXT));
        assertThat(parser.calls).hasValue(0);
    }

    private StatementAnalysis analysis() {
        return new StatementAnalysis(snapshot -> Optional.of(text(snapshot)), store, access, configured::get,
                clock());
    }

    static Clock clock() {
        return Clock.fixed(Instant.parse("2026-11-09T15:00:00Z"), ZoneOffset.UTC);
    }

    static PageText text(PageSnapshot snapshot) {
        return new PageText("test/v1", new String(snapshot.payload().bytes(), StandardCharsets.UTF_8), List.of());
    }

    static PageSnapshot page(String pageId, String version, String text) {
        return EnrichmentTestPages.text("wiki", pageId, version, text);
    }
}
