package com.aresstack.spaceharvester.application.knowledge;

import com.aresstack.spaceharvester.application.stepmodel.StepModel;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Chat-Modell für Tests: antwortet über eine Funktion der Eingabe und merkt sich die Eingaben. */
final class FakeStepModel implements StepModel {

    final List<String> inputs = new ArrayList<>();
    String model = "fake-chat";
    boolean available = true;
    Function<String, String> answer;

    FakeStepModel(Function<String, String> answer) {
        this.answer = answer;
    }

    @Override
    public String version() {
        return "statement-model:" + model + "@0000";
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public synchronized String complete(String instructions, String input) {
        inputs.add(input);
        return answer.apply(input);
    }
}
