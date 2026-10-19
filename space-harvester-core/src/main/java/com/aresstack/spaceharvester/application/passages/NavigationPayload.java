package com.aresstack.spaceharvester.application.passages;

import com.aresstack.spaceharvester.application.enrichment.JsonText;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ergebnisformat des Prozessors {@value NavigationProcessor#ID}:
 * {@code {"analysis","reason","chapters":[{"id","label","origin","level","start","end","children":[…]}]}}.
 * Gespeichert werden Struktur, Stellen und Bezeichnungen, nicht der Text; den Anker liefert beim Lesen der Text mit
 * demselben {@code inputSha256}.
 */
public final class NavigationPayload {

    /** Kein Embedding-Modell eingestellt. */
    public static final String REASON_NO_MODEL = PassagePayload.REASON_NO_MODEL;
    /** Quelle nicht für den KI-Dienst freigegeben (ADR 0011). */
    public static final String REASON_NOT_APPROVED = PassagePayload.REASON_NOT_APPROVED;
    /** Für diesen Text liegen noch keine aktuellen Embeddings je Absatz vor. */
    public static final String REASON_NOT_EMBEDDED = "not-embedded";
    /**
     * Kein OpenNLP-Satzmodell, die Absätze kämen aus dem Regel-Splitter: nur Überschriften, wie
     * {@code NO_SENTENCE_MODEL} im Client.
     */
    public static final String REASON_NO_SENTENCE_MODEL = "no-sentence-model";
    /** Die Embeddings dieses Textes sind gescheitert, wie {@code INDEXING_FAILED} im Client. */
    public static final String REASON_INDEXING_FAILED = "indexing-failed";
    /** Keine Themenwechsel gefunden: die Überschriften reichen. */
    public static final String REASON_NO_TOPIC_CHANGE = "no-topic-change";
    /** Modell eingestellt, aber nicht erreichbar oder unbrauchbar. */
    public static final String REASON_MODEL_UNAVAILABLE = PassagePayload.REASON_MODEL_UNAVAILABLE;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private NavigationPayload() {
    }

    static String write(DocumentNavigationBuilder.Navigation navigation) {
        StringBuilder json = new StringBuilder("{\"analysis\":")
                .append(JsonText.quote(name(navigation.analysis())))
                .append(",\"reason\":").append(navigation.reason() == null ? "null"
                        : JsonText.quote(navigation.reason()))
                .append(",\"chapters\":");
        sections(json, navigation.chapters());
        return json.append('}').toString();
    }

    private static void sections(StringBuilder json, List<NavigationSection> sections) {
        json.append('[');
        for (int i = 0; i < sections.size(); i++) {
            NavigationSection section = sections.get(i);
            json.append(i == 0 ? "" : ",").append("{\"id\":").append(JsonText.quote(section.id()))
                    .append(",\"label\":").append(JsonText.quote(section.label()))
                    .append(",\"origin\":").append(JsonText.quote(name(section.origin())))
                    .append(",\"level\":").append(section.level())
                    .append(",\"start\":").append(section.start())
                    .append(",\"end\":").append(section.end())
                    .append(",\"children\":");
            sections(json, section.children());
            json.append('}');
        }
        json.append(']');
    }

    /**
     * Liest ein Ergebnis.
     *
     * @param text der Text, zu dem die Stellen gehören, für die Anker
     * @throws IllegalArgumentException bei einem unlesbaren Ergebnis
     */
    static DocumentNavigationBuilder.Navigation parse(String json, String text) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Navigation nicht lesbar", e);
        }
        if (!root.isObject()) {
            throw new IllegalArgumentException("Navigation nicht lesbar");
        }
        return new DocumentNavigationBuilder.Navigation(
                of(DocumentNavigationBuilder.Analysis.class, root.path("analysis")),
                root.path("reason").isString() ? root.path("reason").asString() : null,
                sections(root.path("chapters"), null, text));
    }

    private static List<NavigationSection> sections(JsonNode array, String parentId, String text) {
        if (!array.isArray()) {
            throw new IllegalArgumentException("Abschnitte fehlen");
        }
        List<NavigationSection> sections = new ArrayList<>();
        for (JsonNode item : array) {
            String id = item.path("id").asString();
            String label = item.path("label").asString();
            NavigationSection.Origin origin = of(NavigationSection.Origin.class, item.path("origin"));
            int start = item.path("start").asInt();
            int end = item.path("end").asInt();
            if (end > text.length()) {
                throw new IllegalArgumentException("Abschnitt reicht über das Textende");
            }
            String anchor = origin == NavigationSection.Origin.HEADING ? label
                    : anchorAt(text, start, DocumentNavigationBuilder.ANCHOR_CHARS);
            sections.add(new NavigationSection(id, label, origin, item.path("level").asInt(), start, end, anchor,
                    parentId, sections(item.path("children"), id, text)));
        }
        return sections;
    }

    static String anchorAt(String text, int start, int max) {
        int end = Math.min(text.length(), start + max);
        int newline = text.indexOf('\n', start);
        if (newline >= 0 && newline < end) {
            end = newline;
        }
        return start >= end ? "" : text.substring(start, end).strip();
    }

    static String name(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static <E extends Enum<E>> E of(Class<E> type, JsonNode value) {
        if (!value.isString()) {
            throw new IllegalArgumentException(type.getSimpleName() + " fehlt");
        }
        return Enum.valueOf(type, value.asString().toUpperCase(Locale.ROOT).replace('-', '_'));
    }
}
