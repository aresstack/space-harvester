package com.aresstack.spaceharvester.provingground.player;

import java.nio.file.Path;
import java.util.List;

/**
 * The outcome of one playback.
 *
 * @param testCase   the name of the test case
 * @param steps      one result per step, in order
 * @param traceFile  the Playwright trace of the run, or null
 * @param screenshot the screenshot at the failing step, or null
 */
public record RunResult(String testCase, List<StepResult> steps, Path traceFile, Path screenshot) {

    public RunResult {
        steps = List.copyOf(steps);
    }

    public boolean passed() {
        return steps.stream().allMatch(step -> step.status() == StepResult.Status.PASSED);
    }

    /** The first failed step, or null. */
    public StepResult failure() {
        return steps.stream().filter(step -> step.status() == StepResult.Status.FAILED).findFirst().orElse(null);
    }

    /** One readable block: a line per step, for logs and the chat. */
    public String render() {
        StringBuilder text = new StringBuilder(testCase).append(passed() ? ": bestanden" : ": fehlgeschlagen")
                .append('\n');
        for (StepResult step : steps) {
            text.append("  ").append(step.render()).append('\n');
        }
        if (traceFile != null) {
            text.append("  Trace: ").append(traceFile).append('\n');
        }
        if (screenshot != null) {
            text.append("  Bildschirmfoto: ").append(screenshot).append('\n');
        }
        return text.toString();
    }
}
