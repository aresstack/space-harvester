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
 * <p>Besitzer (ADR 0047, ersetzt ADR 0043 F3): Artefakte gehören immer dem Projekt des Chats, auch dem
 * Standardprojekt, und liegen in dessen Ordner ({@link #projects}); alle Chats des Projekts arbeiten darauf, auch ein
 * neuer. Ältere Artefakte in Chat-Ordnern ziehen einmal um ({@link #adoptAll}). Die offenen Reiter bleiben je Chat.
 *
 * <p>Wer zuschaut, bekommt jede Änderung gemeldet ({@link #addListener}, ADR 0027: kein Polling). In Logs stehen nie
 * Texte aus Artefakten, nur die Ausnahmeklasse (Abschnitt 9).
 */
public final class ChatArtifactStore {

    /** Die Endung einer Artefakt-Datei; bewusst nicht {@code .json}, damit die Chat-Listen sie nie lesen. */
    static final String SUFFIX = ".artefakt";
    /** Ein gelöschtes Artefakt bis zum nächsten Start (Papierkorb wie beim Chat, ADR 0047): wiederherstellbar. */
    static final String DELETED = SUFFIX + ".deleted";
    /**
     * Was vom endgültig Gelöschten bleibt: eine leere Marke, damit seine Kennung nie wieder vergeben wird und alte
     * Verweise ({@code artefakt:G2}) nie auf fremden Inhalt zeigen.
     */
    static final String TOMBSTONE = SUFFIX + ".vergeben";
    /** Die Besitzer, deren Gelöschte dieser Programmlauf schon endgültig entfernt hat (einmal je Start). */
    private static final java.util.Set<Path> PURGED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Jede Kennung eines erwarteten Stands passt: Die Werkzeuge schreiben immer auf den neuesten. */
    public static final long ANY = -1;
    /** Die zuletzt offenen Reiter des Chats, eine Kennung je Zeile. */
    private static final String TABS = "offen.txt";

    private static final System.Logger LOG = System.getLogger(ChatArtifactStore.class.getName());
    private static final Pattern ID = Pattern.compile("([DGB])(\\d{1,6})");
    /** Die Kennung eines Buchs (ADR 0044/0047): B1, B2 … – ein Dokument des Projekts, eines je Ordner. */
    public static final String BOOK = "B";
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    /** Ein Schloss für alle Instanzen: Ansicht und Werkzeuge dürfen sich nie überschreiben. */
    private static final Object LOCK = new Object();

    /**
     * Eine Änderung an einem Artefakt, für die Ansicht; {@code owner} ist der Ordner des Besitzers ({@link #owner}),
     * sodass jeder Chat eines Projekts die Änderung eines anderen sieht.
     */
    public record Event(Path folder, String chatId, Path owner, ChatArtifact artifact, List<String> changed,
                        boolean deleted) {
        public Event(Path folder, String chatId, Path owner, ChatArtifact artifact, List<String> changed) {
            this(folder, chatId, owner, artifact, changed, false);
        }
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
    /** Der Artefakt-Ordner eines Projekts; {@code null}, solange keiner bekannt ist (dann besitzt der Chat). */
    private volatile java.util.function.Function<String, Path> projectArtifacts = projectId -> null;
    /** Das Projekt der Chats, die gerade offen sind (auch ein noch nicht gespeicherter); sonst steht es im Chat. */
    private final Map<String, String> projectOf = new ConcurrentHashMap<>();

    public ChatArtifactStore(ChatStore chats) {
        this.chats = chats;
    }

    /** Wo die Artefakte eines Projekts liegen (F3); {@code null} heißt: die Chats besitzen sie selbst. */
    public void projects(java.util.function.Function<String, Path> projectArtifacts) {
        this.projectArtifacts = projectArtifacts == null ? projectId -> null : projectArtifacts;
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
     * Der Ordner, in dem die Artefakte des Chats liegen: immer der seines Projekts, auch im Standardprojekt (ADR 0047);
     * der Ordner des Chats nur, solange kein Projektordner bekannt ist. {@code null} ohne Datenordner.
     */
    public Path owner(Path folder, String chatId) {
        Path project = projectArtifacts.apply(projectOf(folder, chatId));
        return project != null ? project : chats.artifacts(folder, chatId);
    }

    /** Das Projekt des Chats; ohne eines das Standardprojekt. */
    private String projectOf(Path folder, String chatId) {
        String projectId = chatId == null ? null : projectOf.get(chatId);
        if (projectId == null && chatId != null) {
            com.aresstack.spaceharvester.app.chat.ChatRecord record = chats.load(folder, chatId);
            projectId = record == null ? null : record.projectId();
        }
        return projectId == null || projectId.isEmpty()
                ? com.aresstack.spaceharvester.app.project.Project.DEFAULT_ID : projectId;
    }

    /**
     * Der einmalige Umzug (ADR 0047): Artefakte, die noch in Chat-Ordnern liegen, ziehen in den Ordner ihres Projekts,
     * mit freien Kennungen; Verweise zwischen ihnen, die offenen Reiter und die Verweise im Verlauf des Chats folgen.
     * Nur lokal, ohne Netz und ohne KI; danach findet ein weiterer Aufruf nichts mehr.
     *
     * @return die Zahl der umgezogenen Artefakte
     */
    public int adoptAll() {
        return adoptAllByChat().values().stream().mapToInt(Map::size).sum();
    }

    /** Welche Chat-Datenordner in diesem Programmlauf schon umgezogen sind (einmal je Datenordner). */
    private static final java.util.Set<Path> ADOPTED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Der einmalige Umzug, beim ersten Zugriff auf einen bekannten Datenordner; ohne Datenordner (die Einstellungen
     * sind noch nicht geladen) geschieht nichts und der nächste Aufruf versucht es wieder.
     *
     * @return je Chat die neuen Kennungen (alt → neu), damit ein schon geladener Chat seinen Verlauf nachzieht
     */
    public Map<String, Map<String, String>> adoptOnce() {
        Path root = chats.rootPath();
        if (root == null || !ADOPTED.add(root)) {
            return Map.of();
        }
        return adoptAllByChat();
    }

    private Map<String, Map<String, String>> adoptAllByChat() {
        Map<String, Map<String, String>> moved = new LinkedHashMap<>();
        for (com.aresstack.spaceharvester.app.chat.ChatStore.ArtifactFolder each : chats.artifactFolders()) {
            // Ein Chat, dessen Umzug scheitert, bleibt unverändert liegen und hält die übrigen nicht auf.
            try {
                Map<String, String> renamed = moveToProject(each.folder(), each.chatId(),
                        projectOf(each.folder(), each.chatId()));
                if (renamed.isEmpty()) {
                    continue;
                }
                moved.put(each.chatId(), renamed);
                com.aresstack.spaceharvester.app.chat.ChatRecord record = chats.load(each.folder(), each.chatId());
                if (record != null && !record.isEmpty()) {
                    chats.save(each.folder(), record.withTexts(text -> relink(text, renamed)));
                }
            } catch (RuntimeException failed) {
                LOG.log(System.Logger.Level.WARNING, "Artefakte eines Chats nicht umgezogen (Bereich "
                        + each.folder() + "): " + failed.getClass().getSimpleName());
            }
        }
        return moved;
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
        List<Event> events = new ArrayList<>();
        Map<String, String> moved = moveLocked(folder, chatId, projectId, events);
        // Jede Ansicht beider Besitzer erfährt den Umzug (etwa die Liste „Artefakte“ links): fort im alten, neu im neuen.
        events.forEach(this::notify);
        return moved;
    }

    private Map<String, String> moveLocked(Path folder, String chatId, String projectId, List<Event> events) {
        synchronized (LOCK) {
            // Der bisherige Besitzer, bevor das neue Projekt gilt: Wechselt der Chat das Projekt, ziehen die von ihm
            // erzeugten Artefakte mit (ADR 0047, erzeugender Chat); die übrigen bleiben im alten Projekt.
            Path previous = owner(folder, chatId);
            String before = chatId == null ? null : projectOf.get(chatId);
            chatProject(chatId, projectId);
            Path chatFolder = chats.artifacts(folder, chatId);
            Path to = projectOwner(projectId);
            if (to == null) {
                return Map.of();
            }
            Map<ChatArtifact, Path> origins = new LinkedHashMap<>();
            if (chatFolder != null && !chatFolder.equals(to)) {
                listIn(chatFolder).forEach(artifact -> origins.put(artifact, chatFolder));
            }
            if (previous != null && !previous.equals(to) && !previous.equals(chatFolder)) {
                listIn(previous).stream().filter(artifact -> chatId != null && chatId.equals(artifact.chatId()))
                        .forEach(artifact -> origins.put(artifact, previous));
            }
            // Grafiken, die ein mitziehendes Dokument einbettet, aber ein anderer Chat erzeugt hat, werden mitkopiert
            // (das Original bleibt bei seinem Chat), damit das Dokument im neuen Projekt dieselbe Grafik zeigt.
            Map<ChatArtifact, Path> borrowed = new LinkedHashMap<>();
            if (previous != null && !previous.equals(to)) {
                Map<String, ChatArtifact> there = new LinkedHashMap<>();
                listIn(previous).forEach(artifact -> there.put(artifact.id(), artifact));
                java.util.Set<String> moving = new java.util.HashSet<>();
                origins.forEach((artifact, from) -> {
                    if (from.equals(previous)) {
                        moving.add(artifact.id());
                    }
                });
                for (ChatArtifact artifact : List.copyOf(origins.keySet())) {
                    if (artifact.kind() != ChatArtifact.Kind.DOCUMENT) {
                        continue;
                    }
                    for (String referenced : references(artifact)) {
                        ChatArtifact graphic = there.get(referenced);
                        if (graphic != null && !moving.contains(referenced) && !borrowed.containsKey(graphic)) {
                            borrowed.put(graphic, previous);
                        }
                    }
                }
            }
            List<ChatArtifact> own = List.copyOf(origins.keySet());
            if (own.isEmpty()) {
                return Map.of();
            }
            List<ChatArtifact> copied = new ArrayList<>(own);
            copied.addAll(borrowed.keySet());
            Map<String, String> renamed = new LinkedHashMap<>();
            Map<String, Integer> next = new java.util.HashMap<>();
            for (String existing : takenIds(to)) {
                Matcher match = ID.matcher(existing);
                if (match.matches()) {
                    next.merge(match.group(1), Integer.parseInt(match.group(2)) + 1, Math::max);
                }
            }
            for (ChatArtifact artifact : copied) {
                Matcher own_ = ID.matcher(artifact.id());
                String prefix = own_.matches() ? own_.group(1)
                        : artifact.kind() == ChatArtifact.Kind.GRAPHIC ? "G" : "D";
                int number = next.getOrDefault(prefix, 1);
                next.put(prefix, number + 1);
                renamed.put(artifact.id(), prefix + number);
            }
            // Ganz oder gar nicht: erst alle Kopien schreiben; scheitert eine, bleibt der Chat, wie er war.
            List<ChatArtifact> copies = new ArrayList<>();
            for (ChatArtifact artifact : copied) {
                // Ältere Artefakte bekommen beim Umzug den Chat, aus dessen Ordner sie kommen.
                ChatArtifact copy = renamed(artifact.chatId() != null || origins.get(artifact) != chatFolder
                        ? artifact : artifact.withChat(chatId), renamed);
                if (copy == null || !write(to, copy)) {
                    for (ChatArtifact written : copies) {
                        try {
                            Files.deleteIfExists(to.resolve(written.id() + SUFFIX));
                        } catch (IOException e) {
                            LOG.log(System.Logger.Level.WARNING, "Kopie nicht entfernt: "
                                    + e.getClass().getSimpleName());
                        }
                    }
                    LOG.log(System.Logger.Level.WARNING, "Artefakte eines Chats nicht umgezogen (Bereich " + folder
                            + "); sie bleiben beim Chat.");
                    // Der Chat bleibt beim bisherigen Besitzer, damit ein neuer Versuch die Originale wiederfindet.
                    restoreProject(chatId, before);
                    return Map.of();
                }
                copies.add(copy);
            }
            Map<String, String> moved = new LinkedHashMap<>();
            for (ChatArtifact artifact : own) {
                try {
                    Files.deleteIfExists(origins.get(artifact).resolve(artifact.id() + SUFFIX));
                } catch (IOException e) {
                    // Die Kopie gilt. Das Original (etwa gesperrt) bleibt liegen; ein späterer Umzug fände es noch einmal.
                    LOG.log(System.Logger.Level.WARNING, "Original nicht entfernt: " + e.getClass().getSimpleName());
                }
                moved.put(artifact.id(), renamed.get(artifact.id()));
                events.add(new Event(null, null, origins.get(artifact), artifact, List.of(), true));
            }
            copies.forEach(copy -> events.add(new Event(null, null, to, copy, List.of())));
            List<String> tabs = openTabs(folder, chatId).stream().map(moved::get)
                    .filter(java.util.Objects::nonNull).toList();
            Path tabsFile = chatFolder == null ? null : chatFolder.resolve(TABS);
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

    /**
     * Setzt das gemerkte Projekt eines offenen Chats auf den Stand vor einem gescheiterten Umzug zurück. Nur beim
     * Scheitern: Gibt es nichts umzuziehen, gilt das neue Projekt (auch für einen noch nicht gespeicherten Chat).
     */
    private void restoreProject(String chatId, String before) {
        if (chatId == null) {
            return;
        }
        if (before == null) {
            projectOf.remove(chatId);
        } else {
            projectOf.put(chatId, before);
        }
    }

    /** Die Grafiken, auf die ein Dokument in irgendeinem Stand verweist ({@code artefakt:G1}). */
    private static java.util.Set<String> references(ChatArtifact artifact) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (ChatArtifact.Revision revision : artifact.revisions()) {
            if (revision.document() != null) {
                ids.addAll(GraphicReferences.ids(revision.document().markdown()));
            }
        }
        return ids;
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
                    artifact.title(), artifact.createdAt(), artifact.revisions(), artifact.chatId()));
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
            Path owner = owner(folder, chatId);
            purgeOnce(owner);
            return listIn(owner);
        }
    }

    /** Die gelöschten Artefakte des Besitzers (Papierkorb bis zum nächsten Start), zuletzt angelegte zuletzt. */
    public List<ChatArtifact> deleted(Path folder, String chatId) {
        synchronized (LOCK) {
            Path owner = owner(folder, chatId);
            purgeOnce(owner);
            List<ChatArtifact> artifacts = new ArrayList<>();
            if (owner == null || !Files.isDirectory(owner)) {
                return artifacts;
            }
            try (DirectoryStream<Path> files = Files.newDirectoryStream(owner, "*" + DELETED)) {
                for (Path file : files) {
                    ChatArtifact artifact = read(file);
                    if (artifact != null) {
                        artifacts.add(artifact);
                    }
                }
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Papierkorb nicht lesbar: " + e.getClass().getSimpleName());
            }
            artifacts.sort(Comparator.comparingLong(ChatArtifact::createdAt).thenComparing(ChatArtifact::id));
            return artifacts;
        }
    }

    /**
     * Löscht ein Artefakt (ADR 0047: einzeln am Artefakt, nicht mit dem Chat): Es geht in den Papierkorb und ist bis zum
     * nächsten Start wiederherstellbar ({@link #restore}); seine Kennung wird nicht neu vergeben.
     */
    public WriteResult delete(Path folder, String chatId, String artifactId) {
        return deleteAt(() -> owner(folder, chatId), folder, chatId, artifactId);
    }

    /** Wie {@link #delete}, für ein Artefakt des Projekts ohne Chat (Liste „Artefakte“ links, ADR 0047 §5). */
    public WriteResult deleteInProject(String projectId, String artifactId) {
        return deleteAt(() -> projectOwner(projectId), null, null, artifactId);
    }

    private WriteResult deleteAt(java.util.function.Supplier<Path> where, Path folder, String chatId,
                                 String artifactId) {
        ChatArtifact current;
        Path owner;
        synchronized (LOCK) {
            owner = where.get();
            current = readAt(owner, artifactId);
            String problem = problem(current, artifactId, ANY, null);
            if (problem != null) {
                return WriteResult.failed(current, problem);
            }
            // Erst den Papierkorb des letzten Laufs leeren, sonst ginge beim nächsten Auflisten auch dieses mit.
            purgeOnce(owner);
            try {
                Files.move(owner.resolve(current.id() + SUFFIX), owner.resolve(current.id() + DELETED),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Artefakt nicht gelöscht: " + e.getClass().getSimpleName());
                return WriteResult.failed(current, "Das Artefakt konnte nicht gelöscht werden.");
            }
        }
        notify(new Event(folder, chatId, owner, current, List.of(), true));
        return WriteResult.ok(current, List.of());
    }

    /** Holt ein gelöschtes Artefakt aus dem Papierkorb zurück; {@code null}, wenn es dort nicht (mehr) liegt. */
    public ChatArtifact restore(Path folder, String chatId, String artifactId) {
        ChatArtifact restored;
        synchronized (LOCK) {
            Path owner = owner(folder, chatId);
            String id = artifactId == null ? "" : artifactId.strip().toUpperCase(java.util.Locale.ROOT);
            if (owner == null || !ID.matcher(id).matches()) {
                return null;
            }
            Path trashed = owner.resolve(id + DELETED);
            Path target = owner.resolve(id + SUFFIX);
            if (!Files.isRegularFile(trashed) || Files.exists(target)) {
                return null;
            }
            try {
                Files.move(trashed, target);
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Artefakt nicht wiederhergestellt: "
                        + e.getClass().getSimpleName());
                return null;
            }
            restored = read(target);
        }
        if (restored != null) {
            notify(folder, chatId, restored, List.of());
        }
        return restored;
    }

    /** Die vergebenen Kennungen eines Besitzers, auch die im Papierkorb: Sie werden nicht neu vergeben. */
    private static List<String> takenIds(Path owner) {
        List<String> ids = new ArrayList<>();
        if (owner == null || !Files.isDirectory(owner)) {
            return ids;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(owner, "*" + SUFFIX + "*")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                String id = name.endsWith(DELETED) ? name.substring(0, name.length() - DELETED.length())
                        : name.endsWith(TOMBSTONE) ? name.substring(0, name.length() - TOMBSTONE.length())
                        : name.endsWith(SUFFIX) ? name.substring(0, name.length() - SUFFIX.length()) : null;
                if (id != null && ID.matcher(id).matches()) {
                    ids.add(id);
                }
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Artefakte nicht lesbar: " + e.getClass().getSimpleName());
        }
        return ids;
    }

    /** Für Tests: wie ein Neustart der App, der Papierkorb des letzten Laufs wird beim nächsten Zugriff geleert. */
    static void newRun() {
        PURGED.clear();
    }

    /** Beim ersten Zugriff eines Programmlaufs: Was im letzten Lauf gelöscht wurde, geht endgültig. */
    private static void purgeOnce(Path owner) {
        if (owner == null || !Files.isDirectory(owner) || !PURGED.add(owner.toAbsolutePath().normalize())) {
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(owner, "*" + DELETED)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                Path mark = owner.resolve(name.substring(0, name.length() - DELETED.length()) + TOMBSTONE);
                if (!Files.exists(mark)) {
                    Files.createFile(mark);
                }
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Papierkorb nicht geleert: " + e.getClass().getSimpleName());
        }
    }

    static List<ChatArtifact> listIn(Path directory) {
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

    /** Der Artefakt-Ordner eines Projekts ({@code null} = Standardprojekt); {@code null} ohne Datenordner. */
    public Path projectOwner(String projectId) {
        return projectArtifacts.apply(projectId == null || projectId.isEmpty()
                ? com.aresstack.spaceharvester.app.project.Project.DEFAULT_ID : projectId);
    }

    /**
     * Die globalen Artefakte eines Projekts ({@code null} = Standardprojekt) ohne geladenen Chat (ADR 0047 §5, Liste
     * „Artefakte“ links): Dokumente, Grafiken und Bücher, ohne die gelöschten; leer ohne Datenordner.
     */
    public List<ChatArtifact> listInProject(String projectId) {
        synchronized (LOCK) {
            Path owner = projectOwner(projectId);
            purgeOnce(owner);
            return listIn(owner);
        }
    }

    /**
     * Legt das Buch eines Ordners an (ADR 0047): ein Dokument mit Kennung B… im Projekt, ohne Chat. Welcher Ordner es
     * hat, merkt sich die Buchverwaltung. Jeder Chat des Projekts sieht und bearbeitet es; „Neue Auflage“ ist
     * {@link #newEdition}.
     */
    public ChatArtifact createBook(String projectId, String title, String markdown) {
        Path owner = projectOwner(projectId);
        if (owner == null) {
            return null;
        }
        SectionDocument document = SectionDocument.parse(markdown == null ? "" : markdown);
        List<String> changed = document.sections().stream().map(SectionDocument.Section::id).toList();
        ChatArtifact created;
        synchronized (LOCK) {
            int next = 1;
            for (String existing : takenIds(owner)) {
                Matcher match = ID.matcher(existing);
                if (match.matches() && match.group(1).equals(BOOK)) {
                    next = Math.max(next, Integer.parseInt(match.group(2)) + 1);
                }
            }
            long now = System.currentTimeMillis();
            ChatArtifact.Revision first = new ChatArtifact.Revision(1, 0, now, ChatArtifact.Origin.AI, "angelegt",
                    changed, document, "");
            created = new ChatArtifact(BOOK + next, ChatArtifact.Kind.DOCUMENT, title, now, List.of(first));
            if (!write(owner, created)) {
                return null;
            }
        }
        notifyOwner(owner, created, changed);
        return created;
    }

    /** Ein Artefakt eines Projekts ohne Chat (etwa das Buch eines Ordners) oder {@code null}. */
    public ChatArtifact readInProject(String projectId, String artifactId) {
        return readAt(projectOwner(projectId), artifactId);
    }

    /** Ein Artefakt eines Besitzers oder {@code null}. */
    private static ChatArtifact readAt(Path owner, String artifactId) {
        String id = artifactId == null ? null : artifactId.strip().toUpperCase(java.util.Locale.ROOT);
        if (owner == null || id == null || !ID.matcher(id).matches()) {
            return null;
        }
        synchronized (LOCK) {
            Path file = owner.resolve(id + SUFFIX);
            return Files.isRegularFile(file) ? read(file) : null;
        }
    }

    /**
     * Eine neue Auflage eines Buchs (ADR 0047): Die bisherige Auflage wird mit ihrem letzten Stand samt
     * Nachbearbeitungen abgeschlossen und bleibt lesbar ({@link ChatArtifact#editions}, {@link ChatArtifact#edition});
     * der neue Text ist der erste Stand der nächsten Auflage. Dasselbe Artefakt, kein zweites.
     */
    public WriteResult newEdition(String projectId, String artifactId, String markdown, String note) {
        Path owner = projectOwner(projectId);
        WriteResult result;
        synchronized (LOCK) {
            ChatArtifact current = readInProject(projectId, artifactId);
            String problem = problem(current, artifactId, ANY, ChatArtifact.Kind.DOCUMENT);
            if (problem != null) {
                return WriteResult.failed(current, problem);
            }
            // Kennungen werden nie wieder vergeben, auch nicht über Auflagen: ein alter Verweis trifft sonst einen anderen
            // Abschnitt. Höchster Zähler über alle Stände, denn Rückgängig holt auch ältere Stände zurück.
            long nextId = current.revisions().stream().map(ChatArtifact.Revision::document)
                    .filter(java.util.Objects::nonNull).mapToLong(SectionDocument::nextId).max().orElse(1);
            SectionDocument document = SectionDocument.parseFrom(markdown == null ? "" : markdown, nextId);
            List<String> changed = document.sections().stream().map(SectionDocument.Section::id).toList();
            // Rückgängig führt nicht über die Grenze einer Auflage zurück: der erste Stand hat keinen Vorgänger.
            ChatArtifact.Revision revision = new ChatArtifact.Revision(current.revision() + 1, 0,
                    System.currentTimeMillis(), ChatArtifact.Origin.AI, note, changed, document, "");
            ChatArtifact next = current.closeEdition().with(revision);
            if (!write(owner, next)) {
                return WriteResult.failed(current, "Das Artefakt konnte nicht gespeichert werden.");
            }
            result = WriteResult.ok(next, changed);
        }
        notifyOwner(owner, result.artifact(), result.changed());
        return result;
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
            int next = 1;
            for (String existing : takenIds(directory)) {
                Matcher match = ID.matcher(existing);
                if (match.matches() && match.group(1).equals(prefix)) {
                    next = Math.max(next, Integer.parseInt(match.group(2)) + 1);
                }
            }
            long now = System.currentTimeMillis();
            ChatArtifact.Revision first = new ChatArtifact.Revision(1, 0, now, origin, "angelegt", changed,
                    document, svg);
            created = new ChatArtifact(prefix + next, kind, title, now, List.of(first), chatId);
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
        return renameAt(() -> owner(folder, chatId), folder, chatId, artifactId, title);
    }

    /** Wie {@link #rename}, für ein Artefakt des Projekts ohne Chat (Liste „Artefakte“ links, ADR 0047 §5). */
    public WriteResult renameInProject(String projectId, String artifactId, String title) {
        return renameAt(() -> projectOwner(projectId), null, null, artifactId, title);
    }

    private WriteResult renameAt(java.util.function.Supplier<Path> where, Path folder, String chatId,
                                 String artifactId, String title) {
        WriteResult result;
        Path owner;
        synchronized (LOCK) {
            owner = where.get();
            ChatArtifact current = readAt(owner, artifactId);
            String problem = problem(current, artifactId, ANY, null);
            if (problem != null) {
                return WriteResult.failed(current, problem);
            }
            if (title == null || title.isBlank()) {
                return WriteResult.failed(current, "Der Titel ist leer.");
            }
            ChatArtifact next = current.withTitle(title);
            result = owner == null || !write(owner, next)
                    ? WriteResult.failed(current, "Das Artefakt konnte nicht gespeichert werden.")
                    : WriteResult.ok(next, List.of());
        }
        if (result.success()) {
            notify(new Event(folder, chatId, owner, result.artifact(), List.of()));
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
        Path directory = owner(folder, chatId);
        if (directory == null || !write(directory, next)) {
            return WriteResult.failed(current, "Das Artefakt konnte nicht gespeichert werden.");
        }
        return WriteResult.ok(next, changed);
    }

    private void notify(Path folder, String chatId, ChatArtifact artifact, List<String> changed) {
        notify(new Event(folder, chatId, owner(folder, chatId), artifact, changed));
    }

    /** Eine Änderung ohne Chat (Buch): jede Ansicht dieses Besitzers sieht sie. */
    private void notifyOwner(Path owner, ChatArtifact artifact, List<String> changed) {
        notify(new Event(null, null, owner, artifact, changed));
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
        Path directory = owner(folder, chatId);
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
