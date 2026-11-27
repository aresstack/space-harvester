package com.aresstack.spaceharvester.provingground.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A recorded test case: a name and its steps in Playwright's action format ({@link TestStep}). Suites, Given/When/Then,
 * variables and users (the WD4J subject matter) come on top of it later, kept apart from the actions. Not thread-safe;
 * the editor and the recorder work on it from one thread.
 */
public final class TestCase {

    private String name;
    private final List<TestStep> steps = new ArrayList<>();

    public TestCase(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public TestCase(String name, List<TestStep> steps) {
        this(name);
        this.steps.addAll(steps);
    }

    public String name() {
        return name;
    }

    public void rename(String newName) {
        name = Objects.requireNonNull(newName, "newName");
    }

    /** The steps in order (read-only view). */
    public List<TestStep> steps() {
        return Collections.unmodifiableList(steps);
    }

    public int size() {
        return steps.size();
    }

    public TestStep step(int index) {
        return steps.get(index);
    }

    public void add(TestStep step) {
        steps.add(Objects.requireNonNull(step, "step"));
    }

    public void insert(int index, TestStep step) {
        steps.add(index, Objects.requireNonNull(step, "step"));
    }

    public void replace(int index, TestStep step) {
        steps.set(index, Objects.requireNonNull(step, "step"));
    }

    public TestStep remove(int index) {
        return steps.remove(index);
    }

    /** Moves the step at {@code from} so that it ends up at {@code to}. */
    public void move(int from, int to) {
        TestStep step = steps.remove(from);
        steps.add(to, step);
    }

    /** The last step, or null. */
    public TestStep last() {
        return steps.isEmpty() ? null : steps.get(steps.size() - 1);
    }
}
