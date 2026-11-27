package com.aresstack.spaceharvester.app.chat.artifact;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.app.chat.ChatRoom;
import com.aresstack.spaceharvester.app.chat.ChatToolset;
import com.aresstack.spaceharvester.app.runtime.ModelBudget;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Die Schreibwerkzeuge des Chats (ADR 0045, Abschnitt 3), angeboten nur bei eingeschaltetem Schalter „Schreiben“. Das
 * Modell bekommt ein Dokument nie als Ganzes, sondern liest und ändert es Abschnitt für Abschnitt; ein Werkzeug, das
 * ein vorhandenes Dokument ganz ersetzt, gibt es nicht. Jede Antwort ist eine kurze Bestätigung mit dem neuen Stand,
 * jede Fehlermeldung sagt, was zu tun ist.
 */
public final class DocumentChatTools {

    public static final String LIST = "dokumente_auflisten";
    public static final String CREATE = "dokument_anlegen";
    public static final String OUTLINE = "dokument_gliederung";
    public static final String READ = "abschnitt_lesen";
    public static final String REPLACE = "abschnitt_ersetzen";
    public static final String REPLACE_TEXT = "text_ersetzen";
    public static final String INSERT = "abschnitt_einfuegen";
    public static final String DELETE = "abschnitt_loeschen";
    public static final String RENAME = "ueberschrift_aendern";
    public static final String MOVE = "abschnitt_verschieben";
    public static final String GRAPHIC_DRAW = "grafik_zeichnen";
    public static final String GRAPHIC_CREATE = "grafik_anlegen";
    public static final String GRAPHIC_EMBED = "grafik_einbinden";
    public static final String GRAPHIC_ELEMENTS = "grafik_elemente";
    public static final String GRAPHIC_READ = "grafik_lesen";
    public static final String GRAPHIC_ELEMENT_REPLACE = "grafik_element_ersetzen";
    public static final String GRAPHIC_REPLACE = "grafik_ersetzen";
    private static final Set<String> NAMES = Set.of(LIST, CREATE, OUTLINE, READ, REPLACE, REPLACE_TEXT, INSERT,
            DELETE, RENAME, MOVE, GRAPHIC_DRAW, GRAPHIC_CREATE, GRAPHIC_EMBED, GRAPHIC_ELEMENTS, GRAPHIC_READ,
            GRAPHIC_ELEMENT_REPLACE, GRAPHIC_REPLACE);

    /** Das Schema eines Links auf ein Artefakt in der Chat-Antwort; ein Klick öffnet den Reiter. */
    public static final String LINK_SCHEME = "artefakt:";

    static final String RULES = """
            Schreibumgebung ist eingeschaltet. Längere Texte, die der Benutzer als Dokument haben möchte, schreibst du \
            nicht in die Antwort, sondern mit den Werkzeugen in ein Dokument des Chats; der Benutzer sieht es in der \
            Mitte und kann es nach Markdown, Word oder PDF exportieren.
            Regeln:
            - Neues Dokument: dokument_anlegen mit Titel und, wenn du schon weißt was, einer Gliederung aus Überschriften \
            (Markdown mit #, ##). Danach jeden Abschnitt einzeln mit abschnitt_ersetzen füllen, ein Aufruf je Abschnitt.
            - Vorhandenes Dokument ändern: erst dokument_gliederung, dann nur den gemeinten Abschnitt lesen und ändern. \
            Für ein Wort, eine Zahl oder einen Satz text_ersetzen mit der wörtlichen Stelle. Unbeteiligte Abschnitte \
            nie anfassen.
            - Abschnittskennungen (a1, a2, …) und Dokumentkennungen (D1, …) stammen aus den Werkzeugen; nie raten.
            - abschnitt_ersetzen ersetzt nur den Text unter der Überschrift; die Überschrift ändert ueberschrift_aendern.
            - Möchte der Benutzer eine Grafik (Bild, Schaubild, Diagramm, Skizze, Plan), legst du eine Grafik an, \
            kein Dokument: grafik_zeichnen (Thema und Inhalt, gezeichnet vom Bildmodell) oder grafik_anlegen (du \
            lieferst das SVG selbst). Ein Dokument nur, wenn er auch Text haben möchte; dann die Grafik mit \
            grafik_einbinden in den passenden Abschnitt. Ein SVG nie in den Text eines Abschnitts schreiben.
            - Vorhandene Grafik ändern: erst grafik_elemente (jedes Element hat eine Kennung e1, e2, …), dann nur das \
            gemeinte Element mit grafik_element_ersetzen (leeres svg löscht es). Nur für einen Umbau des Ganzen \
            grafik_lesen und grafik_ersetzen. Eine Grafik nie neu anlegen, wenn der Benutzer eine vorhandene ändern will.
            - Beginnt ein Zitat in der Nachricht mit „(Bezug: Dokument D…, Abschnitt a…)“, „(Bezug: Grafik G…)“ oder \
            „(Bezug: Grafik G…, Element e…)“, meint der Benutzer genau diese Stelle: lies sie und ändere nur dort.
            - Antworte am Ende kurz, was du im Dokument getan hast; wiederhole das Dokument nicht in der Antwort.""";

    /** Das Wort vor dem Bezug eines Zitats aus einem Artefakt (ADR 0045 §7), wie es {@link #RULES} nennen. */
    public static final String QUOTE_REFERENCE = "Bezug:";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String DOC = "\"dokument\":{\"type\":\"string\",\"description\":\"Kennung des Dokuments, "
            + "etwa D1; leer lassen, wenn der Benutzer gerade eines geöffnet hat\"}";
    private static final String SECTION = "\"abschnitt\":{\"type\":\"string\",\"description\":\"Kennung des "
            + "Abschnitts aus dokument_gliederung, etwa a3\"}";

    private final ChatArtifactStore store;
    private final Path folder;
    private final String chatId;
    private final Supplier<String> focus;
    private final Consumer<ChatArtifact> changed;
    /** Zeichnet Grafiken mit dem Modell der Bildgenerierung; {@code null}: kein {@value #GRAPHIC_DRAW}. */
    private final com.aresstack.spaceharvester.app.chat.ModelChatTools.Diagrams diagrams;
    /** Was dieser Turn angelegt oder geändert hat, in der Reihenfolge der ersten Änderung. */
    private final Map<String, String> touched = new LinkedHashMap<>();

    /**
     * @param focus   die Kennung des Artefakts, das der Benutzer gerade offen hat, oder {@code null}
     * @param changed bekommt jedes angelegte oder geänderte Artefakt (die Ansicht öffnet seinen Reiter)
     */
    public DocumentChatTools(ChatArtifactStore store, Path folder, String chatId, Supplier<String> focus,
                             Consumer<ChatArtifact> changed) {
        this(store, folder, chatId, focus, changed, null);
    }

    /**
     * @param diagrams die Bildgenerierung (Zeilen „Bildgenerierung“, wie Podcast und {@code schaubild_zeichnen});
     *                 {@code null} ohne {@value #GRAPHIC_DRAW}
     */
    public DocumentChatTools(ChatArtifactStore store, Path folder, String chatId, Supplier<String> focus,
                             Consumer<ChatArtifact> changed,
                             com.aresstack.spaceharvester.app.chat.ModelChatTools.Diagrams diagrams) {
        this.diagrams = diagrams;
        this.store = store;
        this.folder = folder;
        this.chatId = chatId;
        this.focus = focus == null ? () -> null : focus;
        this.changed = changed == null ? artifact -> { } : changed;
    }

    public static boolean handles(String name) {
        return NAMES.contains(name);
    }

    public List<AiChat.Tool> definitions() {
        List<AiChat.Tool> all = new ArrayList<>(documentDefinitions());
        if (diagrams != null) {
            all.add(tool(GRAPHIC_DRAW, "Zeichnet eine Grafik für ein Dokument (Karten mit Symbolen, Pfeilen, kurzen "
                    + "Beschriftungen) mit dem Modell der Bildgenerierung und legt sie als Grafik des Chats an.",
                    "\"titel\":{\"type\":\"string\"},\"inhalt\":{\"type\":\"string\",\"description\":"
                            + "\"Fakten und Zusammenhänge, die die Grafik zeigen soll\"}", List.of("titel", "inhalt")));
        }
        all.add(tool(GRAPHIC_CREATE, "Legt eine Grafik aus einem SVG an, das du selbst schreibst.",
                "\"titel\":{\"type\":\"string\"},\"svg\":{\"type\":\"string\",\"description\":"
                        + "\"vollständiges SVG mit <svg …>\"}", List.of("titel", "svg")));
        String graphic = "\"grafik\":{\"type\":\"string\",\"description\":\"Kennung der Grafik, etwa G1; leer "
                + "lassen, wenn der Benutzer gerade eine geöffnet hat\"}";
        all.add(tool(GRAPHIC_ELEMENTS, "Listet die Elemente einer Grafik mit Kennung (e1, e2, …), Art, Text und "
                + "Lage; Gruppen mit eingerückten Teilen.", graphic, List.of()));
        all.add(tool(GRAPHIC_READ, "Liest das SVG einer Grafik, so viel wie in die Anfrage passt; mit element nur "
                + "dieses Element.", graphic + ",\"element\":{\"type\":\"string\"},\"versatz\":{\"type\":"
                + "\"integer\",\"minimum\":0}", List.of()));
        all.add(tool(GRAPHIC_ELEMENT_REPLACE, "Ersetzt ein Element einer Grafik durch neues SVG (ein oder mehrere "
                + "Elemente); leeres svg löscht es. Das übrige SVG bleibt unverändert.", graphic
                + ",\"element\":{\"type\":\"string\",\"description\":\"Kennung aus grafik_elemente, etwa e3\"},"
                + "\"svg\":{\"type\":\"string\",\"description\":\"etwa <rect …/> oder <g>…</g>\"}",
                List.of("element", "svg")));
        all.add(tool(GRAPHIC_REPLACE, "Ersetzt das ganze SVG einer Grafik (für einen Umbau des Ganzen).", graphic
                + ",\"svg\":{\"type\":\"string\",\"description\":\"vollständiges SVG mit <svg …>\"}",
                List.of("svg")));
        all.add(tool(GRAPHIC_EMBED, "Bindet eine Grafik am Ende eines Abschnitts ein, als Verweis (keine Kopie): "
                + "wird die Grafik später geändert, stimmt das Dokument von selbst.", DOC + "," + SECTION
                + ",\"grafik\":{\"type\":\"string\",\"description\":\"Kennung der Grafik, etwa G1\"}",
                List.of("abschnitt", "grafik")));
        return all;
    }

    private List<AiChat.Tool> documentDefinitions() {
        return List.of(
                tool(LIST, "Listet die Dokumente dieses Chats mit Kennung, Titel und Umfang.", "{}", List.of()),
                tool(CREATE, "Legt ein neues Dokument an. Optional mit erstem Text, am besten nur der Gliederung "
                        + "aus Überschriften; die Abschnitte danach einzeln füllen.",
                        "\"titel\":{\"type\":\"string\"},\"text\":{\"type\":\"string\",\"description\":"
                                + "\"Markdown, etwa die Überschriften der Gliederung\"}", List.of("titel")),
                tool(OUTLINE, "Zeigt die Gliederung eines Dokuments: je Abschnitt Kennung, Überschrift und "
                        + "Wortzahl.", DOC, List.of()),
                tool(READ, "Liest einen Abschnitt (Überschrift und Text), so viel, wie in die Anfrage passt; "
                        + "lange Abschnitte mit versatz weiterlesen.", DOC + "," + SECTION
                        + ",\"versatz\":{\"type\":\"integer\",\"minimum\":0}", List.of("abschnitt")),
                tool(REPLACE, "Ersetzt den Text eines Abschnitts; die Überschrift bleibt. Unterüberschriften im "
                        + "Text werden eigene neue Abschnitte.", DOC + "," + SECTION
                        + ",\"text\":{\"type\":\"string\",\"description\":\"neuer Text in Markdown, ohne die "
                        + "Überschrift des Abschnitts\"}", List.of("abschnitt", "text")),
                tool(REPLACE_TEXT, "Ersetzt eine wörtliche Textstelle in einem Abschnitt, für ein Wort, eine Zahl "
                        + "oder einen Satz. Die Stelle muss genau einmal vorkommen.", DOC + "," + SECTION
                        + ",\"suchen\":{\"type\":\"string\",\"description\":\"die Stelle wörtlich wie in "
                        + "abschnitt_lesen\"},\"ersetzen\":{\"type\":\"string\"}",
                        List.of("abschnitt", "suchen", "ersetzen")),
                tool(INSERT, "Fügt einen neuen Abschnitt nach einem vorhandenen ein; ohne 'nach' an den Anfang.",
                        DOC + ",\"nach\":{\"type\":\"string\",\"description\":\"Kennung des Abschnitts davor\"},"
                                + "\"ueberschrift\":{\"type\":\"string\"},\"ebene\":{\"type\":\"integer\","
                                + "\"description\":\"1 bis 6, wie die Zahl der # (ohne Angabe wie davor)\"},"
                                + "\"text\":{\"type\":\"string\"}", List.of("ueberschrift")),
                tool(DELETE, "Löscht einen Abschnitt.", DOC + "," + SECTION, List.of("abschnitt")),
                tool(RENAME, "Ändert nur die Überschrift eines Abschnitts, wahlweise auch die Ebene.",
                        DOC + "," + SECTION + ",\"ueberschrift\":{\"type\":\"string\"},"
                                + "\"ebene\":{\"type\":\"integer\"}", List.of("abschnitt", "ueberschrift")),
                tool(MOVE, "Setzt einen Abschnitt hinter einen anderen; ohne 'nach' an den Anfang.",
                        DOC + "," + SECTION + ",\"nach\":{\"type\":\"string\"}", List.of("abschnitt")));
    }

    private static AiChat.Tool tool(String name, String description, String properties, List<String> required) {
        String props = properties.equals("{}") ? "" : properties;
        String requiredJson = required.isEmpty() ? "" : ",\"required\":[\"" + String.join("\",\"", required) + "\"]";
        return new AiChat.Tool(name, description,
                "{\"type\":\"object\",\"properties\":{" + props + "}" + requiredJson + "}");
    }

    /** Kurz für die Statuszeile, ohne Argumente des Modells. */
    public static String describe(String name) {
        return switch (name) {
            case LIST -> "Dokumente werden aufgelistet";
            case CREATE -> "Dokument wird angelegt";
            case OUTLINE -> "Gliederung wird gelesen";
            case READ -> "Abschnitt wird gelesen";
            case REPLACE, REPLACE_TEXT -> "Abschnitt wird überarbeitet";
            case INSERT -> "Abschnitt wird eingefügt";
            case DELETE -> "Abschnitt wird gelöscht";
            case RENAME -> "Überschrift wird geändert";
            case MOVE -> "Abschnitt wird verschoben";
            case GRAPHIC_DRAW -> "Grafik wird gezeichnet …";
            case GRAPHIC_CREATE -> "Grafik wird angelegt";
            case GRAPHIC_EMBED -> "Grafik wird eingebunden";
            case GRAPHIC_ELEMENTS, GRAPHIC_READ -> "Grafik wird gelesen";
            case GRAPHIC_ELEMENT_REPLACE, GRAPHIC_REPLACE -> "Grafik wird geändert";
            default -> "Werkzeug";
        };
    }

    /**
     * Die Regeln für die Systemnachricht samt der Gliederung des gerade geöffneten Dokuments (ADR 0033: Was der
     * Benutzer sieht, sieht der Bot).
     */
    public String rules() {
        StringBuilder out = new StringBuilder(RULES);
        List<ChatArtifact> artifacts = store.list(folder, chatId);
        if (!artifacts.isEmpty()) {
            out.append("\n\nDokumente dieses Chats:\n").append(listing(artifacts));
        }
        ChatArtifact open = focused();
        if (open != null && open.kind() == ChatArtifact.Kind.DOCUMENT) {
            out.append("\n\nDer Benutzer hat gerade ").append(open.id()).append(" „").append(open.title())
                    .append("“ geöffnet. Gliederung:\n").append(outline(open));
        }
        return out.toString();
    }

    /** Führt einen Aufruf aus; immer Text für das Modell, nie eine Ausnahme. */
    public String run(AiChat.ToolCall call) {
        JsonNode arguments;
        try {
            String raw = call.argumentsJson();
            arguments = JSON.readTree(raw == null || raw.isBlank() ? "{}" : raw);
        } catch (JacksonException e) {
            return "Die Argumente sind kein gültiges JSON.";
        }
        try {
            return switch (call.name()) {
                case LIST -> list();
                case CREATE -> create(arguments);
                case OUTLINE -> withDocument(arguments, DocumentChatTools::outline);
                case READ -> withDocument(arguments, document -> read(document, text(arguments, "abschnitt"),
                        Math.max(0, arguments.path("versatz").asInt(0))));
                case REPLACE -> edit(arguments, "Text ersetzt", doc ->
                        doc.replaceText(text(arguments, "abschnitt"), text(arguments, "text")));
                case REPLACE_TEXT -> edit(arguments, "Textstelle ersetzt", doc -> doc.replaceLiteral(
                        text(arguments, "abschnitt"), raw(arguments, "suchen"), raw(arguments, "ersetzen")));
                case INSERT -> edit(arguments, "Abschnitt eingefügt", doc -> doc.insertAfter(
                        text(arguments, "nach"), arguments.path("ebene").asInt(0), text(arguments, "ueberschrift"),
                        text(arguments, "text")));
                case DELETE -> edit(arguments, "Abschnitt gelöscht", doc -> doc.delete(text(arguments, "abschnitt")));
                case RENAME -> edit(arguments, "Überschrift geändert", doc -> doc.rename(
                        text(arguments, "abschnitt"), text(arguments, "ueberschrift"),
                        arguments.path("ebene").asInt(0)));
                case MOVE -> edit(arguments, "Abschnitt verschoben", doc -> doc.move(
                        text(arguments, "abschnitt"), text(arguments, "nach")));
                case GRAPHIC_DRAW -> draw(arguments);
                case GRAPHIC_CREATE -> createGraphic(text(arguments, "titel"), raw(arguments, "svg"));
                case GRAPHIC_EMBED -> embed(arguments);
                case GRAPHIC_ELEMENTS -> withGraphic(arguments, graphic -> graphic.id() + " „" + graphic.title()
                        + "“, Stand " + graphic.revision() + ":\n" + SvgElements.listing(graphic.current().svg()));
                case GRAPHIC_READ -> withGraphic(arguments, graphic -> readSvg(graphic, text(arguments, "element"),
                        arguments.path("versatz").asInt(0)));
                case GRAPHIC_ELEMENT_REPLACE -> changeGraphic(arguments, "Element " + text(arguments, "element")
                        + " ersetzt", svg -> SvgElements.replace(svg, text(arguments, "element"),
                        raw(arguments, "svg")));
                case GRAPHIC_REPLACE -> changeGraphic(arguments, "SVG ersetzt", svg -> wholeSvg(raw(arguments, "svg")));
                default -> "Unbekanntes Werkzeug.";
            };
        } catch (SectionDocument.SectionException invalid) {
            return "Fehler: " + invalid.getMessage();
        } catch (SvgElements.SvgException invalid) {
            return "Fehler: " + invalid.getMessage();
        } catch (RuntimeException failed) {
            return "Fehler: Das Werkzeug ist gescheitert (" + failed.getClass().getSimpleName() + ").";
        }
    }

    /**
     * Die Zeilen unter der Antwort: je angelegtem oder geändertem Artefakt sein Titel als Link, ein Klick öffnet den
     * Reiter (ADR 0045, Abschnitt 5); leer, wenn dieser Turn nichts geschrieben hat.
     */
    public String footer() {
        synchronized (touched) {
            if (touched.isEmpty()) {
                return "";
            }
            StringBuilder out = new StringBuilder("\n\n");
            List<String> graphics = new ArrayList<>();
            touched.forEach((id, title) -> {
                boolean graphic = id.startsWith("G");
                out.append(graphic ? "Grafik: [" : "Dokument: [").append(title.replace("]", ")"))
                        .append("](").append(LINK_SCHEME).append(id).append(")  \n");
                if (graphic) {
                    graphics.add(id);
                }
            });
            // Grafiken erscheinen im Chat über dieselbe Anzeige wie direktes SVG (ein Renderweg).
            for (String id : graphics) {
                ChatArtifact graphic = store.read(folder, chatId, id);
                if (graphic != null && !graphic.current().svg().isBlank()) {
                    out.append("\n````svg\n").append(graphic.current().svg().strip()).append("\n````\n");
                }
            }
            return out.toString().stripTrailing() + "\n";
        }
    }

    /** {@code base} samt den Schreibwerkzeugen. */
    public ChatToolset attach(ChatToolset base) {
        return new ChatToolset() {
            @Override
            public List<AiChat.Tool> definitions() {
                List<AiChat.Tool> all = new ArrayList<>(base.definitions());
                all.addAll(DocumentChatTools.this.definitions());
                return all;
            }

            @Override
            public String run(AiChat.ToolCall call) {
                return handles(call.name()) ? DocumentChatTools.this.run(call) : base.run(call);
            }

            @Override
            public String describe(AiChat.ToolCall call) {
                return handles(call.name()) ? DocumentChatTools.describe(call.name()) : base.describe(call);
            }

            @Override
            public String viewContext() {
                return base.viewContext();
            }

            @Override
            public String viewIdentity() {
                return base.viewIdentity();
            }

            @Override
            public void modelSelected(String model) {
                base.modelSelected(model);
            }

            @Override
            public void cancel() {
                base.cancel();
            }
        };
    }

    // ------------------------------------------------------------------ Werkzeuge

    private String list() {
        List<ChatArtifact> artifacts = store.list(folder, chatId);
        return artifacts.isEmpty() ? "Dieser Chat hat noch keine Dokumente. Mit dokument_anlegen beginnen."
                : listing(artifacts);
    }

    private String create(JsonNode arguments) {
        String title = text(arguments, "titel");
        if (title.isEmpty()) {
            return "Fehler: 'titel' fehlt.";
        }
        ChatArtifact created = store.createDocument(folder, chatId, title, raw(arguments, "text"),
                ChatArtifact.Origin.AI);
        if (created == null) {
            return "Fehler: Das Dokument konnte nicht gespeichert werden (kein Datenordner).";
        }
        report(created);
        List<SectionDocument.Section> sections = created.document().sections();
        return "Dokument " + created.id() + " „" + created.title() + "“ angelegt" + (sections.isEmpty()
                ? ", noch leer. Weiter mit abschnitt_einfuegen ohne 'nach'."
                : " mit " + sections.size() + " Abschnitten. Gliederung:\n" + outline(created.document())
                + "\nJetzt die Abschnitte einzeln mit abschnitt_ersetzen füllen.");
    }

    private String draw(JsonNode arguments) {
        String title = text(arguments, "titel");
        String material = text(arguments, "inhalt");
        if (material.isEmpty()) {
            return "Fehler: Gib im Feld inhalt an, was die Grafik zeigen soll.";
        }
        java.util.Optional<String> svg;
        try {
            svg = diagrams.compose(title.isEmpty() ? material : title, material);
        } catch (RuntimeException failed) {
            return "Fehler: Die Bildgenerierung ist gescheitert (" + failed.getClass().getSimpleName() + ").";
        }
        if (svg.isEmpty() || svg.get().isBlank()) {
            return "Keine Grafik: Unter Einstellungen › KI-Dienst › Modelle ist keine Bildgenerierung eingerichtet, "
                    + "oder das Modell lieferte keinen brauchbaren Plan. Mit grafik_anlegen selbst ein SVG schreiben "
                    + "oder es dem Benutzer sagen.";
        }
        return createGraphic(title.isEmpty() ? "Grafik" : title, svg.get());
    }

    private String createGraphic(String title, String svg) {
        if (title.isEmpty()) {
            return "Fehler: 'titel' fehlt.";
        }
        String content = svg == null ? "" : svg.strip();
        int start = content.indexOf("<svg");
        int end = content.lastIndexOf("</svg>");
        if (start < 0 || end < start) {
            return "Fehler: 'svg' enthält kein vollständiges SVG (<svg …>…</svg>).";
        }
        ChatArtifact created = store.createGraphic(folder, chatId, title, content.substring(start, end + 6),
                ChatArtifact.Origin.AI);
        if (created == null) {
            return "Fehler: Die Grafik konnte nicht gespeichert werden (kein Datenordner).";
        }
        report(created);
        return "Grafik " + created.id() + " „" + created.title() + "“ angelegt. Mit grafik_einbinden in einen "
                + "Abschnitt setzen.";
    }

    /** Das vollständige SVG aus {@code text}, oder ein Fehler für das Modell. */
    private static String wholeSvg(String text) {
        String content = text == null ? "" : text.strip();
        int start = content.indexOf("<svg");
        int end = content.lastIndexOf("</svg>");
        if (start < 0 || end < start) {
            throw new SvgElements.SvgException("'svg' enthält kein vollständiges SVG (<svg …>…</svg>).");
        }
        return content.substring(start, end + 6);
    }

    /** Die gemeinte Grafik: die genannte, sonst die geöffnete, sonst die einzige des Chats. */
    private ChatArtifact graphic(JsonNode arguments) {
        String id = text(arguments, "grafik");
        if (id.isEmpty()) {
            ChatArtifact open = focused();
            if (open != null && open.kind() == ChatArtifact.Kind.GRAPHIC) {
                return open;
            }
            List<ChatArtifact> graphics = store.list(folder, chatId).stream()
                    .filter(artifact -> artifact.kind() == ChatArtifact.Kind.GRAPHIC).toList();
            if (graphics.size() == 1) {
                return graphics.get(0);
            }
            throw new SvgElements.SvgException(graphics.isEmpty() ? "Dieser Chat hat noch keine Grafik."
                    : "'grafik' fehlt. Vorhandene Grafiken:\n" + listing(graphics));
        }
        ChatArtifact graphic = store.read(folder, chatId, id);
        if (graphic == null || graphic.kind() != ChatArtifact.Kind.GRAPHIC) {
            throw new SvgElements.SvgException("Grafik '" + id + "' gibt es in diesem Chat nicht. Mit "
                    + LIST + " nachsehen.");
        }
        return graphic;
    }

    private String withGraphic(JsonNode arguments, Function<ChatArtifact, String> reading) {
        return reading.apply(graphic(arguments));
    }

    /** Ändert das SVG einer Grafik auf den neuesten Stand; die Ansicht zeigt die Grafik danach. */
    private String changeGraphic(JsonNode arguments, String note, java.util.function.UnaryOperator<String> change) {
        ChatArtifact graphic = graphic(arguments);
        String next = change.apply(graphic.current().svg());
        ChatArtifactStore.WriteResult result = store.replaceSvg(folder, chatId, graphic.id(), ChatArtifactStore.ANY,
                ChatArtifact.Origin.AI, note, next);
        if (!result.success()) {
            return "Fehler: " + result.reason();
        }
        report(result.artifact());
        return graphic.id() + " „" + graphic.title() + "“: " + note + ", jetzt Stand " + result.revision() + ".";
    }

    /** Das SVG einer Grafik oder eines Elements, so viel wie in die Anfrage passt (wie {@link #read}). */
    static String readSvg(ChatArtifact graphic, String element, int offset) {
        String text = element == null || element.isBlank() ? graphic.current().svg().strip()
                : SvgElements.element(graphic.current().svg(), element);
        int room = ChatRoom.chars();
        if (room == 0) {
            return ChatRoom.FULL;
        }
        int from = Math.min(Math.max(0, offset), text.length());
        int to = ModelBudget.fit(text, from, room);
        StringBuilder b = new StringBuilder(graphic.id()).append(" „").append(graphic.title()).append('“')
                .append(element == null || element.isBlank() ? "" : ", Element " + element.strip());
        if (from > 0 || to < text.length()) {
            b.append(", Zeichen ").append(from).append(" bis ").append(to).append(" von ").append(text.length());
        }
        b.append(":\n").append(text, from, to);
        if (to < text.length()) {
            b.append("\n\n(Fortsetzung mit ").append(GRAPHIC_READ).append(", versatz ").append(to).append(')');
        }
        return b.toString();
    }

    private String embed(JsonNode arguments) {
        String id = text(arguments, "grafik");
        ChatArtifact graphic = id.isEmpty() ? null : store.read(folder, chatId, id);
        if (graphic == null || graphic.kind() != ChatArtifact.Kind.GRAPHIC) {
            return "Fehler: Grafik '" + id + "' gibt es in diesem Chat nicht. Mit dokumente_auflisten nachsehen.";
        }
        String reference = GraphicReferences.reference(graphic);
        return edit(arguments, "Grafik " + graphic.id() + " eingebunden", doc -> {
            SectionDocument.Section section = doc.section(text(arguments, "abschnitt"));
            if (GraphicReferences.ids(section.text()).contains(graphic.id())) {
                throw new SectionDocument.SectionException("Grafik " + graphic.id() + " steht schon in Abschnitt "
                        + section.id() + ".");
            }
            return doc.replaceText(section.id(), section.text().isEmpty() ? reference
                    : section.text() + "\n\n" + reference);
        });
    }

    private String withDocument(JsonNode arguments, Function<SectionDocument, String> reading) {
        ChatArtifact artifact = document(arguments);
        return artifact.id() + " „" + artifact.title() + "“, Stand " + artifact.revision() + ":\n"
                + reading.apply(artifact.document());
    }

    private String edit(JsonNode arguments, String note,
                        Function<SectionDocument, SectionDocument.Change> operation) {
        ChatArtifact artifact = document(arguments);
        ChatArtifactStore.WriteResult result = store.edit(folder, chatId, artifact.id(), ChatArtifactStore.ANY,
                ChatArtifact.Origin.AI, note, operation);
        if (!result.success()) {
            return "Fehler: " + result.reason();
        }
        report(result.artifact());
        String ids = String.join(", ", result.changed());
        return "Erledigt: " + note + (ids.isEmpty() ? "" : " (" + ids + ")") + ", " + result.artifact().id()
                + " jetzt Stand " + result.revision() + ".";
    }

    /**
     * Ein Abschnitt, so viel davon, wie in die laufende Anfrage passt ({@link ChatRoom}); den Rest holt das Modell mit
     * {@code versatz}, wie beim Lesen einer Seite ({@code ChatTools.read}).
     */
    static String read(SectionDocument document, String id, int offset) {
        SectionDocument.Section section = document.section(id);
        String text = section.markdown().strip();
        int room = ChatRoom.chars();
        if (room == 0) {
            return ChatRoom.FULL;
        }
        int from = Math.min(offset, text.length());
        int to = ModelBudget.fit(text, from, room);
        StringBuilder b = new StringBuilder("Abschnitt ").append(section.id())
                .append(section.level() == 0 ? " (Einleitung)" : ", Ebene " + section.level());
        if (from > 0 || to < text.length()) {
            b.append(", Zeichen ").append(from).append(" bis ").append(to).append(" von ").append(text.length());
        }
        b.append(":\n").append(text, from, to);
        if (to < text.length()) {
            b.append("\n\n(Fortsetzung mit ").append(READ).append(", versatz ").append(to).append(')');
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ Hilfen

    private ChatArtifact document(JsonNode arguments) {
        String id = text(arguments, "dokument");
        if (id.isEmpty()) {
            ChatArtifact open = focused();
            if (open != null && open.kind() == ChatArtifact.Kind.DOCUMENT) {
                return open;
            }
            List<ChatArtifact> documents = store.list(folder, chatId).stream()
                    .filter(artifact -> artifact.kind() == ChatArtifact.Kind.DOCUMENT).toList();
            if (documents.size() == 1) {
                return documents.get(0);
            }
            throw new SectionDocument.SectionException(documents.isEmpty()
                    ? "Dieser Chat hat noch kein Dokument. Mit dokument_anlegen beginnen."
                    : "'dokument' fehlt. Vorhandene Dokumente:\n" + listing(documents));
        }
        ChatArtifact artifact = store.read(folder, chatId, id);
        if (artifact == null) {
            throw new SectionDocument.SectionException("Dokument '" + id + "' gibt es in diesem Chat nicht. "
                    + "Mit dokumente_auflisten nachsehen.");
        }
        if (artifact.kind() != ChatArtifact.Kind.DOCUMENT) {
            throw new SectionDocument.SectionException(artifact.id() + " ist eine Grafik, kein Dokument.");
        }
        return artifact;
    }

    private ChatArtifact focused() {
        String id = focus.get();
        return id == null || id.isBlank() ? null : store.read(folder, chatId, id);
    }

    private void report(ChatArtifact artifact) {
        synchronized (touched) {
            touched.put(artifact.id(), artifact.title());
        }
        changed.accept(artifact);
    }

    static String listing(List<ChatArtifact> artifacts) {
        StringBuilder out = new StringBuilder();
        for (ChatArtifact artifact : artifacts) {
            out.append(artifact.id()).append(' ')
                    .append(artifact.kind() == ChatArtifact.Kind.DOCUMENT ? "Dokument" : "Grafik")
                    .append(" „").append(artifact.title()).append("“");
            if (artifact.kind() == ChatArtifact.Kind.DOCUMENT) {
                out.append(" (").append(artifact.document().sections().size()).append(" Abschnitte, ")
                        .append(artifact.document().words()).append(" Wörter)");
            }
            out.append('\n');
        }
        return out.toString().stripTrailing();
    }

    static String outline(ChatArtifact artifact) {
        return outline(artifact.document());
    }

    static String outline(SectionDocument document) {
        if (document.sections().isEmpty()) {
            return "(leer; mit abschnitt_einfuegen ohne 'nach' beginnen)";
        }
        StringBuilder out = new StringBuilder();
        for (SectionDocument.Section section : document.sections()) {
            out.append(section.id()).append(' ');
            if (section.level() == 0) {
                out.append("[Einleitung]");
            } else {
                out.append("#".repeat(section.level())).append(' ').append(section.heading());
            }
            out.append(" (").append(section.words()).append(" Wörter)\n");
        }
        return out.toString().stripTrailing();
    }

    private static String text(JsonNode arguments, String field) {
        return raw(arguments, field).strip();
    }

    private static String raw(JsonNode arguments, String field) {
        JsonNode value = arguments.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asString("");
    }
}
