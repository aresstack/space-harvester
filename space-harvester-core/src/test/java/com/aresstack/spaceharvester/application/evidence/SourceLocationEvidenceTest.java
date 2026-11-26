package com.aresstack.spaceharvester.application.evidence;

import com.aresstack.spaceharvester.application.assets.DocumentKey;
import com.aresstack.spaceharvester.application.evidence.SourceLocationEvidence.CodeSite;
import com.aresstack.spaceharvester.application.evidence.SourceLocationEvidence.Dependency;
import com.aresstack.spaceharvester.application.evidence.SourceLocationEvidence.ParserFact;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Review;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static com.aresstack.spaceharvester.application.evidence.SourceLocationCheck.Outcome.CURRENT;
import static com.aresstack.spaceharvester.application.evidence.SourceLocationCheck.Outcome.NOT_VERIFIABLE;
import static com.aresstack.spaceharvester.application.evidence.SourceLocationCheck.Outcome.POSSIBLY_STALE;
import static com.aresstack.spaceharvester.application.evidence.SourceLocationCheck.Reason.ANALYSIS_VERSION_CHANGED;
import static com.aresstack.spaceharvester.application.evidence.SourceLocationCheck.Reason.DEPENDENCY_CHANGED;
import static com.aresstack.spaceharvester.application.evidence.SourceLocationCheck.Reason.DEPENDENCY_GONE;
import static com.aresstack.spaceharvester.application.evidence.SourceLocationCheck.Reason.DEPENDENCY_NOW_FOUND;
import static com.aresstack.spaceharvester.application.evidence.SourceLocationCheck.Reason.DOCUMENT_UNAVAILABLE;
import static com.aresstack.spaceharvester.application.evidence.SourceLocationCheck.Reason.LINES_OUT_OF_RANGE;
import static com.aresstack.spaceharvester.application.evidence.SourceLocationCheck.Reason.PROGRAM_CHANGED;
import static com.aresstack.spaceharvester.application.evidence.SourceLocationCheck.Reason.SITE_CHANGED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Erfundene Programme: ANTRAG01 mit Copybook ANTRAGCP, eine Regel über zwei Codestellen. */
class SourceLocationEvidenceTest {

    private static final DocumentKey PROGRAM = DocumentKey.page(new PageKey("ftp-test", "TEST.COBOL(ANTRAG01)"));
    private static final DocumentKey COPYBOOK = DocumentKey.page(new PageKey("ftp-test", "TEST.COPY(ANTRAGCP)"));
    private static final DocumentKey OTHER_COPYBOOK = DocumentKey.page(new PageKey("ftp-test", "ALT.COPY(ANTRAGCP)"));
    private static final String VERSION = "cobol-program@3";

    @Test
    void ruleSpansSeveralSitesAndIsAlwaysIst() {
        var evidence = evidence();

        assertThat(evidence.sites()).hasSize(2);
        assertThat(evidence.kind()).isEqualTo(Evidence.Kind.SOURCE_LOCATION);
        assertThat(evidence.bearing()).contains(Bearing.IST);
        assertThat(evidence.facts()).allSatisfy(fact -> assertThat(fact.level()).isEqualTo(InsightLevel.PARSER_FACT));
    }

    @Test
    void factMustLieInsideItsSite() {
        assertThatThrownBy(() -> new SourceLocationEvidence(PROGRAM, "p1",
                List.of(new CodeSite(PROGRAM, "p1", 120, 130)),
                List.of(new ParserFact("CONTROL", "IF KZ-STATUS = 'A'", 0, 140, 140)), List.of(), VERSION))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("außerhalb");
        assertThatThrownBy(() -> new SourceLocationEvidence(PROGRAM, "p1",
                List.of(new CodeSite(PROGRAM, "p1", 120, 130)),
                List.of(new ParserFact("CONTROL", "IF KZ-STATUS = 'A'", 1, 125, 125)), List.of(), VERSION))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Codestelle 1");
    }

    @Test
    void needsSiteAndConsistentProgramRevision() {
        assertThatThrownBy(() -> new SourceLocationEvidence(PROGRAM, "p1", List.of(), List.of(), List.of(), VERSION))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceLocationEvidence(PROGRAM, "p1",
                List.of(new CodeSite(PROGRAM, "p0", 1, 2)), List.of(), List.of(), VERSION))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fremder Revision");
    }

    @Test
    void fingerprintChangesWithEveryRevisionButNotWithFactWording() {
        var base = evidence();
        String fingerprint = base.fingerprint();

        assertThat(evidence().fingerprint()).isEqualTo(fingerprint);
        assertThat(withProgramRevision("p2").fingerprint()).isNotEqualTo(fingerprint);
        assertThat(withCopybookRevision("c2").fingerprint()).isNotEqualTo(fingerprint);
        assertThat(new SourceLocationEvidence(PROGRAM, "p1", base.sites(), base.facts(), base.dependencies(),
                "cobol-program@4").fingerprint()).isNotEqualTo(fingerprint);
        assertThat(new SourceLocationEvidence(PROGRAM, "p1", base.sites(), base.facts(),
                List.of(base.dependencies().get(0)), VERSION).fingerprint()).isNotEqualTo(fingerprint);
        assertThat(new SourceLocationEvidence(PROGRAM, "p1", base.sites(),
                List.of(new ParserFact("CONTROL", "anders formuliert", 0, 125, 125)), base.dependencies(), VERSION)
                .fingerprint()).isEqualTo(fingerprint);
    }

    @Test
    void unchangedSourcesAreCurrent() {
        var result = SourceLocationCheck.check(evidence(), new Sources());

        assertThat(result.outcome()).isEqualTo(CURRENT);
        assertThat(result.reasons()).isEmpty();
    }

    @Test
    void changedCodeOrDependencyIsPossiblyStale() {
        var sources = new Sources();
        sources.documents.put(PROGRAM, new CurrentSources.Current("p2", 400));
        assertThat(SourceLocationCheck.check(evidence(), sources).reasons()).containsExactly(PROGRAM_CHANGED);

        sources = new Sources();
        sources.documents.put(COPYBOOK, new CurrentSources.Current("c2", 40));
        sources.dependencies.put("ANTRAGCP", new CurrentSources.Resolved(COPYBOOK, "c2"));
        var result = SourceLocationCheck.check(evidence(), sources);
        assertThat(result.outcome()).isEqualTo(POSSIBLY_STALE);
        assertThat(result.reasons()).containsExactlyInAnyOrder(SITE_CHANGED, DEPENDENCY_CHANGED);

        sources = new Sources();
        sources.dependencies.put("ANTRAGCP", new CurrentSources.Resolved(OTHER_COPYBOOK, "c1"));
        assertThat(SourceLocationCheck.check(evidence(), sources).reasons()).containsExactly(DEPENDENCY_CHANGED);

        sources = new Sources();
        sources.dependencies.remove("ANTRAGCP");
        assertThat(SourceLocationCheck.check(evidence(), sources).reasons()).containsExactly(DEPENDENCY_GONE);

        sources = new Sources();
        sources.dependencies.put("DATUMCP", new CurrentSources.Resolved(OTHER_COPYBOOK, "d1"));
        assertThat(SourceLocationCheck.check(evidence(), sources).reasons()).containsExactly(DEPENDENCY_NOW_FOUND);

        sources = new Sources();
        sources.version = Optional.of("cobol-program@4");
        assertThat(SourceLocationCheck.check(evidence(), sources).reasons()).containsExactly(ANALYSIS_VERSION_CHANGED);

        sources = new Sources();
        sources.version = Optional.empty();
        assertThat(SourceLocationCheck.check(evidence(), sources).reasons()).containsExactly(ANALYSIS_VERSION_CHANGED);
    }

    @Test
    void missingDocumentOrInventedLinesAreNotVerifiable() {
        var sources = new Sources();
        sources.documents.remove(COPYBOOK);
        assertThat(SourceLocationCheck.check(evidence(), sources).outcome()).isEqualTo(NOT_VERIFIABLE);
        assertThat(SourceLocationCheck.check(evidence(), sources).reasons()).containsExactly(DOCUMENT_UNAVAILABLE);

        sources = new Sources();
        sources.documents.put(PROGRAM, new CurrentSources.Current("p1", 100));
        assertThat(SourceLocationCheck.check(evidence(), sources).reasons()).containsExactly(LINES_OUT_OF_RANGE);
    }

    @Test
    void confirmationHoldsOnlyForTheConfirmedState() {
        var current = SourceLocationCheck.check(evidence(), new Sources());
        var changed = new Sources();
        changed.documents.put(PROGRAM, new CurrentSources.Current("p2", 400));
        var stale = SourceLocationCheck.check(evidence(), changed);

        assertThat(SourceInsight.of(Review.UNREVIEWED, current).level()).isEqualTo(InsightLevel.HYPOTHESIS);
        assertThat(SourceInsight.of(Review.CONFIRMED, current).level()).isEqualTo(InsightLevel.CONFIRMED);
        assertThat(SourceInsight.of(Review.CONFIRMED, current).bearing()).isEqualTo(Bearing.IST);

        var outdated = SourceInsight.of(Review.CONFIRMED, stale);
        assertThat(outdated.level()).isEqualTo(InsightLevel.HYPOTHESIS);
        assertThat(outdated.confirmationOutdated()).isTrue();

        assertThat(SourceInsight.of(Review.REJECTED, stale).level()).isEqualTo(InsightLevel.REJECTED);
        assertThat(SourceInsight.of(Review.UNREVIEWED, stale).confirmationOutdated()).isFalse();
    }

    private static SourceLocationEvidence evidence() {
        return withRevisions("p1", "c1");
    }

    private static SourceLocationEvidence withProgramRevision(String revision) {
        return withRevisions(revision, "c1");
    }

    private static SourceLocationEvidence withCopybookRevision(String revision) {
        return withRevisions("p1", revision);
    }

    private static SourceLocationEvidence withRevisions(String program, String copybook) {
        return new SourceLocationEvidence(PROGRAM, program,
                List.of(new CodeSite(PROGRAM, program, 120, 310), new CodeSite(COPYBOOK, copybook, 12, 14)),
                List.of(new ParserFact("CONTROL", "IF KZ-STATUS = 'A'", 0, 125, 125),
                        new ParserFact("SYMBOL", "05 KZ-STATUS PIC X", 1, 12, 12)),
                List.of(Dependency.resolved("ANTRAGCP", COPYBOOK, copybook), Dependency.missing("DATUMCP")),
                VERSION);
    }

    /** Jetziger Stand wie zur Zeit des Belegs; Tests ändern einzelne Einträge. */
    private static final class Sources implements CurrentSources {
        final Map<DocumentKey, Current> documents = new HashMap<>(Map.of(
                PROGRAM, new Current("p1", 400), COPYBOOK, new Current("c1", 40)));
        final Map<String, Resolved> dependencies = new HashMap<>(Map.of(
                "ANTRAGCP", new Resolved(COPYBOOK, "c1")));
        Optional<String> version = Optional.of(VERSION);

        @Override
        public Optional<Current> document(DocumentKey document) {
            return Optional.ofNullable(documents.get(document));
        }

        @Override
        public Optional<Resolved> dependency(DocumentKey program, String name) {
            return Optional.ofNullable(dependencies.get(name));
        }

        @Override
        public Optional<String> analysisVersion(DocumentKey program) {
            return version;
        }
    }
}
