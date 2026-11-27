package com.aresstack.spaceharvester.app.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Projekte nach ADR 0043 (Abschnitt 3, Nachtrag F1–F6): Ordner je Projekt, Standardprojekt, Papierkorb, aktiv. */
class ProjectStoreTest {

    @TempDir
    Path home;

    private ProjectStore store() {
        return new ProjectStore(() -> home.resolve(ProjectStore.FOLDER));
    }

    @Test
    void theDefaultProjectAlwaysExistsWithoutAFileAndWithoutColor() {
        ProjectStore store = store();
        List<Project> projects = store.list();
        assertThat(projects).hasSize(1);
        Project standard = projects.get(0);
        assertThat(standard.isDefault()).isTrue();
        assertThat(standard.name()).isEqualTo("AresStack");
        assertThat(standard.color()).isNull();
        assertThat(store.active().isDefault()).isTrue();
        assertThat(home.resolve(ProjectStore.FOLDER)).doesNotExist();
    }

    @Test
    void newProjectsGetTheNextUnusedColorAndAreListedAfterTheDefaultByName() {
        ProjectStore store = store();
        Project zebra = store.create("Zebra", null);
        Project apfel = store.create("Apfel", null);
        Project eigene = store.create("Mitte", ProjectColor.GRAU);

        assertThat(zebra.color()).isEqualTo(ProjectColor.BLAU);
        assertThat(apfel.color()).isEqualTo(ProjectColor.TUERKIS);
        assertThat(eigene.color()).isEqualTo(ProjectColor.GRAU);
        assertThat(store.list()).extracting(Project::name).containsExactly("AresStack", "Apfel", "Mitte", "Zebra");
        assertThat(home.resolve(ProjectStore.FOLDER).resolve(zebra.id()).resolve(ProjectStore.FILE)).exists();
        assertThat(new ProjectStore(() -> home.resolve(ProjectStore.FOLDER)).resolve(apfel.id())).isEqualTo(apfel);
    }

    @Test
    void renameAndRecolorAreStoredAndTheDefaultStaysAresStack() throws Exception {
        ProjectStore store = store();
        Project project = store.create("Alt", ProjectColor.GRUEN);
        store.rename(project.id(), "Neu");
        store.recolor(project.id(), ProjectColor.LILA);
        assertThat(store.resolve(project.id())).isEqualTo(new Project(project.id(), "Neu", ProjectColor.LILA));

        assertThat(store.rename(Project.DEFAULT_ID, "Sonstiges").name()).isEqualTo("AresStack");
        assertThat(store.recolor(Project.DEFAULT_ID, ProjectColor.ROSA).color()).isNull();
        assertThat(store.list().get(0).name()).isEqualTo("AresStack");
        assertThat(store.resolve(null).name()).isEqualTo("AresStack");
        assertThat(store.folder(Project.DEFAULT_ID)).doesNotExist();
        String json = Files.readString(home.resolve(ProjectStore.FOLDER).resolve(project.id())
                .resolve(ProjectStore.FILE));
        assertThat(json).contains("\"version\"").contains("\"lila\"");
    }

    @Test
    void theTargetIsStoredWithItsKindAndTheDefaultHasNone() {
        ProjectStore store = store();
        Project project = store.create("Mit Ziel", null);
        assertThat(project.target()).isNull();
        store.retarget(project.id(), ProjectTarget.of(ProjectTarget.Kind.WEBSITE, "beispiel.invalid/projekt"));
        Project web = new ProjectStore(() -> home.resolve(ProjectStore.FOLDER)).resolve(project.id());
        assertThat(web.target()).isEqualTo(new ProjectTarget(ProjectTarget.Kind.WEBSITE,
                "https://beispiel.invalid/projekt"));

        ProjectTarget folder = ProjectTarget.of(ProjectTarget.Kind.PATH, "https-ordner/Aufgaben");
        assertThat(store.retarget(project.id(), folder).target().isWebsite())
                .as("die Art kommt aus der Wahl, nicht aus dem Inhalt").isFalse();
        assertThat(store.retarget(project.id(), null).target()).isNull();
        assertThat(store.retarget(Project.DEFAULT_ID, folder).target()).isNull();
    }

    @Test
    void onlyHttpAndHttpsAreWebsites() {
        assertThat(ProjectTarget.of(ProjectTarget.Kind.WEBSITE, "http://beispiel.invalid").value())
                .isEqualTo("http://beispiel.invalid");
        assertThat(ProjectTarget.of(ProjectTarget.Kind.WEBSITE, "beispiel.invalid:8080/x").value())
                .isEqualTo("https://beispiel.invalid:8080/x");
        assertThat(ProjectTarget.of(ProjectTarget.Kind.WEBSITE, "file:///C:/x")).isNull();
        assertThat(ProjectTarget.of(ProjectTarget.Kind.WEBSITE, "javascript:alert(1)")).isNull();
        assertThat(ProjectTarget.of(ProjectTarget.Kind.WEBSITE, " ")).isNull();
        assertThat(ProjectTarget.of(ProjectTarget.Kind.PATH, " /ablage/aufgaben ").value())
                .isEqualTo("/ablage/aufgaben");
    }

    @Test
    void unknownIdsAndUnknownColorsFallBackInsteadOfFailing() throws Exception {
        ProjectStore store = store();
        assertThat(store.resolve("gibt-es-nicht").isDefault()).isTrue();
        Path folder = home.resolve(ProjectStore.FOLDER).resolve("p1");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(ProjectStore.FILE),
                "{\"version\":1,\"id\":\"p1\",\"name\":\"Alt\",\"color\":\"rot\"}");
        assertThat(store.resolve("p1").color()).as("rot gibt es nicht mehr").isEqualTo(ProjectColor.GRAU);
    }

    @Test
    void anUnreadableFileGoesToQuarantineAndIsNeverOverwritten() throws Exception {
        Path folder = home.resolve(ProjectStore.FOLDER).resolve("kaputt");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(ProjectStore.FILE), "{nicht json");

        ProjectStore store = store();
        assertThat(store.list()).extracting(Project::id).containsExactly(Project.DEFAULT_ID);
        assertThat(folder.resolve(ProjectStore.FILE)).doesNotExist();
        try (var files = Files.list(folder)) {
            assertThat(files.map(path -> path.getFileName().toString()))
                    .singleElement().asString().startsWith(ProjectStore.FILE + ".defekt-");
        }
    }

    @Test
    void deletingMovesTheFolderToTheTrashUntilRestoredAndNeverTheDefault() throws Exception {
        ProjectStore store = store();
        Project project = store.create("Weg", null);
        Path parts = store.folder(project.id()).resolve("artefakte");
        Files.createDirectories(parts);
        Files.writeString(parts.resolve("D1.artefakt"), "{}");
        store.activate(project.id());

        assertThat(store.trash(Project.DEFAULT_ID)).isFalse();
        assertThat(store.trash(project.id())).isTrue();
        assertThat(store.list()).extracting(Project::id).containsExactly(Project.DEFAULT_ID);
        assertThat(store.active().isDefault()).as("gelöschtes aktives Projekt").isTrue();
        assertThat(store.deleted()).extracting(Project::id).containsExactly(project.id());

        assertThat(store.restore(project.id())).isEqualTo(project);
        assertThat(store.folder(project.id()).resolve("artefakte/D1.artefakt")).exists();
        assertThat(store.deleted()).isEmpty();
    }

    @Test
    void theActiveProjectIsKeptAcrossRestartsAndOnlyRealChangesAreReported() {
        ProjectStore store = store();
        AtomicInteger changes = new AtomicInteger();
        store.addListener(changes::incrementAndGet);
        Project project = store.create("Aktiv", null);
        assertThat(changes).hasValue(1);

        store.activate(project.id());
        store.activate(project.id());
        assertThat(changes).hasValue(2);
        assertThat(new ProjectStore(() -> home.resolve(ProjectStore.FOLDER)).active()).isEqualTo(project);

        store.activate("unbekannt");
        assertThat(store.active().isDefault()).isTrue();
        assertThat(changes).hasValue(3);
    }

    @Test
    void withoutDataFolderEverythingStaysInTheDefault() {
        ProjectStore store = new ProjectStore(() -> null);
        assertThat(store.list()).extracting(Project::id).containsExactly(Project.DEFAULT_ID);
        assertThat(store.active().isDefault()).isTrue();
        assertThat(store.folder("x")).isNull();
    }
}
