package com.aresstack.spaceharvester.provingground.run;

import java.util.List;

/**
 * The outcome of a project run.
 *
 * @param name   the project's name
 * @param setup  failed project beforeAll entries
 * @param suites the suites that ran
 * @param checks the project's afterAll checks
 */
public record ProjectRunResult(String name, List<CheckResult> setup, List<SuiteRunResult> suites,
                               List<CheckResult> checks) {

    public ProjectRunResult {
        setup = List.copyOf(setup);
        suites = List.copyOf(suites);
        checks = List.copyOf(checks);
    }

    public boolean passed() {
        return setup.isEmpty() && suites.stream().allMatch(SuiteRunResult::passed)
                && checks.stream().allMatch(CheckResult::passed);
    }

    /** All test cases that ran, in order. */
    public List<CaseRunResult> cases() {
        return suites.stream().flatMap(suite -> suite.cases().stream()).toList();
    }

    /** One readable block for logs and the chat. */
    public String render() {
        StringBuilder text = new StringBuilder(name).append(passed() ? ": bestanden" : ": fehlgeschlagen").append('\n');
        setup.forEach(check -> text.append(check.render()).append('\n'));
        for (SuiteRunResult suite : suites) {
            text.append("Suite ").append(suite.name()).append('\n');
            suite.setup().forEach(check -> text.append("  ").append(check.render()).append('\n'));
            for (CaseRunResult testCase : suite.cases()) {
                text.append("  ").append(testCase.render().replace("\n", "\n  ").stripTrailing()).append('\n');
            }
            suite.checks().forEach(check -> text.append("  ").append(check.render()).append('\n'));
        }
        checks.forEach(check -> text.append(check.render()).append('\n'));
        return text.toString();
    }
}
