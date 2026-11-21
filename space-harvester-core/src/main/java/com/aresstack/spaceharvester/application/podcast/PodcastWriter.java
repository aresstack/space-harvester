package com.aresstack.spaceharvester.application.podcast;

import com.aresstack.spaceharvester.application.podcast.PodcastPlan.Beat;
import com.aresstack.spaceharvester.application.podcast.PodcastPlan.Segment;
import com.aresstack.spaceharvester.application.podcast.PodcastPlan.Speaker;
import com.aresstack.spaceharvester.application.podcast.PodcastPlan.Topic;
import com.aresstack.spaceharvester.application.podcast.PodcastPlan.Turn;
import com.aresstack.spaceharvester.application.stepmodel.StepModel;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Schreibt den Podcast in drei Stufen über das Chat-Modell, wie das Audio-Studio (aresstack/audio) Musik plant: die
 * Grobübersicht ({@link #outline}), je Thema die Abschnitte mit Sprecherwechseln ({@link #segments}) und je Abschnitt den
 * gesprochenen Text ({@link #script}). Jeder Aufruf kennt den ganzen Plan, damit die Abschnitte, die parallel entstehen,
 * zusammenpassen; der Anschluss an den vorigen Abschnitt kommt aus dessen Plan, nicht aus seinem Text.
 *
 * <p>Selbstheilend (Angelo 21.11.2026): Jede Stufe fragt bis zu {@link #ATTEMPTS}-mal; eine unbrauchbare Antwort wird
 * repariert, und gelingt gar nichts, entsteht die Stufe ohne Modell aus der Zusammenfassung selbst. Der Podcast
 * verstummt also nie wegen einer Antwort des Modells; jede Reparatur steht in {@link PodcastPlan#repairs()}.
 */
public final class PodcastWriter {

    /** Versuche je Stufe, bevor der Ersatz ohne Modell gilt. */
    public static final int ATTEMPTS = 3;
    /** Längster Text eines Beitrags; das liegt unter den rund 500 Zeichen je Stück des Stimmstabilitäts-Fixes. */
    static final int MAX_TURN_CHARS = 450;
    /** So viel der Zusammenfassung geht höchstens an das Modell. */
    static final int MAX_MATERIAL_CHARS = 12000;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static final String STYLE = "Du schreibst einen lockeren deutschen Podcast im Stil von NotebookLM: zwei Stimmen "
            + "unterhalten sich natürlich und gut gelaunt über das, was sich in einer Wissensquelle getan hat. Sie "
            + "erklären, ordnen ein und reagieren aufeinander, ohne zu übertreiben. Nur Fakten aus dem Material, nichts "
            + "erfinden, keine Namen von Personen. Antworte nur mit gültigem JSON, ohne Markdown.";

    private final StepModel model;
    private final Pause pause;

    /** Wartet zwischen zwei Versuchen; wirft, wenn der Auftrag abgebrochen wird. */
    @FunctionalInterface
    public interface Pause {
        void before(int attempt);
    }

    public PodcastWriter(StepModel model, Pause pause) {
        this.model = Objects.requireNonNull(model, "model");
        this.pause = Objects.requireNonNull(pause, "pause");
    }

    // --- Stufe 1: Grobübersicht ---------------------------------------------------------------------------------

    /** Die Grobübersicht aus dem Material; nie leer. */
    public PodcastPlan outline(Material material) {
        String prompt = "# MATERIAL: KI-Zusammenfassung des Updates" + material.describe() + "\n"
                + cut(material.text(), MAX_MATERIAL_CHARS) + "\n\n# AUFGABE\nPlane die Folge grob: einen Titel und "
                + "2 bis " + PodcastPlan.MAX_TOPICS + " Themen in Sendereihenfolge. Das erste Thema ist ein kurzer "
                + "Einstieg (worum es heute geht), das letzte ein kurzer Abschluss (was man mitnehmen sollte). Fasse "
                + "Zusammengehöriges zu einem Thema zusammen. energy: 0 ruhig und einordnend bis 1 lebhaft. points: "
                + "die Kernpunkte aus dem Material, die das Thema tragen.\nAntworte mit genau einem JSON-Objekt: "
                + "{\"title\":\"...\",\"topics\":[{\"title\":\"...\",\"angle\":\"ein Satz\",\"energy\":0.5,"
                + "\"points\":[\"...\"]}]}";
        List<String> repairs = new ArrayList<>();
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                PodcastPlan plan = readOutline(model.complete(STYLE, prompt), repairs);
                if (plan != null) {
                    return plan;
                }
                repairs.add("Grobübersicht: Antwort " + attempt + " ohne Themen");
            } catch (RuntimeException e) {
                repairs.add("Grobübersicht: Versuch " + attempt + " gescheitert (" + e.getClass().getSimpleName()
                        + ")");
            }
            if (attempt < ATTEMPTS) {
                pause.before(attempt + 1);
            }
        }
        repairs.add("Grobübersicht ohne Modell aus der Zusammenfassung");
        return fallbackOutline(material, repairs);
    }

    static PodcastPlan readOutline(String answer, List<String> repairs) {
        JsonNode root = json(answer);
        if (root == null || !root.path("topics").isArray()) {
            return null;
        }
        List<Topic> topics = new ArrayList<>();
        for (JsonNode node : root.path("topics")) {
            String title = text(node, "title");
            if (title.isBlank()) {
                repairs.add("Grobübersicht: Thema ohne Titel übergangen");
                continue;
            }
            topics.add(new Topic(title, text(node, "angle"), node.path("energy").asDouble(0.5), strings(node,
                    "points")));
        }
        if (topics.isEmpty()) {
            return null;
        }
        if (topics.size() > PodcastPlan.MAX_TOPICS) {
            repairs.add("Grobübersicht: " + topics.size() + " Themen auf " + PodcastPlan.MAX_TOPICS + " gekürzt");
            List<Topic> kept = new ArrayList<>(topics.subList(0, PodcastPlan.MAX_TOPICS - 1));
            kept.add(topics.get(topics.size() - 1)); // der Abschluss bleibt
            topics = kept;
        }
        return new PodcastPlan(text(root, "title"), topics, repairs);
    }

    /** Ein Einstieg, je Aussage der Zusammenfassung ein Thema (höchstens so viele, wie Platz ist), ein Abschluss. */
    static PodcastPlan fallbackOutline(Material material, List<String> repairs) {
        List<Topic> topics = new ArrayList<>();
        topics.add(new Topic("Einstieg", "Worum es in diesem Update geht", 0.6, List.of()));
        List<String> statements = material.statements().isEmpty() ? paragraphs(material.text())
                : material.statements();
        for (String statement : statements) {
            if (topics.size() >= PodcastPlan.MAX_TOPICS - 1) {
                break;
            }
            topics.add(new Topic(headline(statement), "", 0.5, List.of(statement)));
        }
        topics.add(new Topic("Abschluss", "Was man mitnehmen sollte", 0.4, List.of()));
        return new PodcastPlan(null, topics, repairs);
    }

    // --- Stufe 2: Abschnitte je Thema ---------------------------------------------------------------------------

    /** Die Abschnitte eines Themas mit ihren Sprecherwechseln; nie leer. */
    public List<Segment> segments(PodcastPlan plan, int topicIndex, Voices voices, List<String> repairs) {
        Topic topic = plan.topics().get(topicIndex);
        boolean edge = topicIndex == 0 || topicIndex == plan.topics().size() - 1;
        String prompt = planText(plan, voices) + "\n# AUFGABE\nPlane Thema " + (topicIndex + 1) + " \"" + topic.title()
                + "\" genauer: " + (edge ? "genau 1 Abschnitt" : "1 bis " + PodcastPlan.MAX_SEGMENTS + " Abschnitte")
                + ", je Abschnitt 2 bis " + PodcastPlan.MAX_BEATS + " Wortbeiträge mit Sprecherwechsel. speaker ist "
                + "\"host\" (" + voices.host() + ", führt durch die Folge) oder \"cohost\" (" + voices.cohost()
                + ", fragt nach, ordnet ein). intent sagt in wenigen Worten, was der Beitrag tut.\nAntworte mit genau "
                + "einem JSON-Objekt: {\"segments\":[{\"title\":\"...\",\"beats\":[{\"speaker\":\"host\","
                + "\"intent\":\"...\"}]}]}";
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                List<Segment> segments = readSegments(model.complete(STYLE, prompt), edge, repairs);
                if (!segments.isEmpty()) {
                    return segments;
                }
                repairs.add("Thema " + (topicIndex + 1) + ": Antwort " + attempt + " ohne Abschnitte");
            } catch (RuntimeException e) {
                repairs.add("Thema " + (topicIndex + 1) + ": Versuch " + attempt + " gescheitert ("
                        + e.getClass().getSimpleName() + ")");
            }
            if (attempt < ATTEMPTS) {
                pause.before(attempt + 1);
            }
        }
        repairs.add("Thema " + (topicIndex + 1) + ": Abschnitt ohne Modell");
        return List.of(fallbackSegment(topic));
    }

    static List<Segment> readSegments(String answer, boolean single, List<String> repairs) {
        JsonNode root = json(answer);
        List<Segment> segments = new ArrayList<>();
        if (root == null || !root.path("segments").isArray()) {
            return segments;
        }
        for (JsonNode node : root.path("segments")) {
            List<Beat> beats = new ArrayList<>();
            Speaker next = Speaker.HOST;
            for (JsonNode beat : node.path("beats")) {
                Speaker speaker = Speaker.parse(text(beat, "speaker"), next);
                beats.add(new Beat(speaker, text(beat, "intent")));
                next = speaker.other();
                if (beats.size() == PodcastPlan.MAX_BEATS) {
                    break;
                }
            }
            if (beats.isEmpty()) {
                repairs.add("Abschnitt ohne Beiträge übergangen");
                continue;
            }
            segments.add(new Segment(text(node, "title"), beats));
            if (segments.size() == (single ? 1 : PodcastPlan.MAX_SEGMENTS)) {
                break;
            }
        }
        return segments;
    }

    static Segment fallbackSegment(Topic topic) {
        return new Segment(topic.title(), List.of(new Beat(Speaker.HOST, "stellt das Thema vor"),
                new Beat(Speaker.COHOST, "ordnet ein")));
    }

    // --- Stufe 3: gesprochener Text -----------------------------------------------------------------------------

    /**
     * Der gesprochene Text eines Abschnitts nach seinem Plan; nie leer. {@code before} ist der Plan des Abschnitts davor
     * ({@code null} am Anfang), damit der Übergang passt, auch wenn beide gleichzeitig entstehen.
     */
    public List<Turn> script(PodcastPlan plan, int topicIndex, Segment segment, Segment before, boolean last,
                             Voices voices, List<String> repairs) {
        Topic topic = plan.topics().get(topicIndex);
        StringBuilder prompt = new StringBuilder(planText(plan, voices));
        prompt.append("\n# ABSCHNITT\nThema ").append(topicIndex + 1).append(" \"").append(topic.title())
                .append("\", Abschnitt \"").append(segment.title()).append("\", Energie ")
                .append(String.format(Locale.ROOT, "%.1f", topic.energy())).append('\n');
        if (!topic.points().isEmpty()) {
            prompt.append("Kernpunkte: ").append(String.join(" | ", topic.points())).append('\n');
        }
        prompt.append(before == null ? "Davor: nichts, das ist der Anfang der Folge; begrüßt kurz.\n"
                : "Davor endete der Abschnitt \"" + before.title() + "\"; schließt natürlich daran an, ohne neu zu "
                + "begrüßen.\n");
        if (last) {
            prompt.append("Das ist der letzte Abschnitt: verabschiedet euch kurz.\n");
        }
        prompt.append("Beiträge in dieser Reihenfolge:\n");
        for (Beat beat : segment.beats()) {
            prompt.append("- ").append(name(beat.speaker(), voices)).append(": ").append(beat.intent()).append('\n');
        }
        prompt.append("\n# AUFGABE\nSchreibe genau diese Beiträge als gesprochenen Text, je Beitrag 1 bis 3 kurze Sätze, "
                + "höchstens ").append(MAX_TURN_CHARS).append(" Zeichen, Umgangssprache, keine Aufzählungen, keine "
                + "Abkürzungen, die man nicht sprechen kann, keine Regieanweisungen.\nAntworte mit genau einem "
                + "JSON-Objekt: {\"turns\":[{\"speaker\":\"host\",\"text\":\"...\"}]}");
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                List<Turn> turns = readTurns(model.complete(STYLE, prompt.toString()), segment);
                if (!turns.isEmpty()) {
                    return turns;
                }
                repairs.add("Abschnitt \"" + segment.title() + "\": Antwort " + attempt + " ohne Text");
            } catch (RuntimeException e) {
                repairs.add("Abschnitt \"" + segment.title() + "\": Versuch " + attempt + " gescheitert ("
                        + e.getClass().getSimpleName() + ")");
            }
            if (attempt < ATTEMPTS) {
                pause.before(attempt + 1);
            }
        }
        repairs.add("Abschnitt \"" + segment.title() + "\": Text ohne Modell aus den Kernpunkten");
        return fallbackScript(topic, before == null, last);
    }

    static List<Turn> readTurns(String answer, Segment segment) {
        JsonNode root = json(answer);
        List<Turn> turns = new ArrayList<>();
        if (root == null || !root.path("turns").isArray()) {
            return turns;
        }
        int index = 0;
        for (JsonNode node : root.path("turns")) {
            Speaker planned = index < segment.beats().size() ? segment.beats().get(index).speaker()
                    : turns.isEmpty() ? Speaker.HOST : turns.get(turns.size() - 1).speaker().other();
            String text = spoken(text(node, "text"));
            if (!text.isBlank()) {
                turns.add(new Turn(Speaker.parse(text(node, "speaker"), planned), text));
            }
            index++;
        }
        return turns;
    }

    /** Die Kernpunkte, abwechselnd vorgelesen; am Anfang eine Begrüßung, am Ende ein Abschied. */
    static List<Turn> fallbackScript(Topic topic, boolean first, boolean last) {
        List<Turn> turns = new ArrayList<>();
        Speaker speaker = Speaker.HOST;
        if (first) {
            turns.add(new Turn(speaker, "Hallo und willkommen! Heute schauen wir uns an, was sich getan hat."));
            speaker = speaker.other();
        }
        turns.add(new Turn(speaker, "Weiter geht es mit: " + topic.title() + "."));
        speaker = speaker.other();
        for (String point : topic.points()) {
            turns.add(new Turn(speaker, cut(spoken(point), MAX_TURN_CHARS)));
            speaker = speaker.other();
        }
        if (last) {
            turns.add(new Turn(speaker, "Das war es für heute. Danke fürs Zuhören und bis zum nächsten Mal!"));
        }
        return turns;
    }

    // --- Hilfen -------------------------------------------------------------------------------------------------

    /** Die Zusammenfassung, aus der der Podcast entsteht; nur Text, den der Aufrufer sehen darf. */
    public record Material(String text, List<String> statements, String scope) {
        public Material {
            text = text == null ? "" : text;
            statements = List.copyOf(statements);
        }

        String describe() {
            return scope == null || scope.isBlank() ? "" : " (" + scope + ")";
        }
    }

    /** Die Namen der beiden Stimmen, wie sie sich im Gespräch nennen. */
    public record Voices(String host, String cohost) {
        public Voices {
            host = host == null || host.isBlank() ? "Gastgeberin" : host.replace('_', ' ');
            cohost = cohost == null || cohost.isBlank() ? "Co-Host" : cohost.replace('_', ' ');
        }
    }

    private static String planText(PodcastPlan plan, Voices voices) {
        StringBuilder text = new StringBuilder("# PLAN DER FOLGE \"").append(plan.title()).append("\"\nStimmen: ")
                .append(voices.host()).append(" (host) und ").append(voices.cohost()).append(" (cohost)\n");
        for (int i = 0; i < plan.topics().size(); i++) {
            Topic topic = plan.topics().get(i);
            text.append(i + 1).append(". ").append(topic.title())
                    .append(topic.angle().isEmpty() ? "" : " – " + topic.angle()).append('\n');
        }
        return text.toString();
    }

    private static String name(Speaker speaker, Voices voices) {
        return speaker == Speaker.HOST ? voices.host() + " (host)" : voices.cohost() + " (cohost)";
    }

    /** Entfernt Sprecherpräfixe, Markdown und Regieanweisungen in Klammern, die eine Stimme sonst mitliest. */
    static String spoken(String text) {
        String result = text.replaceAll("(?s)\\*\\*|__|`|#+\\s", "")
                .replaceAll("\\[[^\\]]{0,60}\\]", "")
                .replaceAll("^\\s*\\w{1,20}\\s*:\\s+", "")
                .replaceAll("\\s+", " ").strip();
        return cut(result, MAX_TURN_CHARS * 2);
    }

    static JsonNode json(String answer) {
        if (answer == null) {
            return null;
        }
        int start = answer.indexOf('{');
        int end = answer.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return JSON.readTree(answer.substring(start, end + 1));
        } catch (JacksonException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.asString().strip() : value.isNumber() ? value.asString() : "";
    }

    private static List<String> strings(JsonNode node, String field) {
        List<String> values = new ArrayList<>();
        for (JsonNode value : node.path(field)) {
            if (value.isString() && !value.asString().isBlank()) {
                values.add(value.asString().strip());
            }
        }
        return values;
    }

    private static List<String> paragraphs(String text) {
        List<String> parts = new ArrayList<>();
        for (String part : text.split("\\n\\s*\\n")) {
            if (!part.isBlank()) {
                parts.add(part.strip());
            }
        }
        return parts;
    }

    private static String headline(String statement) {
        String flat = statement.replaceAll("\\s+", " ").strip();
        int end = flat.indexOf(". ");
        String first = end > 0 ? flat.substring(0, end) : flat;
        return cut(first, 60);
    }

    static String cut(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int space = text.lastIndexOf(' ', max - 1);
        return text.substring(0, space > max / 2 ? space : max - 1) + "…";
    }
}
