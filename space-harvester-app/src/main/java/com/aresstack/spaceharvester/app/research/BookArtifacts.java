package com.aresstack.spaceharvester.app.research;

import com.aresstack.spaceharvester.app.chat.artifact.ChatArtifact;
import com.aresstack.spaceharvester.app.chat.artifact.ChatArtifactStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Das Buch eines Favoritenordners als globales Artefakt des Projekts (ADR 0044 Nachtrag, ADR 0047): ein Artefakt
 * {@code B…} je Ordner und Projekt, jede Auflage eine Version darin. Am Ordner steht neben den Auflagen
 * ({@link BookEditions}) je Projekt die Kennung des Artefakts; das Artefakt selbst kennt keinen Ordner.
 *
 * <p>{@link #publish} bringt das Artefakt auf den Stand der Auflagen: Fehlt es, legt es die erste Auflage an, und jede
 * weitere wird eine neue Auflage darin, in ihrer Reihenfolge und ohne KI. So werden auch Auflagen von vor dem Umbau
 * übernommen; nichts geht verloren, keine Datei wird gelöscht.
 */
public final class BookArtifacts {

    private static final System.Logger LOG = System.getLogger(BookArtifacts.class.getName());
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final String SUFFIX = ".artifacts.json";

    private final Supplier<Path> data;
    private final Function<String, Path> projectArtifacts;
    private final Function<String, List<BookEditions.Source>> members;
    private final Function<BookEditions.Source, String> version;

    /**
     * @param data             der Datenordner der App, {@code null} ohne
     * @param projectArtifacts der Artefakt-Ordner eines Projekts ({@code ProjectStore.artifacts})
     * @param members          die jetzigen Seiten eines Ordners (wie „Buch schreiben“ sie liest)
     * @param version          der jetzige Stand einer Seite in der Ablage, {@code null} ohne
     */
    public BookArtifacts(Supplier<Path> data, Function<String, Path> projectArtifacts,
                         Function<String, List<BookEditions.Source>> members,
                         Function<BookEditions.Source, String> version) {
        this.data = Objects.requireNonNull(data, "data");
        this.projectArtifacts = Objects.requireNonNull(projectArtifacts, "projectArtifacts");
        this.members = Objects.requireNonNull(members, "members");
        this.version = Objects.requireNonNull(version, "version");
    }

    /** Die Kennung des Buch-Artefakts des Ordners im Projekt, {@code null} ohne. Liest eine Datei. */
    public String artifactOf(String folderId, String projectId) {
        Path dir = data.get();
        if (dir == null || folderId == null) {
            return null;
        }
        JsonNode known = read(file(dir, folderId)).get(project(projectId));
        return known == null || known.isNull() ? null : known.asString();
    }

    /** Der Favoritenordner zum Buch-Artefakt des Projekts, {@code null} ohne. Liest die Dateien am Buchordner. */
    public String folderOf(String projectId, String artifactId) {
        Path dir = data.get();
        if (dir == null || artifactId == null) {
            return null;
        }
        Path books = BookNotes.file(dir, "x").getParent();
        if (!Files.isDirectory(books)) {
            return null;
        }
        try (Stream<Path> files = Files.list(books)) {
            for (Path file : files.filter(each -> each.getFileName().toString().endsWith(SUFFIX)).toList()) {
                JsonNode json = read(file);
                JsonNode known = json.get(project(projectId));
                JsonNode folder = json.get("folderId");
                if (known != null && artifactId.equals(known.asString()) && folder != null) {
                    return folder.asString();
                }
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Buchordner nicht lesbar ({0})", e.getClass().getSimpleName());
        }
        return null;
    }

    /**
     * Ob die neueste Auflage veraltet ist (ADR 0044 §4): eine Seite des Ordners hinzugekommen oder entfernt, oder eine
     * gelesene Seite in neuer Version. Liest Dateien, nie auf dem EDT.
     */
    public boolean stale(String folderId) {
        return stale(folderId, BookEditions.latest(data.get(), folderId).orElse(null));
    }

    /** Wie {@link #stale(String)} für eine schon gelesene Auflage. */
    public boolean stale(String folderId, BookEditions.Edition edition) {
        if (edition == null) {
            return false;
        }
        return BookEditions.stale(edition, members.apply(folderId), version);
    }

    /** Meldet die Ordner-ID bei jeder neuen Auflage, bis {@link AutoCloseable#close()}; der Aufrufer hält die Rückgabe. */
    public static AutoCloseable onChange(Consumer<String> listener) {
        return BookEditions.onAdded(listener);
    }

    /**
     * Bringt das Buch-Artefakt des Ordners im Projekt auf den Stand der Auflagen und gibt es zurück; {@code null} ohne
     * Auflage oder ohne Projektordner. Läuft im Hintergrund.
     *
     * @param title der Titel eines neu angelegten Artefakts (der Ordnername)
     */
    public ChatArtifact publish(String folderId, String projectId, String title) {
        Path dir = data.get();
        if (dir == null || folderId == null) {
            return null;
        }
        List<BookEditions.Edition> editions = BookEditions.all(dir, folderId);
        if (editions.isEmpty()) {
            return null;
        }
        ChatArtifactStore store = new ChatArtifactStore(null);
        store.projects(projectArtifacts);
        synchronized (BookArtifacts.class) {
            String id = artifactOf(folderId, projectId);
            ChatArtifact artifact = id == null ? null : store.readInProject(projectId, id);
            int known = artifact == null ? 0 : artifact.editions().size();
            for (BookEditions.Edition edition : editions.subList(Math.min(known, editions.size()), editions.size())) {
                if (artifact == null) {
                    artifact = store.createBook(projectId, title == null || title.isBlank() ? "Buch" : title,
                            edition.book());
                    if (artifact == null) {
                        return null;
                    }
                    remember(dir, folderId, projectId, artifact.id());
                } else {
                    ChatArtifactStore.WriteResult result = store.newEdition(projectId, artifact.id(), edition.book(),
                            edition.number() + ". Auflage");
                    if (!result.success()) {
                        LOG.log(System.Logger.Level.WARNING, "Buchauflage {0} nicht übernommen", edition.number());
                        return artifact;
                    }
                    artifact = result.artifact();
                }
            }
            return artifact;
        }
    }

    private static void remember(Path dir, String folderId, String projectId, String artifactId) {
        Path file = file(dir, folderId);
        ObjectNode json = JSON.createObjectNode();
        JsonNode old = read(file);
        old.properties().forEach(entry -> json.set(entry.getKey(), entry.getValue()));
        json.put("folderId", folderId);
        json.put(project(projectId), artifactId);
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, JSON.writeValueAsString(json), StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Buch-Artefakt nicht gemerkt ({0})", e.getClass().getSimpleName());
        }
    }

    private static JsonNode read(Path file) {
        if (!Files.isRegularFile(file)) {
            return JSON.createObjectNode();
        }
        try {
            return JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Buch-Artefakte nicht lesbar ({0})", e.getClass().getSimpleName());
            return JSON.createObjectNode();
        }
    }

    /** Neben den Notizen des Ordners: {@code <Ordner>.artifacts.json}. */
    static Path file(Path dir, String folderId) {
        Path notes = BookNotes.file(dir, folderId);
        String name = notes.getFileName().toString();
        return notes.resolveSibling(name.substring(0, name.length() - ".json".length()) + SUFFIX);
    }

    private static String project(String projectId) {
        return "project:" + (projectId == null || projectId.isEmpty()
                ? com.aresstack.spaceharvester.app.project.Project.DEFAULT_ID : projectId);
    }
}
