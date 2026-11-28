package com.aresstack.spaceharvester.provingground.run;

import java.util.List;

/**
 * The outcome of one suite.
 *
 * @param id     the suite id
 * @param name   its name
 * @param setup  failed beforeAll entries
 * @param cases  its test cases that ran
 * @param checks its afterAll checks
 */
public record SuiteRunResult(String id, String name, List<CheckResult> setup, List<CaseRunResult> cases,
                             List<CheckResult> checks) {

    public SuiteRunResult {
        setup = List.copyOf(setup);
        cases = List.copyOf(cases);
        checks = List.copyOf(checks);
    }

    public boolean passed() {
        return setup.isEmpty() && cases.stream().allMatch(CaseRunResult::passed)
                && checks.stream().allMatch(CheckResult::passed);
    }
}
