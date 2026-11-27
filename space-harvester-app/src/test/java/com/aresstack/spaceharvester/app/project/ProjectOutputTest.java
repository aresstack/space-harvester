package com.aresstack.spaceharvester.app.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Wohin ein Projekt Exporte und Downloads speichert (Angelo, 27.11.2026). */
class ProjectOutputTest {

    @TempDir
    Path home;

    private Project folderProject(ProjectOutput output) {
        return new Project("p1", "Umzug", new ProjectTarget(ProjectTarget.Kind.PATH, home.toString()), output);
    }

    @Test
    void byDefaultEverythingGoesIntoTheSubfolderDownloadsWhichIsCreatedSilently() {
        Project project = new Project("p1", "Umzug", new ProjectTarget(ProjectTarget.Kind.PATH, home.toString()));
        assertThat(project.output()).isEqualTo(ProjectOutput.standard());
        Path expected = home.resolve(ProjectOutput.DEFAULT_SUBFOLDER);
        assertThat(ProjectOutput.directory(project)).isEqualTo(expected);
        assertThat(expected).doesNotExist();
        assertThat(ProjectOutput.prepare(project)).isEqualTo(expected);
        assertThat(expected).isDirectory();
    }

    @Test
    void anEmptySubfolderMeansTheProjectFolderItself() {
        assertThat(ProjectOutput.prepare(folderProject(new ProjectOutput(true, "  ")))).isEqualTo(home);
        assertThat(ProjectOutput.prepare(folderProject(new ProjectOutput(true, null)))).isEqualTo(home);
    }

    @Test
    void nestedSubfoldersAndMissingProjectFoldersAreCreated() {
        Path project = home.resolve("neu");
        Project nested = new Project("p1", "Umzug", new ProjectTarget(ProjectTarget.Kind.PATH, project.toString()),
                new ProjectOutput(true, "/Ablage/2026/"));
        assertThat(ProjectOutput.prepare(nested)).isEqualTo(project.resolve("Ablage").resolve("2026"));
        assertThat(project.resolve("Ablage").resolve("2026")).isDirectory();
    }

    @Test
    void theSettingsFolderAppliesWithoutTickDefaultProjectWebsiteOrFile() throws Exception {
        assertThat(ProjectOutput.directory(folderProject(new ProjectOutput(false, "Downloads")))).isNull();
        assertThat(ProjectOutput.directory(Project.standard())).isNull();
        assertThat(ProjectOutput.directory(new Project("p1", "Umzug", null))).isNull();
        assertThat(ProjectOutput.directory(new Project("p1", "Umzug",
                ProjectTarget.of(ProjectTarget.Kind.WEBSITE, "beispiel.invalid")))).isNull();
        Path file = Files.writeString(home.resolve("plan.txt"), "x");
        assertThat(ProjectOutput.directory(new Project("p1", "Umzug",
                new ProjectTarget(ProjectTarget.Kind.PATH, file.toString())))).isNull();
    }

    @Test
    void subfoldersMustStayInsideTheProjectFolder() {
        assertThat(ProjectOutput.valid("Downloads")).isTrue();
        assertThat(ProjectOutput.valid("")).isTrue();
        assertThat(ProjectOutput.valid("a/b")).isTrue();
        assertThat(ProjectOutput.valid("../weg")).isFalse();
        assertThat(ProjectOutput.valid("a/../../weg")).isFalse();
        assertThat(ProjectOutput.valid("C:\\Temp")).isFalse();
        assertThat(ProjectOutput.directory(folderProject(new ProjectOutput(true, "../weg")))).isNull();
    }

    @Test
    void theStoreKeepsTheSettingsAndOlderFilesGetTheDefault() throws Exception {
        ProjectStore store = new ProjectStore(() -> home.resolve(ProjectStore.FOLDER));
        Project project = store.create("Umzug");
        assertThat(store.resolve(project.id()).output()).isEqualTo(ProjectOutput.standard());
        store.configure(project.id(), new ProjectTarget(ProjectTarget.Kind.PATH, home.toString()),
                new ProjectOutput(false, ""));
        Project reread = new ProjectStore(() -> home.resolve(ProjectStore.FOLDER)).resolve(project.id());
        assertThat(reread.output()).isEqualTo(new ProjectOutput(false, ""));
        assertThat(reread.target().value()).isEqualTo(home.toString());

        Path file = home.resolve(ProjectStore.FOLDER).resolve(project.id()).resolve(ProjectStore.FILE);
        Files.writeString(file, "{\"version\":1,\"id\":\"" + project.id() + "\",\"name\":\"Alt\"}");
        assertThat(store.resolve(project.id()).output()).isEqualTo(ProjectOutput.standard());
        assertThat(store.configure(Project.DEFAULT_ID, null, new ProjectOutput(false, "")).output())
                .isEqualTo(ProjectOutput.standard());
    }
}
