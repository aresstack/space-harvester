package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.ai.AiChat;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Die Werkzeuge des Chats für einen Bereich des Command Deck (FTP, NDV, BetaView, Dateien): allgemein für jeden
 * Bereich die aktuelle Ansicht lesen ({@value #VIEW}) und einen ihrer Texte ganz lesen ({@value #TEXT}, etwa den
 * Quelltext im Editor oder einen Spool), dazu die Werkzeuge des Bereichs selbst ({@code extra}). Nur lesen: Ändern,
 * Speichern, Abschicken oder Löschen gibt es hier nicht.
 *
 * <p>Gebunden an den Bereich, der beim Senden gewählt war: Hat der Benutzer inzwischen gewechselt, lehnen die
 * Werkzeuge ab. Ins Protokoll gehen nur Werkzeug und Grund, nie Inhalte.
 */
public final class DeckChatTools implements ChatToolset {

    public static final String VIEW = "ansicht_lesen";
    public static final String TEXT = "text_lesen";

    /** Wie viel ein lesendes Werkzeug je Aufruf liefert, für seine Beschreibung. */
    static final String PIECE = "je Aufruf so viel, wie in die Anfrage passt, mit ab weiter.";
    // Wie viel Ansicht und Text ein Aufruf liefert, folgt aus dem Platz in der Anfrage (ChatRoom); früher feste
    // Zeichengrenzen (TEXT_CHARS, VIEW_CHARS, …, docs/grenzen.md „Modellkontext“).

    static final String SWITCHED = "Der Benutzer hat inzwischen einen anderen Bereich gewählt; die Frage galt dem "
            + "vorigen. Er soll sie dort neu stellen.";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final System.Logger LOG = System.getLogger(DeckChatTools.class.getName());

    private final Supplier<Component> view;
    private final BooleanSupplier stillShown;
    private final ChatToolset extra;
    /** Die Texte der zuletzt gelesenen Ansicht, damit {@value #TEXT} dieselbe Nummerierung trifft. */
    private volatile List<ScreenText.Block> blocks;
    private volatile String blocksIdentity = "";

    /**
     * @param view       die Oberfläche des Bereichs, wie sie gerade angezeigt wird
     * @param stillShown ob der beim Senden gewählte Bereich noch gewählt ist
     * @param extra      die Werkzeuge des Bereichs selbst, {@code null} ohne
     */
    public DeckChatTools(Supplier<Component> view, BooleanSupplier stillShown, ChatToolset extra) {
        this.view = view;
        this.stillShown = stillShown == null ? () -> true : stillShown;
        this.extra = extra;
    }

    /**
     * Liest die Ansicht jetzt (beim Senden), für die Systemnachricht, so viel, wie in die Anfrage passt
     * ({@link ChatRoom}); merkt sich ihre Texte für {@value #TEXT}.
     */
    public String snapshot() {
        return capture();
    }

    /**
     * Die Systemnachricht: wer der Assistent ist, welcher Bereich, was der Benutzer gerade sieht, und was die
     * Werkzeuge können.
     *
     * @param area      der Bereich in Worten, etwa „Command Deck › Dateien“
     * @param about     ein, zwei Sätze, was man in diesem Bereich tut
     * @param rules     weitere Regeln des Bereichs, je eine Zeile, oder leer
     * @param shownView die Ansicht beim Senden ({@link #snapshot()})
     */
    public static String systemMessage(String area, String about, List<String> rules, String shownView) {
        StringBuilder b = new StringBuilder();
        b.append("Du bist der Assistent im Command Deck von Space Harvester. Der Benutzer arbeitet gerade im ")
                .append(area).append(". ").append(about).append('\n');
        b.append("- Unten steht, was der Benutzer in diesem Moment sieht. Beantworte Fragen dazu (\"was ist das\", "
                + "\"erkläre\", \"fasse zusammen\") zuerst daraus.\n");
        b.append("- Mit ").append(VIEW).append(" liest du die Ansicht neu und ausführlicher, mit ").append(TEXT)
                .append(" einen ihrer Texte (Editor, Spool, Dokument) ganz, abschnittsweise.\n");
        for (String rule : rules) {
            b.append("- ").append(rule).append('\n');
        }
        b.append("- Keine Hostdaten ändern, speichern, löschen, abschicken oder ausführen. Nur ausdrücklich "
                + "freigegebene Such- und Navigationsaktionen sind erlaubt.\n");
        b.append("- Lehnt ein Werkzeug ab, nenne den Grund und schlage den nächsten Schritt vor.\n");
        b.append("Antworte auf Deutsch, kurz und konkret.\n\n");
        b.append("Aktuelle Ansicht (").append(area).append("):\n").append(shownView);
        return b.toString();
    }

    @Override
    public void modelSelected(String model) {
        if (extra != null) extra.modelSelected(model);
    }

    @Override
    public List<AiChat.Tool> definitions() {
        List<AiChat.Tool> tools = new ArrayList<>();
        tools.add(new AiChat.Tool(VIEW, "Liest, was der Benutzer im Bereich gerade sieht: Beschriftungen, Felder, "
                + "Tabellen und Listen mit Auswahl (*), aufgeklappte Bäume und den Anfang jedes Textes (Editor, "
                + "Spool, Dokument) mit Nummer.", "{\"type\":\"object\",\"properties\":{}}"));
        tools.add(new AiChat.Tool(TEXT, "Liest einen Text der aktuellen Ansicht ganz, " + PIECE, "{\"type\":\"object\",\"properties\":{"
                        + "\"nr\":{\"type\":\"integer\",\"description\":\"Nummer des Textes aus der Ansicht, "
                        + "Vorgabe 1\"},"
                        + "\"ab\":{\"type\":\"integer\",\"description\":\"ab diesem Zeichen, Vorgabe 0\"}}}"));
        if (extra != null) {
            tools.addAll(extra.definitions());
        }
        return tools;
    }

    @Override
    public String describe(AiChat.ToolCall call) {
        return switch (call.name()) {
            case VIEW -> "Liest die Ansicht";
            case TEXT -> "Liest den Text";
            default -> extra == null ? "Werkzeug" : extra.describe(call);
        };
    }

    @Override
    public String run(AiChat.ToolCall call) {
        if (!stillShown.getAsBoolean()) {
            return SWITCHED;
        }
        try {
            return switch (call.name()) {
                case VIEW -> view();
                case TEXT -> text(arguments(call));
                default -> extra == null ? "Unbekanntes Werkzeug." : extra.run(call);
            };
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Deck-Chat: " + call.name() + " gescheitert: "
                    + e.getClass().getSimpleName());
            return "Das Werkzeug ist gescheitert (" + e.getClass().getSimpleName() + ").";
        }
    }

    @Override
    public void cancel() {
        if (extra != null) {
            extra.cancel();
        }
    }

    private String view() {
        return capture();
    }

    /** Die Ansicht in so vielen Zeichen, wie Platz ist; jeder Text der Ansicht bekommt davon den gleichen Teil. */
    private String capture() {
        if (!stillShown.getAsBoolean()) return SWITCHED;
        String before = extra == null ? "" : extra.viewIdentity();
        String context = extra == null ? "" : extra.viewContext();
        ScreenText screen = ScreenText.capture(view.get());
        String after = extra == null ? "" : extra.viewIdentity();
        if (!stillShown.getAsBoolean()) return SWITCHED;
        if (!before.equals(after)) {
            blocks = null;
            return "Die Auswahl hat sich beim Lesen geändert. Bitte Ansicht erneut lesen.";
        }
        blocksIdentity = after;
        blocks = screen.blocks();
        int max = ChatRoom.chars();
        if (max == 0) {
            return ChatRoom.FULL;
        }
        int room = Math.max(0, max - context.length());
        int perBlock = screen.blocks().isEmpty() ? room : Math.max(0, room - screen.view().length())
                / screen.blocks().size();
        String display = screen.isEmpty() ? "(leer)" : screen.format(room, perBlock);
        return context.isEmpty() ? display : context + "\n\n" + display;
    }

    private String text(JsonNode arguments) {
        List<ScreenText.Block> known = blocks;
        if (known == null) {
            view();
            known = blocks;
        }
        if (known == null || !stillShown.getAsBoolean()) return SWITCHED;
        if (extra != null && !blocksIdentity.equals(extra.viewIdentity())) {
            return "Die Textauswahl hat sich geändert. Bitte ansicht_lesen erneut aufrufen.";
        }
        if (known.isEmpty()) {
            return "In der Ansicht ist kein Text (Editor, Spool, Dokument) offen.";
        }
        int nr = arguments.path("nr").asInt(1);
        if (nr < 1 || nr > known.size()) {
            return "Abgelehnt: nr muss zwischen 1 und " + known.size() + " liegen.";
        }
        ScreenText.Block block = known.get(nr - 1);
        return stillShown.getAsBoolean() ? page(block.name().isEmpty() ? "Text " + nr : block.name(), block.text(),
                arguments.path("ab").asInt(0)) : SWITCHED;
    }

    /** Ein Abschnitt eines langen Textes mit Angabe, wo es weitergeht. */
    static String page(String name, String text, int from) {
        int room = ChatRoom.chars();
        if (room == 0) {
            return ChatRoom.FULL;
        }
        int start = Math.max(0, Math.min(from, text.length()));
        if (start < text.length() && Character.isLowSurrogate(text.charAt(start))) start--;
        // Nach Zeicheneinheiten, nicht nach Zeichen: als Text gelesene Binärdaten kosten je Zeichen mehrere Tokens.
        int end = com.aresstack.spaceharvester.app.runtime.ModelBudget.fit(text, start, room);
        StringBuilder b = new StringBuilder(name).append(" (Zeichen ").append(start).append('–').append(end)
                .append(" von ").append(text.length()).append("):\n").append(text, start, end);
        if (end < text.length()) {
            b.append("\n… weiter mit ab=").append(end);
        }
        return b.toString();
    }

    static JsonNode arguments(AiChat.ToolCall call) {
        try {
            JsonNode node = call.argumentsJson() == null || call.argumentsJson().isBlank() ? null
                    : JSON.readTree(call.argumentsJson());
            return node != null && node.isObject() ? node : JSON.createObjectNode();
        } catch (JacksonException e) {
            return JSON.createObjectNode();
        }
    }
}
