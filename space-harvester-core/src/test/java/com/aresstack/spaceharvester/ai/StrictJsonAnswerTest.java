package com.aresstack.spaceharvester.ai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StrictJsonAnswerTest {

    @Test
    void readsTheObjectAroundSurroundingText() {
        assertThat(StrictJsonAnswer.object("Hier: {\"a\": 1} Ende").path("a").asInt()).isEqualTo(1);
    }

    @Test
    void unreadableAnswersNameOnlyTheirShape() {
        assertThatThrownBy(() -> StrictJsonAnswer.object(" ")).hasMessage("empty answer");
        assertThatThrownBy(() -> StrictJsonAnswer.object("geheim")).hasMessage("no JSON object in 6 characters");
        assertThatThrownBy(() -> StrictJsonAnswer.object("{geheim}")).hasMessageStartingWith("unreadable JSON: ")
                .hasMessageNotContaining("geheim");
    }

    @Test
    void repairsOnceWithThePreviousAnswer() {
        List<String> repairs = new ArrayList<>();
        String value = StrictJsonAnswer.read(new StrictJsonAnswer.Asker() {
            @Override
            public String ask() {
                return "kein JSON";
            }

            @Override
            public String repair(String previous, String instruction) {
                repairs.add(previous + "|" + instruction);
                return "{\"a\": \"b\"}";
            }
        }, answer -> StrictJsonAnswer.object(answer).path("a").asString());
        assertThat(value).isEqualTo("b");
        assertThat(repairs).containsExactly("kein JSON|" + StrictJsonAnswer.REPAIR);
    }

    @Test
    void aSecondUnreadableAnswerCombinesBothReasons() {
        assertThatThrownBy(() -> StrictJsonAnswer.read(new StrictJsonAnswer.Asker() {
            @Override
            public String ask() {
                return "";
            }

            @Override
            public String repair(String previous, String instruction) {
                return "immer noch nicht";
            }
        }, StrictJsonAnswer::object)).isInstanceOf(StrictJsonAnswer.Unreadable.class)
                .hasMessage("empty answer; after repair: no JSON object in 16 characters");
    }
}
