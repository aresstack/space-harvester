package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.deck.terminal.CursorMove;
import com.aresstack.spaceharvester.deck.terminal.MaskAssistant;
import com.aresstack.spaceharvester.deck.terminal.MaskRefused;
import com.aresstack.spaceharvester.deck.terminal.MaskSnapshot;
import com.aresstack.spaceharvester.deck.terminal.TerminalSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Die Werkzeuge des Chats in der Perspektive Command Deck › Terminal (#1151): der KI-Maskenassistent. Drei Werkzeuge,
 * mehr nicht – Maske lesen, Felder lokal ausfüllen, Schreibmarke lokal bewegen. Ein Werkzeug zum Abschicken (ENTER,
 * PF, PA, CLEAR, SysReq) oder einen allgemeinen Tastensender gibt es nicht; die Klasse kennt nur den
 * {@link MaskAssistant}, der selbst keinen Weg zum Host hat.
 *
 * <p>Gebunden an die Sitzung, die beim Senden gewählt war: Ist inzwischen eine andere gewählt oder die Sitzung zu,
 * wird abgelehnt. Ins Protokoll gehen nur Werkzeug und Grund, nie Inhalte der Maske.
 */
public final class TerminalChatTools implements ChatToolset {

    public static final String READ = "maske_lesen";
    public static final String FILL = "felder_ausfuellen";
    public static final String CURSOR = "cursor_bewegen";

    static final String NO_SESSION = "Im Command Deck ist keine Terminal-Sitzung gewählt. Bitte den Benutzer, eine "
            + "Verbindung zu öffnen.";
    static final String SWITCHED = "Der Benutzer hat inzwischen eine andere Terminal-Sitzung gewählt; nichts "
            + "geändert. Er soll die Frage dort neu stellen.";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final System.Logger LOG = System.getLogger(TerminalChatTools.class.getName());

    private final TerminalSession session;
    private final Supplier<TerminalSession> active;

    /**
     * @param session die beim Senden gewählte Sitzung, {@code null} ohne Sitzung
     * @param active  welche Sitzung gerade gewählt ist (zum Prüfen bei jedem Aufruf)
     */
    public TerminalChatTools(TerminalSession session, Supplier<TerminalSession> active) {
        this.session = session;
        this.active = active == null ? () -> session : active;
    }

    /** Die Systemnachricht für Fragen im Terminal. */
    public static String systemMessage(String area) {
        return "Du bist der Maskenassistent im Command Deck von Space Harvester. Der Benutzer arbeitet in einer "
                + "TN3270-Sitzung mit einem IBM-Mainframe (etwa TSO/ISPF)" + (area == null ? "" : " – " + area)
                + ".\n"
                + "- Lies die aktuelle Maske mit " + READ + ", bevor du etwas über sie sagst oder ausfüllst; nach "
                + "jedem Abschicken durch den Benutzer ist es eine neue Maske.\n"
                + "- Fülle Felder nur, wenn der Benutzer darum bittet: mit " + FILL + " und der Revision aus "
                + READ + ". Nur Eingabefelder, nur so lang wie das Feld, Kennwortfelder nie.\n"
                + "- Mit " + CURSOR + " setzt du die Schreibmarke lokal (Tab, neue Zeile, ein Feld).\n"
                + "- Du kannst NICHTS abschicken: ENTER, PF- und PA-Tasten, CLEAR und SysReq drückt nur der "
                + "Benutzer selbst. Sag ihm am Ende, welche Taste er zum Bestätigen drücken soll, und dass er die "
                + "gelb umrandeten Felder vorher prüfen und mit „KI-Eingaben zurücknehmen“ verwerfen kann.\n"
                + "- Was ein Feld fachlich bedeutet, ergibt sich nur aus den Bildschirmtexten; wo du dir nicht sicher "
                + "bist, sag, dass es eine Vermutung ist.\n"
                + "- Lehnt ein Werkzeug ab, nenne den Grund und schlage den nächsten Schritt vor.\n"
                + "Antworte auf Deutsch, kurz und konkret.";
    }

    @Override
    public List<AiChat.Tool> definitions() {
        return List.of(
                new AiChat.Tool(READ, "Liest die aktuelle Maske des Terminals: Bildschirmzeilen mit Nummer, die "
                        + "Eingabefelder mit Nummer, Position, Länge, Wert und vermuteter Beschriftung, die "
                        + "Schreibmarke und die Revision, die " + FILL + " und " + CURSOR + " brauchen. Kennwörter "
                        + "sind geschwärzt.", "{\"type\":\"object\",\"properties\":{}}"),
                new AiChat.Tool(FILL, "Füllt Eingabefelder der aktuellen Maske lokal im Terminal aus (überschreibt "
                        + "den alten Wert, löscht den Rest des Feldes). Schickt nichts ab. Alles oder nichts: Ist "
                        + "ein Wert ungültig, wird gar nichts geändert.",
                        "{\"type\":\"object\",\"properties\":{"
                                + "\"revision\":{\"type\":\"string\",\"description\":\"Revision aus " + READ + "\"},"
                                + "\"felder\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{"
                                + "\"nr\":{\"type\":\"integer\",\"description\":\"Nummer des Eingabefeldes\"},"
                                + "\"wert\":{\"type\":\"string\",\"description\":\"neuer Wert, leer zum Leeren\"}},"
                                + "\"required\":[\"nr\",\"wert\"]}}},\"required\":[\"revision\",\"felder\"]}"),
                new AiChat.Tool(CURSOR, "Bewegt die Schreibmarke lokal: tab (nächstes Eingabefeld), backtab, "
                        + "neue_zeile (erstes Eingabefeld einer folgenden Zeile), anfang (erstes Eingabefeld) oder "
                        + "feld (Feld nr). Schickt nichts ab.",
                        "{\"type\":\"object\",\"properties\":{"
                                + "\"revision\":{\"type\":\"string\",\"description\":\"Revision aus " + READ + "\"},"
                                + "\"bewegung\":{\"type\":\"string\",\"enum\":[\"tab\",\"backtab\",\"neue_zeile\","
                                + "\"anfang\",\"feld\"]},"
                                + "\"nr\":{\"type\":\"integer\",\"description\":\"Nummer des Eingabefeldes bei "
                                + "feld\"}},\"required\":[\"revision\",\"bewegung\"]}"));
    }

    @Override
    public String describe(AiChat.ToolCall call) {
        return switch (call.name()) {
            case READ -> "Liest die Terminal-Maske";
            case FILL -> "Füllt Felder im Terminal aus";
            case CURSOR -> "Bewegt die Schreibmarke";
            default -> "Werkzeug";
        };
    }

    @Override
    public String run(AiChat.ToolCall call) {
        if (session == null) {
            return NO_SESSION;
        }
        if (active.get() != session) {
            return SWITCHED;
        }
        JsonNode arguments;
        try {
            arguments = call.argumentsJson() == null || call.argumentsJson().isBlank() ? JSON.createObjectNode()
                    : JSON.readTree(call.argumentsJson());
        } catch (JacksonException e) {
            return "Die Argumente sind kein gültiges JSON.";
        }
        if (arguments == null || !arguments.isObject()) {
            arguments = JSON.createObjectNode();
        }
        try {
            return switch (call.name()) {
                case READ -> format(session.assistant().read());
                case FILL -> fill(arguments);
                case CURSOR -> cursor(arguments);
                default -> "Unbekanntes Werkzeug. Abschicken (ENTER, PF, PA, CLEAR) kann nur der Benutzer.";
            };
        } catch (MaskRefused refused) {
            LOG.log(System.Logger.Level.INFO, "Maskenassistent: " + call.name() + " abgelehnt");
            return "Abgelehnt: " + refused.getMessage();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Maskenassistent: " + call.name() + " gescheitert: "
                    + e.getClass().getSimpleName());
            return "Das Werkzeug ist gescheitert (" + e.getClass().getSimpleName() + ").";
        }
    }

    private String fill(JsonNode arguments) {
        String revision = arguments.path("revision").asString("");
        List<MaskAssistant.FieldValue> values = new ArrayList<>();
        for (JsonNode entry : arguments.path("felder")) {
            if (!entry.path("nr").canConvertToInt()) {
                return "Abgelehnt: Jedes Feld braucht eine Nummer nr.";
            }
            values.add(new MaskAssistant.FieldValue(entry.path("nr").asInt(), entry.path("wert").asString("")));
        }
        List<MaskAssistant.Edit> edits = session.assistant().fill(revision, values);
        StringBuilder b = new StringBuilder();
        if (edits.isEmpty()) {
            b.append("Nichts zu ändern: Die Felder hatten schon diese Werte.");
        } else {
            b.append("Lokal ausgefüllt (noch nicht abgeschickt): ");
            for (int i = 0; i < edits.size(); i++) {
                MaskAssistant.Edit edit = edits.get(i);
                b.append(i == 0 ? "" : ", ").append("Feld ").append(edit.field()).append(" = \"")
                        .append(edit.after()).append('"');
            }
            b.append(". Die Felder sind gelb umrandet; der Benutzer prüft sie und schickt selbst ab.");
        }
        return b.toString();
    }

    private String cursor(JsonNode arguments) {
        String revision = arguments.path("revision").asString("");
        CursorMove move = switch (arguments.path("bewegung").asString("").toLowerCase(Locale.ROOT)) {
            case "tab" -> CursorMove.TAB;
            case "backtab" -> CursorMove.BACKTAB;
            case "neue_zeile", "newline", "new_line" -> CursorMove.NEW_LINE;
            case "anfang", "home" -> CursorMove.HOME;
            case "feld", "field" -> CursorMove.FIELD;
            default -> null;
        };
        if (move == null) {
            return "Abgelehnt: bewegung muss tab, backtab, neue_zeile, anfang oder feld sein.";
        }
        int[] at = session.assistant().moveCursor(revision, move, arguments.path("nr").asInt(0));
        return "Schreibmarke steht jetzt in Zeile " + at[0] + ", Spalte " + at[1] + " (lokal, nichts abgeschickt).";
    }

    /** Die Maske als Text für das Modell. */
    static String format(MaskSnapshot mask) {
        StringBuilder b = new StringBuilder();
        b.append("Revision: ").append(mask.revision()).append('\n');
        b.append("Bild: ").append(mask.rows()).append(" × ").append(mask.columns()).append(", Schreibmarke Zeile ")
                .append(mask.cursorRow()).append(", Spalte ").append(mask.cursorColumn()).append(", Tastatur ")
                .append(mask.keyboardLocked() ? "gesperrt (X-Wait – erst warten)" : "frei").append('\n');
        b.append("\nBildschirm (Zeilennummer|Text, leere Zeilen weggelassen):\n");
        for (int i = 0; i < mask.lines().size(); i++) {
            String line = mask.lines().get(i);
            if (!line.isBlank()) {
                b.append(String.format(Locale.ROOT, "%02d|", i + 1)).append(line).append('\n');
            }
        }
        if (mask.fields().isEmpty()) {
            b.append("\nKeine Eingabefelder (unformatiertes oder reines Anzeigebild).\n");
        } else {
            b.append("\nEingabefelder (nur diese lassen sich ausfüllen; Beschriftung vermutet):\n");
            for (MaskSnapshot.InputField field : mask.fields()) {
                b.append(field.number()).append(". Zeile ").append(field.row()).append(", Spalte ")
                        .append(field.column()).append(", Länge ").append(field.length());
                if (!field.label().isEmpty()) {
                    b.append(", Beschriftung \"").append(field.label()).append('"');
                }
                if (field.numeric()) {
                    b.append(", nur Ziffern");
                }
                if (field.sensitive()) {
                    b.append(", Kennwortfeld – nur der Benutzer füllt es");
                } else {
                    b.append(", Wert \"").append(field.value()).append('"');
                }
                if (field.byAssistant()) {
                    b.append(", von dir ausgefüllt");
                } else if (field.modified()) {
                    b.append(", geändert");
                }
                b.append('\n');
            }
        }
        b.append("\nAbschicken (ENTER, PF, PA, CLEAR) kann nur der Benutzer.");
        return b.toString();
    }
}
