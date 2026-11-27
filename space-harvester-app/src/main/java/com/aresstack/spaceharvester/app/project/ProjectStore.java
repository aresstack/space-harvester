package com.aresstack.spaceharvester.app.project;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Die Projekte im Datenordner der App (ADR 0043, Abschnitt 3 und Nachtrag F4): je Projekt ein Ordner
 * {@code projekte/<id>/}, darin {@code projekt.json} mit Versionsfeld, Name, Farbe und Link-Ziel. Atomar geschrieben, eine
 * unlesbare Datei kommt in Quarantäne und das Projekt fehlt, bis jemand sie repariert; überschrieben wird sie nie.
 *
 * <p>Das Standardprojekt „AresStack“ gibt es immer, ohne Datei; es lässt sich weder umbenennen noch umfärben noch
 * löschen (F5; Angelo, 27.11.2026). Ein gelöschtes Projekt geht samt Ordner (also auch seinen Artefakten) in den
 * Papierkorb und lässt sich bis zum nächsten Start der App zurückholen, wie ein gelöschter Chat.
 *
 * <p>Das aktive Projekt (F6) gilt für die ganze App und steht in {@code projekte/aktiv.json}. Fehlt es oder gibt es das
 * Projekt nicht mehr, ist das Standardprojekt aktiv. Jede Änderung an Projekten oder an der Wahl wird den Beobachtern
 * gemeldet (ADR 0027); niemand fragt ab, und eine Meldung startet nichts von selbst.
 */
public final class ProjectStore {

    /** Der Ordner unter dem Datenordner der App. */
    public static final String FOLDER = "projekte";
    static final String FILE = "projekt.json";
    static final String ACTIVE = "aktiv.json";
    static final int FORMAT_VERSION = 1;
    /** Ein gelöschter Projektordner bis zum nächsten Start der App. */
    private static final String DELETED = ".deleted";

    private static final System.Logger LOG = System.getLogger(ProjectStore.class.getName());
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final ObjectMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    /** Die Projekt-Ordner, deren Gelöschte dieser Programmlauf schon endgültig entfernt hat (einmal je Start). */
    private static final Set<Path> PURGED = ConcurrentHashMap.newKeySet();

    private final Supplier<Path> root;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /** @param root der Ordner aller Projekte; liefert {@code null}, solange die App keinen Datenordner kennt */
    public ProjectStore(Supplier<Path> root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    // ------------------------------------------------------------------ Beobachter

    /** Meldet jede Änderung an Projekten oder am aktiven Projekt. */
    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private void changed() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Beobachter der Projekte gescheitert: "
                        + e.getClass().getSimpleName());
            }
        }
    }

    // ------------------------------------------------------------------ Lesen

    /** Alle Projekte: zuerst das Standardprojekt, dann die übrigen nach Namen. */
    public synchronized List<Project> list() {
        purgeOnce();
        List<Project> projects = new ArrayList<>();
        Path base = root.get();
        if (base != null && Files.isDirectory(base)) {
            try (DirectoryStream<Path> folders = Files.newDirectoryStream(base, Files::isDirectory)) {
                for (Path folder : folders) {
                    String id = folder.getFileName().toString();
                    if (!ID.matcher(id).matches() || Project.DEFAULT_ID.equals(id)) {
                        continue;
                    }
                    Project project = read(folder.resolve(FILE), id);
                    if (project != null) {
                        projects.add(project);
                    }
                }
            } catch (IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Projekt-Ordner nicht lesbar: " + e.getClass().getSimpleName());
            }
        }
        projects.sort(Comparator.comparing((Project project) -> project.name().toLowerCase(java.util.Locale.ROOT))
                .thenComparing(Project::id));
        projects.add(0, Project.standard());
        return List.copyOf(projects);
    }

    /** Das Projekt zur Kennung; das Standardprojekt bei {@code null}, unbekannter oder gelöschter Kennung (F2). */
    public synchronized Project resolve(String projectId) {
        if (projectId != null) {
            for (Project project : list()) {
                if (project.id().equals(projectId)) {
                    return project;
                }
            }
        }
        return Project.standard();
    }

    /**
     * Der Ordner eines Projekts für seine Bestandteile (etwa die Artefakte, F3); {@code null} ohne Datenordner oder
     * bei ungültiger Kennung. Der Ordner muss nicht existieren.
     */
    public Path folder(String projectId) {
        Path base = root.get();
        return base == null || projectId == null || !ID.matcher(projectId).matches() ? null
                : base.resolve(projectId);
    }

    // ------------------------------------------------------------------ Ändern

    /** Legt ein neues Projekt an; ohne Farbe bekommt es die erste noch unbenutzte. */
    public synchronized Project create(String name, ProjectColor color) {
        ProjectColor chosen = color;
        if (chosen == null) {
            Set<ProjectColor> used = EnumSet.noneOf(ProjectColor.class);
            for (Project project : list()) {
                if (project.color() != null) {
                    used.add(project.color());
                }
            }
            chosen = ProjectColor.next(used);
        }
        Project project = new Project(UUID.randomUUID().toString(), name, chosen);
        write(project);
        changed();
        return project;
    }

    /** Benennt ein Projekt um; liefert den neuen Stand, das Standardprojekt unverändert, {@code null}, wenn es fehlt. */
    public synchronized Project rename(String projectId, String name) {
        Project project = existing(projectId);
        if (project == null || project.isDefault()) {
            return project;
        }
        Project renamed = project.withName(name);
        write(renamed);
        changed();
        return renamed;
    }

    /** Ändert die Farbe; das Standardprojekt hat keine (liefert es unverändert). */
    public synchronized Project recolor(String projectId, ProjectColor color) {
        Project project = existing(projectId);
        if (project == null || project.isDefault() || color == null) {
            return project;
        }
        Project recolored = project.withColor(color);
        write(recolored);
        changed();
        return recolored;
    }

    /** Setzt das Link-Ziel ({@code null} oder leer entfernt es); beim Standardprojekt unverändert. */
    public synchronized Project relink(String projectId, String link) {
        Project project = existing(projectId);
        if (project == null || project.isDefault()) {
            return project;
        }
        Project relinked = project.withLink(link);
        write(relinked);
        changed();
        return relinked;
    }

    /**
     * Löscht ein Projekt vorläufig (F5): Sein Ordner samt Bestandteilen geht in den Papierkorb, bis zum nächsten Start
     * mit {@link #restore} zurückzuholen. Die Chats bleiben unangetastet; da ihre Projekt-ID ins Leere zeigt, gehören
     * sie zum Standardprojekt. War das Projekt aktiv, wird das Standardprojekt aktiv. Das Standardprojekt selbst lässt
     * sich nicht löschen.
     *
     * @return ob gelöscht wurde
     */
    public synchronized boolean trash(String projectId) {
        Project project = existing(projectId);
        Path folder = folder(projectId);
        if (project == null || project.isDefault() || folder == null || !Files.isDirectory(folder)) {
            return false;
        }
        purgeOnce();
        try {
            Path target = folder.resolveSibling(projectId + DELETED);
            deleteTree(target);
            Files.move(folder, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Projekt nicht gelöscht: " + e.getClass().getSimpleName());
            return false;
        }
        changed();
        return true;
    }

    /** Holt ein vorläufig gelöschtes Projekt zurück; liefert es, oder {@code null}, wenn es das nicht mehr gibt. */
    public synchronized Project restore(String projectId) {
        Path folder = folder(projectId);
        if (folder == null) {
            return null;
        }
        Path deleted = folder.resolveSibling(projectId + DELETED);
        if (!Files.isDirectory(deleted) || Files.exists(folder)) {
            return existing(projectId);
        }
        try {
            Files.move(deleted, folder);
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Projekt nicht wiederhergestellt: " + e.getClass().getSimpleName());
            return null;
        }
        changed();
        return existing(projectId);
    }

    /** Die in diesem Programmlauf vorläufig gelöschten Projekte, nach Namen. */
    public synchronized List<Project> deleted() {
        purgeOnce();
        List<Project> projects = new ArrayList<>();
        Path base = root.get();
        if (base == null || !Files.isDirectory(base)) {
            return projects;
        }
        try (DirectoryStream<Path> folders = Files.newDirectoryStream(base, "*" + DELETED)) {
            for (Path folder : folders) {
                String name = folder.getFileName().toString();
                String id = name.substring(0, name.length() - DELETED.length());
                Project project = ID.matcher(id).matches() ? read(folder.resolve(FILE), id) : null;
                if (project != null) {
                    projects.add(project);
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Projekt-Ordner nicht lesbar: " + e.getClass().getSimpleName());
        }
        projects.sort(Comparator.comparing(Project::name));
        return projects;
    }

    // ------------------------------------------------------------------ Aktives Projekt

    /** Das aktive Projekt der ganzen App (F6); das Standardprojekt ohne Wahl oder wenn das gewählte fehlt. */
    public synchronized Project active() {
        Path base = root.get();
        if (base == null) {
            return Project.standard();
        }
        Path file = base.resolve(ACTIVE);
        if (!Files.isRegularFile(file)) {
            return Project.standard();
        }
        try {
            JsonNode json = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            JsonNode id = json.get("projectId");
            return resolve(id == null || id.isNull() ? null : id.asString());
        } catch (IOException | RuntimeException e) {
            quarantine(file, e);
            return Project.standard();
        }
    }

    /** Macht ein Projekt aktiv; eine unbekannte Kennung macht das Standardprojekt aktiv. Meldet nur echte Wechsel. */
    public synchronized Project activate(String projectId) {
        Project before = active();
        Project wanted = resolve(projectId);
        Path base = root.get();
        if (base != null) {
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("version", FORMAT_VERSION);
            content.put("projectId", wanted.id());
            try {
                ProfileFiles.writeAtomic(base.resolve(ACTIVE), JSON.writeValueAsString(content));
            } catch (IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Aktives Projekt nicht gespeichert: "
                        + e.getClass().getSimpleName());
            }
        }
        if (!before.id().equals(wanted.id())) {
            changed();
        }
        return wanted;
    }

    // ------------------------------------------------------------------ Datei

    /** Das gespeicherte Projekt; das Standardprojekt ohne Datei; {@code null} sonst. */
    private Project existing(String projectId) {
        if (Project.DEFAULT_ID.equals(projectId)) {
            return Project.standard();
        }
        Path folder = folder(projectId);
        return folder == null ? null : read(folder.resolve(FILE), projectId);
    }

    private void write(Project project) {
        Path folder = folder(project.id());
        if (folder == null) {
            return;
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("version", FORMAT_VERSION);
        content.put("id", project.id());
        content.put("name", project.name());
        if (project.color() != null) {
            content.put("color", project.color().key());
        }
        if (project.link() != null) {
            content.put("link", project.link());
        }
        try {
            ProfileFiles.writeAtomic(folder.resolve(FILE), JSON.writeValueAsString(content));
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Projekt nicht gespeichert: " + e.getClass().getSimpleName());
        }
    }

    /** Liest {@code projekt.json}; eine unlesbare oder fremde Datei kommt in Quarantäne, das Projekt fehlt dann. */
    private static Project read(Path file, String id) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonNode json = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            JsonNode storedId = json.get("id");
            if (storedId == null || !id.equals(storedId.asString())) {
                throw new IllegalStateException("Kennung passt nicht zum Ordner");
            }
            JsonNode name = json.get("name");
            return new Project(id, text(json, "name"), ProjectColor.of(text(json, "color")), text(json, "link"));
        } catch (IOException | RuntimeException e) {
            quarantine(file, e);
            return null;
        }
    }

    private static String text(JsonNode json, String field) {
        JsonNode value = json.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static void quarantine(Path file, Exception cause) {
        Path target = ProfileFiles.quarantine(file);
        LOG.log(System.Logger.Level.WARNING, "Projektdatei nicht lesbar (" + cause.getClass().getSimpleName() + "), "
                + (target == null ? "nicht beiseitezulegen: " + file : "beiseitegelegt: " + target));
    }

    /** Beim ersten Zugriff eines Programmlaufs: Was im letzten Lauf gelöscht wurde, geht endgültig. */
    private void purgeOnce() {
        Path base = root.get();
        if (base == null || !Files.isDirectory(base) || !PURGED.add(base.toAbsolutePath().normalize())) {
            return;
        }
        try (DirectoryStream<Path> folders = Files.newDirectoryStream(base, "*" + DELETED)) {
            for (Path folder : folders) {
                deleteTree(folder);
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Gelöschte Projekte nicht entfernt: " + e.getClass().getSimpleName());
        }
    }

    private static void deleteTree(Path tree) {
        if (!Files.exists(tree)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(tree)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            LOG.log(System.Logger.Level.WARNING, "Projekt-Ordner nicht entfernt: " + e.getClass().getSimpleName());
        }
    }
}
