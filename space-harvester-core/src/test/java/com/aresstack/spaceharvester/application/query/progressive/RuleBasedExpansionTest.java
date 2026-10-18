package com.aresstack.spaceharvester.application.query.progressive;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RuleBasedExpansionTest {

    private final RuleBasedExpansion expansion = new RuleBasedExpansion();

    @Test
    void startsWithAllTermsAndDropsTheWeakestStepByStep() {
        List<String> variants = expansion.expand("Wie beantrage ich eine Verlängerung meiner Dienstreise beim BVA?",
                10);

        assertThat(variants.getFirst()).isEqualTo("beantrage Verlängerung meiner Dienstreise beim BVA");
        // Gewöhnliche Wörter fallen zuerst, die Kennung BVA bleibt am längsten.
        assertThat(variants).containsSubsequence("beantrage Verlängerung meiner Dienstreise beim BVA",
                "Verlängerung Dienstreise BVA", "Verlängerung BVA", "BVA");
        assertThat(variants).doesNotHaveDuplicates();
        for (int i = 1; i < variants.size(); i++) {
            assertThat(variants.get(i).split(" ").length)
                    .isLessThanOrEqualTo(variants.get(i - 1).split(" ").length);
        }
    }

    @Test
    void neverMoreThanAskedAndNeverTheOriginalItself() {
        assertThat(expansion.expand("Dienstreise Verlängerung", 1)).containsExactly("Dienstreise");
        assertThat(expansion.expand("Dienstreise", 5)).isEmpty();
        assertThat(expansion.expand("Wie das?", 5)).isEmpty();
    }

    @Test
    void longQuestionsStayLinearNotCombinatorial() {
        String question = "eins zwei drei vier fünf sechs sieben acht neun zehn elf zwölf";

        List<String> variants = expansion.expand(question, 100);

        assertThat(variants.size()).isLessThanOrEqualTo(2 * RuleBasedExpansion.MAX_TERMS);
        assertThat(variants.getFirst().split(" ")).hasSize(RuleBasedExpansion.MAX_TERMS);
    }
}
