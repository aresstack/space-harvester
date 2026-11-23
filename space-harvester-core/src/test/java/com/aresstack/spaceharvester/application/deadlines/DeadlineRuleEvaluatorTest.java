package com.aresstack.spaceharvester.application.deadlines;

import com.aresstack.spaceharvester.application.deadlines.DeadlineAssessment.DeviationConfirmed;
import com.aresstack.spaceharvester.application.deadlines.DeadlineAssessment.ExceptionCheckClosure;
import com.aresstack.spaceharvester.application.deadlines.DeadlineAssessment.Proof;
import com.aresstack.spaceharvester.application.deadlines.DeadlineAssessment.ProvisionalReason;
import com.aresstack.spaceharvester.application.deadlines.DeadlineAssessment.StandardConfirmed;
import com.aresstack.spaceharvester.application.deadlines.DeadlineAssessment.StandardProvisional;
import com.aresstack.spaceharvester.application.deadlines.DeadlineRuleEvaluator.Request;
import com.aresstack.spaceharvester.application.deadlines.DeadlineRuleEvaluator.Result;
import com.aresstack.spaceharvester.application.deadlines.LegalKnowledgeRepository.State;
import com.aresstack.spaceharvester.application.extraction.AnalysisSection;
import com.aresstack.spaceharvester.application.extraction.DocumentRef;
import com.aresstack.spaceharvester.application.extraction.ExtractedField;
import com.aresstack.spaceharvester.application.extraction.ExtractionOrigin;
import com.aresstack.spaceharvester.application.extraction.FieldStatus;
import com.aresstack.spaceharvester.application.extraction.Processor;
import com.aresstack.spaceharvester.application.extraction.Qualifier;
import com.aresstack.spaceharvester.application.extraction.SourceReference;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.aresstack.spaceharvester.application.deadlines.DeadlineFieldKeys.ACCOUNT_CATEGORY;
import static com.aresstack.spaceharvester.application.deadlines.DeadlineFieldKeys.BOOK;
import static com.aresstack.spaceharvester.application.deadlines.DeadlineFieldKeys.BOOK_ADVANCE;
import static com.aresstack.spaceharvester.application.deadlines.DeadlineFieldKeys.BOOK_CUSTODY;
import static com.aresstack.spaceharvester.application.deadlines.DeadlineFieldKeys.CATEGORY_PASS_THROUGH;
import static com.aresstack.spaceharvester.application.deadlines.DeadlineFieldKeys.DEADLINE_EXTENSION;
import static org.assertj.core.api.Assertions.assertThat;

/** Grundsatz und Ausnahme an erfundenen Sachverhalten (ADR 0035, Abschnitt 6). */
class DeadlineRuleEvaluatorTest {

    private static final DocumentRef DOC = new DocumentRef("eakte", "/akte/erlass-erfunden.docx", "etag-1");
    private static final DeadlineRuleEvaluator EVALUATOR = new DeadlineRuleEvaluator(new PublicDeadlineRules());

    private static ExtractedField field(String key, String value, FieldStatus status, boolean verified,
                                        Qualifier qualifier) {
        return new ExtractedField(key, key, value, value, status, ExtractionOrigin.REGEX, new Processor("test", "1"),
                verified, SourceReference.NONE, qualifier, "", "");
    }

    private static ExtractedField fact(String key, String value) {
        return field(key, value, FieldStatus.EXPLICIT, true, Qualifier.NONE);
    }

    private static AnalysisSection matter(ExtractedField... fields) {
        return new AnalysisSection("m1", "Konto 1", "Konto", List.of(fields));
    }

    private static Result evaluate(AnalysisSection matter, Integer originYear, State knowledge,
                                   ExceptionCheckClosure closure) {
        return EVALUATOR.evaluate(new Request(DOC, matter, Optional.ofNullable(originYear), knowledge, "g1",
                Optional.ofNullable(closure)));
    }

    @Test
    void anOrdinaryAdvanceGetsOnlyAProvisionalStandardWithoutClosureOrKnowledge() {
        Result result = evaluate(matter(fact(BOOK, BOOK_ADVANCE)), 2030, State.EMPTY, null);
        assertThat(result.assessment()).get().isInstanceOfSatisfying(StandardProvisional.class, p -> {
            assertThat(p.rule().id()).isEqualTo("rib-6-vorschuss");
            assertThat(p.reasons()).containsExactlyInAnyOrder(ProvisionalReason.KNOWLEDGE_EMPTY,
                    ProvisionalReason.UNCHECKED_EXCEPTIONS);
            assertThat(p.deadline().concrete()).contains(LocalDate.of(2031, 3, 31));
        });
    }

    @Test
    void passThroughMoneyBookedAsAdvanceDisplacesTheStandard() {
        Result result = evaluate(matter(fact(BOOK, BOOK_ADVANCE), fact(ACCOUNT_CATEGORY, CATEGORY_PASS_THROUGH)),
                2030, State.EMPTY, null);
        assertThat(result.assessment()).get().isInstanceOfSatisfying(DeviationConfirmed.class, d -> {
            assertThat(d.rule().id()).isEqualTo("rib-6-durchlaufend");
            assertThat(d.displaced().id()).isEqualTo("rib-6-vorschuss");
            assertThat(d.deadline().concrete()).contains(LocalDate.of(2032, 12, 31));
        });
    }

    @Test
    void withoutOriginYearThereIsNoDateAndTheExceptionStaysOpen() {
        Result result = evaluate(matter(fact(BOOK, BOOK_ADVANCE), fact(ACCOUNT_CATEGORY, CATEGORY_PASS_THROUGH)),
                null, State.READY, null);
        assertThat(result.missing()).contains("entstehungsjahr");
        assertThat(result.assessment()).get().isInstanceOfSatisfying(StandardProvisional.class, p -> {
            assertThat(p.reasons()).contains(ProvisionalReason.NO_ORIGIN_DATE);
            assertThat(p.deadline().concrete()).isEmpty();
            assertThat(p.deadline().rule()).contains("drei Monate");
            assertThat(p.candidates()).singleElement().satisfies(c -> {
                assertThat(c.exception().id()).isEqualTo("rib-6-durchlaufend");
                assertThat(c.effectiveness()).isEqualTo(Proof.OPEN);
            });
        });
    }

    @Test
    void aRequestedExtensionIsNoApproval() {
        Result result = evaluate(matter(fact(BOOK, BOOK_ADVANCE), field(DEADLINE_EXTENSION, "bis 30.06.2032",
                FieldStatus.EXPLICIT, true, Qualifier.REQUESTED)), 2030, State.READY, null);
        assertThat(result.assessment()).get().isInstanceOfSatisfying(StandardProvisional.class,
                p -> assertThat(p.candidates()).singleElement().satisfies(c -> {
                    assertThat(c.exception().id()).isEqualTo("bho-60-1-einwilligung");
                    assertThat(c.legalEffect()).isEqualTo(Proof.NOT_PROVEN);
                }));
    }

    @Test
    void aGrantedExtensionDisplacesTheStandardWithItsOwnWording() {
        Result result = evaluate(matter(fact(BOOK, BOOK_CUSTODY), field(DEADLINE_EXTENSION, "bis 30.06.2032",
                FieldStatus.EXPLICIT, true, Qualifier.GRANTED)), 2030, State.READY, null);
        assertThat(result.assessment()).get().isInstanceOfSatisfying(DeviationConfirmed.class, d -> {
            assertThat(d.rule().id()).isEqualTo("rib-8-bmf");
            assertThat(d.deadline().rule()).contains("bis 30.06.2032");
            assertThat(d.deadline().concrete()).isEmpty();
        });
    }

    @Test
    void onlyAMatchingClosureConfirmsTheStandard() {
        ExceptionCheckClosure closure = new ExceptionCheckClosure("angelo", Instant.EPOCH,
                PublicDeadlineRules.VERSION, "g1", DOC, "m1");
        assertThat(evaluate(matter(fact(BOOK, BOOK_CUSTODY)), 2030, State.READY, closure).assessment()).get()
                .isInstanceOfSatisfying(StandardConfirmed.class, c -> {
                    assertThat(c.rule().id()).isEqualTo("rib-8-verwahrung");
                    assertThat(c.deadline().concrete()).contains(LocalDate.of(2031, 3, 31));
                });
        ExceptionCheckClosure oldCatalog = new ExceptionCheckClosure("angelo", Instant.EPOCH, "public-0", "g1",
                DOC, "m1");
        assertThat(evaluate(matter(fact(BOOK, BOOK_CUSTODY)), 2030, State.READY, oldCatalog).assessment()).get()
                .isInstanceOf(StandardProvisional.class);
        assertThat(evaluate(matter(fact(BOOK, BOOK_CUSTODY)), 2030, State.EMPTY, closure).assessment()).get()
                .isInstanceOf(StandardProvisional.class);
    }

    @Test
    void missingOrConflictingBookDecidesNothing() {
        Result missing = evaluate(matter(), 2030, State.READY, null);
        assertThat(missing.assessment()).isEmpty();
        assertThat(missing.missing()).containsExactly(BOOK);
        Result conflict = evaluate(matter(field(BOOK, BOOK_ADVANCE, FieldStatus.CONFLICT, true, Qualifier.NONE),
                field(BOOK, BOOK_CUSTODY, FieldStatus.CONFLICT, true, Qualifier.NONE)), 2030, State.READY, null);
        assertThat(conflict.assessment()).isEmpty();
        assertThat(conflict.conflicts()).containsExactly(BOOK);
    }

    @Test
    void unverifiedFactsCountForNothing() {
        Result result = evaluate(matter(fact(BOOK, BOOK_ADVANCE), field(ACCOUNT_CATEGORY, CATEGORY_PASS_THROUGH,
                FieldStatus.EXPLICIT, false, Qualifier.NONE)), 2030, State.READY, null);
        assertThat(result.assessment()).get().isInstanceOfSatisfying(StandardProvisional.class,
                p -> assertThat(p.candidates()).isEmpty());
    }

    @Test
    void everyPublicRuleCarriesWordingSourceAndAKnownDisplacedRule() {
        PublicDeadlineRules catalog = new PublicDeadlineRules();
        List<String> ids = new ArrayList<>(catalog.rules().stream().map(DeadlineRule::id).toList());
        assertThat(ids).doesNotHaveDuplicates();
        for (DeadlineRule rule : catalog.rules()) {
            assertThat(rule.sources()).isNotEmpty();
            rule.displaces().ifPresent(id -> assertThat(catalog.rule(id)).isPresent());
        }
        assertThat(catalog.rule("rib-6-durchlaufend").orElseThrow().wording())
                .contains("Durchlaufende Gelder, die als Vorschuss gebucht werden, sind spätestens zum Ende des "
                        + "zweiten auf ihre Entstehung folgenden Haushaltsjahres auszugleichen.");
        assertThat(catalog.rule("bho-60-1-einwilligung").orElseThrow().validFrom()).isEmpty();
    }
}
