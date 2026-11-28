package com.aresstack.spaceharvester.app.ui.proving;

import com.aresstack.spaceharvester.provingground.model.StepBinding;
import com.aresstack.spaceharvester.provingground.model.StepRole;
import com.aresstack.spaceharvester.provingground.model.TestCase;
import com.aresstack.spaceharvester.provingground.model.TestCaseEntry;
import com.aresstack.spaceharvester.provingground.model.TestProject;
import com.aresstack.spaceharvester.provingground.model.TestStep;
import com.aresstack.spaceharvester.provingground.model.TestSuite;
import com.aresstack.spaceharvester.provingground.store.ProjectFile;
import com.aresstack.spaceharvester.provingground.store.TestCaseFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.JTree;
import javax.swing.SwingUtilities;

import static org.assertj.core.api.Assertions.assertThat;

class ProvingGroundWorkspaceTest {

    @TempDir
    Path directory;

    @Test
    void theProjectTreeAndTheStepsOfTheChosenTestCaseAreShown() throws Exception {
        TestCase recorded = new TestCase("Anmelden", List.of(TestStep.navigate("p", "{{start}}"),
                TestStep.assertText("p", "#status", "Angemeldet", false)));
        TestCaseFile.write(recorded, directory.resolve("anmeldung/login.jsonl"));
        TestCaseEntry login = TestCaseEntry.of("login", "Anmelden", "anmeldung/login.jsonl")
                .withUser("sb")
                .withBinding(recorded.step(0).id(), StepBinding.NONE.withRole(StepRole.GIVEN)
                        .withDescription("Startseite öffnen"));
        ProjectFile.write(new TestProject("Fachverfahren", null, List.of(),
                List.of(new com.aresstack.spaceharvester.provingground.model.TestUser("sb", "Test/SB", "")), "sb",
                List.of(TestSuite.of("anmeldung", "Anmeldung", List.of(login)))), directory);

        AtomicReference<ProvingGroundWorkspace> workspace = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> workspace.set(new ProvingGroundWorkspace(() -> null, () -> directory)));
        SwingUtilities.invokeAndWait(() -> workspace.get().open(directory));
        JTree tree = find(workspace.get(), JTree.class);
        await(() -> tree.getRowCount() == 3);
        SwingUtilities.invokeAndWait(() -> tree.setSelectionRow(2));
        ProvingGroundWorkspace.StepsModel steps = workspace.get().steps();
        await(() -> steps.getRowCount() == 2);
        assertThat(steps.getValueAt(0, 1)).isEqualTo("Given");
        assertThat(steps.getValueAt(0, 2)).isEqualTo("sb");
        assertThat(steps.getValueAt(0, 3)).isEqualTo("Startseite öffnen");
        assertThat(steps.getValueAt(1, 1)).isEqualTo("Then");
        assertThat((String) steps.getValueAt(1, 3)).startsWith("assertText #status");
        assertThat(workspace.get().perspective().ready()).isTrue();

        // Bearbeiten: Fachliches in der Tabelle, Verschieben, Löschen – alles sofort gespeichert, Zuordnung per ID.
        String assertId = recorded.step(1).id();
        SwingUtilities.invokeAndWait(() -> steps.setValueAt("Angemeldet prüfen", 1, 3));
        workspace.get().flush();
        assertThat(ProjectFile.read(directory).testCase("anmeldung/login").binding(assertId).description())
                .isEqualTo("Angemeldet prüfen");
        SwingUtilities.invokeAndWait(() -> {
            javax.swing.JTable table = find(workspace.get(), javax.swing.JTable.class);
            table.setRowSelectionInterval(1, 1);
            workspace.get().moveSelected(-1);
        });
        workspace.get().flush();
        TestCase moved = TestCaseFile.read(directory.resolve("anmeldung/login.jsonl"));
        assertThat(moved.step(0).id()).isEqualTo(assertId);
        assertThat(steps.getValueAt(0, 3)).isEqualTo("Angemeldet prüfen");
        SwingUtilities.invokeAndWait(() -> workspace.get().deleteStep(0));
        workspace.get().flush();
        assertThat(TestCaseFile.read(directory.resolve("anmeldung/login.jsonl")).size()).isEqualTo(1);
        assertThat(ProjectFile.read(directory).testCase("anmeldung/login").steps()).doesNotContainKey(assertId);

        // Neue Suite und ein neuer Testfall bekommen lesbare IDs und eine eigene Datei.
        SwingUtilities.invokeAndWait(() -> workspace.get().createSuite("Konto & Daten"));
        workspace.get().flush();
        await(() -> tree.getRowCount() == 4);
        assertThat(ProjectFile.read(directory).suite("konto-daten")).isNotNull();
        TestCaseEntry fresh = ProvingGroundWorkspace.newCase(ProjectFile.read(directory).suite("anmeldung"),
                "Login");
        assertThat(fresh.id()).isEqualTo("login-2");
        assertThat(fresh.file()).isEqualTo("anmeldung/login-2.jsonl");
    }

    @Test
    void anEmptyFolderOffersANewProject() throws Exception {
        AtomicReference<ProvingGroundWorkspace> workspace = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> workspace.set(new ProvingGroundWorkspace(() -> null, () -> directory)));
        SwingUtilities.invokeAndWait(() -> workspace.get().open(directory));
        await(() -> ((com.aresstack.spaceharvester.app.ui.controls.StatusLabel) workspace.get().statusLine()).note()
                .text().startsWith("Kein Testprojekt"));
        SwingUtilities.invokeAndWait(() -> workspace.get().createProject());
        await(() -> Files.exists(directory.resolve(ProjectFile.FILE_NAME)));
        assertThat(ProjectFile.read(directory).suites()).isEmpty();
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        boolean[] met = new boolean[1];
        while (System.nanoTime() < deadline) {
            SwingUtilities.invokeAndWait(() -> met[0] = condition.getAsBoolean());
            if (met[0]) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("condition not met in time");
    }

    private static <T> T find(java.awt.Container root, Class<T> type) {
        for (java.awt.Component child : root.getComponents()) {
            if (type.isInstance(child)) {
                return type.cast(child);
            }
            if (child instanceof java.awt.Container inner) {
                T found = find(inner, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
