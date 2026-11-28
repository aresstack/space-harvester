package com.aresstack.spaceharvester.provingground.run;

import com.aresstack.spaceharvester.provingground.player.RunResult;

import java.nio.file.Path;
import java.util.List;

/**
 * The outcome of one test case in a project run.
 *
 * @param reference     {@code <suite>/<case>}
 * @param name          the test case's name
 * @param setup         failed before entries (empty = setup fine)
 * @param preconditions the played preconditions, in order
 * @param steps         the test case's own steps, or null when it did not get that far
 * @param checks        the after checks (test case, suite, project)
 * @param traces        the Playwright traces, one per user
 * @param error         why it could not run (file missing ...), or null
 */
public record CaseRunResult(String reference, String name, List<CheckResult> setup, List<RunResult> preconditions,
                            RunResult steps, List<CheckResult> checks, List<Path> traces, String error) {

    public CaseRunResult {
        setup = List.copyOf(setup);
        preconditions = List.copyOf(preconditions);
        checks = List.copyOf(checks);
        traces = List.copyOf(traces);
    }

    public boolean passed() {
        return error == null && setup.isEmpty() && preconditions.stream().allMatch(RunResult::passed)
                && steps != null && steps.passed() && checks.stream().allMatch(CheckResult::passed);
    }

    public String render() {
        StringBuilder text = new StringBuilder(passed() ? "✓ " : "✗ ").append(name).append(" (").append(reference)
                .append(")\n");
        if (error != null) {
            text.append("  ").append(error).append('\n');
        }
        setup.forEach(check -> text.append("  ").append(check.render()).append('\n'));
        for (RunResult precondition : preconditions) {
            text.append("  Vorbedingung ").append(precondition.render().replace("\n", "\n  ").stripTrailing())
                    .append('\n');
        }
        if (steps != null) {
            text.append("  ").append(steps.render().replace("\n", "\n  ").stripTrailing()).append('\n');
        }
        checks.forEach(check -> text.append("  ").append(check.render()).append('\n'));
        traces.forEach(trace -> text.append("  Trace: ").append(trace).append('\n'));
        return text.toString();
    }
}
