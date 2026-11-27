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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 * <p>Besitzer (ADR 0043, F3): Im Standardprojekt gehören die Artefakte dem Chat; gehört der Chat zu einem anderen
 * Projekt, gehören sie dem Projekt und liegen in dessen Ordner ({@link #projects}), und alle Chats des Projekts
 * arbeiten darauf. Die offenen Reiter bleiben je Chat.
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
    private static final Pattern ID = Pattern.compile("([DGB])(\\d{1,6})");
    /** Die Kennung eines Buchs (ADR 0044): B1, B2 … – ein Dokument, das einem Ordner gehört, nicht einem Chat. */
    public static final String BOOK = "B";
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    /** Ein Schloss für alle Instanzen: Ansicht und Werkzeuge dürfen sich nie überschreiben. */
    private static final Object LOCK = new Object();

    /**
     * Eine Änderung an einem Artefakt, für die Ansicht; {@code owner} ist der Ordner des Besitzers ({@link #owner}),
     * sodass jeder Chat eines Projekts die Änderung eines anderen sieht.
     */
    public record Event(Path folder, String chatId, Path owner, ChatArtifact artifact, List<String> changed) {
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
    /** Der Artefakt-Ordner eines Projekts; {@code null} für das Standardprojekt und unbekannte Projekte. */
    private volatile java.util.function.Function<String, Path> projectArtifacts = projectId -> null;
    /** Das Projekt der Chats, die gerade offen sind (auch ein noch nicht gespeicherter); sonst steht es im Chat. */
    private final Map<String, String> projectOf = new ConcurrentHashMap<>();
    /** Weitere Besitzer, deren Artefakte ein Chat sieht und bearbeitet (Bücher seines Ordners im Standardprojekt). */
    private volatile java.util.function.BiFunction<Path, String, List<Path>> shared = (folder, chatId) -> List.of();

    public ChatArtifactStore(ChatStore chats) {
        this.chats = chats;
    }

    /** Wo die Artefakte eines Projekts liegen (F3); {@code null} heißt: die Chats besitzen sie selbst. */
    public void projects(java.util.function.Function<String, Path> projectArtifacts) {
        this.projectArtifacts = projectArtifacts == null ? projectId -> null : projectArtifacts;
    }

    /**
     * Weitere Besitzer eines Chats (Bücher am Favoritenordner, Angelo 27.11.): ihre Artefakte erscheinen in der Liste
     * des Chats, lassen sich öffnen und von der KI bearbeiten; gespeichert wird jedes dort, wo es liegt.
     * {@code chatId} darf {@code null} sein (Ansicht ohne Chat).
     */
    public void shared(java.util.function.BiFunction<Path, String, List<Path>> owners) {
        this.shared = owners == null ? (folder, chatId) -> List.of() : owners;
    }

    /** Alle Besitzer, deren Artefakte der Chat sieht: zuerst sein eigener, dann die geteilten. */
    public List<Path> owners(Path folder, String chatId) {
        java.util.LinkedHashSet<Path> all = new java.util.LinkedHashSet<>();
        Path own = owner(folder, chatId);
        if (own != null) {
            all.add(own);
        }
        List<Path> more = shared.apply(folder, chatId);
        if (more != null) {
            more.stream().filter(java.util.Objects::nonNull).forEach(all::add);
        }
        return List.copyOf(all);
    }

    /** Der Besitzer, bei dem das Artefakt liegt; für ein noch nicht vorhandenes der eigene des Chats. */
    public Path ownerOf(Path folder, String chatId, String artifactId) {
        String id = artifactId == null ? null : artifactId.strip().toUpperCase(java.util.Locale.ROOT);
        if (id != null && ID.matcher(id).matches()) {
            for (Path candidate : owners(folder, chatId)) {
                if (Files.isRegularFile(candidate.resolve(id + SUFFIX))) {
                    return candidate;
                }
            }
        }
        return owner(folder, chatId);
    }

    /** Meldet das Projekt eines offenen Chats, auch bevor er gespeichert ist ({@code null} = Standardprojekt). */
    public void chatProject(String chatId, String projectId) {
        // Unter demselben Schloss wie jedes Lesen und Schreiben: eine laufende Änderung liest und schreibt beim selben
        // Besitzer, auch wenn der Chat gerade umzieht.
        synchronized (LOCK) {
            if (chatId != null) {
                projectOf.put(chatId, projectId == null ? "" : projectId);
            }
        }
    }

    /**
     * Der Ordner, in dem die Artefakte des Chats liegen: der des Projekts, wenn der Chat zu einem Projekt außer dem
     * Standardprojekt gehört, sonst der des Chats; {@code null} ohne Datenordner.
     */
    public Path owner(Path folder, String chatId) {
        String projectId = chatId == null ? null : projectOf.get(chatId);
        if (projectId == null && chatId != null) {
            com.aresstack.spaceharvester.app.chat.ChatRecord record = chats.load(folder, chatId);
            projectId = record == null ? null : record.projectId();
        }
        Path project = projectId == null || projectId.isEmpty() ? null : projectArtifacts.apply(projectId);
        return project != null ? project : chats.artifacts(folder, chatId);
    }

    /**
     * Ein Chat kommt in ein Projekt (F3): seine eigenen Artefakte ziehen in den Ordner des Projekts um. Dort bekommen
     * sie freie Kennungen; Verweise zwischen ihnen (Grafiken im Dokument) und die offenen Reiter des Chats folgen.
     *
     * <p>Auch beim Öffnen gerufen: Ein Chat, der schon vor der Projektablage für Artefakte in einem Projekt war, hat
     * seine Artefakte noch bei sich; sie ziehen dann einmal um.
     *
     * @return die neuen Kennungen der umgezogenen Artefakte (alt → neu), für Verweise im Verlauf des Chats
     */
    public Map<String, String> moveToProject(Path folder, String chatId, String projectId) {
        synchronized (LOCK) {
            chatProject(chatId, projectId);
            Path from = chats.artifacts(folder, chatId);
            Path to = projectId == null ? null : projectArtifacts.apply(projectId);
            List<ChatArtifact> own = from == null ? List.of() : listIn(from);
            if (to == null || to.equals(from)) {
                return Map.of();
            }
            if (own.isEmpty()) {
                return Map.of();
            }
            Map<String, String> renamed = new LinkedHashMap<>();
            Map<String, Integer> next = new java.util.HashMap<>();
            for (ChatArtifact existing : listIn(to)) {
                Matcher match = ID.matcher(existing.id());
                if (match.matches()) {
                    next.merge(match.group(1), Integer.parseInt(match.group(2)) + 1, Math::max);
                }
            }
            for (ChatArtifact artifact : own) {
                String prefix = artifact.kind() == ChatArtifact.Kind.GRAPHIC ? "G" : "D";
                int number = next.getOrDefault(prefix, 1);
                next.put(prefix, number + 1);
                renamed.put(artifact.id(), prefix + number);
            }
            Map<String, String> moved = new LinkedHashMap<>();
            for (ChatArtifact artifact : own) {
                ChatArtifact copy = renamed(artifact, renamed);
                if (copy == null || !write(to, copy)) {
                    continue;
                }
                try {
                    Files.deleteIfExists(from.resolve(artifact.id() + SUFFIX));
                    moved.put(artifact.id(), copy.id());
                } catch (IOException e) {
                    LOG.log(System.Logger.Level.WARNING, "Artefakt nicht verschoben: " + e.getClass().getSimpleName());
                }
            }
            List<String> tabs = openTabs(folder, chatId).stream().map(moved::get)
                    .filter(java.util.Objects::nonNull).toList();
            Path tabsFile = from == null ? null : from.resolve(TABS);
            try {
                if (tabsFile != null && tabs.isEmpty()) {
                    Files.deleteIfExists(tabsFile);
                } else if (tabsFile != null) {
                    Files.write(tabsFile, tabs, StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Offene Reiter nicht umgestellt: " + e.getClass().getSimpleName());
            }
            return moved;
        }
    }

    /** {@code text} mit jedem Verweis {@code artefakt:D1} auf die neue Kennung aus {@code renamed}. */
    public static String relink(String text, Map<String, String> renamed) {
        if (text == null || renamed.isEmpty() || !text.contains(DocumentChatTools.LINK_SCHEME)) {
            return text;
        }
        Matcher match = LINK.matcher(text);
        StringBuilder out = new StringBuilder();
        while (match.find()) {
            String target = renamed.get(match.group(1).toUpperCase(java.util.Locale.ROOT));
            match.appendReplacement(out, Matcher.quoteReplacement(target == null ? match.group()
                    : DocumentChatTools.LINK_SCHEME + target));
        }
        match.appendTail(out);
        return out.toString();
    }

    /** Verweise auf eine Grafik im Text: {@code artefakt:G1}, ohne weitere Ziffer dahinter. */
    private static final Pattern LINK = Pattern.compile(Pattern.quote(DocumentChatTools.LINK_SCHEME)
            + "([DG]\\d{1,6})(?!\\d)", Pattern.CASE_INSENSITIVE);

    /** Das Artefakt mit neuer Kennung, jeder Verweis in jeder Revision umgeschrieben; {@code null}, wenn es scheitert. */
    private static ChatArtifact renamed(ChatArtifact artifact, Map<String, String> renamed) {
        try {
            String json = JSON.writeValueAsString(new ChatArtifact(renamed.get(artifact.id()), artifact.kind(),
                    artifact.title(), artifact.createdAt(), artifact.revisions()));
            return JSON.readValue(relink(json, renamed), ChatArtifact.class);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Artefakt nicht umbenannt: " + e.getClass().getSimpleName());
            return null;
        }
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
            List<ChatArtifact> all = new ArrayList<>();
            for (Path directory : owners(folder, chatId)) {
                all.addAll(listIn(directory));
            }
            all.sort(Comparator.comparingLong(ChatArtifact::createdAt).thenComparing(ChatArtifact::id));
            return all;
        }
    }

    /** Die Artefakte eines Besitzers (Projekt-, Chat- oder Buchordner) in der Reihenfolge ihres Anlegens. */
    public static List<ChatArtifact> listIn(Path directory) {
        synchronized (LOCK) {
            List<ChatArtifact> artifacts = new ArrayList<>();
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
            // Im Projekt liegen die Artefakte beim Projekt; der Ordner des Chats entsteht dann erst für seine Reiter.
            if (directory == null || !Files.isDirectory(directory) && (ids.isEmpty() || list(folder, chatId).isEmpty())) {
                return;
            }
            try {
                Files.createDirectories(directory);
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

    /**
     * Legt ein Buch an (ADR 0044, Angelo 27.11.): ein Dokument mit Kennung B…, das dem Ordner {@code owner} gehört,
     * ohne Chat. Jeder Chat, der diesen Besitzer sieht ({@link #owners}), kann es öffnen und bearbeiten.
     */
    public ChatArtifact createBook(Path owner, String title, String markdown, ChatArtifact.Origin origin) {
        if (owner == null) {
            return null;
        }
        SectionDocument document = SectionDocument.parse(markdown == null ? "" : markdown);
        List<String> changed = document.sections().stream().map(SectionDocument.Section::id).toList();
        ChatArtifact created;
        synchronized (LOCK) {
            created = newArtifact(listIn(owner), BOOK, ChatArtifact.Kind.DOCUMENT, title, origin, changed, document,
                    "");
            if (!write(owner, created)) {
                return null;
            }
        }
        notify(new Event(null, null, owner, created, changed));
        return created;
    }

    /** Ein Artefakt eines Besitzers oder {@code null}. */
    public ChatArtifact readIn(Path owner, String artifactId) {
        String id = artifactId == null ? null : artifactId.strip().toUpperCase(java.util.Locale.ROOT);
        if (owner == null || id == null || !ID.matcher(id).matches()) {
            return null;
        }
        synchronized (LOCK) {
            Path file = owner.resolve(id + SUFFIX);
            return Files.isRegularFile(file) ? read(file) : null;
        }
    }

    /** Legt eine Grafik an; {@code null} ohne Datenordner. */
    public ChatArtifact createGraphic(Path folder, String chatId, String title, String svg,
                                      ChatArtifact.Origin origin) {
        // Jedes sichtbare Element bekommt eine feste Kennung (e1, e2, …), damit Bezüge und Änderungen eines treffen.
        return create(folder, chatId, ChatArtifact.Kind.GRAPHIC, title, origin, List.of(), null,
                SvgElements.withIds(svg));
    }

    private ChatArtifact create(Path folder, String chatId, ChatArtifact.Kind kind, String title,
                                ChatArtifact.Origin origin, List<String> changed, SectionDocument document,
                                String svg) {
        ChatArtifact created;
        synchronized (LOCK) {
            Path directory = owner(folder, chatId);
            if (directory == null) {
                return null;
            }
            String prefix = kind == ChatArtifact.Kind.GRAPHIC ? "G" : "D";
            created = newArtifact(list(folder, chatId), prefix, kind, title, origin, changed, document, svg);
            if (!write(directory, created)) {
                return null;
            }
        }
        notify(folder, chatId, created, changed);
        return created;
    }

    /** Ein neues Artefakt mit der nächsten freien Kennung zum Präfix unter {@code existing}. */
    private static ChatArtifact newArtifact(List<ChatArtifact> existing, String prefix, ChatArtifact.Kind kind,
                                            String title, ChatArtifact.Origin origin, List<String> changed,
                                            SectionDocument document, String svg) {
        int next = 1;
        for (ChatArtifact each : existing) {
            Matcher match = ID.matcher(each.id());
            if (match.matches() && match.group(1).equals(prefix)) {
                next = Math.max(next, Integer.parseInt(match.group(2)) + 1);
            }
        }
        long now = System.currentTimeMillis();
        ChatArtifact.Revision first = new ChatArtifact.Revision(1, 0, now, origin, "angelegt", changed, document,
                svg);
        return new ChatArtifact(prefix + next, kind, title, now, List.of(first));
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
        return changeSvg(folder, chatId, artifactId, expectedRevision, origin, note, old -> svg);
    }

    /**
     * Ändert das SVG einer Grafik ausgehend vom neuesten Stand, unter demselben Schloss wie jedes Schreiben: Zwei
     * gleichzeitige Änderungen (parallele Werkzeugaufrufe) gehen nacheinander und keine verliert die andere.
     * {@code change} darf {@link SvgElements.SvgException} werfen; dann bleibt die Grafik, wie sie ist.
     */
    public WriteResult changeSvg(Path folder, String chatId, String artifactId, long expectedRevision,
                                 ChatArtifact.Origin origin, String note,
                                 java.util.function.UnaryOperator<String> change) {
        WriteResult result;
        synchronized (LOCK) {
            ChatArtifact current = read(folder, chatId, artifactId);
            String problem = problem(current, artifactId, expectedRevision, ChatArtifact.Kind.GRAPHIC);
            if (problem != null) {
                return WriteResult.failed(current, problem);
            }
            ChatArtifact.Revision revision = new ChatArtifact.Revision(current.revision() + 1, current.revision(),
                    System.currentTimeMillis(), origin, note, List.of(), null,
                    SvgElements.withIds(change.apply(current.current().svg())));
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
        Path directory = ownerOf(folder, chatId, next.id());
        if (directory == null || !write(directory, next)) {
            return WriteResult.failed(current, "Das Artefakt konnte nicht gespeichert werden.");
        }
        return WriteResult.ok(next, changed);
    }

    private void notify(Path folder, String chatId, ChatArtifact artifact, List<String> changed) {
        notify(new Event(folder, chatId, ownerOf(folder, chatId, artifact.id()), artifact, changed));
    }

    private void notify(Event event) {
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
        Path directory = ownerOf(folder, chatId, id);
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
