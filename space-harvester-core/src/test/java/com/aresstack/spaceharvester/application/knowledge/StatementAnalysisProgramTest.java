package com.aresstack.spaceharvester.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.assets.DocumentKey;
import com.aresstack.spaceharvester.application.evidence.CurrentSources;
import com.aresstack.spaceharvester.application.evidence.Evidence;
import com.aresstack.spaceharvester.application.evidence.SourceLocationEvidence;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * KI-Hypothesen der Programmanalyse als Aussagen im Fachlogik-Bestand (ADR 0041, Punkt 7; ADR 0032, Fortschreibung).
 * Erfundenes Programm ANTRAG01; der Fake-Analysator kennt die Verben „prüft“, „genehmigt“.
 */
class StatementAnalysisProgramTest {

    private static final AccessContext CALLER = AccessContext.technicalClient("desktop");
    private static final String VERSION = "cobol-program@3";

    private final InMemoryKnowledgeGraphStore store = new InMemoryKnowledgeGraphStore();
    private final FakeStatementAnalyzer parser = new FakeStatementAnalyzer();
    private final AccessGuard access = new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW);
    private final StatementAnalysis analysis = new StatementAnalysis(
            snapshot -> Optional.of(StatementAnalysisTest.text(snapshot)), store, access,
            new AtomicReference<StatementAnalyzer>(parser)::get, StatementAnalysisTest.clock());

    private final PageSnapshot program = StatementAnalysisTest.page("ANTRAG01", "1",
            "       IF KZ-STATUS = 'A' PERFORM ABSCHLUSS.");
    private final DocumentKey document = DocumentKey.page(program.key());

    @Test
    void hypothesesBecomeStatementsWithTheirSourceLocationAndStayCurrent() {
        var first = source(120, "p1");
        var second = source(200, "p1");

        var run = analysis.analyzeProgram(CALLER, program, List.of(
                new StatementAnalysis.ProgramHypothesis("Der Sachbearbeiter prüft den Antrag.", first),
                new StatementAnalysis.ProgramHypothesis("Die Stelle\ngenehmigt die Auszahlung.", second)),
                new Sources(), () -> false);

        assertThat(run.stored()).isEqualTo(2);
        assertThat(run.rejected()).isZero();
        var statements = run.statements().analysis().result().statements();
        assertThat(statements).hasSize(2);
        assertThat(statements.get(0).derivation().origin()).isEqualTo(ParsedStatements.Origin.PROGRAMM);
        assertThat(statements.get(0).derivation().source()).isEqualTo(first);
        assertThat(statements.get(1).derivation().source()).isEqualTo(second);
        assertThat(statements.get(1).derivation().simplifiedSentence()).isEqualTo("Die Stelle genehmigt die Auszahlung.");
        var page = new KnowledgeGraph.PageRef(program.key(), null, "ANTRAG01", null, null);
        assertThat(Evidence.of(page, statements.get(0))).isSameAs(first);
        assertThat(analysis.read(CALLER, program).state()).isEqualTo(PageStatements.State.CURRENT);
    }

    @Test
    void idsChangeWithTheEvidenceSoConfirmationsNeverMoveToANewState() {
        String before = analysis.analyzeProgram(CALLER, program, List.of(new StatementAnalysis.ProgramHypothesis(
                "Der Sachbearbeiter prüft den Antrag.", source(120, "p1"))), new Sources(), () -> false)
                .statements().analysis().result().statements().get(0).id();
        var copybook = DocumentKey.page(new com.aresstack.spaceharvester.application.snapshot.PageKey("wiki", "CP"));
        var withCopybook = new SourceLocationEvidence(document, "p1",
                List.of(new SourceLocationEvidence.CodeSite(document, "p1", 120, 121)), List.of(),
                List.of(SourceLocationEvidence.Dependency.resolved("ANTRAGCP", copybook, "c2")), VERSION);
        var sources = new Sources();
        sources.copybook = Optional.of(new CurrentSources.Resolved(copybook, "c2"));

        String after = analysis.analyzeProgram(CALLER, program, List.of(new StatementAnalysis.ProgramHypothesis(
                "Der Sachbearbeiter prüft den Antrag.", withCopybook)), sources, () -> false)
                .statements().analysis().result().statements().get(0).id();

        assertThat(after).isNotEqualTo(before);
    }

    @Test
    void staleForeignOrUnverifiableEvidenceIsNeverStored() {
        var foreign = DocumentKey.page(new com.aresstack.spaceharvester.application.snapshot.PageKey("wiki", "X"));
        var run = analysis.analyzeProgram(CALLER, program, List.of(
                new StatementAnalysis.ProgramHypothesis("Der Sachbearbeiter prüft den Antrag.", source(120, "p0")),
                new StatementAnalysis.ProgramHypothesis("Die Stelle prüft den Beleg.", new SourceLocationEvidence(
                        foreign, "p1", List.of(new SourceLocationEvidence.CodeSite(foreign, "p1", 1, 1)), List.of(),
                        List.of(), VERSION)),
                new StatementAnalysis.ProgramHypothesis("Die Stelle prüft den Antrag.", source(9000, "p1"))),
                new Sources(), () -> false);

        assertThat(run.stored()).isZero();
        assertThat(run.rejected()).isEqualTo(3);
        assertThat(run.statements().analysis().result().statements()).isEmpty();
    }

    @Test
    void theSentenceAnalysisNeverOverwritesProgramStatements() {
        analysis.analyzeProgram(CALLER, program, List.of(new StatementAnalysis.ProgramHypothesis(
                "Der Sachbearbeiter prüft den Antrag.", source(120, "p1"))), new Sources(), () -> false);
        int calls = parser.calls.get();

        var read = analysis.analyze(CALLER, program, () -> false);

        assertThat(parser.calls).hasValue(calls);
        assertThat(read.analysis().result().statements()).singleElement()
                .satisfies(statement -> assertThat(statement.derivation().origin())
                        .isEqualTo(ParsedStatements.Origin.PROGRAMM));
    }

    @Test
    void aChangedCopybookOrAnalysisMarksTheProgramStatementsPossiblyStaleWithoutTextChange() {
        var copybook = DocumentKey.page(new com.aresstack.spaceharvester.application.snapshot.PageKey("wiki", "CP"));
        var withCopybook = new SourceLocationEvidence(document, "p1",
                List.of(new SourceLocationEvidence.CodeSite(document, "p1", 120, 121)), List.of(),
                List.of(SourceLocationEvidence.Dependency.resolved("ANTRAGCP", copybook, "c1")), VERSION);
        var sources = new Sources();
        sources.copybook = Optional.of(new CurrentSources.Resolved(copybook, "c1"));
        analysis.programSources(reader -> sources);
        analysis.analyzeProgram(CALLER, program, List.of(new StatementAnalysis.ProgramHypothesis(
                "Der Sachbearbeiter prüft den Antrag.", withCopybook)), sources, () -> false);
        assertThat(analysis.read(CALLER, program).state()).isEqualTo(PageStatements.State.CURRENT);

        sources.copybook = Optional.of(new CurrentSources.Resolved(copybook, "c2"));
        var afterCopybook = analysis.read(CALLER, program);
        sources.copybook = Optional.of(new CurrentSources.Resolved(copybook, "c1"));
        sources.version = "cobol-program@4";
        var afterAnalysis = analysis.read(CALLER, program);

        assertThat(afterCopybook.state()).isEqualTo(PageStatements.State.STALE);
        assertThat(afterCopybook.reason()).isEqualTo(PageStatements.EVIDENCE_CHANGED);
        assertThat(afterAnalysis.reason()).isEqualTo(PageStatements.EVIDENCE_CHANGED);
    }

    @Test
    void anUnreadableCopybookCannotKeepTheEvidenceCurrent() {
        var copybook = DocumentKey.page(new com.aresstack.spaceharvester.application.snapshot.PageKey("wiki", "CP"));
        var sources = new Sources();
        sources.copybook = Optional.of(new CurrentSources.Resolved(copybook, "c1"));
        analysis.analyzeProgram(CALLER, program, List.of(new StatementAnalysis.ProgramHypothesis(
                "Der Sachbearbeiter prüft den Antrag.", new SourceLocationEvidence(document, "p1",
                        List.of(new SourceLocationEvidence.CodeSite(document, "p1", 120, 121),
                                new SourceLocationEvidence.CodeSite(copybook, "c1", 3, 4)), List.of(),
                        List.of(SourceLocationEvidence.Dependency.resolved("ANTRAGCP", copybook, "c1")), VERSION))),
                sources, () -> false);
        var unreadable = new Sources();
        unreadable.copybook = Optional.of(new CurrentSources.Resolved(copybook, "c1"));
        unreadable.copybookReadable = false;
        analysis.programSources(reader -> unreadable);

        assertThat(analysis.read(CALLER, program).reason()).isEqualTo(PageStatements.EVIDENCE_CHANGED);
    }

    private SourceLocationEvidence source(int line, String revision) {
        return new SourceLocationEvidence(document, revision,
                List.of(new SourceLocationEvidence.CodeSite(document, revision, line, line + 1)),
                List.of(new SourceLocationEvidence.ParserFact("CONTROL", "IF KZ-STATUS = 'A'", 0, line, line)),
                List.of(), VERSION);
    }

    /** Programm in Revision p1 mit 400 Zeilen, analysiert mit {@code version} (Vorgabe {@value #VERSION}). */
    private final class Sources implements CurrentSources {
        Optional<Resolved> copybook = Optional.empty();
        boolean copybookReadable = true;
        String version = VERSION;

        @Override
        public Optional<Current> document(DocumentKey key) {
            if (key.equals(document)) {
                return Optional.of(new Current("p1", 400));
            }
            return copybook.filter(resolved -> copybookReadable && resolved.document().equals(key))
                    .map(resolved -> new Current(resolved.revision(), 50));
        }

        @Override
        public Optional<Resolved> dependency(DocumentKey program, String name) {
            return copybook;
        }

        @Override
        public Optional<String> analysisVersion(DocumentKey program) {
            return Optional.of(version);
        }
    }
}
