package com.aresstack.spaceharvester.app.chat;

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
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Chats als JSON-Dateien je Bereich (nach {@code FileChatHistoryStore} des Enterprise AI Client): unter dem
 * Datenordner der App {@code chats/<Quelle>/<Space>/<Seite>/<chatId>.json} ({@link ChatNode#directory}). Alle Methoden
 * sind nachsichtig wie dort: ein unlesbarer Chat fehlt in der Liste, ein gescheitertes Schreiben wird protokolliert,
 * nie geworfen. Ein leerer Chat wird nicht gespeichert; speichert man ihn, verschwindet seine Datei.
 */
public final class ChatStore {

    /** Der Ordner unter dem Datenordner der App. */
    public static final String FOLDER = "chats";

    private static final System.Logger LOG = System.getLogger(ChatStore.class.getName());
    private static final String SUFFIX = ".json";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final ObjectMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private final Supplier<Path> root;

    /** @param root der Ordner aller Chats; {@code null}, solange die App keinen Datenordner kennt */
    public ChatStore(Supplier<Path> root) {
        this.root = root;
    }

    /**
     * Ein gespeicherter Chat samt dem Ordner seines Bereichs. Ein Chat gehört zu dem Bereich, in dem er begonnen wurde,
     * auch wenn er beim Navigieren offen bleibt (Angelo, 21.11.2026); der Ordner ist seine Adresse im Speicher,
     * relativ zum Chat-Ordner, damit sie auch gilt, wenn der Datenordner der App wechselt.
     */
    public record Entry(Path folder, ChatRecord record) {
    }

    /** Der Ordner der Chats dieses Bereichs, relativ zum Chat-Ordner; {@code null} ohne Bereich. */
    public Path folder(ChatNode node) {
        return node == null ? null : node.directory(Path.of(""));
    }

    /** Der Ordner auf der Platte; {@code null} ohne Datenordner oder wenn die Adresse aus dem Chat-Ordner führt. */
    private Path resolve(Path folder) {
        Path base = root.get();
        if (base == null || folder == null || folder.isAbsolute()) {
            return null;
        }
        Path absolute = base.toAbsolutePath().normalize();
        Path directory = absolute.resolve(folder).normalize();
        return directory.startsWith(absolute) ? directory : null;
    }

    /** Die Chats des Bereichs, zuletzt geänderte zuerst; leer ohne Ordner. */
    public synchronized List<ChatRecord> list(ChatNode node) {
        return list(folder(node));
    }

    private List<ChatRecord> list(Path folder) {
        List<ChatRecord> records = new ArrayList<>();
        Path directory = resolve(folder);
        if (directory == null || !Files.isDirectory(directory)) {
            return records;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*" + SUFFIX)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                ChatRecord record = read(file, name.substring(0, name.length() - SUFFIX.length()));
                if (record != null && !record.isEmpty()) {
                    records.add(record);
                }
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Chat-Ordner nicht lesbar: " + e.getClass().getSimpleName());
        }
        records.sort(Comparator.comparingLong(ChatRecord::modifiedAt).reversed());
        return records;
    }

    /** Alle gespeicherten Chats aller Bereiche, zuletzt geänderte zuerst; leer ohne Ordner. */
    public synchronized List<Entry> all() {
        List<Entry> entries = new ArrayList<>();
        Path base = root.get();
        if (base == null || !Files.isDirectory(base)) {
            return entries;
        }
        List<Path> folders = new ArrayList<>();
        // Bereichsordner liegen höchstens drei Ebenen tief: <Quelle>/<Space>/<Seite>, _sammlung/<Ordner> oder _alle.
        try (java.util.stream.Stream<Path> walk = Files.walk(base, 3)) {
            walk.filter(Files::isDirectory).forEach(folders::add);
        } catch (IOException | java.io.UncheckedIOException e) {
            LOG.log(System.Logger.Level.WARNING, "Chat-Ordner nicht lesbar: " + e.getClass().getSimpleName());
        }
        for (Path directory : folders) {
            Path folder = base.relativize(directory);
            for (ChatRecord record : list(folder)) {
                entries.add(new Entry(folder, record));
            }
        }
        entries.sort(Comparator.comparingLong((Entry entry) -> entry.record().modifiedAt()).reversed());
        return entries;
    }

    public synchronized ChatRecord load(ChatNode node, String chatId) {
        return load(folder(node), chatId);
    }

    public synchronized ChatRecord load(Path folder, String chatId) {
        Path directory = resolve(folder);
        if (directory == null || !ID.matcher(chatId).matches()) {
            return null;
        }
        Path file = directory.resolve(chatId + SUFFIX);
        return Files.isRegularFile(file) ? read(file, chatId) : null;
    }

    /** Schreibt den Chat atomar (erst {@code .tmp}, dann umbenannt); ein leerer Chat löscht seine Datei. */
    public synchronized void save(ChatNode node, ChatRecord record) {
        save(folder(node), record);
    }

    /** Wie {@link #save(ChatNode, ChatRecord)}, in den Ordner des Bereichs, in dem der Chat begonnen wurde. */
    public synchronized void save(Path folder, ChatRecord record) {
        Path directory = resolve(folder);
        if (directory == null || record == null || !ID.matcher(record.id()).matches()) {
            return;
        }
        if (record.isEmpty()) {
            delete(folder, record.id());
            return;
        }
        Path target = directory.resolve(record.id() + SUFFIX);
        Path temp = directory.resolve(record.id() + SUFFIX + ".tmp");
        try {
            Files.createDirectories(directory);
            Files.writeString(temp, JSON.writeValueAsString(record), StandardCharsets.UTF_8);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Chat nicht gespeichert: " + e.getClass().getSimpleName());
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // bleibt liegen und wird beim nächsten Speichern überschrieben
            }
        }
    }

    public synchronized void delete(ChatNode node, String chatId) {
        delete(folder(node), chatId);
    }

    public synchronized void delete(Path folder, String chatId) {
        Path directory = resolve(folder);
        if (directory == null || !ID.matcher(chatId).matches()) {
            return;
        }
        try {
            Files.deleteIfExists(directory.resolve(chatId + SUFFIX));
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Chat nicht gelöscht: " + e.getClass().getSimpleName());
        }
    }

    private static ChatRecord read(Path file, String chatId) {
        try {
            ChatRecord record = JSON.readValue(Files.readString(file, StandardCharsets.UTF_8), ChatRecord.class);
            return record != null && chatId.equals(record.id()) ? record : null;
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Chat " + chatId + " nicht lesbar: " + e.getClass().getSimpleName());
            return null;
        }
    }
}
