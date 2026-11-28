package com.aresstack.spaceharvester.provingground.store;

import com.aresstack.spaceharvester.provingground.model.Check;
import com.aresstack.spaceharvester.provingground.model.Scope;
import com.aresstack.spaceharvester.provingground.model.ScopeEntry;
import com.aresstack.spaceharvester.provingground.model.StepBinding;
import com.aresstack.spaceharvester.provingground.model.StepRole;
import com.aresstack.spaceharvester.provingground.model.TestCase;
import com.aresstack.spaceharvester.provingground.model.TestCaseEntry;
import com.aresstack.spaceharvester.provingground.model.TestProject;
import com.aresstack.spaceharvester.provingground.model.TestStep;
import com.aresstack.spaceharvester.provingground.model.TestSuite;
import com.aresstack.spaceharvester.provingground.model.TestUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectFileTest {

    @TempDir
    Path directory;

    static TestProject sample() {
        Scope projectScope = Scope.EMPTY
                .withBeforeAll(List.of(ScopeEntry.variable("heute", "{{Date('dd.MM.yyyy')}}")))
                .withTemplates(List.of(ScopeEntry.variable("code", "{{otp()}}").withDescription("Einmalcode")))
                .withAfterEach(List.of(ScopeEntry.check("Titel", "{{title()}}", new Check("!contains", "Fehler"))
                        .withEnabled(false)));
        TestCaseEntry login = new TestCaseEntry("login", "Anmelden", ProjectFile.defaultCaseFile("anmeldung", "login"),
                "sb", Scope.EMPTY, List.of(), Map.of("s1", new StepBinding(StepRole.GIVEN, null, "Startseite", null)),
                "");
        TestCaseEntry antrag = new TestCaseEntry("antrag", "Antrag stellen", "antrag/antrag.jsonl", null,
                Scope.EMPTY.withBeforeAll(List.of(ScopeEntry.variable("name", "Bea"))), List.of("anmeldung/login"),
                Map.of("s2", new StepBinding(null, "pruefer", "", Duration.ofSeconds(2))), "Stellt einen Antrag");
        return new TestProject("Fachverfahren", projectScope, List.of(),
                List.of(new TestUser("sb", "Fachverfahren/SB", "Sachbearbeitung"), new TestUser("pruefer", "", "")),
                "sb", List.of(TestSuite.of("anmeldung", "Anmeldung", List.of(login)),
                TestSuite.of("antrag", "Antrag", List.of(antrag)).withPreconditions(List.of())));
    }

    @Test
    void theProjectSurvivesWritingAndReading() throws IOException {
        TestProject project = sample();
        ProjectFile.write(project, directory);
        TestProject read = ProjectFile.read(directory);
        assertEquals(project, read);
        String json = Files.readString(directory.resolve(ProjectFile.FILE_NAME));
        assertTrue(json.contains("\"minDuration\": \"PT2S\""), json);
        assertTrue(json.contains("\"enabled\": false"), json);
        assertTrue(project.problems().isEmpty(), project.problems().toString());
    }

    @Test
    void caseFilesStayInsideTheProject() throws IOException {
        TestCase recorded = new TestCase("Anmelden", List.of(TestStep.navigate("page@1", "{{start}}")));
        TestCaseEntry entry = sample().testCase("anmeldung/login");
        TestCaseFile.write(recorded, ProjectFile.caseFile(directory, entry));
        assertEquals(recorded.steps(), ProjectFile.readCase(directory, entry).steps());
        TestCaseEntry outside = TestCaseEntry.of("x", "x", "../x.jsonl");
        assertThrows(IOException.class, () -> ProjectFile.caseFile(directory, outside));
    }

    @Test
    void problemsNameUnknownReferencesUsersAndCycles() {
        TestCaseEntry a = TestCaseEntry.of("a", "A", "s/a.jsonl").withPreconditions(List.of("s/b"));
        TestCaseEntry b = TestCaseEntry.of("b", "B", "s/b.jsonl").withPreconditions(List.of("s/a"));
        TestCaseEntry c = TestCaseEntry.of("c", "C", "s/c.jsonl").withPreconditions(List.of("s/nix"))
                .withUser("niemand");
        TestProject project = TestProject.of("P", List.of(TestSuite.of("s", "S", List.of(a, b, c))));
        List<String> problems = project.problems();
        assertTrue(problems.contains("s/c: Vorbedingung unbekannt: s/nix"), problems.toString());
        assertTrue(problems.contains("s/c: Benutzer unbekannt: niemand"), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("Vorbedingungen im Kreis: s/a → s/b → s/a")),
                problems.toString());
        assertThrows(IllegalStateException.class, () -> project.preconditionsOf("s/a"));
    }

    @Test
    void bindingsFollowTheStepIdThroughEdits() {
        TestCase recorded = new TestCase("T", List.of(TestStep.navigate("p", "a"), TestStep.navigate("p", "b")));
        String first = recorded.step(0).id();
        String second = recorded.step(1).id();
        TestCaseEntry entry = TestCaseEntry.of("t", "T", "s/t.jsonl")
                .withBinding(first, StepBinding.NONE.withRole(StepRole.GIVEN))
                .withBinding(second, StepBinding.NONE.withDescription("Zweiter"));
        recorded.move(1, 0);
        recorded.insert(1, TestStep.navigate("p", "neu"));
        assertEquals("Zweiter", entry.binding(recorded.step(0).id()).description());
        assertEquals(StepRole.GIVEN, entry.binding(recorded.step(2).id()).role());
        recorded.remove(0);
        TestCaseEntry pruned = entry.prunedTo(recorded);
        assertFalse(pruned.steps().containsKey(second));
        assertTrue(pruned.steps().containsKey(first));
        assertTrue(entry.withBinding(first, StepBinding.NONE).binding(first).isEmpty());
    }
}
