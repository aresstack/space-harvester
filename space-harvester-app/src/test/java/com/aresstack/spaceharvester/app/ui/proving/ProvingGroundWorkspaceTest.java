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
    void fromAnEmptyFolderTheOfferInTheMiddleLeadsToProjectSuiteAndRecording() throws Exception {
        AtomicReference<ProvingGroundWorkspace> workspace = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> workspace.set(new ProvingGroundWorkspace(() -> null, () -> directory)));
        SwingUtilities.invokeAndWait(() -> workspace.get().open(directory));
        await(() -> ((com.aresstack.spaceharvester.app.ui.controls.StatusLabel) workspace.get().statusLine()).note()
                .text().equals(ProvingGroundWorkspace.NO_PROJECT));
        assertThat(workspace.get().offer().offered()).isTrue();
        assertThat(workspace.get().offer().button().getText()).isEqualTo(ProvingGroundWorkspace.OFFER_PROJECT);

        SwingUtilities.invokeAndWait(() -> workspace.get().offer().button().doClick());
        await(() -> ProvingGroundWorkspace.OFFER_SUITE.equals(workspace.get().offer().button().getText()));
        assertThat(ProjectFile.read(directory).suites()).isEmpty();
        assertThat(workspace.get().offer().offered()).isTrue();

        SwingUtilities.invokeAndWait(() -> workspace.get().createSuite("Anmeldung"));
        workspace.get().flush();
        await(() -> ProvingGroundWorkspace.OFFER_RECORD.equals(workspace.get().offer().button().getText()));
        assertThat(workspace.get().offer().offered()).isTrue();
        assertThat(ProjectFile.read(directory).suite("anmeldung")).isNotNull();
    }

    @Test
    void anEarlierOpenThatFinishesLastDoesNotReplaceTheFolderOpenedAfterIt() throws Exception {
        // The workspace opens the remembered folder in its constructor, the caller then opens another one. A slow
        // first read (large project file) finishes last; it must not take the folder back (CI #1770: project written
        // into the remembered folder of an earlier test).
        Path slow = directory.resolve("frueher");
        ProjectFile.write(TestProject.of("Früher", List.of()), slow);
        Path big = slow.resolve(ProjectFile.FILE_NAME);
        String json = Files.readString(big);
        Files.writeString(big, json.substring(0, json.lastIndexOf('}')) + " ".repeat(40_000_000) + "}\n");
        Path chosen = directory.resolve("neu");
        Files.createDirectories(chosen);

        AtomicReference<ProvingGroundWorkspace> workspace = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            workspace.set(new ProvingGroundWorkspace(() -> null, () -> directory));
            workspace.get().open(slow);
            workspace.get().open(chosen);
        });
        await(() -> ProvingGroundWorkspace.OFFER_PROJECT.equals(workspace.get().offer().button().getText())
                && ((com.aresstack.spaceharvester.app.ui.controls.StatusLabel) workspace.get().statusLine()).note()
                .text().equals(ProvingGroundWorkspace.NO_PROJECT));
        // let the slow read finish (open() reads in the common pool), then the offer must still be for the chosen folder
        java.util.concurrent.ForkJoinPool.commonPool().awaitQuiescence(1, java.util.concurrent.TimeUnit.MINUTES);
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(workspace.get().offer().button().getText()).isEqualTo(ProvingGroundWorkspace.OFFER_PROJECT);
        SwingUtilities.invokeAndWait(() -> workspace.get().offer().button().doClick());
        await(() -> Files.exists(chosen.resolve(ProjectFile.FILE_NAME)));
        assertThat(ProjectFile.read(chosen).name()).isEqualTo("neu");
    }

    @Test
    void withoutAChosenFolderTheProjectGoesBelowTheDataDirectory() throws Exception {
        String before = com.aresstack.spaceharvester.app.ui.AppState.shared().get(ProvingGroundWorkspace.PROJECT_KEY);
        com.aresstack.spaceharvester.app.ui.AppState.shared().put(ProvingGroundWorkspace.PROJECT_KEY, "");
        try {
            AtomicReference<ProvingGroundWorkspace> workspace = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> workspace.set(new ProvingGroundWorkspace(() -> null,
                    () -> directory)));
            await(() -> workspace.get().offer().offered());
            SwingUtilities.invokeAndWait(() -> workspace.get().offer().button().doClick());
            Path expected = directory.resolve("proving-ground").resolve(ProvingGroundWorkspace.DEFAULT_PROJECT);
            await(() -> Files.exists(expected.resolve(ProjectFile.FILE_NAME)));
        } finally {
            com.aresstack.spaceharvester.app.ui.AppState.shared().put(ProvingGroundWorkspace.PROJECT_KEY,
                    before == null ? "" : before);
        }
    }

    /** Die Schrift der App hat nicht jedes Zeichen (Angelo 28.11.: Tofu statt ＋): Alle Meldungen müssen darstellbar sein. */
    @Test
    void everyMessageCanBeDrawnInTheAppFont() {
        java.awt.Font font = com.aresstack.spaceharvester.app.ui.Comic.regular(13f);
        for (String text : List.of(ProvingGroundWorkspace.NO_PROJECT, ProvingGroundWorkspace.NO_SUITE,
                ProvingGroundWorkspace.NO_CASES, ProvingGroundWorkspace.RECORDING,
                ProvingGroundWorkspace.RECORDING_PAUSED, ProvingGroundWorkspace.OFFER_PROJECT,
                ProvingGroundWorkspace.OFFER_SUITE, ProvingGroundWorkspace.OFFER_RECORD, ProvingGroundWorkspace.RUN_TIP,
                ProvingGroundWorkspace.RECORD_TIP, ProvingGroundWorkspace.CREATE_TIP, ProvingGroundWorkspace.SUITE_TIP)) {
            assertThat(font.canDisplayUpTo(text)).as(text).isEqualTo(-1);
        }
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
