package com.aresstack.spaceharvester.application.query.progressive;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AiQueryExpansionTest {

    private static final String ORIGINAL = "Warum bricht der Nachtlauf der Abrechnung ab";
    private static final QueryExpansionStrategy RULES = (original, max) -> List.of("regel");

    private final List<String> questions = new CopyOnWriteArrayList<>();

    @Test
    void aValidAnswerGivesTheVariantsInPriorityOrderWithoutTheOriginal() {
        List<String> variants = expansion(answer("""
                Gern, hier die Varianten:
                ```json
                {"queries":[
                  {"text":"Nachtlauf Abrechnung","priority":2},
                  {"text":"Nachtlauf Abrechnung Abbruch","priority":1},
                  {"text":"Nachtlauf","priority":3}
                ]}
                ```""")).expand(ORIGINAL, 8);

        assertThat(variants).containsExactly("Nachtlauf Abrechnung Abbruch", "Nachtlauf Abrechnung", "Nachtlauf");
        assertThat(questions).containsExactly(ORIGINAL);
    }

    @Test
    void brokenJsonFallsBackToTheRules() {
        assertThat(expansion(answer("{\"queries\":[{\"text\":\"Nachtlauf\"")).expand(ORIGINAL, 8))
                .containsExactly("regel");
        assertThat(expansion(answer("Leider keine Varianten.")).expand(ORIGINAL, 8)).containsExactly("regel");
        assertThat(expansion(answer("{\"queries\":\"Nachtlauf\"}")).expand(ORIGINAL, 8)).containsExactly("regel");
    }

    @Test
    void duplicatesOperatorsQuerySyntaxAndTheOriginalAreDropped() {
        List<String> variants = expansion(answer("""
                {"queries":[
                  {"text":"Warum  bricht der Nachtlauf der ABRECHNUNG ab","priority":1},
                  {"text":"Nachtlauf Abrechnung","priority":2},
                  {"text":"nachtlauf   abrechnung","priority":3},
                  {"text":"Nachtlauf AND Abrechnung","priority":4},
                  {"text":"title ~ Nachtlauf","priority":5},
                  {"text":"space = OPS Nachtlauf","priority":6},
                  {"text":"\\"Nachtlauf Abrechnung\\"","priority":7},
                  {"text":"Nachtlauf*","priority":8},
                  {"text":"(Nachtlauf) Abrechnung","priority":9},
                  {"text":"","priority":10},
                  {"text":"%s","priority":11},
                  {"text":"Nachtlauf Abbruch Oracle","priority":12}
                ]}""".formatted("x".repeat(AiQueryExpansion.MAX_VARIANT_CHARS + 1)))).expand(ORIGINAL, 8);

        assertThat(variants).containsExactly("Nachtlauf Abrechnung", "Nachtlauf Abbruch Oracle");
    }

    @Test
    void theCountIsLimited() {
        assertThat(expansion(answer("""
                {"queries":[{"text":"a b c","priority":1},{"text":"a b","priority":2},{"text":"a","priority":3}]}"""))
                .expand(ORIGINAL, 2)).containsExactly("a b c", "a b");
    }

    @Test
    void aTimeoutFallsBackToTheRules() {
        CountDownLatch never = new CountDownLatch(1);
        AiQueryExpansion expansion = new AiQueryExpansion(() -> Optional.of((instructions, question) -> {
            try {
                never.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "{\"queries\":[{\"text\":\"zu spät\",\"priority\":1}]}";
        }), RULES, Duration.ofMillis(100));

        long started = System.nanoTime();
        assertThat(expansion.expand(ORIGINAL, 8)).containsExactly("regel");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void aFailingOrMissingModelFallsBackToTheRules() {
        AiQueryExpansion failing = new AiQueryExpansion(() -> Optional.of((instructions, question) -> {
            throw new IllegalStateException("Chat-Dienst: HTTP 503");
        }), RULES, Duration.ofSeconds(1));
        AiQueryExpansion missing = new AiQueryExpansion(Optional::empty, RULES, Duration.ofSeconds(1));

        assertThat(failing.expand(ORIGINAL, 8)).containsExactly("regel");
        assertThat(missing.expand(ORIGINAL, 8)).containsExactly("regel");
    }

    @Test
    void theInstructionsAskForSearchTermsOnlyAndNeverForQuerySyntax() {
        assertThat(AiQueryExpansion.INSTRUCTIONS).contains("DERSELBEN Frage").contains("keine Abfragesprache wie CQL")
                .contains("\"queries\"").contains("\"priority\"");
    }

    private QueryVariantModel answer(String text) {
        return (instructions, question) -> {
            questions.add(question);
            return text;
        };
    }

    private AiQueryExpansion expansion(QueryVariantModel model) {
        return new AiQueryExpansion(() -> Optional.of(model), RULES, Duration.ofSeconds(5));
    }
}
