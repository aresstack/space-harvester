package com.aresstack.spaceharvester.app.ui.proving;

import com.aresstack.spaceharvester.provingground.model.Check;
import com.aresstack.spaceharvester.provingground.model.Scope;
import com.aresstack.spaceharvester.provingground.model.ScopeEntry;
import com.aresstack.spaceharvester.provingground.model.TestCase;
import com.aresstack.spaceharvester.provingground.model.TestCaseEntry;
import com.aresstack.spaceharvester.provingground.model.TestProject;
import com.aresstack.spaceharvester.provingground.model.TestStep;
import com.aresstack.spaceharvester.provingground.model.TestSuite;
import com.aresstack.spaceharvester.provingground.model.TestUser;
import com.aresstack.spaceharvester.provingground.store.ProjectFile;
import com.aresstack.spaceharvester.provingground.store.TestCaseFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.JTree;
import javax.swing.SwingUtilities;

import static org.assertj.core.api.Assertions.assertThat;

/** Benutzer, Hooks und Vorbedingungen im Editor von Proving Ground. */
class ProvingGroundEditorsTest {

    @TempDir
    Path directory;

    @Test
    void scopeRowsRoundTripAndDropEmptyRowsAndChecksOfBeforeHooks() {
        Scope scope = new Scope(List.of(ScopeEntry.variable("start", "{{url()}}")),
                List.of(ScopeEntry.variable("kunde", "4711")),
                List.of(ScopeEntry.variable("code", "{{otp()}}")),
                List.of(ScopeEntry.check("Titel", "{{title()}}", new Check("contains", "Start"))),
                List.of());
        List<ScopeEditor.Row> rows = new ArrayList<>(ScopeEditor.Rows.rowsOf(scope));
        assertThat(rows).extracting(ScopeEditor.Row::hook).containsExactly(ScopeEditor.Hook.BEFORE_ALL,
                ScopeEditor.Hook.BEFORE_EACH, ScopeEditor.Hook.TEMPLATE, ScopeEditor.Hook.AFTER_EACH);
        assertThat(ScopeEditor.Rows.scopeOf(rows)).isEqualTo(scope);

        rows.add(new ScopeEditor.Row(ScopeEditor.Hook.AFTER_ALL, new ScopeEntry("", " ", true, "", null)));
        rows.set(0, new ScopeEditor.Row(ScopeEditor.Hook.BEFORE_ALL,
                new ScopeEntry("start", "x", true, "", new Check("equals", "y"))));
        Scope back = ScopeEditor.Rows.scopeOf(rows);
        assertThat(back.afterAll()).as("leere Zeile").isEmpty();
        assertThat(back.beforeAll().get(0).check()).as("Vorher prüft nicht").isNull();
    }

    @Test
    void editingATableCellPublishesTheNewScope() {
        ScopeEditor.Rows rows = new ScopeEditor.Rows();
        AtomicReference<Scope> published = new AtomicReference<>();
        rows.show(Scope.EMPTY, false, published::set);
        rows.add();
        rows.setValueAt("benutzer", 0, ScopeEditor.Rows.NAME);
        rows.setValueAt("{{user.login}}", 0, ScopeEditor.Rows.EXPRESSION);
        assertThat(published.get().beforeAll()).containsExactly(ScopeEntry.variable("benutzer", "{{user.login}}"));
        rows.setValueAt(ScopeEditor.Hook.AFTER_EACH, 0, ScopeEditor.Rows.HOOK);
        rows.setValueAt("Angemeldet", 0, ScopeEditor.Rows.EXPECTED);
        assertThat(published.get().afterEach().get(0).check()).isEqualTo(new Check("equals", "Angemeldet"));
        assertThat(rows.isCellEditable(0, ScopeEditor.Rows.CHECK)).isTrue();
    }

    @Test
    void onlyOneUserIsStandardAndUsersWithoutNameAreNotSaved() {
        UsersEditor.Rows rows = new UsersEditor.Rows();
        AtomicReference<List<TestUser>> users = new AtomicReference<>();
        AtomicReference<String> standard = new AtomicReference<>();
        rows.show(List.of(new TestUser("sb", "Test/SB", "")), "sb", (list, name) -> {
            users.set(list);
            standard.set(name);
        });
        rows.add();
        rows.setValueAt(Boolean.TRUE, 1, UsersEditor.Rows.DEFAULT);
        assertThat(users.get()).extracting(TestUser::name).containsExactly("sb");
        assertThat(standard.get()).as("ohne Namen noch nicht Standard").isNull();
        rows.setValueAt("leitung", 1, UsersEditor.Rows.NAME);
        rows.setValueAt("Test/Leitung", 1, UsersEditor.Rows.ENTRY);
        assertThat(users.get()).extracting(TestUser::name).containsExactly("sb", "leitung");
        assertThat(users.get().get(1).vaultEntry()).isEqualTo("Test/Leitung");
        assertThat(standard.get()).isEqualTo("leitung");
        assertThat(rows.getValueAt(0, UsersEditor.Rows.DEFAULT)).isEqualTo(false);
    }

    @Test
    void usersHooksAndPreconditionsAreSavedAtTheirLevel() throws Exception {
        TestCase recorded = new TestCase("Anmelden", List.of(TestStep.navigate("p", "{{start}}")));
        TestCaseFile.write(recorded, directory.resolve("a/login.jsonl"));
        TestCaseFile.write(recorded, directory.resolve("a/suche.jsonl"));
        ProjectFile.write(TestProject.of("Fachverfahren", List.of(TestSuite.of("a", "Anmeldung", List.of(
                TestCaseEntry.of("login", "Anmelden", "a/login.jsonl"),
                TestCaseEntry.of("suche", "Suchen", "a/suche.jsonl"))))), directory);
        AtomicReference<ProvingGroundWorkspace> workspace = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> workspace.set(new ProvingGroundWorkspace(() -> null, () -> directory)));
        SwingUtilities.invokeAndWait(() -> workspace.get().open(directory));
        JTree tree = find(workspace.get(), JTree.class);
        await(() -> tree.getRowCount() == 4);

        // Projekt: Benutzer und Hooks
        SwingUtilities.invokeAndWait(() -> {
            tree.setSelectionRow(0);
            UsersEditor.Rows users = workspace.get().users().rows();
            users.add();
            users.setValueAt("sb", 0, UsersEditor.Rows.NAME);
            users.setValueAt("Test/SB", 0, UsersEditor.Rows.ENTRY);
            users.setValueAt(Boolean.TRUE, 0, UsersEditor.Rows.DEFAULT);
            ScopeEditor.Rows hooks = workspace.get().hooks().rows();
            hooks.add();
            hooks.setValueAt("start", 0, ScopeEditor.Rows.NAME);
            hooks.setValueAt("https://example.org/", 0, ScopeEditor.Rows.EXPRESSION);
        });
        workspace.get().flush();
        TestProject saved = ProjectFile.read(directory);
        assertThat(saved.users()).containsExactly(new TestUser("sb", "Test/SB", ""));
        assertThat(saved.defaultUser()).isEqualTo("sb");
        assertThat(saved.scope().beforeAll()).containsExactly(ScopeEntry.variable("start", "https://example.org/"));

        // Testfall: Vorbedingung (sich selbst nicht wählbar) und Hook nur am Testfall
        SwingUtilities.invokeAndWait(() -> tree.setSelectionRow(3));
        await(() -> workspace.get().preconditions().choices().getItemCount() == 1);
        SwingUtilities.invokeAndWait(() -> {
            workspace.get().preconditions().addChosen();
            ScopeEditor.Rows hooks = workspace.get().hooks().rows();
            hooks.add();
            hooks.setValueAt("treffer", 0, ScopeEditor.Rows.NAME);
            hooks.setValueAt("3", 0, ScopeEditor.Rows.EXPRESSION);
        });
        workspace.get().flush();
        saved = ProjectFile.read(directory);
        TestCaseEntry suche = saved.testCase("a/suche");
        assertThat(suche.preconditions()).containsExactly("a/login");
        assertThat(suche.scope().beforeAll()).containsExactly(ScopeEntry.variable("treffer", "3"));
        assertThat(saved.scope().beforeAll()).as("Projekt unverändert").hasSize(1);
        assertThat(saved.users()).as("Benutzer bleiben").hasSize(1);
        assertThat(saved.problems()).isEmpty();
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
