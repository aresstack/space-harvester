package com.aresstack.spaceharvester.app.chat.artifact;

import com.aresstack.spaceharvester.app.chat.ChatStore;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Die Artefakte der Chats (ADR 0045, Abschnitt 1), je Chat in dessen Artefakt-Ordner ({@link ChatStore#artifacts}),
 * eine JSON-Datei je Artefakt mit allen Revisionen. Ansicht und Werkzeuge der KI arbeiten auf diesem einen Speicher;
 * der Vertrag folgt dem {@code AgentArtifactStore} aus Ask AI: lesen mit Revision, schreiben mit erwarteter Revision
 * (die Ansicht) oder auf den neuesten Stand (die Werkzeuge, denn eine Änderung des Benutzers gilt für das nächste
 * Werkzeug, Abschnitt 3). Papierkorb, Wiederherstellen und Löschen gehen mit dem Chat ({@link ChatStore}).
 *
 * <p>Wer zuschaut, bekommt jede Änderung gemeldet ({@link #addListener}, ADR 0027: kein Polling). In Logs stehen nie
 * Texte aus Artefakten, nur die Ausnahmeklasse (Abschnitt 9).
 */
public final class ChatArtifactStore {

    /** Die Endung einer Artefakt-Datei; bewusst nicht {@code .json}, damit die Chat-Listen sie nie lesen. */
    static final String SUFFIX = ".artefakt";
    /** Jede Kennung eines erwarteten Stands passt: Die Werkzeuge schreiben immer auf den neuesten. */
    public static final long ANY = -1;
    /** Die zuletzt offenen Reiter des Chats, eine Kennung je Zeile. */
    private static final String TABS = "offen.txt";

    private static final System.Logger LOG = System.getLogger(ChatArtifactStore.class.getName());
    private static final Pattern ID = Pattern.compile("([DG])(\\d{1,6})");
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    /** Ein Schloss für alle Instanzen: Ansicht und Werkzeuge dürfen sich nie überschreiben. */
    private static final Object LOCK = new Object();

    /** Eine Änderung an einem Artefakt, für die Ansicht. */
    public record Event(Path folder, String chatId, ChatArtifact artifact, List<String> changed) {
    }

    /** Das Ergebnis eines Schreibens; bei Erfolg der neue Stand, sonst der Grund und der aktuelle Stand. */
    public record WriteResult(boolean success, ChatArtifact artifact, List<String> changed, String reason) {
        static WriteResult ok(ChatArtifact artifact, List<String> changed) {
            return new WriteResult(true, artifact, changed, "");
        }

        static WriteResult failed(ChatArtifact current, String reason) {
            return new WriteResult(false, current, List.of(), reason);
        }

        public long revision() {
            return artifact == null ? 0 : artifact.revision();
        }
    }

    private final ChatStore chats;
    private final List<Consumer<Event>> listeners = new CopyOnWriteArrayList<>();

    public ChatArtifactStore(ChatStore chats) {
        this.chats = chats;
    }

    public void addListener(Consumer<Event> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<Event> listener) {
        listeners.remove(listener);
    }

    /** Die Artefakte des Chats in der Reihenfolge ihres Anlegens; leer ohne Ordner. */
    public List<ChatArtifact> list(Path folder, String chatId) {
        synchronized (LOCK) {
            List<ChatArtifact> artifacts = new ArrayList<>();
            Path directory = chats.artifacts(folder, chatId);
            if (directory == null || !Files.isDirectory(directory)) {
                return artifacts;
            }
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*" + SUFFIX)) {
                for (Path file : files) {
                    ChatArtifact artifact = read(file);
                    if (artifact != null) {
                        artifacts.add(artifact);
                    }
                }
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Artefakte nicht lesbar: " + e.getClass().getSimpleName());
            }
            artifacts.sort(Comparator.comparingLong(ChatArtifact::createdAt).thenComparing(ChatArtifact::id));
            return artifacts;
        }
    }

    /** Die Kennungen der Reiter, die in diesem Chat zuletzt offen waren (ADR 0045, Abschnitt 5). */
    public List<String> openTabs(Path folder, String chatId) {
        synchronized (LOCK) {
            Path directory = chats.artifacts(folder, chatId);
            Path file = directory == null ? null : directory.resolve(TABS);
            if (file == null || !Files.isRegularFile(file)) {
                return List.of();
            }
            try {
                return Files.readAllLines(file, StandardCharsets.UTF_8).stream().map(String::strip)
                        .filter(id -> ID.matcher(id).matches()).distinct().toList();
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Offene Reiter nicht lesbar: " + e.getClass().getSimpleName());
                return List.of();
            }
        }
    }

    /** Merkt sich die offenen Reiter; nur, wenn der Chat schon Artefakte hat. */
    public void rememberTabs(Path folder, String chatId, List<String> ids) {
        synchronized (LOCK) {
            Path directory = chats.artifacts(folder, chatId);
            if (directory == null || !Files.isDirectory(directory)) {
                return;
            }
            try {
                Files.write(directory.resolve(TABS), ids.stream().filter(id -> ID.matcher(id).matches()).toList(),
                        StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Offene Reiter nicht gespeichert: "
                        + e.getClass().getSimpleName());
            }
        }
    }

    /** Das Artefakt oder {@code null}. Die Kennung ist unempfindlich gegen Groß- und Kleinschreibung. */
    public ChatArtifact read(Path folder, String chatId, String artifactId) {
        synchronized (LOCK) {
            Path file = file(folder, chatId, artifactId);
            return file != null && Files.isRegularFile(file) ? read(file) : null;
        }
    }

    /** Legt ein Dokument an, wahlweise mit erstem Text; {@code null} ohne Datenordner. */
    public ChatArtifact createDocument(Path folder, String chatId, String title, String markdown,
                                       ChatArtifact.Origin origin) {
        SectionDocument document = SectionDocument.parse(markdown == null ? "" : markdown);
        List<String> changed = document.sections().stream().map(SectionDocument.Section::id).toList();
        return create(folder, chatId, ChatArtifact.Kind.DOCUMENT, title, origin, changed, document, "");
    }

    /** Legt eine Grafik an; {@code null} ohne Datenordner. */
    public ChatArtifact createGraphic(Path folder, String chatId, String title, String svg,
                                      ChatArtifact.Origin origin) {
        return create(folder, chatId, ChatArtifact.Kind.GRAPHIC, title, origin, List.of(), null, svg);
    }

    private ChatArtifact create(Path folder, String chatId, ChatArtifact.Kind kind, String title,
                                ChatArtifact.Origin origin, List<String> changed, SectionDocument document,
                                String svg) {
        ChatArtifact created;
        synchronized (LOCK) {
            Path directory = chats.artifacts(folder, chatId);
            if (directory == null) {
                return null;
            }
            String prefix = kind == ChatArtifact.Kind.GRAPHIC ? "G" : "D";
            int next = 1;
            for (ChatArtifact existing : list(folder, chatId)) {
                Matcher match = ID.matcher(existing.id());
                if (match.matches() && match.group(1).equals(prefix)) {
                    next = Math.max(next, Integer.parseInt(match.group(2)) + 1);
                }
            }
            long now = System.currentTimeMillis();
            ChatArtifact.Revision first = new ChatArtifact.Revision(1, 0, now, origin, "angelegt", changed,
                    document, svg);
            created = new ChatArtifact(prefix + next, kind, title, now, List.of(first));
            if (!write(directory, created)) {
                return null;
            }
        }
        notify(folder, chatId, created, changed);
        return created;
    }

    /**
     * Ändert ein Dokument über eine Operation des {@link SectionDocument}. Mit {@code expectedRevision} (die Ansicht)
     * gilt die Änderung nur, wenn niemand dazwischen geschrieben hat; mit {@link #ANY} (die Werkzeuge) auf den
     * neuesten Stand. Eine {@link SectionDocument.SectionException} wird zum Grund des Ergebnisses.
     */
    public WriteResult edit(Path folder, String chatId, String artifactId, long expectedRevision,
                            ChatArtifact.Origin origin, String note,
                            java.util.function.Function<SectionDocument, SectionDocument.Change> operation) {
        WriteResult result;
        synchronized (LOCK) {
            ChatArtifact current = read(folder, chatId, artifactId);
            String problem = problem(current, artifactId, expectedRevision, ChatArtifact.Kind.DOCUMENT);
            if (problem != null) {
                return WriteResult.failed(current, problem);
            }
            SectionDocument.Change change;
            try {
                change = operation.apply(current.document());
            } catch (SectionDocument.SectionException invalid) {
                return WriteResult.failed(current, invalid.getMessage());
            }
            ChatArtifact.Revision revision = new ChatArtifact.Revision(current.revision() + 1, current.revision(),
                    System.currentTimeMillis(), origin, note, change.touched(), change.document(), "");
            result = store(folder, chatId, current, current.with(revision), change.touched());
        }
        if (result.success()) {
            notify(folder, chatId, result.artifact(), result.changed());
        }
        return result;
    }

    /** Ersetzt das SVG einer Grafik; Bedingungen wie {@link #edit}. */
    public WriteResult replaceSvg(Path folder, String chatId, String artifactId, long expectedRevision,
                                  ChatArtifact.Origin origin, String note, String svg) {
        WriteResult result;
        synchronized (LOCK) {
            ChatArtifact current = read(folder, chatId, artifactId);
            String problem = problem(current, artifactId, expectedRevision, ChatArtifact.Kind.GRAPHIC);
            if (problem != null) {
                return WriteResult.failed(current, problem);
            }
            ChatArtifact.Revision revision = new ChatArtifact.Revision(current.revision() + 1, current.revision(),
                    System.currentTimeMillis(), origin, note, List.of(), null, svg);
            result = store(folder, chatId, current, current.with(revision), List.of());
        }
        if (result.success()) {
            notify(folder, chatId, result.artifact(), result.changed());
        }
        return result;
    }

    /**
     * Holt den Stand vor der aktuellen Änderung zurück, als neue Revision. Wiederholt aufgerufen geht es Schritt für
     * Schritt weiter zurück; der erste Stand lässt sich nicht rückgängig machen.
     */
    public WriteResult undo(Path folder, String chatId, String artifactId, long expectedRevision,
                            ChatArtifact.Origin origin) {
        WriteResult result;
        synchronized (LOCK) {
            ChatArtifact current = read(folder, chatId, artifactId);
            String problem = problem(current, artifactId, expectedRevision, null);
            if (problem != null) {
                return WriteResult.failed(current, problem);
            }
            ChatArtifact.Revision target = current.revision(current.current().parent());
            if (target == null) {
                return WriteResult.failed(current, "Es gibt nichts mehr rückgängig zu machen.");
            }
            List<String> changed = changedBetween(current.current().document(), target.document());
            // Die Abschnitte des Ziels, aber der Zähler bleibt oben: eine zurückgenommene Kennung kommt nie wieder.
            SectionDocument now = current.current().document();
            SectionDocument restored = target.document() == null || now == null ? target.document()
                    : new SectionDocument(target.document().sections(),
                    Math.max(target.document().nextId(), now.nextId()));
            ChatArtifact.Revision revision = new ChatArtifact.Revision(current.revision() + 1, target.parent(),
                    System.currentTimeMillis(), origin, "rückgängig", changed, restored, target.svg());
            result = store(folder, chatId, current, current.with(revision), changed);
        }
        if (result.success()) {
            notify(folder, chatId, result.artifact(), result.changed());
        }
        return result;
    }

    /** Benennt ein Artefakt um; der Titel ist keine Revision. */
    public WriteResult rename(Path folder, String chatId, String artifactId, String title) {
        WriteResult result;
        synchronized (LOCK) {
            ChatArtifact current = read(folder, chatId, artifactId);
            String problem = problem(current, artifactId, ANY, null);
            if (problem != null) {
                return WriteResult.failed(current, problem);
            }
            if (title == null || title.isBlank()) {
                return WriteResult.failed(current, "Der Titel ist leer.");
            }
            result = store(folder, chatId, current, current.withTitle(title), List.of());
        }
        if (result.success()) {
            notify(folder, chatId, result.artifact(), List.of());
        }
        return result;
    }

    private String problem(ChatArtifact current, String artifactId, long expectedRevision, ChatArtifact.Kind kind) {
        if (current == null) {
            return "Artefakt '" + artifactId + "' gibt es in diesem Chat nicht. Mit dokumente_auflisten nachsehen.";
        }
        if (kind != null && current.kind() != kind) {
            return kind == ChatArtifact.Kind.DOCUMENT
                    ? current.id() + " ist eine Grafik, kein Dokument."
                    : current.id() + " ist ein Dokument, keine Grafik.";
        }
        if (expectedRevision != ANY && expectedRevision != current.revision()) {
            return "Das Artefakt wurde inzwischen geändert (jetzt Stand " + current.revision() + ").";
        }
        return null;
    }

    private WriteResult store(Path folder, String chatId, ChatArtifact current, ChatArtifact next,
                              List<String> changed) {
        Path directory = chats.artifacts(folder, chatId);
        if (directory == null || !write(directory, next)) {
            return WriteResult.failed(current, "Das Artefakt konnte nicht gespeichert werden.");
        }
        return WriteResult.ok(next, changed);
    }

    private void notify(Path folder, String chatId, ChatArtifact artifact, List<String> changed) {
        Event event = new Event(folder, chatId, artifact, changed);
        for (Consumer<Event> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Artefakt-Beobachter gescheitert: "
                        + e.getClass().getSimpleName());
            }
        }
    }

    /** Die Abschnitte, die sich zwischen zwei Ständen unterscheiden oder neu sind. */
    private static List<String> changedBetween(SectionDocument from, SectionDocument to) {
        List<String> changed = new ArrayList<>();
        for (SectionDocument.Section section : to.sections()) {
            SectionDocument.Section before = from.sections().stream()
                    .filter(candidate -> candidate.id().equals(section.id())).findFirst().orElse(null);
            if (!section.equals(before)) {
                changed.add(section.id());
            }
        }
        return changed;
    }

    private Path file(Path folder, String chatId, String artifactId) {
        if (artifactId == null) {
            return null;
        }
        String id = artifactId.strip().toUpperCase(java.util.Locale.ROOT);
        Path directory = chats.artifacts(folder, chatId);
        return directory == null || !ID.matcher(id).matches() ? null : directory.resolve(id + SUFFIX);
    }

    private static boolean write(Path directory, ChatArtifact artifact) {
        Path target = directory.resolve(artifact.id() + SUFFIX);
        Path temp = directory.resolve(artifact.id() + SUFFIX + ".tmp");
        try {
            Files.createDirectories(directory);
            Files.writeString(temp, JSON.writeValueAsString(artifact), StandardCharsets.UTF_8);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Artefakt nicht gespeichert: " + e.getClass().getSimpleName());
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // bleibt liegen und wird beim nächsten Speichern überschrieben
            }
            return false;
        }
    }

    private static ChatArtifact read(Path file) {
        try {
            ChatArtifact artifact = JSON.readValue(Files.readString(file, StandardCharsets.UTF_8), ChatArtifact.class);
            return artifact == null || artifact.revisions().isEmpty() ? null : artifact;
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Artefakt nicht lesbar: " + e.getClass().getSimpleName());
            return null;
        }
    }
}
