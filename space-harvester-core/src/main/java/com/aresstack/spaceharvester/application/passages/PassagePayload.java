package com.aresstack.spaceharvester.application.passages;

import com.aresstack.spaceharvester.application.enrichment.JsonText;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ergebnisformat des Prozessors {@value PassageProcessor#ID}:
 * {@code {"mode","reason","model","segmentation","sentences","passages":[{"start","end","firstSentence","sentences",
 * "section","kind","boundary","similarity"}]}}. Gespeichert werden nur Stellen, nie Text: Den Wortlaut liefert die
 * Textprojektion mit demselben {@code inputSha256}.
 */
public final class PassagePayload {

    /** Kein Embedding-Modell eingestellt. */
    public static final String REASON_NO_MODEL = "no-model";
    /** Quelle nicht für den KI-Dienst freigegeben (ADR 0011). */
    public static final String REASON_NOT_APPROVED = "source-not-approved";
    /** Modell eingestellt, aber nicht erreichbar. */
    public static final String REASON_MODEL_UNAVAILABLE = "model-unavailable";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private PassagePayload() {
    }

    /** Wie die Grenzen entstanden. */
    public enum Mode {
        /** Struktur und gemessene Themenwechsel. */
        SEMANTIC,
        /** Nur Struktur und Token-Budget: die Rückfallebene ohne Modell. */
        STRUCTURE
    }

    /**
     * Ein gelesenes Ergebnis.
     *
     * @param mode         wie die Grenzen entstanden
     * @param reason       bei {@link Mode#STRUCTURE} warum, sonst {@code null}
     * @param model        bei {@link Mode#SEMANTIC} das Embedding-Modell, sonst {@code null}
     * @param segmentation {@link PassageSegmentation#fingerprint()}
     * @param sentences    Zahl aller Sätze
     * @param passages     in Lesereihenfolge
     */
    public record Parsed(Mode mode, String reason, String model, String segmentation, int sentences,
                         List<PassageSegmentation.Passage> passages) {

        public Parsed {
            Objects.requireNonNull(mode, "mode");
            passages = List.copyOf(passages);
        }
    }

    static String write(Mode mode, String reason, String model, String segmentation, int sentences,
                        List<PassageSegmentation.Passage> passages) {
        StringBuilder json = new StringBuilder("{\"mode\":").append(JsonText.quote(name(mode)))
                .append(",\"reason\":").append(reason == null ? "null" : JsonText.quote(reason))
                .append(",\"model\":").append(model == null ? "null" : JsonText.quote(model))
                .append(",\"segmentation\":").append(JsonText.quote(segmentation))
                .append(",\"sentences\":").append(sentences)
                .append(",\"passages\":[");
        for (int i = 0; i < passages.size(); i++) {
            PassageSegmentation.Passage passage = passages.get(i);
            json.append(i == 0 ? "" : ",")
                    .append("{\"start\":").append(passage.start())
                    .append(",\"end\":").append(passage.end())
                    .append(",\"firstSentence\":").append(passage.firstSentence())
                    .append(",\"sentences\":").append(passage.sentences())
                    .append(",\"section\":").append(passage.section() == null ? "null"
                            : JsonText.quote(passage.section()))
                    .append(",\"kind\":").append(JsonText.quote(name(passage.kind())))
                    .append(",\"boundary\":").append(JsonText.quote(name(passage.boundary())))
                    .append(",\"similarity\":").append(passage.similarity() == null ? "null"
                            : rounded(passage.similarity()))
                    .append('}');
        }
        return json.append("]}").toString();
    }

    /**
     * Liest ein Ergebnis.
     *
     * @throws IllegalArgumentException wenn es nicht diesem Format entspricht
     */
    public static Parsed parse(String payloadJson) {
        JsonNode root;
        try {
            root = JSON.readTree(payloadJson);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Ergebnis nicht lesbar", e);
        }
        JsonNode items = root.path("passages");
        if (!items.isArray()) {
            throw new IllegalArgumentException("passages fehlt");
        }
        List<PassageSegmentation.Passage> passages = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            JsonNode similarity = item.path("similarity");
            passages.add(new PassageSegmentation.Passage(number(item, "start"), number(item, "end"),
                    number(item, "firstSentence"), number(item, "sentences"), textOrNull(item.path("section")),
                    of(PassageSegmentation.Kind.class, text(item, "kind")),
                    of(PassageSegmentation.Boundary.class, text(item, "boundary")),
                    similarity.isNumber() ? similarity.asDouble() : null));
        }
        return new Parsed(of(Mode.class, text(root, "mode")), textOrNull(root.path("reason")),
                textOrNull(root.path("model")), text(root, "segmentation"), number(root, "sentences"), passages);
    }

    /** Kleinschreibung mit Bindestrich, wie in der REST-Antwort. */
    public static String name(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static <E extends Enum<E>> E of(Class<E> type, String name) {
        try {
            return Enum.valueOf(type, name.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unbekannter Wert " + name, e);
        }
    }

    private static String rounded(double value) {
        return Double.toString(Math.round(value * 1_000_000d) / 1_000_000d);
    }

    private static int number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber()) {
            throw new IllegalArgumentException(field + " fehlt");
        }
        return value.asInt();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString()) {
            throw new IllegalArgumentException(field + " fehlt");
        }
        return value.asString();
    }

    private static String textOrNull(JsonNode node) {
        return node.isString() ? node.asString() : null;
    }
}
