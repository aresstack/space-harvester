package com.aresstack.research4j.judge;

import com.aresstack.knowledge4j.access.FetchedContent;
import com.aresstack.knowledge4j.scope.Fence;
import com.aresstack.research4j.run.ModelAsk;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Locale;
import java.util.Map;

/**
 * The fence check of a fetched source (Zaunprüfung, the first level of the source check of the concept-led flow): one
 * model call per source reads title, address, metadata and the beginning of the text with the fence and gives what the
 * source is about, where it lies against the fence and why. It keeps no state and decides nothing on its own: the
 * research puts every source but a clearly outside one into the basket. In doubt the model is told UNKLAR, never
 * AUSSEN. Thinking is the host's setting of the model slot; there are no rules of a particular subject here.
 */
public final class SourceJudge {

    /**
     * How the judge works.
     *
     * @param textChars      characters of the source's beginning shown
     * @param missionChars   characters of the mission shown
     * @param answerTokens   answer budget of one call
     * @param repairAttempts how often an invalid answer goes back
     * @param temperature    sampling temperature
     */
    public record Settings(int textChars, int missionChars, int answerTokens, int repairAttempts, double temperature) {
        public Settings {
            if (textChars < 1 || missionChars < 1 || answerTokens < 1 || repairAttempts < 0 || temperature < 0) {
                throw new IllegalArgumentException("invalid judge settings");
            }
        }

        /** 3,000 characters of text, 1,200 of the mission, 400 answer tokens, one repair, temperature 0. */
        public static Settings defaults() {
            return new Settings(3000, 1200, 400, 1, 0.0);
        }
    }

    static final String SYSTEM_EN = "You check sources for a research. You get the research's fence (mission and "
            + "bounds) and the title, address and beginning of a web page or document. Say in one line what the source "
            + "is about and where it lies against the fence: INNEN when the whole source lies inside the fence; "
            + "GEMISCHT when parts lie inside and parts outside (such as a document on several subjects of which only "
            + "one belongs to the research); AUSSEN when the source lies wholly outside; UNKLAR when title and "
            + "beginning do not tell. In doubt UNKLAR, never AUSSEN. Judge only against the fence, never by your own "
            + "idea of the subject. Answer only with JSON: \"subject\", \"fence\", \"reason\".";

    static final String SYSTEM_DE = "Du prüfst Quellen für eine Recherche. Du bekommst den Weidezaun der Recherche "
            + "(Auftrag und Grenzen) sowie Titel, Adresse und Anfang einer Webseite oder eines Dokuments. Sage in "
            + "einer Zeile, worum es in der Quelle geht und wo sie zum Weidezaun liegt: INNEN, wenn die ganze Quelle "
            + "innerhalb liegt; GEMISCHT, wenn Teile innerhalb und Teile außerhalb liegen (etwa ein Dokument zu "
            + "mehreren Gegenständen, von denen nur einer zur Recherche gehört); AUSSEN, wenn sie ganz außerhalb "
            + "liegt; UNKLAR, wenn sich das aus Titel und Anfang nicht sagen lässt. Im Zweifel UNKLAR, nie AUSSEN. "
            + "Urteile nur am Weidezaun, nie nach eigener Vorstellung vom Thema. Antworte nur mit JSON: \"subject\", "
            + "\"fence\", \"reason\".";

    private final ModelAsk model;
    private final Settings settings;
    private final boolean german;
    private final Fence fence;

    public SourceJudge(ModelAsk model, Settings settings, String language, Fence fence) {
        this.model = model;
        this.settings = settings == null ? Settings.defaults() : settings;
        this.german = language != null && language.toLowerCase(Locale.ROOT).startsWith("de");
        this.fence = fence == null ? Fence.of("") : fence;
    }

    public SourceProfile judge(FetchedContent content) {
        ModelAsk.Outcome<SourceProfile> outcome = model.ask(german ? SYSTEM_DE : SYSTEM_EN, prompt(content), SCHEMA,
                settings.temperature(), settings.answerTokens(), settings.repairAttempts(), answer -> read(content, answer));
        return outcome.ok() ? outcome.value() : new SourceProfile(content.ref(), null, "", outcome.problem());
    }

    String prompt(FetchedContent content) {
        StringBuilder sb = new StringBuilder(german ? "WEIDEZAUN\n" : "FENCE\n")
                .append(fence.describe(german, settings.missionChars())).append('\n');
        sb.append(german ? "QUELLE\n" : "SOURCE\n");
        String title = content.ref().title();
        sb.append(german ? "Titel: " : "Title: ").append(title.isEmpty() ? "-" : title).append('\n');
        sb.append(german ? "Adresse: " : "Address: ").append(content.ref().id()).append('\n');
        for (Map.Entry<String, String> entry : content.metadata().entrySet()) {
            sb.append(entry.getKey()).append(": ").append(Fence.cut(entry.getValue(), settings.missionChars()))
                    .append('\n');
        }
        sb.append('\n').append(german ? "ANFANG\n" : "BEGINNING\n")
                .append(Fence.cut(content.text(), settings.textChars())).append('\n');
        return sb.toString();
    }

    static SourceProfile read(FetchedContent content, JsonObject answer) throws ModelAsk.Rejected {
        String value = ModelAsk.text(answer, "fence").toUpperCase(Locale.ROOT);
        FenceFit fit;
        try {
            fit = FenceFit.valueOf(value);
        } catch (IllegalArgumentException unknown) {
            throw new ModelAsk.Rejected("\"fence\" must be one of INNEN, GEMISCHT, AUSSEN, UNKLAR");
        }
        String reason = ModelAsk.text(answer, "reason");
        if (reason.isEmpty()) {
            throw new ModelAsk.Rejected("\"reason\" is missing");
        }
        return new SourceProfile(content.ref(), fit, ModelAsk.text(answer, "subject"), reason);
    }

    static final String SCHEMA;

    static {
        JsonObject text = new JsonObject();
        text.addProperty("type", "string");
        JsonObject fit = new JsonObject();
        fit.addProperty("type", "string");
        JsonArray values = new JsonArray();
        for (FenceFit f : FenceFit.values()) {
            values.add(f.name());
        }
        fit.add("enum", values);
        JsonObject props = new JsonObject();
        props.add("subject", text);
        props.add("fence", fit);
        props.add("reason", text.deepCopy());
        JsonObject root = new JsonObject();
        root.addProperty("type", "object");
        root.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("subject");
        required.add("fence");
        required.add("reason");
        root.add("required", required);
        root.addProperty("additionalProperties", false);
        SCHEMA = root.toString();
    }
}
