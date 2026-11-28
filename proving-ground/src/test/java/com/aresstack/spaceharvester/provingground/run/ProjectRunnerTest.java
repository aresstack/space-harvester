package com.aresstack.spaceharvester.provingground.run;

import com.aresstack.spaceharvester.provingground.model.Check;
import com.aresstack.spaceharvester.provingground.model.Scope;
import com.aresstack.spaceharvester.provingground.model.ScopeEntry;
import com.aresstack.spaceharvester.provingground.model.TestCase;
import com.aresstack.spaceharvester.provingground.model.TestCaseEntry;
import com.aresstack.spaceharvester.provingground.model.TestProject;
import com.aresstack.spaceharvester.provingground.model.TestSuite;
import com.aresstack.spaceharvester.provingground.model.TestUser;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The order of hooks and preconditions, without a browser: the test cases have no steps, so no context opens. */
class ProjectRunnerTest {

    private final List<String> played = new ArrayList<>();
    private final ProjectRunner.CaseSource source = entry -> {
        played.add(entry.id());
        return new TestCase(entry.name());
    };
    private final ContextFactory noBrowser = user -> {
        throw new AssertionError("no context needed");
    };

    private static ScopeEntry var(String name, String expression) {
        return ScopeEntry.variable(name, expression);
    }

    private static ScopeEntry equals(String name, String expression, String expected) {
        return ScopeEntry.check(name, expression, new Check("equals", expected));
    }

    @Test
    void hooksRunInWd4jOrderAndVariablesAreFoundFromTheInsideOut() {
        TestCaseEntry first = TestCaseEntry.of("eins", "Eins", "s/eins.jsonl").withScope(Scope.EMPTY
                .withBeforeAll(List.of(var("x", "fall"), var("seen", "{{p}}{{s}}{{pe}}{{se}}")))
                .withAfterEach(List.of(equals("innen", "{{x}}|{{seen}}", "fall|PSpese"))));
        TestCaseEntry second = TestCaseEntry.of("zwei", "Zwei", "s/zwei.jsonl").withScope(Scope.EMPTY
                .withAfterEach(List.of(equals("ohne eigenes x", "{{x}}", "suite"),
                        equals("Fall-Wert weg", "{{seen}}", "nicht da"))));
        TestSuite suite = TestSuite.of("s", "Suite", List.of(first, second)).withScope(Scope.EMPTY
                .withBeforeAll(List.of(var("s", "S"), var("x", "suite")))
                .withBeforeEach(List.of(var("se", "se")))
                .withAfterAll(List.of(equals("Suite am Ende", "{{x}}", "suite"))));
        TestProject project = new TestProject("P", Scope.EMPTY
                .withBeforeAll(List.of(var("p", "P"), var("x", "projekt")))
                .withBeforeEach(List.of(var("pe", "pe")))
                .withAfterAll(List.of(equals("Projekt am Ende", "{{x}}", "projekt"))),
                List.of(), List.of(), null, List.of(suite));

        ProjectRunResult result = new ProjectRunner().run(project, source, noBrowser, Credentials.NONE,
                RunOptions.defaults());

        CaseRunResult one = result.cases().get(0);
        assertTrue(one.passed(), one.render());
        CaseRunResult two = result.cases().get(1);
        assertTrue(two.checks().get(0).passed(), two.render());
        assertFalse(two.checks().get(1).passed(), "a test case's values end with it");
        assertTrue(two.checks().get(1).error().contains("seen"), two.render());
        assertTrue(result.suites().get(0).checks().get(0).passed());
        assertTrue(result.checks().get(0).passed());
        assertFalse(result.passed());
        assertEquals(List.of("eins", "zwei"), played);
    }

    @Test
    void preconditionsRunDeepestFirstOnceAndNeverTheTestCaseItself() {
        TestCaseEntry login = TestCaseEntry.of("login", "Anmelden", "a/login.jsonl");
        TestCaseEntry start = TestCaseEntry.of("start", "Start", "a/start.jsonl").withPreconditions(List.of("a/login"));
        TestCaseEntry antrag = TestCaseEntry.of("antrag", "Antrag", "b/antrag.jsonl")
                .withPreconditions(List.of("a/start", "a/login"));
        TestProject project = new TestProject("P", Scope.EMPTY, List.of("a/login"), List.of(), null,
                List.of(TestSuite.of("a", "A", List.of(login, start)), TestSuite.of("b", "B", List.of(antrag))));

        ProjectRunResult result = new ProjectRunner().run(project, source, noBrowser, Credentials.NONE,
                RunOptions.defaults().withOnly(List.of("a/login", "b/antrag")));

        assertTrue(result.passed(), result.render());
        assertEquals(List.of("login", "login", "start", "antrag"), played,
                "login alone (it is the project precondition, not its own), then antrag after login and start");
        assertEquals(2, result.cases().get(1).preconditions().size());
        assertThrows(IllegalArgumentException.class, () -> new ProjectRunner().run(project, source, noBrowser,
                Credentials.NONE, RunOptions.defaults().withOnly(List.of("b/nix"))));
    }

    @Test
    void aFailingBeforeEntryStopsTheTestCaseButAfterChecksStillRun() {
        TestCaseEntry broken = TestCaseEntry.of("kaputt", "Kaputt", "s/k.jsonl").withScope(Scope.EMPTY
                .withBeforeAll(List.of(var("pin", "{{secret.Pin}}")))
                .withAfterEach(List.of(ScopeEntry.check("Wert", "{{Echo('da')}}", null))));
        TestProject project = TestProject.of("P", List.of(TestSuite.of("s", "S", List.of(broken))));

        ProjectRunResult result = new ProjectRunner().run(project, source, noBrowser, Credentials.NONE,
                RunOptions.defaults());

        CaseRunResult outcome = result.cases().get(0);
        assertFalse(outcome.passed());
        assertTrue(outcome.setup().get(0).error().contains("secret.Pin"), outcome.render());
        assertNull(outcome.steps());
        assertTrue(played.isEmpty(), "nothing is played after a failed setup");
        assertEquals("da", outcome.checks().get(0).value());
    }

    @Test
    void oneTimeCodesComeFromTheVaultOfTheUserAndNeverShowInResults() {
        Clock at59 = Clock.fixed(Instant.ofEpochSecond(59), ZoneOffset.UTC);
        TestUser sb = new TestUser("sb", "Fachverfahren/SB", "");
        TestCaseEntry entry = TestCaseEntry.of("t", "T", "s/t.jsonl").withScope(Scope.EMPTY
                .withAfterEach(List.of(equals("Code", "{{otp()}}", "287082"), equals("Falsch", "{{otp('sb')}}", "1"))));
        TestProject project = new TestProject("P", Scope.EMPTY, List.of(), List.of(sb), "sb",
                List.of(TestSuite.of("s", "S", List.of(entry))));
        Credentials vault = user -> new UserCredentials("login", () -> "pw",
                () -> "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");

        ProjectRunResult result = new ProjectRunner().run(project, source, noBrowser, vault,
                RunOptions.defaults().withClock(at59));

        List<CheckResult> checks = result.cases().get(0).checks();
        assertTrue(checks.get(0).passed(), result.render());
        assertEquals("{{secret}}", checks.get(0).value(), "the code itself is masked");
        assertFalse(checks.get(1).passed());
        assertFalse(result.render().contains("287082"), result.render());
    }
}
