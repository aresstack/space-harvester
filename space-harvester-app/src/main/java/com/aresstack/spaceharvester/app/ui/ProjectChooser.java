package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.chat.ChatStore;
import com.aresstack.spaceharvester.app.event.Severity;
import com.aresstack.spaceharvester.app.event.StatusMessageEvent;
import com.aresstack.spaceharvester.app.project.Project;
import com.aresstack.spaceharvester.app.project.ProjectStore;
import com.aresstack.spaceharvester.app.project.ProjectTarget;
import com.aresstack.spaceharvester.app.ui.controls.PopupHover;
import com.aresstack.spaceharvester.app.ui.markdown.DesktopLinkOpener;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import java.awt.Component;
import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Die Wahl des aktiven Projekts unten in der Ecke (ADR 0043, Nachtrag F6): Der Pfeil öffnet nach oben die Liste der
 * Projekte (das aktive mit Haken) und „Neues Projekt …“; am aktiven Projekt stehen „Umbenennen …“, „Ziel ändern …“ und
 * „Projekt löschen …“. Ein Klick auf den Namen öffnet das Ziel; ohne Ziel oder wenn es nicht erreichbar ist, öffnet
 * sich der Dialog für das Ziel. Nichts öffnet sich ohne Klick; das Ziel steht nie im Log.
 */
final class ProjectChooser {

    static final String NEW = "Neues Projekt …";
    static final String RENAME = "Umbenennen …";
    static final String TARGET = "Ziel ändern …";
    static final String DELETE = "Projekt löschen …";
    static final String UNREACHABLE = "Ziel des Projekts nicht erreichbar";

    private final ProjectStore projects;
    private final java.util.function.Supplier<Path> chatsDirectory;
    private final Consumer<StatusMessageEvent> status;
    /** Ja/Nein vor dem Löschen; Tests antworten selbst. */
    BiPredicate<String, String> confirm;
    /** Fragt einen Namen ab ({@code null} = abgebrochen); Tests antworten selbst. */
    java.util.function.BiFunction<String, String, String> askName;
    /** Fragt ein Ziel ab ({@code null} = abgebrochen); Tests antworten selbst. */
    java.util.function.BiFunction<Project, ProjectTarget, ProjectTarget> askTarget;
    /** Öffnet eine Website. */
    Consumer<String> browse = DesktopLinkOpener.systemDefault()::open;
    /** Öffnet einen lokalen Ordner oder eine Datei; wirft, wenn das System es nicht kann. */
    PathOpener openPath = path -> {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            throw new IOException("Das System kann hier nichts öffnen");
        }
        Desktop.getDesktop().open(path.toFile());
    };

    /** Öffnet einen Pfad mit dem Programm des Systems. */
    interface PathOpener {
        void open(Path path) throws IOException;
    }

    ProjectChooser(AppContext context, Component parent) {
        this(context.projects(), context::chatsDirectory, context.events()::publish, parent);
    }

    ProjectChooser(ProjectStore projects, java.util.function.Supplier<Path> chatsDirectory,
                   Consumer<StatusMessageEvent> status, Component parent) {
        this.projects = Objects.requireNonNull(projects, "projects");
        this.chatsDirectory = chatsDirectory;
        this.status = status;
        confirm = (title, question) -> Dialogs.confirm(parent, title, question);
        askName = (title, current) -> Dialogs.askText(parent, title, "Name des Projekts", current);
        askTarget = (project, current) -> ProjectTargetDialog.ask(parent, project.name(), current);
    }

    /** Die Liste nach oben über dem Pfeil. */
    void show(JComponent anchor) {
        JPopupMenu menu = new JPopupMenu();
        items().forEach(menu::add);
        menu.pack();
        menu.show(anchor, 0, -menu.getPreferredSize().height);
        if (anchor instanceof javax.swing.AbstractButton button) {
            PopupHover.clearOnClose(menu, button);
        }
    }

    /** Die Einträge der Liste; für Tests ohne geöffnetes Popup. */
    List<Component> items() {
        List<Component> items = new ArrayList<>();
        Project active = projects.active();
        for (Project project : projects.list()) {
            boolean current = project.id().equals(active.id());
            JMenuItem item = new JMenuItem((current ? "✓ " : "    ") + project.name());
            item.addActionListener(e -> projects.activate(project.id()));
            items.add(item);
        }
        items.add(new JPopupMenu.Separator());
        JMenuItem create = new JMenuItem(NEW);
        create.addActionListener(e -> create());
        items.add(create);
        if (!active.isDefault()) {
            items.add(new JPopupMenu.Separator());
            JMenuItem rename = new JMenuItem(RENAME);
            rename.addActionListener(e -> rename(active));
            items.add(rename);
            JMenuItem target = new JMenuItem(TARGET);
            target.addActionListener(e -> changeTarget(active));
            items.add(target);
            JMenuItem delete = new JMenuItem(DELETE);
            delete.addActionListener(e -> delete(active));
            items.add(delete);
        }
        return items;
    }

    /** „Neues Projekt …“: anlegen und gleich aktiv machen. */
    void create() {
        String name = askName.apply("Neues Projekt", "");
        if (name != null && !name.isBlank()) {
            projects.activate(projects.create(name).id());
        }
    }

    void rename(Project project) {
        String name = askName.apply("Projekt umbenennen", project.name());
        if (name != null && !name.isBlank()) {
            projects.rename(project.id(), name);
        }
    }

    void changeTarget(Project project) {
        ProjectTarget target = askTarget.apply(project, project.target());
        if (target != null) {
            projects.retarget(project.id(), target);
        }
    }

    /** Klick auf den Namen: das Ziel öffnen; ohne Ziel oder wenn es nicht erreichbar ist, den Dialog. */
    void open(Project project) {
        ProjectTarget target = project.target();
        if (target == null) {
            changeTarget(project);
            return;
        }
        if (target.isWebsite()) {
            browse.accept(target.value());
            return;
        }
        try {
            Path path = Path.of(target.value());
            if (!Files.exists(path)) {
                throw new IOException("fehlt");
            }
            openPath.open(path);
        } catch (IOException | InvalidPathException | UnsupportedOperationException e) {
            status.accept(new StatusMessageEvent(UNREACHABLE, 8000, Severity.ERROR));
            changeTarget(project);
        }
    }

    /**
     * „Projekt löschen …“ nach „wirklich“ Ja/Nein mit den Zahlen (F5): Die Chats gehören danach ohne Gruppe zu
     * AresStack, Gruppen und Artefakte gehen mit dem Projekt in den Papierkorb.
     */
    void delete(Project project) {
        int chats = chatsOf(project);
        int groups = projects.groups(project.id()).size();
        int artifacts = artifactsOf(project);
        String question = "Projekt „" + project.name() + "“ wirklich löschen? "
                + Texts.count(chats, "Chat", "Chats") + " bleiben erhalten und gehören danach zu "
                + Project.DEFAULT_NAME + "; " + Texts.count(groups, "Gruppe", "Gruppen") + " und "
                + Texts.count(artifacts, "Artefakt", "Artefakte")
                + " gehen in den Papierkorb (bis zum Neustart wiederherstellbar).";
        if (confirm.test("Projekt löschen", question)) {
            projects.trash(project.id());
        }
    }

    private int chatsOf(Project project) {
        int count = 0;
        for (ChatStore.Entry entry : new ChatStore(chatsDirectory).all()) {
            if (project.id().equals(entry.record().projectId())) {
                count++;
            }
        }
        return count;
    }

    /** Die Artefakte im Ordner des Projekts (Endung wie im Artefakt-Speicher, ADR 0045). */
    private int artifactsOf(Project project) {
        Path folder = projects.folder(project.id());
        if (folder == null || !Files.isDirectory(folder)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(folder)) {
            return (int) walk.filter(path -> path.getFileName().toString().endsWith(".artefakt")).count();
        } catch (IOException | java.io.UncheckedIOException e) {
            return 0;
        }
    }
}
