package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.deck.ndv.NdvObject;
import com.aresstack.spaceharvester.deck.ndv.NdvSession;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;

/**
 * Die Werkzeuge des Bereichs NDV (Command Deck): Bibliotheken und Objekte des Servers der gewählten Sitzung nach
 * Namensmuster suchen und den Quelltext eines Objekts lesen. Nur lesen; Speichern geht nur im Editor durch den
 * Benutzer. Gebunden an die Sitzung, die beim Senden gewählt war.
 */
public final class NdvChatTools implements ChatToolset {

    public static final String LIBRARIES = "bibliotheken_auflisten";
    public static final String SEARCH = "objekte_suchen";
    public static final String READ = "objekt_lesen";

    static final int MAX_OBJECTS = 300;
    static final String NO_SESSION = "Im NDV-Bereich ist keine verbundene Sitzung gewählt. Bitte den Benutzer, eine "
            + "Verbindung zu öffnen.";

    private final NdvSession session;
    private final Supplier<NdvSession> active;
    private final Map<String, String> read = new ConcurrentHashMap<>();

    /**
     * @param session die beim Senden gewählte Sitzung, {@code null} ohne
     * @param active  welche Sitzung gerade gewählt ist
     */
    public NdvChatTools(NdvSession session, Supplier<NdvSession> active) {
        this.session = session;
        this.active = active == null ? () -> session : active;
    }

    /** Die Regeln für die Systemnachricht. */
    public static List<String> rules() {
        return List.of("Mit " + LIBRARIES + " und " + SEARCH + " suchst du auf dem NDV-Server nach Namen (Muster mit *, "
                + "etwa \"KD*\"), mit " + READ + " liest du den Natural-Quelltext eines Objekts.");
    }

    @Override
    public List<AiChat.Tool> definitions() {
        return List.of(
                new AiChat.Tool(LIBRARIES, "Listet die Bibliotheken des NDV-Servers, deren Name zum Muster passt.",
                        "{\"type\":\"object\",\"properties\":{\"muster\":{\"type\":\"string\",\"description\":"
                                + "\"Namensmuster mit *, Vorgabe *\"}}}"),
                new AiChat.Tool(SEARCH, "Sucht Objekte (Programme, Subprogramme, Maps, DDMs …) einer Bibliothek nach "
                        + "Namensmuster; höchstens " + MAX_OBJECTS + " Treffer.",
                        "{\"type\":\"object\",\"properties\":{"
                                + "\"bibliothek\":{\"type\":\"string\"},"
                                + "\"muster\":{\"type\":\"string\",\"description\":\"Namensmuster mit *, Vorgabe *\"}},"
                                + "\"required\":[\"bibliothek\"]}"),
                new AiChat.Tool(READ, "Liest den Quelltext eines Objekts, " + DeckChatTools.PIECE, "{\"type\":\"object\",\"properties\":{"
                                + "\"bibliothek\":{\"type\":\"string\"},"
                                + "\"name\":{\"type\":\"string\"},"
                                + "\"ab\":{\"type\":\"integer\"}},\"required\":[\"bibliothek\",\"name\"]}"));
    }

    @Override
    public String describe(AiChat.ToolCall call) {
        return switch (call.name()) {
            case LIBRARIES -> "Listet NDV-Bibliotheken";
            case SEARCH -> "Sucht NDV-Objekte";
            case READ -> "Liest ein NDV-Objekt";
            default -> "Werkzeug";
        };
    }

    @Override
    public String run(AiChat.ToolCall call) {
        if (session == null) {
            return NO_SESSION;
        }
        if (active.get() != session) {
            return "Der Benutzer hat inzwischen eine andere NDV-Sitzung gewählt; er soll die Frage dort neu stellen.";
        }
        JsonNode arguments = DeckChatTools.arguments(call);
        String library = arguments.path("bibliothek").asString("").strip().toUpperCase(Locale.ROOT);
        try {
            return switch (call.name()) {
                case LIBRARIES -> {
                    List<String> found = session.libraries(arguments.path("muster").asString("*"));
                    yield found.isEmpty() ? "Keine Bibliothek passt." : "Bibliotheken (" + found.size() + "): "
                            + String.join(", ", found.subList(0, Math.min(found.size(), MAX_OBJECTS)));
                }
                case SEARCH -> search(library, arguments.path("muster").asString("*"));
                case READ -> read(library, arguments.path("name").asString("").strip().toUpperCase(Locale.ROOT),
                        arguments.path("ab").asInt(0));
                default -> "Unbekanntes Werkzeug.";
            };
        } catch (IOException | RuntimeException e) {
            return "NDV meldet einen Fehler: " + (e.getMessage() == null ? e.getClass().getSimpleName()
                    : e.getMessage());
        }
    }

    private List<NdvObject> objects(String library, String pattern) throws IOException {
        List<NdvObject> found = new ArrayList<>();
        session.objects(library, pattern, (page, total) -> {
            found.addAll(page);
            return found.size() < MAX_OBJECTS;
        });
        return found.size() > MAX_OBJECTS ? found.subList(0, MAX_OBJECTS) : found;
    }

    private String search(String library, String pattern) throws IOException {
        if (library.isEmpty()) {
            return "Abgelehnt: bibliothek fehlt.";
        }
        List<NdvObject> found = objects(library, pattern);
        if (found.isEmpty()) {
            return "Keine Objekte in " + library + " passen zu " + pattern + ".";
        }
        StringBuilder b = new StringBuilder("Objekte in ").append(library).append(" (").append(found.size())
                .append(found.size() >= MAX_OBJECTS ? ", weitere nicht gezeigt" : "").append("):\n");
        for (NdvObject object : found) {
            b.append(object.displayName()).append(" · ").append(object.type().label());
            if (!object.changed().isEmpty()) {
                b.append(" · geändert ").append(object.changed());
            }
            if (!object.user().isEmpty()) {
                b.append(" · ").append(object.user());
            }
            b.append(object.source() ? "" : " · ohne Quelltext").append('\n');
        }
        return b.toString();
    }

    private String read(String library, String name, int from) throws IOException {
        if (library.isEmpty() || name.isEmpty()) {
            return "Abgelehnt: bibliothek und name sind nötig.";
        }
        String key = library + "/" + name;
        String text = read.get(key);
        if (text == null) {
            NdvObject object = objects(library, name).stream()
                    .filter(candidate -> candidate.name().equalsIgnoreCase(name)
                            || candidate.displayName().equalsIgnoreCase(name))
                    .filter(NdvObject::source).findFirst().orElse(null);
            if (object == null) {
                return "Kein Objekt " + name + " mit Quelltext in " + library + ".";
            }
            text = session.read(object);
            read.put(key, text);
        }
        return DeckChatTools.page(key, text, from);
    }
}
