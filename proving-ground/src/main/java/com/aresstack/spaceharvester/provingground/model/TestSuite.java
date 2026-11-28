package com.aresstack.spaceharvester.provingground.model;

import java.util.List;

/**
 * A suite of test cases with its own hooks and preconditions.
 *
 * @param id            unique within the project (also its directory below the project)
 * @param name          shown name
 * @param scope         its hooks
 * @param preconditions test cases played before each of its test cases, as {@code <suite>/<case>}
 * @param cases         its test cases in run order
 * @param description   what the suite covers; may be empty
 */
public record TestSuite(String id, String name, Scope scope, List<String> preconditions, List<TestCaseEntry> cases,
                        String description) {

    public TestSuite {
        if (id == null || id.isBlank() || id.contains("/")) {
            throw new IllegalArgumentException("Ungültige Suite-ID: " + id);
        }
        name = name == null || name.isBlank() ? id : name;
        scope = scope == null ? Scope.EMPTY : scope;
        preconditions = preconditions == null ? List.of() : List.copyOf(preconditions);
        cases = cases == null ? List.of() : List.copyOf(cases);
        description = description == null ? "" : description;
        long distinct = cases.stream().map(TestCaseEntry::id).distinct().count();
        if (distinct != cases.size()) {
            throw new IllegalArgumentException("Suite " + id + ": Testfall-IDs doppelt");
        }
    }

    public static TestSuite of(String id, String name, List<TestCaseEntry> cases) {
        return new TestSuite(id, name, Scope.EMPTY, List.of(), cases, "");
    }

    /** The test case with this id, or null. */
    public TestCaseEntry testCase(String caseId) {
        return cases.stream().filter(entry -> entry.id().equals(caseId)).findFirst().orElse(null);
    }

    public TestSuite withScope(Scope value) {
        return new TestSuite(id, name, value, preconditions, cases, description);
    }

    public TestSuite withPreconditions(List<String> value) {
        return new TestSuite(id, name, scope, value, cases, description);
    }

    public TestSuite withCases(List<TestCaseEntry> value) {
        return new TestSuite(id, name, scope, preconditions, value, description);
    }
}
