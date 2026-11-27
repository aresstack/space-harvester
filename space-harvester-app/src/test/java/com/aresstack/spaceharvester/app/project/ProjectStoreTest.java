package com.aresstack.spaceharvester.app.project;

import com.aresstack.spaceharvester.app.group.GroupColor;
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
    void theDefaultProjectAlwaysExistsWithoutAFile() {
        ProjectStore store = store();
        List<Project> projects = store.list();
        assertThat(projects).hasSize(1);
        Project standard = projects.get(0);
        assertThat(standard.isDefault()).isTrue();
        assertThat(standard.name()).isEqualTo("AresStack");
        assertThat(store.active().isDefault()).isTrue();
        assertThat(home.resolve(ProjectStore.FOLDER)).doesNotExist();
    }

    @Test
    void newProjectsAreListedAfterTheDefaultByNameAndHaveNoColor() {
        ProjectStore store = store();
        Project zebra = store.create("Zebra");
        Project apfel = store.create("Apfel");
        assertThat(store.list()).extracting(Project::name).containsExactly("AresStack", "Apfel", "Zebra");
        assertThat(home.resolve(ProjectStore.FOLDER).resolve(zebra.id()).resolve(ProjectStore.FILE)).exists();
        assertThat(new ProjectStore(() -> home.resolve(ProjectStore.FOLDER)).resolve(apfel.id())).isEqualTo(apfel);
    }

    @Test
    void renameIsStoredAndTheDefaultStaysAresStack() throws Exception {
        ProjectStore store = store();
        Project project = store.create("Alt");
        store.rename(project.id(), "Neu");
        assertThat(store.resolve(project.id())).isEqualTo(new Project(project.id(), "Neu", null));
        assertThat(store.rename(Project.DEFAULT_ID, "Sonstiges").name()).isEqualTo("AresStack");
        assertThat(store.list().get(0).name()).isEqualTo("AresStack");
        assertThat(store.resolve(null).name()).isEqualTo("AresStack");
        String json = Files.readString(home.resolve(ProjectStore.FOLDER).resolve(project.id())
                .resolve(ProjectStore.FILE));
        assertThat(json).contains("\"version\"").contains("\"Neu\"").doesNotContain("color");
    }

    @Test
    void groupsGetTheNextUnusedColorAndBelongToTheirProjectAlsoInTheDefault() throws Exception {
        ProjectStore store = store();
        Project project = store.create("Mit Gruppen");
        ChatGroup first = store.createGroup(project.id(), "Entwurf", null);
        ChatGroup second = store.createGroup(project.id(), "Prüfung", null);
        ChatGroup own = store.createGroup(project.id(), "Eigen", GroupColor.GRAU);
        assertThat(first.color()).isEqualTo(GroupColor.BLAU);
        assertThat(second.color()).isEqualTo(GroupColor.TUERKIS);
        assertThat(own.color()).isEqualTo(GroupColor.GRAU);
        assertThat(store.groups(project.id())).extracting(ChatGroup::name)
                .containsExactly("Entwurf", "Prüfung", "Eigen");

        store.updateGroup(project.id(), first.withName("Text").withColor(GroupColor.LILA));
        assertThat(new ProjectStore(() -> home.resolve(ProjectStore.FOLDER)).group(project.id(), first.id()))
                .isEqualTo(new ChatGroup(first.id(), "Text", GroupColor.LILA));
        assertThat(store.dissolveGroup(project.id(), second.id())).isTrue();
        assertThat(store.group(project.id(), second.id())).as("aufgelöst = Chats ohne Gruppe").isNull();

        ChatGroup standard = store.createGroup(Project.DEFAULT_ID, "Allerlei", null);
        assertThat(store.groups(Project.DEFAULT_ID)).containsExactly(standard);
        assertThat(standard.color()).as("Farben je Projekt").isEqualTo(GroupColor.BLAU);
        assertThat(store.list()).extracting(Project::id).as("Gruppenordner ist kein Projekt")
                .containsExactly(Project.DEFAULT_ID, project.id());
        assertThat(store.createGroup("gibt-es-nicht", "x", null)).isNull();
        assertThat(Files.readString(store.folder(project.id()).resolve(ProjectStore.GROUPS)))
                .contains("\"version\"").contains("\"lila\"");
    }

    @Test
    void unreadableGroupsGoToQuarantine() throws Exception {
        ProjectStore store = store();
        Project project = store.create("Kaputt");
        Path file = store.folder(project.id()).resolve(ProjectStore.GROUPS);
        Files.writeString(file, "[nicht json");
        assertThat(store.groups(project.id())).isEmpty();
        assertThat(file).doesNotExist();
    }

    @Test
    void theTargetIsStoredWithItsKindAndTheDefaultHasNone() {
        ProjectStore store = store();
        Project project = store.create("Mit Ziel");
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
    void unknownIdsAndUnknownGroupColorsFallBackInsteadOfFailing() throws Exception {
        ProjectStore store = store();
        assertThat(store.resolve("gibt-es-nicht").isDefault()).isTrue();
        Path folder = home.resolve(ProjectStore.FOLDER).resolve("p1");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(ProjectStore.FILE), "{\"version\":1,\"id\":\"p1\",\"name\":\"Alt\"}");
        Files.writeString(folder.resolve(ProjectStore.GROUPS),
                "{\"version\":1,\"groups\":[{\"id\":\"g1\",\"name\":\"Alt\",\"color\":\"rot\"}]}");
        assertThat(store.group("p1", "g1").color()).as("rot gibt es nicht").isEqualTo(GroupColor.GRAU);
        assertThat(store.group("p1", "unbekannt")).isNull();
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
        Project project = store.create("Weg");
        store.createGroup(project.id(), "Mit", null);
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
        assertThat(store.groups(project.id())).hasSize(1);
        assertThat(store.deleted()).isEmpty();
    }

    @Test
    void theActiveProjectIsKeptAcrossRestartsAndOnlyRealChangesAreReported() {
        ProjectStore store = store();
        AtomicInteger changes = new AtomicInteger();
        store.addListener(changes::incrementAndGet);
        Project project = store.create("Aktiv");
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
