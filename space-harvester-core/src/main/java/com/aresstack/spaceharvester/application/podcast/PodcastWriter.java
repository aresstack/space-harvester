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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Schreibt den Podcast in drei Stufen über das Chat-Modell, wie das Audio-Studio (aresstack/audio) Musik plant: die
 * Grobübersicht ({@link #outline}), je Thema die Abschnitte mit Sprecherwechseln ({@link #segments}) und je Abschnitt den
 * gesprochenen Text ({@link #script}).
 *
 * <p>Nachrichtenformat (Angelo 21.11.2026): Das Material sind die Meldungen der Zusammenfassung, je Thema mit seiner
 * ausführlichen Fassung und dem Ort, an dem es steht. Die Grobübersicht bündelt zusammengehörige Meldungen und ordnet
 * sie, wie es am spannendsten ist (mit Aufmacher); jeder Abschnitt bekommt nur die Meldungen seines Themas im Volltext,
 * dazu die Titel der übrigen, damit nichts doppelt erzählt wird. Erzählt wird, was inhaltlich neu ist, mit einem kurzen
 * Verweis, wo es steht; Seitenzahlen, Größen und andere Metadaten sind kein Gesprächsstoff.
 *
 * <p>Selbstheilend: Jede Stufe fragt bis zu {@link #ATTEMPTS}-mal; eine unbrauchbare Antwort wird repariert, und gelingt
 * gar nichts, entsteht die Stufe ohne Modell aus den Meldungen selbst. Jede Reparatur steht in
 * {@link PodcastPlan#repairs()}.
 */
public final class PodcastWriter {

    /** Versuche je Stufe, bevor der Ersatz ohne Modell gilt. */
    public static final int ATTEMPTS = 3;
    /** Längster Text eines Beitrags; das liegt unter den rund 500 Zeichen je Stück des Stimmstabilitäts-Fixes. */
    static final int MAX_TURN_CHARS = 450;
    /** So viel einer Meldung geht in der Grobübersicht an das Modell. */
    static final int MAX_STORY_BRIEF_CHARS = 400;
    /** So viel einer Meldung geht in Abschnitt und Text an das Modell. */
    static final int MAX_STORY_CHARS = 3000;
    /** So viele Orte nennt eine Meldung höchstens. */
    static final int MAX_PLACES = 3;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * Was die Sprachausgabe nicht vorlesen kann (Angelo 22.11.2026: kryptische interne Bezeichner): umschreiben statt
     * buchstabieren. Die Leseansicht zeigt beim Abspielen ohnehin die Stelle, um die es geht.
     */
    static final String SPOKEN = "Alles wird vorgelesen: keine internen Kennungen und Bezeichner, keine Kürzel aus "
            + "Buchstaben und Ziffern, keine Projekt-, Auftrags- oder Ticketnummern, Aktenzeichen, Dateinamen, Pfade, "
            + "Klassen-, Tabellen- oder Benutzernamen, keine Fachkürzel, die man buchstabieren müsste. Sagt "
            + "stattdessen in einfachen Worten, was gemeint ist (etwa „ein Infrastrukturprojekt“ oder „das neue "
            + "Prüfwerkzeug für die Datenbanken“ statt der Kennung); ein gängiges Kürzel höchstens einmal, wenn es "
            + "ohne nicht geht.";
    static final String STYLE = "Du schreibst einen lockeren deutschen Nachrichten-Podcast im Stil von NotebookLM: zwei "
            + "Stimmen erzählen gut gelaunt, was es in einer Wissensquelle Neues gibt, als würden sie Kolleginnen und "
            + "Kollegen auf den neuesten Stand bringen. Sie erzählen anschaulich, WAS inhaltlich neu ist und was das "
            + "bedeutet, ordnen ein und reagieren aufeinander. Regeln: Nur Fakten aus dem Material, nichts erfinden, "
            + "keine Namen von Personen. Nie über Metadaten reden: keine Anzahl von Seiten oder Änderungen, keine "
            + "Größen, Kilobyte, Versionsnummern oder Zeitstempel, keine Feststellung, dass Seiten angelegt oder "
            + "geändert wurden, ohne zu sagen, was drinsteht. " + SPOKEN + " Antworte nur mit gültigem JSON, ohne "
            + "Markdown.";

    /**
     * Der Erklär-Podcast einer Seite (Angelo 21.11.2026): Überblick über das Wesentliche, nicht alles; Tabellen nur, wenn
     * sie etwas aussagen.
     */
    static final String EXPLAINER_STYLE = "Du schreibst einen lockeren deutschen Erklär-Podcast im Stil von NotebookLM: "
            + "zwei Stimmen erklären gut gelaunt, worum es auf einer Seite einer Wissensquelle und ihren Unterseiten "
            + "geht, als würden sie Kolleginnen und Kollegen einen Überblick geben. Sie erzählen das Wesentliche "
            + "anschaulich: wozu es das gibt, wie es funktioniert, was man wissen und beachten muss, mit Beispielen aus "
            + "dem Material. Regeln: Überblick statt Vollständigkeit; Unwichtiges, Wiederholungen, Inhaltsverzeichnisse "
            + "und Änderungshistorien weglassen. Tabellen nie vorlesen: aus einer Tabelle höchstens die Aussage in einem "
            + "Satz, keine Zahlenkolonnen. Nur Fakten aus dem Material, nichts erfinden, keine Namen von Personen. Nie "
            + "über Metadaten reden: keine Anzahl von Seiten, keine Größen, Versionsnummern oder Zeitstempel. "
            + SPOKEN + " Antworte nur mit gültigem JSON, ohne Markdown.";

    /**
     * Der Erklär-Podcast eines ganzen Bereichs (Angelo 24.11.2026, Umschalter „Änderungen | Bereich“): für Neue, die die
     * Seiten noch nie besucht haben; erzählt die Bereichszusammenfassung der Leseansicht didaktisch nach.
     */
    static final String AREA_STYLE = "Du schreibst einen lockeren deutschen Erklär-Podcast im Stil von NotebookLM: zwei "
            + "Stimmen führen gut gelaunt durch einen ganzen Bereich einer Wissensquelle, als würden sie neuen "
            + "Kolleginnen und Kollegen am ersten Tag zeigen, worum es dort geht. Sie erklären didaktisch: erst wozu der "
            + "Bereich da ist und wie er aufgebaut ist, dann die wichtigsten Themen mit Beispielen aus dem Material, "
            + "Fachbegriffe in einfachen Worten, und am Ende, wo man am besten anfängt. Regeln: Überblick statt "
            + "Vollständigkeit; nur Fakten aus dem Material, nichts erfinden, keine Namen von Personen. Nie über "
            + "Metadaten reden: keine Anzahl von Seiten, keine Größen, Versionsnummern oder Zeitstempel. " + SPOKEN
            + " Antworte nur mit gültigem JSON, ohne Markdown.";

    /** Wofür eine Folge entsteht: Neuigkeiten eines Updates, die Erklärung einer Seite oder eines Bereichs. */
    public enum Format {
        /** Was es Neues gibt: jede Meldung genau einmal, Reihenfolge nach Spannung. */
        NEWS(STYLE, "Neuigkeiten", "Meldung", "Meldungen", "Was gibt es Neues bei uns",
                "Das erste Thema ist ein kurzer Einstieg mit Aufmacher: macht neugierig auf die spannendste Meldung.",
                "jede Meldung kommt in genau einem Thema vor. Ordne so, wie es am spannendsten und interessantesten "
                        + "ist, nicht nach Umfang.",
                "inhaltlich neu ist und was es bedeutet", "Heute schauen wir uns an, was es Neues gibt.",
                "Sagt nie, wo etwas steht: keine Namen von Spaces, Seiten oder Bereichen; die Stelle zeigt der Bericht "
                        + "auf dem Bildschirm (Angelo 22.11.2026). ", true, false),
        /** Was auf einer Seite steht: das Wesentliche, Unwichtiges darf wegfallen. */
        EXPLAINER(EXPLAINER_STYLE, "Seite zum Erklären", "Teil", "Teile", "Kurz erklärt",
                "Das erste Thema ist ein kurzer Einstieg: worum es geht und wozu man das braucht.",
                "jeder Teil kommt höchstens in einem Thema vor; Teile ohne wesentliche Aussage (reine Tabellen, "
                        + "Verzeichnisse, Historie, Formales) lässt du weg. Ordne so, dass man es gut versteht: erst das "
                        + "Große und Ganze, dann die Einzelheiten.",
                "erklären: das Wesentliche verständlich und mit Beispielen aus dem Material, statt alles aufzuzählen",
                "Heute erklären wir, worum es auf dieser Seite geht.",
                "Steht etwas auf einer Unterseite, nennt sie einmal knapp; die erklärte Seite selbst nennt ihr nicht "
                        + "immer wieder. ", false, true),
        /** Was ein ganzer Bereich enthält, für Neue: erst das Große und Ganze, dann die Themen. */
        AREA(AREA_STYLE, "Bereich zum Erklären", "Thema", "Themen", "Kurz erklärt",
                "Das erste Thema ist ein kurzer Einstieg: wozu es den Bereich gibt und was man dort findet.",
                "jedes Thema kommt höchstens einmal vor; Themen ohne wesentliche Aussage lässt du weg. Ordne so, dass "
                        + "man den Bereich gut versteht: erst das Große und Ganze, dann die Einzelheiten.",
                "erklären: das Wesentliche verständlich und mit Beispielen aus dem Material, statt alles aufzuzählen",
                "Heute erklären wir, worum es in diesem Bereich geht.",
                "Nennt eine Stelle nur, wenn sie beim Zurechtfinden hilft, und dann knapp. ", false, true);

        final String style;
        final String heading;
        final String one;
        final String many;
        final String episode;
        final String opening;
        final String coverage;
        final String content;
        final String greeting;
        /** Wie oft und wie der Ort vorkommt. */
        final String place;
        /** Jede Meldung muss vorkommen (fehlende werden ergänzt). */
        final boolean complete;
        /**
         * Ob das Modell erfährt, wo etwas steht (Space, Seite, Elternseiten). Die Neuigkeiten nicht: Verweise und
         * Space-Namen gehören nicht in den Sprechtext, die Stelle markiert die Leseansicht (Angelo 22.11.2026).
         */
        final boolean places;

        Format(String style, String heading, String one, String many, String episode, String opening,
               String coverage, String content, String greeting, String place, boolean complete, boolean places) {
            this.style = style;
            this.heading = heading;
            this.one = one;
            this.many = many;
            this.episode = episode;
            this.opening = opening;
            this.coverage = coverage;
            this.content = content;
            this.greeting = greeting;
            this.place = place;
            this.complete = complete;
            this.places = places;
        }
    }

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

    /** Die Grobübersicht aus den Meldungen; nie leer, jede Meldung genau einem Thema zugeordnet. */
    public PodcastPlan outline(Material material) {
        Format format = material.format();
        StringBuilder prompt = new StringBuilder("# MATERIAL: ").append(format.heading).append(material.describe())
                .append('\n');
        if (!material.overview().isBlank()) {
            prompt.append("Überblick: ").append(cut(flat(material.overview()), 1200)).append('\n');
        }
        prompt.append(format.many).append(":\n");
        List<Story> stories = material.stories();
        for (int i = 0; i < stories.size(); i++) {
            Story story = stories.get(i);
            prompt.append('[').append(i + 1).append("] ").append(story.title()).append(": ")
                    .append(cut(flat(story.text()), MAX_STORY_BRIEF_CHARS));
            if (!material.place(story).isEmpty()) {
                prompt.append(" (").append(material.place(story)).append(')');
            }
            prompt.append('\n');
        }
        prompt.append("\n# AUFGABE\nPlane eine Folge \"").append(material.show() == null ? format.episode
                        : material.show()).append("\": einen packenden Titel "
                + "und 3 bis ").append(PodcastPlan.MAX_TOPICS).append(" Themen in Sendereihenfolge. ")
                .append(format.opening).append(" Das letzte ist ein kurzer Abschluss. Dazwischen erzählt jedes Thema ")
                .append(format == Format.NEWS ? "eine " : "einen ").append(format.one).append(" oder mehrere, die inhaltlich zusammenpassen; ")
                .append(format.coverage).append(" title nennt den Inhalt, nie eine Anzahl. angle: ein Satz, was daran "
                + "neu oder wichtig ist. energy: 0 ruhig und einordnend bis 1 lebhaft. stories: die Nummern der ")
                .append(format.many).append(", beim Einstieg und Abschluss leer.\nAntworte mit genau einem "
                + "JSON-Objekt: {\"title\":\"...\","
                + "\"topics\":[{\"title\":\"...\",\"angle\":\"ein Satz\",\"energy\":0.5,\"stories\":[1]}]}");
        List<String> repairs = new ArrayList<>();
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                PodcastPlan plan = readOutline(model.complete(format.style, prompt.toString()), stories.size(),
                        format.complete, repairs);
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
        repairs.add("Grobübersicht ohne Modell aus den Meldungen");
        return fallbackOutline(material, repairs);
    }

    /**
     * Liest die Grobübersicht und sorgt dafür, dass jede Meldung genau einmal erzählt wird: doppelte Nummern fallen weg,
     * vergessene Meldungen bekommen ein eigenes Thema vor dem Abschluss (oder gehen zum letzten Thema davor, wenn kein
     * Platz ist).
     */
    static PodcastPlan readOutline(String answer, int storyCount, List<String> repairs) {
        return readOutline(answer, storyCount, true, repairs);
    }

    /** @param complete ob jede Meldung vorkommen muss; sonst dürfen unwichtige Teile fehlen (Erklär-Podcast) */
    static PodcastPlan readOutline(String answer, int storyCount, boolean complete, List<String> repairs) {
        JsonNode root = json(answer);
        if (root == null || !root.path("topics").isArray()) {
            return null;
        }
        List<Topic> topics = new ArrayList<>();
        Set<Integer> told = new LinkedHashSet<>();
        for (JsonNode node : root.path("topics")) {
            String title = text(node, "title");
            if (title.isBlank()) {
                repairs.add("Grobübersicht: Thema ohne Titel übergangen");
                continue;
            }
            List<Integer> stories = new ArrayList<>();
            for (JsonNode number : node.path("stories")) {
                int story = number.asInt(0);
                if (story >= 1 && story <= storyCount && told.add(story)) {
                    stories.add(story);
                }
            }
            topics.add(new Topic(title, text(node, "angle"), node.path("energy").asDouble(0.5), strings(node,
                    "points"), stories));
        }
        if (topics.isEmpty()) {
            return null;
        }
        if (topics.size() > PodcastPlan.MAX_TOPICS) {
            repairs.add("Grobübersicht: " + topics.size() + " Themen auf " + PodcastPlan.MAX_TOPICS + " gekürzt");
            List<Topic> kept = new ArrayList<>(topics.subList(0, PodcastPlan.MAX_TOPICS - 1));
            kept.add(topics.get(topics.size() - 1)); // der Abschluss bleibt
            told.clear();
            kept.forEach(topic -> told.addAll(topic.stories()));
            topics = kept;
        }
        List<Integer> missing = new ArrayList<>();
        for (int story = 1; story <= storyCount; story++) {
            if (!told.contains(story)) {
                missing.add(story);
            }
        }
        if (complete && !missing.isEmpty()) {
            repairs.add("Grobübersicht: Meldungen " + missing + " ergänzt");
            topics = withMissing(topics, missing);
        }
        return new PodcastPlan(text(root, "title"), topics, repairs);
    }

    /** Vergessene Meldungen: je eine als Thema vor dem Abschluss, bei vollem Plan an das letzte mittlere Thema. */
    private static List<Topic> withMissing(List<Topic> topics, List<Integer> missing) {
        List<Topic> result = new ArrayList<>(topics);
        int closing = result.size() >= 2 ? result.size() - 1 : result.size();
        for (int story : missing) {
            if (result.size() < PodcastPlan.MAX_TOPICS) {
                result.add(closing, new Topic("Außerdem neu", "", 0.5, List.of(), List.of(story)));
                closing++;
            } else {
                int target = Math.max(1, closing - 1);
                Topic topic = result.get(target);
                List<Integer> stories = new ArrayList<>(topic.stories());
                stories.add(story);
                result.set(target, new Topic(topic.title(), topic.angle(), topic.energy(), topic.points(), stories));
            }
        }
        return result;
    }

    /** Ein Einstieg, je Meldung ein Thema (gebündelt, wenn es mehr sind als Platz), ein Abschluss. */
    static PodcastPlan fallbackOutline(Material material, List<String> repairs) {
        List<Topic> topics = new ArrayList<>();
        topics.add(new Topic("Einstieg", "Worum es heute geht", 0.6, List.of(), List.of()));
        int slots = PodcastPlan.MAX_TOPICS - 2;
        List<Story> stories = material.stories();
        for (int slot = 0; slot < Math.min(slots, stories.size()); slot++) {
            List<Integer> numbers = new ArrayList<>();
            for (int i = slot; i < stories.size(); i += slots) {
                numbers.add(i + 1);
            }
            topics.add(new Topic(stories.get(slot).title(), "", 0.5, List.of(), numbers));
        }
        topics.add(new Topic("Abschluss", "Was man mitnehmen sollte", 0.4, List.of(), List.of()));
        return new PodcastPlan(null, topics, repairs);
    }

    // --- Stufe 2: Abschnitte je Thema ---------------------------------------------------------------------------

    /** Die Abschnitte eines Themas mit ihren Sprecherwechseln; nie leer. */
    public List<Segment> segments(Material material, PodcastPlan plan, int topicIndex, Voices voices,
                                  List<String> repairs) {
        Topic topic = plan.topics().get(topicIndex);
        boolean edge = topicIndex == 0 || topicIndex == plan.topics().size() - 1;
        String prompt = planText(plan, voices) + topicMaterial(material, plan, topicIndex) + "\n# AUFGABE\nPlane Thema "
                + (topicIndex + 1) + " \"" + topic.title() + "\" genauer: "
                + (edge ? "genau 1 kurzer Abschnitt" : "1 bis " + PodcastPlan.MAX_SEGMENTS + " Abschnitte, je nach "
                + "Stoff; jeder Abschnitt erzählt etwas anderes. Zusammen erzählen die Abschnitte jeden wichtigen "
                + "Fakt der " + material.format().many + " (jede Regel, jede Stufe, jeden Schritt eines Ablaufs), "
                + "keinen doppelt")
                + ", je Abschnitt 2 bis " + PodcastPlan.MAX_BEATS + " Wortbeiträge mit Sprecherwechsel. speaker ist "
                + "\"host\" (" + voices.host() + ", führt durch die Folge) oder \"cohost\" (" + voices.cohost()
                + ", fragt nach, ordnet ein). intent sagt konkret, welchen Inhalt der Beitrag erzählt (etwa \"erklärt, "
                + "was die neue Prüfung vor dem Start verhindert\"), nie Metadaten. Der letzte Beitrag eines "
                + "Abschnitts ist nie eine offene Frage.\nAntworte mit genau einem "
                + "JSON-Objekt: {\"segments\":[{\"title\":\"...\",\"beats\":[{\"speaker\":\"host\",\"intent\":"
                + "\"...\"}]}]}";
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                List<Segment> segments = readSegments(model.complete(material.format().style, prompt), edge,
                        repairs);
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
        return new Segment(topic.title(), List.of(new Beat(Speaker.HOST, "erzählt, was neu ist"),
                new Beat(Speaker.COHOST, "ordnet ein, was das bedeutet")));
    }

    // --- Stufe 3: gesprochener Text -----------------------------------------------------------------------------

    /**
     * Der gesprochene Text eines Abschnitts nach seinem Plan; nie leer.
     *
     * @param segments alle Abschnitte des Themas, damit jeder seinen Teil erzählt und nicht den der anderen
     * @param before   der Plan des Abschnitts davor ({@code null} am Anfang), damit der Übergang passt, auch wenn beide
     *                 gleichzeitig entstehen
     */
    public List<Turn> script(Material material, PodcastPlan plan, int topicIndex, List<Segment> segments,
                             int segmentIndex, Segment before, boolean last, Voices voices, List<String> repairs) {
        Topic topic = plan.topics().get(topicIndex);
        Segment segment = segments.get(segmentIndex);
        boolean first = topicIndex == 0 && segmentIndex == 0;
        StringBuilder prompt = new StringBuilder(planText(plan, voices));
        prompt.append(topicMaterial(material, plan, topicIndex));
        prompt.append("\n# ABSCHNITT\nThema ").append(topicIndex + 1).append(" \"").append(topic.title())
                .append("\", Abschnitt ").append(segmentIndex + 1).append(" von ").append(segments.size())
                .append(" \"").append(segment.title()).append("\", Energie ")
                .append(String.format(Locale.ROOT, "%.1f", topic.energy())).append('\n');
        if (segments.size() > 1) {
            List<String> others = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                if (i != segmentIndex) {
                    others.add("\"" + segments.get(i).title() + "\"");
                }
            }
            prompt.append("Die anderen Abschnitte dieses Themas erzählen ").append(String.join(", ", others))
                    .append("; das gehört nicht hierher.\n");
        }
        prompt.append(first ? "Davor: nichts, das ist der Anfang der Folge; begrüßt kurz" + greeting(material) + ".\n"
                : "Davor endete der Abschnitt \"" + (before == null ? "" : before.title()) + "\"; schließt natürlich "
                + "daran an, ohne neu zu begrüßen und ohne den Überblick zu wiederholen.\n");
        if (last) {
            prompt.append("Das ist der letzte Abschnitt: verabschiedet euch kurz.\n");
        }
        prompt.append("Beiträge in dieser Reihenfolge:\n");
        for (Beat beat : segment.beats()) {
            prompt.append("- ").append(name(beat.speaker(), voices)).append(": ").append(beat.intent()).append('\n');
        }
        prompt.append("\n# AUFGABE\nSchreibe genau diese Beiträge als gesprochenen Text, je Beitrag 1 bis 3 kurze Sätze, "
                + "höchstens ").append(MAX_TURN_CHARS).append(" Zeichen, Umgangssprache. ");
        if (topic.stories().isEmpty()) {
            prompt.append("Erzählt keine ").append(material.format().many).append(" im Detail, die kommen in "
                    + "ihren eigenen Themen. ");
        } else {
            prompt.append("Erzählt anschaulich, was die ").append(material.format().many)
                    .append(" dieses Themas ").append(material.format().content).append(", mit den konkreten "
                    + "Fakten (Regeln, Stufen, Schritte) statt allgemeiner Worte. ").append(material.format().place);
            if (segmentIndex == segments.size() - 1) {
                prompt.append("Das ist der letzte Abschnitt des Themas: was aus seinen ")
                        .append(material.format().many).append(" Wichtiges noch nicht erzählt ist, gehört hierher. ");
            }
        }
        prompt.append("Jede Frage wird im selben Abschnitt beantwortet; der letzte Beitrag ist keine Frage. ");
        prompt.append("Keine Anzahl von Seiten oder Änderungen, keine Größen, keine Aufzählungen, keine Abkürzungen, "
                + "die man nicht sprechen kann, keine Regieanweisungen.\nAntworte mit genau einem JSON-Objekt: "
                + "{\"turns\":[{\"speaker\":\"host\",\"text\":\"...\"}]}");
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                List<Turn> turns = readTurns(model.complete(material.format().style, prompt.toString()), segment);
                if (!turns.isEmpty()) {
                    return withoutOpenQuestion(turns, segment, repairs);
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
        repairs.add("Abschnitt \"" + segment.title() + "\": Text ohne Modell aus den Meldungen");
        return fallbackScript(material, topic, first, last);
    }

    /**
     * Ein Abschnitt endet nie mit einer offenen Frage: Die Abschnitte entstehen gleichzeitig, der nächste beantwortet sie
     * also nicht. Eine Frage am Ende fällt weg.
     */
    static List<Turn> withoutOpenQuestion(List<Turn> turns, Segment segment, List<String> repairs) {
        List<Turn> result = new ArrayList<>(turns);
        while (result.size() > 1 && result.get(result.size() - 1).text().strip().endsWith("?")) {
            result.remove(result.size() - 1);
            repairs.add("Abschnitt \"" + segment.title() + "\": offene Frage am Ende weggelassen");
        }
        return result;
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

    /** Die Meldungen des Themas, abwechselnd vorgelesen; am Anfang eine Begrüßung, am Ende ein Abschied. */
    static List<Turn> fallbackScript(Material material, Topic topic, boolean first, boolean last) {
        List<Turn> turns = new ArrayList<>();
        Speaker speaker = Speaker.HOST;
        if (first) {
            turns.add(new Turn(speaker, (material.show() == null ? "Hallo und willkommen! "
                    : "Hallo und willkommen zum " + material.show() + "! ") + material.format().greeting));
            speaker = speaker.other();
        }
        List<Story> stories = material.stories(topic);
        if (stories.isEmpty()) {
            turns.add(new Turn(speaker, first ? "Los geht es mit: " + topic.title() + "." : topic.title() + "."));
            speaker = speaker.other();
        }
        for (Story story : stories) {
            // Der Ort hat höchstens ein Drittel des Beitrags: lange Seitentitel lassen dem Inhalt immer Platz.
            String where = material.place(story).isEmpty() ? ""
                    : " Zu finden " + cut(material.place(story), MAX_TURN_CHARS / 3) + ".";
            turns.add(new Turn(speaker, cut(spoken(story.title() + ": " + flat(story.text())), MAX_TURN_CHARS
                    - where.length()) + where));
            speaker = speaker.other();
        }
        for (String point : topic.points()) {
            turns.add(new Turn(speaker, cut(spoken(point), MAX_TURN_CHARS)));
            speaker = speaker.other();
        }
        if (last) {
            turns.add(new Turn(speaker, "Das war es für heute. Danke fürs Zuhören und bis zum nächsten Mal!"));
        }
        return turns;
    }

    // --- Material -----------------------------------------------------------------------------------------------

    /**
     * Woraus der Podcast entsteht; nur Text, den der Aufrufer sehen darf.
     *
     * @param overview der Überblick in wenigen Sätzen
     * @param stories  die Meldungen, je ein Thema der Zusammenfassung
     * @param scope    die Ebene, etwa „Space Betrieb“ oder „Seite „Handbuch““, {@code null} für die ganze Quelle
     * @param format   Neuigkeiten oder Erklärung einer Seite
     * @param show     wie die Sendung heißt, nach der Art der Quelle, etwa „Confluence-Update“ (Angelo 22.11.2026);
     *                 nie ein Servername, {@code null} ohne Namen
     */
    /**
     * @param storyChars so viel vom Text jeder Meldung geht beim Planen der Abschnitte und beim Schreiben an das Modell
     *                   (Seiten-Podcast: die Einstellung „Podcast – Lesen“, sonst {@link #MAX_STORY_CHARS})
     */
    public record Material(String overview, List<Story> stories, String scope, Format format, String show,
                           int storyChars) {
        public Material {
            overview = overview == null ? "" : overview;
            stories = List.copyOf(stories);
            format = format == null ? Format.NEWS : format;
            show = show == null || show.isBlank() ? null : show.strip();
            if (storyChars < 1) {
                throw new IllegalArgumentException("storyChars muss mindestens 1 sein");
            }
        }

        public Material(String overview, List<Story> stories, String scope, Format format, String show) {
            this(overview, stories, scope, format, show, MAX_STORY_CHARS);
        }

        public Material(String overview, List<Story> stories, String scope, Format format) {
            this(overview, stories, scope, format, null);
        }

        /** Dasselbe Material für die Sendung {@code show}. */
        public Material withShow(String show) {
            return new Material(overview, stories, scope, format, show, storyChars);
        }

        /** Dasselbe Material, von jeder Meldung bis zu {@code chars} Zeichen an das Modell. */
        public Material withStoryChars(int chars) {
            return new Material(overview, stories, scope, format, show, chars);
        }

        /** Neuigkeiten eines Updates. */
        public Material(String overview, List<Story> stories, String scope) {
            this(overview, stories, scope, Format.NEWS);
        }

        /** Die Ebene für das Modell; leer, wenn das Format keine Orte nennt. */
        String describe() {
            return !format.places || scope == null || scope.isBlank() ? "" : " (" + scope + ")";
        }

        /** Wo die Meldung steht, für das Modell; leer, wenn das Format keine Orte nennt. */
        String place(Story story) {
            return format.places ? story.place() : "";
        }

        /** Alle Seiten, deren Text in die Meldungen einging, ohne Wiederholung. */
        public List<String> pageIds() {
            java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
            stories.forEach(story -> ids.addAll(story.pageIds()));
            return List.copyOf(ids);
        }

        /** Die Meldungen eines Themas, ungültige Nummern übergangen. */
        public List<Story> stories(Topic topic) {
            List<Story> result = new ArrayList<>();
            for (int number : topic.stories()) {
                if (number >= 1 && number <= stories.size()) {
                    result.add(stories.get(number - 1));
                }
            }
            return result;
        }
    }

    /**
     * Eine Meldung: ein Thema der Zusammenfassung.
     *
     * @param title   kurzer Titel
     * @param text    der Inhalt, die ausführliche Fassung, wenn es sie gibt
     * @param places  wo es steht, etwa „auf der Seite „Eskalation“ im Space Betrieb, unter Handbuch“
     * @param pageIds die Seiten der Meldung (für Bilder und Verweise), in der Reihenfolge der Zusammenfassung
     */
    public record Story(String title, String text, List<String> places, List<String> pageIds) {
        public Story {
            title = title == null || title.isBlank() ? "Neu" : title.strip();
            text = text == null ? "" : text.strip();
            places = places == null ? List.of() : List.copyOf(places);
            pageIds = pageIds == null ? List.of() : List.copyOf(pageIds);
        }

        public Story(String title, String text, List<String> places) {
            this(title, text, places, List.of());
        }

        /** Die Orte als ein Halbsatz, höchstens {@link #MAX_PLACES}. */
        String place() {
            if (places.isEmpty()) {
                return "";
            }
            List<String> shown = places.subList(0, Math.min(MAX_PLACES, places.size()));
            return String.join("; ", shown) + (places.size() > MAX_PLACES ? " und weitere Seiten" : "");
        }
    }

    /** Die Namen der beiden Stimmen, wie sie sich im Gespräch nennen. */
    public record Voices(String host, String cohost) {
        public Voices {
            host = host == null || host.isBlank() ? "Gastgeberin" : host.replace('_', ' ');
            cohost = cohost == null || cohost.isBlank() ? "Co-Host" : cohost.replace('_', ' ');
        }
    }

    // --- Hilfen -------------------------------------------------------------------------------------------------

    /**
     * Wie die Begrüßung die Sendung nennt (Angelo 22.11.2026): nach der Art der Quelle, etwa „Willkommen zum
     * Confluence-Update“, nie mit einem selbst erdachten Sendungsnamen oder einem Servernamen.
     */
    static String greeting(Material material) {
        return material.show() == null ? "" : " mit „Willkommen zum " + material.show() + "“; so heißt die Sendung, "
                + "nennt sie nicht anders und nennt keine Server- oder Rechnernamen";
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

    /**
     * Was ein Thema erzählen darf: seine Meldungen im Volltext mit Ort. Der Einstieg kennt den Überblick und die
     * Titel der Themen, die folgen; der Abschluss nur die Titel.
     */
    static String topicMaterial(Material material, PodcastPlan plan, int topicIndex) {
        Topic topic = plan.topics().get(topicIndex);
        List<Story> stories = material.stories(topic);
        StringBuilder text = new StringBuilder("\n# MATERIAL DIESES THEMAS").append(material.describe()).append('\n');
        if (stories.isEmpty()) {
            if (topicIndex == 0 && !material.overview().isBlank()) {
                text.append("Überblick: ").append(cut(flat(material.overview()), 1200)).append('\n');
            }
            text.append(topicIndex == 0 ? "Macht neugierig auf die folgenden Themen, ohne sie schon zu erzählen.\n"
                    : "Rundet die Folge ab: nennt zu jedem Thema der Folge in einem Halbsatz konkret, was man "
                    + "mitnehmen sollte, ohne allgemeine Worte wie Effizienz oder Struktur.\n");
            if (topicIndex > 0) {
                for (Story story : material.stories()) {
                    text.append("- ").append(story.title()).append('\n');
                }
            }
            return text.toString();
        }
        for (Story story : stories) {
            text.append(material.format().one).append(" \"").append(story.title()).append("\"");
            if (!material.place(story).isEmpty()) {
                text.append(" – steht ").append(material.place(story));
            }
            text.append(":\n").append(cut(story.text(), material.storyChars())).append('\n');
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

    static String flat(String text) {
        return text.replaceAll("\\s+", " ").strip();
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

    static String cut(String text, int max) {
        if (max < 2) {
            return text.isEmpty() ? text : "…";
        }
        if (text.length() <= max) {
            return text;
        }
        int space = text.lastIndexOf(' ', max - 1);
        return text.substring(0, space > max / 2 ? space : max - 1) + "…";
    }
}
