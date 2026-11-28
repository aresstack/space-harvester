package com.aresstack.spaceharvester.provingground.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The root of a test project (WD4J's {@code RootNode}): project-wide hooks and preconditions, the users, the suites.
 * The browser actions are not here: each test case points to its Playwright JSONL file.
 *
 * @param name          shown name
 * @param scope         project-wide hooks
 * @param preconditions test cases played before every test case, as {@code <suite>/<case>}
 * @param users         the users tests run as (references to the vault only)
 * @param defaultUser   the user of test cases without one; null = a context without login
 * @param suites        the suites in run order
 */
public record TestProject(String name, Scope scope, List<String> preconditions, List<TestUser> users,
                          String defaultUser, List<TestSuite> suites) {

    public TestProject {
        name = name == null ? "" : name;
        scope = scope == null ? Scope.EMPTY : scope;
        preconditions = preconditions == null ? List.of() : List.copyOf(preconditions);
        users = users == null ? List.of() : List.copyOf(users);
        defaultUser = defaultUser == null || defaultUser.isBlank() ? null : defaultUser.trim();
        suites = suites == null ? List.of() : List.copyOf(suites);
    }

    public static TestProject of(String name, List<TestSuite> suites) {
        return new TestProject(name, Scope.EMPTY, List.of(), List.of(), null, suites);
    }

    /** The test case of a reference {@code <suite>/<case>}, or null. */
    public TestCaseEntry testCase(String reference) {
        int slash = reference == null ? -1 : reference.indexOf('/');
        if (slash < 0) {
            return null;
        }
        TestSuite suite = suite(reference.substring(0, slash));
        return suite == null ? null : suite.testCase(reference.substring(slash + 1));
    }

    public TestSuite suite(String suiteId) {
        return suites.stream().filter(suite -> suite.id().equals(suiteId)).findFirst().orElse(null);
    }

    public TestUser user(String userName) {
        return users.stream().filter(user -> user.name().equals(userName)).findFirst().orElse(null);
    }

    /** This project with {@code suite} replacing the one of the same id, or appended. */
    public TestProject withSuite(TestSuite suite) {
        List<TestSuite> copy = new ArrayList<>(suites);
        int index = -1;
        for (int i = 0; i < copy.size(); i++) {
            if (copy.get(i).id().equals(suite.id())) {
                index = i;
            }
        }
        if (index < 0) {
            copy.add(suite);
        } else {
            copy.set(index, suite);
        }
        return new TestProject(name, scope, preconditions, users, defaultUser, copy);
    }

    /**
     * An id for {@code name} (lower case, umlauts spelled out, other characters as "-"), not among {@code taken}
     * ("-2", "-3" ... appended if needed).
     */
    public static String newId(String name, java.util.Collection<String> taken) {
        String base = (name == null ? "" : name).toLowerCase(java.util.Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (base.isEmpty()) {
            base = "neu";
        }
        String id = base;
        for (int n = 2; taken.contains(id); n++) {
            id = base + "-" + n;
        }
        return id;
    }

    public static String reference(TestSuite suite, TestCaseEntry testCase) {
        return suite.id() + "/" + testCase.id();
    }

    /**
     * What is wrong with the project, one line each (empty = fine): unknown references, unknown users, preconditions
     * that lead back to themselves.
     */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        Set<String> suiteIds = new HashSet<>();
        Set<String> userNames = new HashSet<>();
        for (TestUser user : users) {
            if (!userNames.add(user.name())) {
                problems.add("Benutzer doppelt: " + user.name());
            }
        }
        if (defaultUser != null && user(defaultUser) == null) {
            problems.add("Standardbenutzer unbekannt: " + defaultUser);
        }
        checkReferences("Projekt", preconditions, problems);
        for (TestSuite suite : suites) {
            if (!suiteIds.add(suite.id())) {
                problems.add("Suite doppelt: " + suite.id());
            }
            checkReferences("Suite " + suite.id(), suite.preconditions(), problems);
            for (TestCaseEntry testCase : suite.cases()) {
                String where = reference(suite, testCase);
                checkReferences(where, testCase.preconditions(), problems);
                if (testCase.user() != null && user(testCase.user()) == null) {
                    problems.add(where + ": Benutzer unbekannt: " + testCase.user());
                }
                testCase.steps().forEach((stepId, binding) -> {
                    if (binding.user() != null && user(binding.user()) == null) {
                        problems.add(where + ": Benutzer unbekannt: " + binding.user());
                    }
                });
                List<String> cycle = cycleFrom(where);
                if (!cycle.isEmpty()) {
                    problems.add("Vorbedingungen im Kreis: " + String.join(" → ", cycle));
                }
            }
        }
        return problems;
    }

    /**
     * The preconditions of {@code reference} in play order (deepest first, each once), without itself; fails if they
     * lead back to a test case already on the way.
     */
    public List<String> preconditionsOf(String reference) {
        List<String> cycle = cycleFrom(reference);
        if (!cycle.isEmpty()) {
            throw new IllegalStateException("Vorbedingungen im Kreis: " + String.join(" → ", cycle));
        }
        LinkedHashSet<String> order = new LinkedHashSet<>();
        collect(reference, order);
        order.remove(reference);
        return List.copyOf(order);
    }

    /** The own preconditions of a test case (not those of its suite and the project). */
    private List<String> direct(String reference) {
        TestCaseEntry testCase = testCase(reference);
        return testCase == null ? List.of() : testCase.preconditions();
    }

    private void collect(String reference, LinkedHashSet<String> order) {
        for (String precondition : direct(reference)) {
            if (!order.contains(precondition)) {
                collect(precondition, order);
            }
        }
        order.add(reference);
    }

    private List<String> cycleFrom(String start) {
        Deque<String> path = new ArrayDeque<>();
        return walk(start, path, new HashSet<>());
    }

    private List<String> walk(String reference, Deque<String> path, Set<String> done) {
        if (path.contains(reference)) {
            List<String> cycle = new ArrayList<>(path);
            java.util.Collections.reverse(cycle);
            cycle = new ArrayList<>(cycle.subList(cycle.indexOf(reference), cycle.size()));
            cycle.add(reference);
            return cycle;
        }
        if (!done.add(reference)) {
            return List.of();
        }
        path.push(reference);
        for (String precondition : direct(reference)) {
            List<String> cycle = walk(precondition, path, done);
            if (!cycle.isEmpty()) {
                return cycle;
            }
        }
        path.pop();
        return List.of();
    }

    private void checkReferences(String where, List<String> references, List<String> problems) {
        for (String reference : references) {
            if (testCase(reference) == null) {
                problems.add(where + ": Vorbedingung unbekannt: " + reference);
            }
        }
    }
}
