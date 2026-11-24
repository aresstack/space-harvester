package com.aresstack.spaceharvester.application.deadlines;

import com.aresstack.spaceharvester.application.deadlines.DeadlineAssessment.Deadline;
import com.aresstack.spaceharvester.application.deadlines.DeadlineAssessment.DeviationConfirmed;
import com.aresstack.spaceharvester.application.deadlines.DeadlineAssessment.ExceptionCheckClosure;
import com.aresstack.spaceharvester.application.deadlines.DeadlineAssessment.ExceptionEvidence;
import com.aresstack.spaceharvester.application.deadlines.DeadlineAssessment.Proof;
import com.aresstack.spaceharvester.application.deadlines.DeadlineAssessment.ProvisionalReason;
import com.aresstack.spaceharvester.application.deadlines.DeadlineAssessment.RuleRef;
import com.aresstack.spaceharvester.application.deadlines.DeadlineAssessment.StandardConfirmed;
import com.aresstack.spaceharvester.application.deadlines.DeadlineAssessment.StandardProvisional;
import com.aresstack.spaceharvester.application.extraction.DocumentRef;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Die drei Zustände lassen sich nur mit ihren Belegen bauen (ADR 0035, Abschnitt 6). */
class DeadlineAssessmentTest {

    private static final DocumentRef DOC = new DocumentRef("eakte", "/akte/erlass-erfunden.docx", "etag-1");
    private static final RuleRef STANDARD = new RuleRef("bho-60-1", "1", "§ 60 Abs. 1 Satz 2 BHO");
    private static final RuleRef EXCEPTION = new RuleRef("test-ausnahme", "1", "erfundene Testregel");
    private static final Deadline ABSTRACT = new Deadline("Ende des zweiten auf die Entstehung folgenden "
            + "Haushaltsjahres", Optional.empty());

    @Test
    void provisionalNeedsAReason() {
        assertThatThrownBy(() -> new StandardProvisional(DOC, "m1", STANDARD, ABSTRACT, Set.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        StandardProvisional provisional = new StandardProvisional(DOC, "m1", STANDARD, ABSTRACT,
                Set.of(ProvisionalReason.KNOWLEDGE_EMPTY, ProvisionalReason.NO_ORIGIN_DATE), List.of());
        assertThat(provisional.reasons()).contains(ProvisionalReason.KNOWLEDGE_EMPTY);
    }

    @Test
    void confirmedStandardNeedsAClosureForThisDocumentVersionAndMatter() {
        ExceptionCheckClosure otherVersion = new ExceptionCheckClosure("angelo", Instant.EPOCH, "k1", "g1",
                new DocumentRef("eakte", "/akte/erlass-erfunden.docx", "etag-2"), "m1");
        assertThatThrownBy(() -> new StandardConfirmed(DOC, "m1", STANDARD, ABSTRACT, otherVersion))
                .isInstanceOf(IllegalArgumentException.class);
        ExceptionCheckClosure otherMatter = new ExceptionCheckClosure("angelo", Instant.EPOCH, "k1", "g1", DOC, "m2");
        assertThatThrownBy(() -> new StandardConfirmed(DOC, "m1", STANDARD, ABSTRACT, otherMatter))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StandardConfirmed(DOC, "m1", STANDARD, ABSTRACT, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ExceptionCheckClosure(" ", Instant.EPOCH, "k1", "g1", DOC, "m1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deviationNeedsAllThreeProofs() {
        ExceptionEvidence open = new ExceptionEvidence(EXCEPTION, Proof.PROVEN, Proof.OPEN, Proof.PROVEN, List.of());
        assertThatThrownBy(() -> new DeviationConfirmed(DOC, "m1", EXCEPTION, ABSTRACT, STANDARD, open))
                .isInstanceOf(IllegalArgumentException.class);
        ExceptionEvidence proven = new ExceptionEvidence(EXCEPTION, Proof.PROVEN, Proof.PROVEN, Proof.PROVEN,
                List.of("Fundstelle"));
        assertThat(new DeviationConfirmed(DOC, "m1", EXCEPTION, ABSTRACT, STANDARD, proven).displaced())
                .isEqualTo(STANDARD);
        assertThatThrownBy(() -> new DeviationConfirmed(DOC, "m1", STANDARD, ABSTRACT, STANDARD, proven))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deadlineGroupsCountFromOne() {
        assertThat(DeadlineFieldKeys.groupOf(2)).isEqualTo("frist-2");
        assertThatThrownBy(() -> DeadlineFieldKeys.groupOf(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
