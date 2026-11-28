package com.aresstack.spaceharvester.provingground.model;

import java.util.List;

/**
 * The hooks of one level (project, suite, test case), as in WD4J.
 *
 * <ul>
 *   <li>{@code beforeAll}: variables set once when the level starts (project: at the start of the run; suite: before its
 *       first test case; test case: WD4J's "before").</li>
 *   <li>{@code beforeEach}: variables set before every test case below the level (on a test case the same as
 *       beforeAll).</li>
 *   <li>{@code templates}: variables resolved anew on every use.</li>
 *   <li>{@code afterEach}: checks after every test case below the level (on a test case: WD4J's "after").</li>
 *   <li>{@code afterAll}: checks once when the level ends.</li>
 * </ul>
 *
 * Variables are looked up from the inside out: test case before suite before project.
 */
public record Scope(List<ScopeEntry> beforeAll, List<ScopeEntry> beforeEach, List<ScopeEntry> templates,
                    List<ScopeEntry> afterEach, List<ScopeEntry> afterAll) {

    public static final Scope EMPTY = new Scope(List.of(), List.of(), List.of(), List.of(), List.of());

    public Scope {
        beforeAll = copy(beforeAll);
        beforeEach = copy(beforeEach);
        templates = copy(templates);
        afterEach = copy(afterEach);
        afterAll = copy(afterAll);
    }

    public Scope withBeforeAll(List<ScopeEntry> entries) {
        return new Scope(entries, beforeEach, templates, afterEach, afterAll);
    }

    public Scope withBeforeEach(List<ScopeEntry> entries) {
        return new Scope(beforeAll, entries, templates, afterEach, afterAll);
    }

    public Scope withTemplates(List<ScopeEntry> entries) {
        return new Scope(beforeAll, beforeEach, entries, afterEach, afterAll);
    }

    public Scope withAfterEach(List<ScopeEntry> entries) {
        return new Scope(beforeAll, beforeEach, templates, entries, afterAll);
    }

    public Scope withAfterAll(List<ScopeEntry> entries) {
        return new Scope(beforeAll, beforeEach, templates, afterEach, entries);
    }

    public boolean isEmpty() {
        return beforeAll.isEmpty() && beforeEach.isEmpty() && templates.isEmpty() && afterEach.isEmpty()
                && afterAll.isEmpty();
    }

    private static List<ScopeEntry> copy(List<ScopeEntry> entries) {
        return entries == null ? List.of() : List.copyOf(entries);
    }
}
