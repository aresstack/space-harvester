package com.aresstack.spaceharvester.provingground.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A test case in the project: where its Playwright actions are (a JSONL file, {@link TestCase}) and its subject matter
 * on top of them. Every test case can serve as a precondition of others.
 *
 * @param id            unique within its suite; referenced as {@code <suite>/<id>}
 * @param name          shown name
 * @param file          the JSONL file, relative to the project directory
 * @param user          the user its steps run as unless a step says otherwise; null = the project's default user
 * @param scope         its hooks (before, templates, after)
 * @param preconditions test cases played before it, as {@code <suite>/<case>}
 * @param steps         the subject matter per step id
 * @param description   what the test case checks; may be empty
 */
public record TestCaseEntry(String id, String name, String file, String user, Scope scope, List<String> preconditions,
                            Map<String, StepBinding> steps, String description) {

    public TestCaseEntry {
        if (id == null || id.isBlank() || id.contains("/")) {
            throw new IllegalArgumentException("Ungültige Testfall-ID: " + id);
        }
        name = name == null || name.isBlank() ? id : name;
        if (file == null || file.isBlank()) {
            throw new IllegalArgumentException("Testfall " + id + " ohne Datei");
        }
        user = user == null || user.isBlank() ? null : user.trim();
        scope = scope == null ? Scope.EMPTY : scope;
        preconditions = preconditions == null ? List.of() : List.copyOf(preconditions);
        Map<String, StepBinding> copy = new LinkedHashMap<>();
        if (steps != null) {
            steps.forEach((stepId, binding) -> {
                if (binding != null && !binding.isEmpty()) {
                    copy.put(stepId, binding);
                }
            });
        }
        steps = java.util.Collections.unmodifiableMap(copy);
        description = description == null ? "" : description;
    }

    /** A test case with only its file. */
    public static TestCaseEntry of(String id, String name, String file) {
        return new TestCaseEntry(id, name, file, null, Scope.EMPTY, List.of(), Map.of(), "");
    }

    /** The subject matter of the step, or {@link StepBinding#NONE}. */
    public StepBinding binding(String stepId) {
        return steps.getOrDefault(stepId, StepBinding.NONE);
    }

    public TestCaseEntry withBinding(String stepId, StepBinding binding) {
        Map<String, StepBinding> copy = new LinkedHashMap<>(steps);
        if (binding == null || binding.isEmpty()) {
            copy.remove(stepId);
        } else {
            copy.put(stepId, binding);
        }
        return new TestCaseEntry(id, name, file, user, scope, preconditions, copy, description);
    }

    /** Without the bindings of steps that are no longer in {@code testCase} (deleted in the editor). */
    public TestCaseEntry prunedTo(TestCase testCase) {
        Set<String> present = testCase.steps().stream().map(TestStep::id).collect(Collectors.toSet());
        Map<String, StepBinding> copy = new LinkedHashMap<>(steps);
        copy.keySet().retainAll(present);
        return new TestCaseEntry(id, name, file, user, scope, preconditions, copy, description);
    }

    public TestCaseEntry withUser(String value) {
        return new TestCaseEntry(id, name, file, value, scope, preconditions, steps, description);
    }

    public TestCaseEntry withScope(Scope value) {
        return new TestCaseEntry(id, name, file, user, value, preconditions, steps, description);
    }

    public TestCaseEntry withPreconditions(List<String> value) {
        return new TestCaseEntry(id, name, file, user, scope, value, steps, description);
    }
}
