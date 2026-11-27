package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.chat.ChatNode;
import com.aresstack.spaceharvester.app.chat.ChatRecord;
import com.aresstack.spaceharvester.app.chat.ChatStore;
import com.aresstack.spaceharvester.app.event.StatusMessageEvent;
import com.aresstack.spaceharvester.app.project.Project;
import com.aresstack.spaceharvester.app.project.ProjectOutput;
import com.aresstack.spaceharvester.app.project.ProjectStore;
import com.aresstack.spaceharvester.app.project.ProjectTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JMenuItem;

import static org.assertj.core.api.Assertions.assertThat;

/** Die Projektwahl unten in der Ecke (ADR 0043, Nachtrag F6): Liste, Neu, Umbenennen, Ziel, Löschen. */
class ProjectChooserTest {

    @TempDir
    Path home;

    private final List<StatusMessageEvent> notices = new ArrayList<>();

    private ProjectStore projects() {
        return new ProjectStore(() -> home.resolve(ProjectStore.FOLDER));
    }

    private ProjectChooser chooser(ProjectStore projects) {
        return new ProjectChooser(projects, () -> home.resolve(ChatStore.FOLDER), notices::add, null);
    }

    private static List<String> texts(List<Component> items) {
        return items.stream().filter(JMenuItem.class::isInstance).map(item -> ((JMenuItem) item).getText()).toList();
    }

    @Test
    void theListShowsAllProjectsWithTheActiveCheckedAndActionsOnlyForOwnProjects() {
        ProjectStore projects = projects();
        ProjectChooser chooser = chooser(projects);
        assertThat(texts(chooser.items())).containsExactly("✓ AresStack", ProjectChooser.NEW,
                ProjectChooser.SETTINGS);
        JMenuItem standardSettings = (JMenuItem) chooser.items().stream().filter(JMenuItem.class::isInstance)
                .filter(item -> ProjectChooser.SETTINGS.equals(((JMenuItem) item).getText())).findFirst().orElseThrow();
        assertThat(standardSettings.isEnabled()).as("AresStack nutzt immer den Ausgabeordner").isFalse();

        chooser.askName = (title, current) -> "Umzug";
        chooser.create();
        Project created = projects.active();
        assertThat(created.name()).as("neues Projekt wird gleich aktiv").isEqualTo("Umzug");
        assertThat(texts(chooser.items())).containsExactly("    AresStack", "✓ Umzug", ProjectChooser.NEW,
                ProjectChooser.SETTINGS, ProjectChooser.RENAME, ProjectChooser.DELETE);

        ((JMenuItem) chooser.items().get(0)).doClick();
        assertThat(projects.active().isDefault()).isTrue();

        chooser.askName = (title, current) -> "Umzug 2027";
        chooser.rename(created);
        assertThat(projects.resolve(created.id()).name()).isEqualTo("Umzug 2027");
    }

    @Test
    void clickingTheNameOpensTheTargetOrAsksForOne() throws Exception {
        ProjectStore projects = projects();
        ProjectChooser chooser = chooser(projects);
        Project project = projects.create("Mit Ziel");
        List<String> browsed = new ArrayList<>();
        List<Path> opened = new ArrayList<>();
        chooser.browse = browsed::add;
        chooser.openPath = opened::add;

        Path folder = Files.createDirectories(home.resolve("aufgaben"));
        chooser.askSettings = p -> p.withTarget(ProjectTarget.of(ProjectTarget.Kind.PATH, folder.toString()));
        chooser.open(project);
        assertThat(opened).as("ohne Ziel nur der Dialog, nichts öffnet sich").isEmpty();
        project = projects.resolve(project.id());
        chooser.open(project);
        assertThat(opened).containsExactly(folder);

        projects.retarget(project.id(), ProjectTarget.of(ProjectTarget.Kind.WEBSITE, "beispiel.invalid"));
        chooser.open(projects.resolve(project.id()));
        assertThat(browsed).containsExactly("https://beispiel.invalid");

        projects.retarget(project.id(), ProjectTarget.of(ProjectTarget.Kind.PATH, home.resolve("fehlt").toString()));
        List<ProjectTarget> asked = new ArrayList<>();
        chooser.askSettings = p -> {
            asked.add(p.target());
            return null;
        };
        chooser.open(projects.resolve(project.id()));
        assertThat(notices).extracting(notice -> notice.getPayload().getText())
                .containsExactly(ProjectChooser.UNREACHABLE);
        assertThat(asked).as("nicht erreichbar: derselbe Dialog mit dem alten Ziel").hasSize(1);

        projects.retarget(project.id(), ProjectTarget.of(ProjectTarget.Kind.WEBSITE, "beispiel.invalid"));
        chooser.browse = url -> {
            throw new java.io.IOException("kein Browser");
        };
        chooser.open(projects.resolve(project.id()));
        assertThat(notices).hasSize(2);
        assertThat(asked).as("auch eine Website ohne Browser führt zum Dialog").hasSize(2);
    }

    @Test
    void deletingAsksWithTheNumbersAndKeepsTheChats() {
        ProjectStore projects = projects();
        ProjectChooser chooser = chooser(projects);
        Project project = projects.create("Weg");
        projects.createGroup(project.id(), "Eine", null);
        ChatStore chats = new ChatStore(() -> home.resolve(ChatStore.FOLDER));
        ChatNode node = new ChatNode("wiki", null, null);
        chats.save(node, ChatRecord.fresh(1, project.id()).plus(ChatRecord.Message.user("a", 1), 1));
        chats.save(node, ChatRecord.fresh(2, project.id()).plus(ChatRecord.Message.user("b", 2), 2));
        chats.save(node, ChatRecord.fresh(3).plus(ChatRecord.Message.user("c", 3), 3));
        List<String> questions = new ArrayList<>();
        chooser.confirm = (title, question) -> {
            questions.add(question);
            return false;
        };
        chooser.delete(project);
        assertThat(questions).singleElement().asString().contains("wirklich").contains("2 Chats")
                .contains("1 Gruppe").contains("0 Artefakte");
        assertThat(projects.resolve(project.id()).isDefault()).as("Nein löscht nichts").isFalse();

        chooser.confirm = (title, question) -> true;
        chooser.delete(project);
        assertThat(projects.list()).extracting(Project::id).containsExactly(Project.DEFAULT_ID);
        assertThat(chats.all()).hasSize(3);

        // Bis zum Neustart steht es durchgestrichen in der Liste; ein Klick holt es samt Gruppe zurück.
        JMenuItem restore = (JMenuItem) chooser.items().get(1);
        assertThat(restore.getText()).contains("<s>").contains("Weg");
        restore.doClick();
        assertThat(projects.active().id()).isEqualTo(project.id());
        assertThat(projects.groups(project.id())).hasSize(1);
    }

    @Test
    void theCornerShowsTheCurrentYearAndTheProjectName() {
        CopyrightLink corner = new CopyrightLink(uri -> { });
        assertThat(corner.copyright().getText()).isEqualTo("© " + Year.now().getValue() + " ");
        assertThat(corner.link().getText()).isEqualTo("AresStack");
        assertThat(corner.chooseButton().isVisible()).as("ohne Projektwahl kein Pfeil").isFalse();
        List<Project> clicked = new ArrayList<>();
        corner.chooser(anchor -> { }, clicked::add);
        assertThat(corner.chooseButton().isVisible()).isTrue();
        Project project = new Project("p1", "Umzug", null);
        corner.show(project);
        assertThat(corner.link().getText()).isEqualTo("Umzug");
        corner.link().dispatchEvent(new java.awt.event.MouseEvent(corner.link(),
                java.awt.event.MouseEvent.MOUSE_CLICKED, 0, 0, 1, 1, 1, false));
        assertThat(clicked).containsExactly(project);
    }

    @Test
    void theTargetDialogOffersAFolderFirstAndOnlyHttpWebsites() {
        ProjectTargetDialog.Body body = new ProjectTargetDialog.Body(null, parent -> home.toFile());
        assertThat(body.kind.selected()).isEqualTo(ProjectTargetDialog.PATH);
        assertThat(body.target()).isNull();
        body.browse.doClick();
        assertThat(body.target()).isEqualTo(new ProjectTarget(ProjectTarget.Kind.PATH, home.toString()));

        body.kind.select(ProjectTargetDialog.WEBSITE);
        assertThat(body.browse.isVisible()).isFalse();
        body.field.setText("ftp://beispiel.invalid");
        assertThat(body.target()).isNull();
        assertThat(body.hint.getText()).isEqualTo(ProjectTargetDialog.INVALID_WEBSITE);
        body.field.setText("beispiel.invalid");
        assertThat(body.target().value()).isEqualTo("https://beispiel.invalid");
    }

    @Test
    void theSettingsOfferSaveHereWithTheSubfolderDownloadsAndAcceptAnEmptyOne() {
        ProjectTargetDialog.Body body = new ProjectTargetDialog.Body(null, ProjectOutput.standard(),
                parent -> home.toFile());
        assertThat(body.saveHere.isSelected()).as("Vorgabe: alles im Projektordner").isTrue();
        assertThat(body.subfolder.getText()).isEqualTo(ProjectOutput.DEFAULT_SUBFOLDER);
        body.browse.doClick();
        assertThat(body.complete()).isTrue();

        body.subfolder.setText("");
        assertThat(body.complete()).as("leer = direkt im Projektordner").isTrue();
        assertThat(body.output()).isEqualTo(new ProjectOutput(true, ""));

        body.subfolder.setText("../weg");
        assertThat(body.complete()).isFalse();
        assertThat(body.subfolderHint.getText()).isEqualTo(ProjectTargetDialog.INVALID_SUBFOLDER);
        body.saveHere.doClick();
        assertThat(body.complete()).as("ohne Haken zählt der Unterordner nicht").isTrue();
        assertThat(body.subfolder.isEnabled()).isFalse();
    }

    @Test
    void projectSettingsAreStoredForTheActiveProject() {
        ProjectStore projects = projects();
        ProjectChooser chooser = chooser(projects);
        Project project = projects.create("Umzug");
        chooser.askSettings = p -> p.withTarget(ProjectTarget.of(ProjectTarget.Kind.PATH, home.toString()))
                .withOutput(new ProjectOutput(false, "Ablage"));
        chooser.configure(project);
        Project stored = projects.resolve(project.id());
        assertThat(stored.target().value()).isEqualTo(home.toString());
        assertThat(stored.output()).isEqualTo(new ProjectOutput(false, "Ablage"));

        List<Project> asked = new ArrayList<>();
        chooser.askSettings = p -> {
            asked.add(p);
            return p;
        };
        chooser.configure(Project.standard());
        assertThat(asked).as("AresStack hat keine Projekteinstellungen").isEmpty();
    }
}
