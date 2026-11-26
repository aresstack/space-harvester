package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.deck.editor.DocumentException;
import com.aresstack.spaceharvester.deck.editor.DocumentStore;
import com.aresstack.spaceharvester.deck.files.FileAccessException;
import com.aresstack.spaceharvester.deck.files.FileEntry;
import com.aresstack.spaceharvester.deck.files.FileSource;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import tools.jackson.databind.JsonNode;

/**
 * Die Werkzeuge des Bereichs Dateien (Command Deck): Ordner auflisten und Dateien lesen – nur im Ordner, der beim
 * Senden offen war, samt Unterordnern. Gelesen wird genau wie beim Öffnen im Editor: Textdateien über den Speicher des
 * Bereichs, Dokumente (PDF, Word, Excel, PowerPoint, OpenDocument, Mail) über die Dokumentvorschau mit dem
 * Tika-Adapter des Core, derselben Textextraktion wie für Confluence-Anhänge. Nichts wird geändert.
 */
public final class FilesChatTools implements ChatToolset {

    public static final String LIST = "ordner_auflisten";
    public static final String READ = "datei_lesen";

    static final int MAX_ENTRIES = 300;
    static final String OUTSIDE = "Abgelehnt: Der Pfad liegt außerhalb des offenen Ordners. Der Chat sieht nur den "
            + "Ordner, den der Benutzer gerade offen hat, samt Unterordnern.";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.GERMANY)
            .withZone(ZoneId.systemDefault());

    private final FileSource source;
    private final String root;
    private final DocumentStore text;
    private final DocumentStore documents;
    private final Predicate<String> isDocument;
    /** Schon gelesene Dateien dieser Frage, damit Weiterlesen nicht neu extrahiert. */
    private final Map<String, String> read = new ConcurrentHashMap<>();

    /**
     * @param source     das Dateisystem des Bereichs
     * @param root       der beim Senden offene Ordner; {@code null}, wenn keiner offen ist
     * @param text       liest Textdateien (wie der Editor)
     * @param documents  liest Dokumente als Text (wie die Vorschau), {@code null} ohne
     * @param isDocument welche Dateinamen als Dokument gelten
     */
    public FilesChatTools(FileSource source, String root, DocumentStore text, DocumentStore documents,
                          Predicate<String> isDocument) {
        this.source = source;
        this.root = root;
        this.text = text;
        this.documents = documents;
        this.isDocument = isDocument == null ? name -> false : isDocument;
    }

    /** Die Regeln für die Systemnachricht. */
    public static List<String> rules() {
        return List.of("Mit " + LIST + " listest du den offenen Ordner oder einen Unterordner, mit " + READ
                        + " liest du eine Datei daraus (auch PDF, Word, Excel, PowerPoint als Text). Pfade relativ "
                        + "zum offenen Ordner, etwa \"bericht.pdf\" oder \"Unterordner/notiz.txt\".",
                "Für \"fasse den Ordner zusammen\" lies die wichtigsten Dateien, bei vielen Dateien eine Auswahl, und "
                        + "sag, welche du gelesen hast.");
    }

    /** Der offene Ordner als Liste (mit [Ordner]-Marke) für die Systemnachricht; leer, wenn keiner offen ist. */
    public String overview() {
        if (root == null) {
            return "";
        }
        try {
            return list("");
        } catch (FileAccessException e) {
            return "";
        }
    }

    @Override
    public List<AiChat.Tool> definitions() {
        return List.of(
                new AiChat.Tool(LIST, "Listet einen Ordner: Name, Art, Größe, Änderungsdatum. Ohne pfad der offene "
                        + "Ordner, sonst ein Unterordner davon.", "{\"type\":\"object\",\"properties\":{"
                                + "\"pfad\":{\"type\":\"string\",\"description\":\"Unterordner relativ zum offenen "
                                + "Ordner, leer für ihn selbst\"}}}"),
                new AiChat.Tool(READ, "Liest den Text einer Datei im offenen Ordner oder einem Unterordner, auch "
                        + "PDF, Word, Excel, PowerPoint, OpenDocument und Mails; "
                        + DeckChatTools.PIECE,
                        "{\"type\":\"object\",\"properties\":{"
                                + "\"pfad\":{\"type\":\"string\",\"description\":\"Datei relativ zum offenen "
                                + "Ordner\"},"
                                + "\"ab\":{\"type\":\"integer\",\"description\":\"ab diesem Zeichen, Vorgabe 0\"}},"
                                + "\"required\":[\"pfad\"]}"));
    }

    @Override
    public String describe(AiChat.ToolCall call) {
        return switch (call.name()) {
            case LIST -> "Listet den Ordner";
            case READ -> "Liest eine Datei";
            default -> "Werkzeug";
        };
    }

    @Override
    public String run(AiChat.ToolCall call) {
        if (root == null) {
            return "Im Bereich Dateien ist gerade kein Ordner offen.";
        }
        JsonNode arguments = DeckChatTools.arguments(call);
        String path = arguments.path("pfad").asString("").strip();
        try {
            return switch (call.name()) {
                case LIST -> list(path);
                case READ -> read(path, arguments.path("ab").asInt(0));
                default -> "Unbekanntes Werkzeug.";
            };
        } catch (FileAccessException | DocumentException e) {
            return "Nicht lesbar: " + e.getMessage();
        }
    }

    /** Die Adresse zu {@code relative}, nur innerhalb des offenen Ordners; sonst {@code null}. */
    String resolve(String relative) throws FileAccessException {
        String cleaned = relative.replace('\\', '/');
        while (cleaned.startsWith("./")) {
            cleaned = cleaned.substring(2);
        }
        if (cleaned.isEmpty() || cleaned.equals(".")) {
            return source.normalize(root);
        }
        String base = source.normalize(root);
        String target = base;
        for (String part : cleaned.split("/")) {
            if (!part.isEmpty() && !part.equals(".")) {
                target = source.child(target, part);
            }
        }
        target = source.normalize(target);
        if (absolute(cleaned)) {
            target = source.normalize(relative);
        }
        return inside(base, target) ? target : null;
    }

    private static boolean absolute(String path) {
        return path.startsWith("/") || path.matches("^[A-Za-z]:.*");
    }

    private static boolean inside(String base, String target) {
        String b = base.replace('\\', '/');
        String t = target.replace('\\', '/');
        if (t.equalsIgnoreCase(b)) {
            return true;
        }
        String prefix = b.endsWith("/") ? b : b + "/";
        return t.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private String list(String relative) throws FileAccessException {
        String folder = resolve(relative);
        if (folder == null) {
            return OUTSIDE;
        }
        List<FileEntry> entries = source.list(folder).stream()
                .sorted(Comparator.comparing(FileEntry::directory).reversed()
                        .thenComparing(entry -> entry.name().toLowerCase(Locale.ROOT)))
                .toList();
        StringBuilder b = new StringBuilder("Ordner ").append(folder).append(" (").append(entries.size())
                .append(" Einträge):\n");
        for (FileEntry entry : entries.subList(0, Math.min(entries.size(), MAX_ENTRIES))) {
            b.append(entry.directory() ? "[Ordner] " : "").append(entry.name());
            if (!entry.directory() && entry.size() >= 0) {
                b.append(" · ").append(size(entry.size()));
            }
            if (entry.modified() != null) {
                b.append(" · ").append(WHEN.format(entry.modified()));
            }
            b.append('\n');
        }
        if (entries.size() > MAX_ENTRIES) {
            b.append("… ").append(entries.size() - MAX_ENTRIES).append(" weitere nicht gezeigt\n");
        }
        return b.toString();
    }

    private String read(String relative, int from) throws FileAccessException, DocumentException {
        if (relative.isEmpty()) {
            return "Abgelehnt: pfad fehlt.";
        }
        String file = resolve(relative);
        if (file == null) {
            return OUTSIDE;
        }
        String content = read.get(file);
        if (content == null) {
            String name = file.replace('\\', '/');
            name = name.substring(name.lastIndexOf('/') + 1);
            DocumentStore store = documents != null && isDocument.test(name) ? documents : text;
            content = store.load(file).text();
            read.put(file, content);
        }
        if (content.isBlank()) {
            return relative + ": kein Text (leer oder nur Bilder).";
        }
        return DeckChatTools.page(relative, content, from);
    }

    static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.GERMANY, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.GERMANY, "%.1f MB", bytes / (1024.0 * 1024));
    }
}
