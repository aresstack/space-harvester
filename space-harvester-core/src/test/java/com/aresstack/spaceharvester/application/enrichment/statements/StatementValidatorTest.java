package com.aresstack.spaceharvester.application.enrichment.statements;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.enrichment.statements.StatementCandidates.MentionCandidate;
import com.aresstack.spaceharvester.application.enrichment.statements.StatementCandidates.Words;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Die Verankerungsregeln des {@link StatementValidator} (aus dem Enterprise AI Client). */
class StatementValidatorTest {

    private static final String SENTENCE = "Die Stelle prüft die Zahlung und bucht die Zahlung.";

    @Test
    void textThatOccursTwiceWithoutValidRangeIsAmbiguous() {
        ParagraphText paragraph = ParagraphText.of(SENTENCE, StatementAnalyzerTest.SENTENCES);
        StatementValidator.Result result = new StatementValidator(PredicateCheck.none()).validate(paragraph,
                new StatementCandidates(List.of(new MentionCandidate("m1", 1, new Words(0, 0, "die Zahlung"),
                        "Zahlung", false)), null, null, null, 0), "p0.");

        assertThat(result.mentions()).isEmpty();
        assertThat(result.rejections()).containsEntry("words-ambiguous", 1);
    }

    @Test
    void lemmaWithoutCommonInitialIsDroppedAndTheSurfaceDecides() {
        ParagraphText paragraph = ParagraphText.of(SENTENCE, StatementAnalyzerTest.SENTENCES);
        StatementValidator.Result result = new StatementValidator(PredicateCheck.none()).validate(paragraph,
                new StatementCandidates(List.of(new MentionCandidate("m1", 1, new Words(1, 2, "Die Stelle"),
                        "Behörde", false)), null, null, null, 0), "p0.");

        Mention mention = result.mentions().getFirst();
        assertThat(mention.lemmaCandidate()).isNull();
        assertThat(mention.normalized()).isEqualTo("stelle");
        assertThat(mention.normalization()).isEqualTo(TermNormalizer.FROM_SURFACE);
        assertThat(mention.key()).isEqualTo("p0.m1");
        assertThat(result.rejections()).containsEntry("lemma-dropped", 1);
    }

    @Test
    void participleLemmaKeepsItsInitialAfterGe() {
        assertThat(StatementValidator.checkedLemma("prüfen", "geprüft", new StatementValidator.Result()))
                .isEqualTo("prüfen");
    }

    @Test
    void verbalCheckRejectsANonVerbPredicate() {
        ParagraphText paragraph = ParagraphText.of(SENTENCE, StatementAnalyzerTest.SENTENCES);
        PredicateCheck noVerb = new PredicateCheck() {
            @Override
            public Boolean isVerbal(SentenceText sentence, List<TextSpan> predicate) {
                return false;
            }

            @Override
            public String id() {
                return "test";
            }
        };
        StatementValidator.Result result = new StatementValidator(noVerb).validate(paragraph,
                new StatementCandidates(null, List.of(new StatementCandidates.EventCandidate("e1", 1,
                        new Words(3, 3, "prüft"), null, "prüfen", List.of(), null)), null, null, 0), "p0.");

        assertThat(result.events()).isEmpty();
        assertThat(result.rejections()).containsEntry("predicate-not-verbal", 1);
    }

    @Test
    void numberedWordsAreWhatTheModelSees() {
        assertThat(ParagraphText.of(SENTENCE, StatementAnalyzerTest.SENTENCES).numbered())
                .isEqualTo("Satz 1: [1]Die [2]Stelle [3]prüft [4]die [5]Zahlung [6]und [7]bucht [8]die [9]Zahlung.\n");
    }
}
