package com.aresstack.spaceharvester.provingground.run;

import com.aresstack.spaceharvester.provingground.player.JUnitExport;

import com.aresstack.spaceharvester.provingground.expressions.ExpressionException;
import com.aresstack.spaceharvester.provingground.model.ScopeEntry;
import com.aresstack.spaceharvester.provingground.model.StepBinding;
import com.aresstack.spaceharvester.provingground.model.StepRole;
import com.aresstack.spaceharvester.provingground.model.TestCase;
import com.aresstack.spaceharvester.provingground.model.TestCaseEntry;
import com.aresstack.spaceharvester.provingground.model.TestProject;
import com.aresstack.spaceharvester.provingground.model.TestStep;
import com.aresstack.spaceharvester.provingground.model.TestSuite;
import com.aresstack.spaceharvester.provingground.model.TestUser;
import com.aresstack.spaceharvester.provingground.player.PlaybackOptions;
import com.aresstack.spaceharvester.provingground.player.Pages;
import com.aresstack.spaceharvester.provingground.player.RunResult;
import com.aresstack.spaceharvester.provingground.player.StepResult;
import com.aresstack.spaceharvester.provingground.player.StepEnvironment;
import com.aresstack.spaceharvester.provingground.player.TestPlayer;
import com.microsoft.playwright.BrowserContext;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Runs a test project in WD4J's order, the browser actions over the Playwright API ({@link TestPlayer}):
 *
 * <pre>
 * project beforeAll
 *   suite beforeAll                                 (before its first test case)
 *     per test case:
 *       project beforeEach, suite beforeEach, test case beforeAll/beforeEach
 *       preconditions (project, suite, test case; each with its own, deepest first, each once)
 *       the steps (each as its user, in that user's own browser context)
 *       test case afterEach/afterAll, suite afterEach, project afterEach
 *   suite afterAll
 * project afterAll
 * </pre>
 *
 * A failing before entry stops what depends on it (the test case, or the suite or project it belongs to); a failing
 * precondition stops its test case. After checks run anyway, they may explain the failure. Every test case starts with
 * fresh browser contexts.
 */
public final class ProjectRunner {

    /** Reads the actions of a test case (the JSONL file in the project directory). */
    @FunctionalInterface
    public interface CaseSource {
        TestCase read(TestCaseEntry testCase) throws IOException;

        /** Where the reference images of the test case's screenshot comparisons are, or null (none known). */
        default Path screenshotReferences(TestCaseEntry testCase) {
            return null;
        }
    }

    private final TestPlayer player = new TestPlayer();

    /** Runs the project in {@code directory} (its test case files), with users' access data from {@code credentials}. */
    public ProjectRunResult run(TestProject project, Path directory, ContextFactory contexts, Credentials credentials,
                                RunOptions options) {
        return run(project, new CaseSource() {
            @Override
            public TestCase read(TestCaseEntry testCase) throws IOException {
                return com.aresstack.spaceharvester.provingground.store.ProjectFile.readCase(directory, testCase);
            }

            @Override
            public Path screenshotReferences(TestCaseEntry testCase) {
                return com.aresstack.spaceharvester.provingground.store.ProjectFile.screenshotReferences(directory,
                        testCase);
            }
        }, contexts, credentials, options);
    }

    public ProjectRunResult run(TestProject project, CaseSource source, ContextFactory contexts,
                                Credentials credentials, RunOptions options) {
        List<String> problems = project.problems();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Testprojekt fehlerhaft:\n" + String.join("\n", problems));
        }
        for (String reference : options.only()) {
            if (project.testCase(reference) == null) {
                throw new IllegalArgumentException("Testfall unbekannt: " + reference);
            }
        }
        Run run = new Run(project, source, contexts, credentials, options);
        return run.project();
    }

    private final class Run {

        private final TestProject project;
        private final CaseSource source;
        private final ContextFactory contexts;
        private final RunOptions options;
        private final RunVariables variables;
        private Pages pages = new Pages();

        Run(TestProject project, CaseSource source, ContextFactory contexts, Credentials credentials,
            RunOptions options) {
            this.project = project;
            this.source = source;
            this.contexts = contexts;
            this.options = options;
            this.variables = new RunVariables(credentials);
            variables.functions(RunFunctions.registry(project, options.clock()));
            variables.page(() -> pages.current());
        }

        ProjectRunResult project() {
            TestUser defaultUser = project.user(project.defaultUser());
            variables.enter("Projekt", project.scope().templates());
            List<CheckResult> setup = set("Projekt · beforeAll", project.scope().beforeAll(), defaultUser);
            List<SuiteRunResult> suites = new ArrayList<>();
            if (setup.isEmpty()) {
                for (TestSuite suite : project.suites()) {
                    List<TestCaseEntry> selected = suite.cases().stream()
                            .filter(testCase -> options.only().isEmpty()
                                    || options.only().contains(TestProject.reference(suite, testCase)))
                            .toList();
                    if (!selected.isEmpty()) {
                        suites.add(suite(suite, selected));
                    }
                }
            }
            List<CheckResult> checks = check("Projekt · afterAll", project.scope().afterAll(), defaultUser);
            variables.leave();
            return new ProjectRunResult(project.name(), setup, suites, checks);
        }

        private SuiteRunResult suite(TestSuite suite, List<TestCaseEntry> selected) {
            TestUser defaultUser = project.user(project.defaultUser());
            String label = "Suite " + suite.id();
            variables.enter(label, suite.scope().templates());
            List<CheckResult> setup = set(label + " · beforeAll", suite.scope().beforeAll(), defaultUser);
            List<CaseRunResult> cases = new ArrayList<>();
            if (setup.isEmpty()) {
                for (TestCaseEntry testCase : selected) {
                    cases.add(testCase(suite, testCase));
                }
            }
            List<CheckResult> checks = check(label + " · afterAll", suite.scope().afterAll(), defaultUser);
            variables.leave();
            return new SuiteRunResult(suite.id(), suite.name(), setup, cases, checks);
        }

        private CaseRunResult testCase(TestSuite suite, TestCaseEntry entry) {
            String reference = TestProject.reference(suite, entry);
            TestUser user = userOf(entry);
            List<CheckResult> setup = new ArrayList<>();
            // beforeEach values belong to their level but are set anew for every test case
            setup.addAll(setOn(1, "Projekt · beforeEach", project.scope().beforeEach(), user));
            setup.addAll(setOn(0, "Suite " + suite.id() + " · beforeEach", suite.scope().beforeEach(), user));
            variables.enter(reference, entry.scope().templates());
            pages = new Pages();
            List<RunResult> preconditions = new ArrayList<>();
            List<TestCaseEntry> before = new ArrayList<>();
            RunResult steps = null;
            String error = null;
            List<CheckResult> checks = new ArrayList<>();
            Path output = options.outputDirectory() == null ? null
                    : options.outputDirectory().resolve(UserSessions.safe(suite.id()))
                    .resolve(UserSessions.safe(entry.id()));
            UserSessions sessions = new UserSessions(contexts, options.actionTimeout(), output, entry.name());
            try {
                if (setup.isEmpty()) {
                    setup.addAll(set(reference + " · beforeAll", entry.scope().beforeAll(), user));
                    setup.addAll(set(reference + " · beforeEach", entry.scope().beforeEach(), user));
                }
                if (setup.isEmpty()) {
                    boolean ready = true;
                    for (String precondition : preconditionsOf(suite, entry)) {
                        TestCaseEntry other = project.testCase(precondition);
                        variables.enter(precondition, other.scope().templates());
                        try {
                            List<CheckResult> own = new ArrayList<>();
                            own.addAll(set(precondition + " · beforeAll", other.scope().beforeAll(), userOf(other)));
                            own.addAll(set(precondition + " · beforeEach", other.scope().beforeEach(),
                                    userOf(other)));
                            setup.addAll(own);
                            if (!own.isEmpty()) {
                                ready = false;
                                break;
                            }
                            if (options.junit() != null) {
                                // one exported test, so a login in a precondition carries over to the case
                                before.add(other);
                                checks.addAll(check(precondition + " · afterEach", other.scope().afterEach(),
                                        userOf(other)));
                                checks.addAll(check(precondition + " · afterAll", other.scope().afterAll(),
                                        userOf(other)));
                                continue;
                            }
                            RunResult played = play(other, sessions, output, "vorbedingung-" + other.id());
                            preconditions.add(played);
                            checks.addAll(check(precondition + " · afterEach", other.scope().afterEach(),
                                    userOf(other)));
                            checks.addAll(check(precondition + " · afterAll", other.scope().afterAll(),
                                    userOf(other)));
                            if (!played.passed()) {
                                ready = false;
                                break;
                            }
                        } finally {
                            variables.leave();
                        }
                    }
                    if (ready && options.junit() != null) {
                        steps = asJUnit(before, entry);
                    } else if (ready) {
                        steps = play(entry, sessions, output, "fehler");
                    }
                }
            } catch (IOException | RuntimeException failed) {
                error = variables.redact(failed.getMessage() == null ? failed.toString() : failed.getMessage());
            }
            checks.addAll(check(reference + " · afterEach", entry.scope().afterEach(), user));
            checks.addAll(check(reference + " · afterAll", entry.scope().afterAll(), user));
            variables.leave();
            checks.addAll(check("Suite " + suite.id() + " · afterEach", suite.scope().afterEach(), user));
            checks.addAll(check("Projekt · afterEach", project.scope().afterEach(), user));
            sessions.close();
            pages = new Pages();
            return new CaseRunResult(reference, entry.name(), setup, preconditions, steps, checks, sessions.traces(),
                    error);
        }

        private RunResult play(TestCaseEntry entry, UserSessions sessions, Path output, String screenshotName)
                throws IOException {
            TestCase testCase = source.read(entry);
            PlaybackOptions playback = PlaybackOptions.defaults().withActionTimeout(options.actionTimeout())
                    .withScreenshotOnFailure(output == null ? null : output.resolve(screenshotName + ".png"))
                    .withScreenshotReferences(source.screenshotReferences(entry));
            return player.play(testCase, new Environment(entry, sessions), pages, playback);
        }

        /**
         * The test case, after the steps of its preconditions, as one exported JUnit test, run with this run's values:
         * users, passwords and one-time codes as the steps would get them (from the application's vault), handed into
         * the test. The test never reads the vault itself.
         */
        private RunResult asJUnit(List<TestCaseEntry> preconditions, TestCaseEntry entry) throws IOException {
            List<TestStep> steps = new ArrayList<>();
            List<String> users = new ArrayList<>();
            List<TestCaseEntry> all = new ArrayList<>(preconditions);
            all.add(entry);
            for (TestCaseEntry part : all) {
                TestCase read = source.read(part);
                for (int i = 0; i < read.size(); i++) {
                    steps.add(read.step(i));
                    users.add(project.userName(part, read.step(i)));
                }
            }
            TestCase testCase = new TestCase(entry.name(), steps);
            JUnitExport.Result exported = JUnitExport.export(testCase, users, options.junit());
            String description = "JUnit-Test " + exported.className();
            long started = System.nanoTime();
            try {
                JUnitExport.run(exported, (text, user) -> {
                    try {
                        return variables.resolve(text, project.user(user));
                    } catch (ExpressionException missing) {
                        throw new IllegalStateException(missing.getMessage(), missing);
                    }
                });
                return new RunResult(testCase.name(), List.of(new StepResult(0, description, StepResult.Status.PASSED,
                        (System.nanoTime() - started) / 1_000_000L, null)), null, null);
            } catch (Throwable failed) {
                if (failed instanceof Error error && !(failed instanceof AssertionError)) {
                    throw error;
                }
                String message = failed.getMessage() == null ? failed.toString() : failed.getMessage();
                return new RunResult(testCase.name(), List.of(new StepResult(0, description, StepResult.Status.FAILED,
                        (System.nanoTime() - started) / 1_000_000L, variables.redact(message.strip()))), null, null);
            }
        }

        /** Project, suite and own preconditions with theirs, deepest first, each once, never the test case itself. */
        private List<String> preconditionsOf(TestSuite suite, TestCaseEntry entry) {
            String self = TestProject.reference(suite, entry);
            LinkedHashSet<String> order = new LinkedHashSet<>();
            List<String> direct = new ArrayList<>(project.preconditions());
            direct.addAll(suite.preconditions());
            direct.addAll(entry.preconditions());
            for (String reference : direct) {
                order.addAll(project.preconditionsOf(reference));
                order.add(reference);
            }
            for (String own : project.preconditionsOf(self)) {
                order.add(own);
            }
            order.remove(self);
            return List.copyOf(order);
        }

        private TestUser userOf(TestCaseEntry entry) {
            return project.user(project.userName(entry, null));
        }

        private TestUser userOf(TestCaseEntry entry, TestStep step) {
            return project.user(project.userName(entry, step));
        }

        /** Before entries onto the innermost level; answers the failed ones. */
        private List<CheckResult> set(String hook, List<ScopeEntry> entries, TestUser user) {
            return setOn(0, hook, entries, user);
        }

        /** Before entries onto the level {@code outward} steps out from the innermost one. */
        private List<CheckResult> setOn(int outward, String hook, List<ScopeEntry> entries, TestUser user) {
            List<CheckResult> failed = new ArrayList<>();
            for (ScopeEntry entry : entries) {
                if (!entry.enabled()) {
                    continue;
                }
                try {
                    variables.setOn(outward, entry, user);
                } catch (ExpressionException | RuntimeException broken) {
                    failed.add(new CheckResult(hook, entry.name(), entry.description(), null, false,
                            variables.redact(message(broken))));
                }
            }
            return failed;
        }

        /** After entries: evaluate, check, report. */
        private List<CheckResult> check(String hook, List<ScopeEntry> entries, TestUser user) {
            List<CheckResult> results = new ArrayList<>();
            for (ScopeEntry entry : entries) {
                if (!entry.enabled()) {
                    continue;
                }
                try {
                    String value = variables.resolve(entry.expression(), user);
                    boolean passed = true;
                    String error = null;
                    if (entry.check() != null && !entry.check().type().isEmpty()) {
                        String expected = variables.resolve(entry.check().expected(), user);
                        passed = entry.check().test(value, expected);
                        if (!passed) {
                            error = "Prüfung nicht erfüllt: " + entry.check().type() + " \"" + expected + "\"";
                        }
                    }
                    results.add(new CheckResult(hook, entry.name(), entry.description(), variables.redact(value),
                            passed, error == null ? null : variables.redact(error)));
                } catch (ExpressionException | RuntimeException broken) {
                    results.add(new CheckResult(hook, entry.name(), entry.description(), null, false,
                            variables.redact(message(broken))));
                }
            }
            return results;
        }

        private static String message(Throwable broken) {
            return broken.getMessage() == null ? broken.getClass().getSimpleName() : broken.getMessage();
        }

        /** The steps of one test case: its users' contexts, its values, its descriptions. */
        private final class Environment implements StepEnvironment {

            private final TestCaseEntry entry;
            private final UserSessions sessions;

            Environment(TestCaseEntry entry, UserSessions sessions) {
                this.entry = entry;
                this.sessions = sessions;
            }

            @Override
            public BrowserContext context(TestStep step) {
                return sessions.context(userOf(entry, step));
            }

            @Override
            public String resolve(String value, TestStep step) {
                try {
                    return variables.resolve(value, userOf(entry, step));
                } catch (ExpressionException missing) {
                    throw new IllegalStateException(missing.getMessage(), missing);
                }
            }

            @Override
            public String redact(String text) {
                return variables.redact(text);
            }

            @Override
            public String describe(TestStep step) {
                StepBinding binding = entry.binding(step.id());
                StepRole role = binding.role() != null ? binding.role() : StepRole.of(step);
                String what = binding.description().isEmpty() ? variables.redact(step.describe())
                        : binding.description();
                String user = binding.user() != null ? " [" + binding.user() + "]" : "";
                return label(role) + " " + what + user;
            }

            @Override
            public Duration minDuration(TestStep step) {
                return entry.binding(step.id()).minDuration();
            }
        }
    }

    static String label(StepRole role) {
        return switch (role) {
            case GIVEN -> "Given";
            case WHEN -> "When";
            case THEN -> "Then";
        };
    }
}
