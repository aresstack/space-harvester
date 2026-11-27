package com.aresstack.spaceharvester.application.changelog;

import com.aresstack.spaceharvester.ai.AiOutputTruncatedException;
import com.aresstack.spaceharvester.ai.AiServiceException;
import com.aresstack.spaceharvester.ai.ModelRetry;
import com.aresstack.spaceharvester.application.jobs.JobCancelledException;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobFailedException;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Die thematische Zusammenfassung über eine Seitenmenge (ADR 0030/0031, verallgemeinert nach ADR 0034, Abschnitt 7):
 * eine Implementierung für Teilen, Plan vorab, parallele Teile, stufenweises Zusammenführen, Prüfung der Seiten-IDs und
 * Ausgabeform. Was je Seite an das Modell geht und wie die Anweisungen lauten, bestimmt die Materialart ({@link Prompts}
 * und {@link Material}), heute die Änderungen eines Updates ({@link UpdateSummarizer}). Seiten heißen für das Modell
 * über einen Alias ({@link #aliases}), damit Seiten mehrerer Quellen nicht zusammenfallen. Ist die Eingabe größer als
 * {@link #MAX_REQUEST_CHARS}, wird in Teilen zusammengefasst und stufenweise zusammengeführt; beim Zusammenführen nennt
 * das Modell nur, welche Themen zusammengehören, Seiten und Details werden hier vereinigt, so geht keine Seite verloren.
 * Jede Antwort wird gegen die gefragten Seiten geprüft; unbekannte Seiten fallen weg. Ins Log kommt nichts davon.
 */
final class ThematicSummarizer {

    static final String COMPONENT = "ChangeLog";
    static final String PHASE = "Zusammenfassen";
    /**
     * Zeichen der Eingabe je Aufruf; darüber in Teilen und stufenweise zusammengeführt. Hält für jeden Aufruf: Rahmen
     * plus eine Seite (bei Updates {@link UpdateSummarizer#MAX_PAGE_DIFF_CHARS} und
     * {@link UpdateSummarizer#MAX_EXCERPT_CHARS}), und beim Zusammenführen Rahmen ohne Spaces plus zwei Teilantworten.
     */
    static final int MAX_REQUEST_CHARS = 32_000;
    /** Kleinster Teil, wenn die Seiten auf gleichzeitige Anfragen verteilt werden; kleinere lohnen den Aufruf nicht. */
    static final int MIN_PART_CHARS = 8_000;
    /** Themen je Antwort. */
    static final int MAX_STATEMENTS = 30;
    /** Themen der fertigen Zusammenfassung, auch wenn beim Zusammenführen nicht zugeordnete Themen angehängt werden. */
    static final int MAX_TOPICS = 60;
    static final int MAX_STATEMENT_PAGES = 200;
    static final int MAX_PARTIAL_TEXT_CHARS = 2000;
    static final int MAX_STATEMENT_CHARS = 800;
    static final int MAX_TOPIC_TITLE_CHARS = 120;
    static final int MAX_DETAILS_CHARS = 3000;
    static final int MAX_TEXT_CHARS = 4000;
    static final int MAX_ANSWER_CHARS = 128 * 1024;
    /** Beispieltitel je Thema in der Eingabe des Zusammenführens; die vollständige Seitenliste bleibt hier. */
    private static final int MAX_COMBINE_EXAMPLES = 3;
    private static final int MAX_COMBINE_TITLE_CHARS = 120;
    private static final int MAX_COMBINE_TEXT_CHARS = 400;
    private static final int MAX_COMBINE_BRIEF_CHARS = 120;
    static final JsonMapper JSON = JsonMapper.builder().build();
    /** Zeichen des Anrisses je Seite im Plan. */
    static final int MAX_PLAN_TEASER_CHARS = 200;
    /** Zeichen der Plan-Eingabe; darüber ohne Anriss, und reicht auch das nicht, ohne Plan wie bisher. */
    static final int MAX_PLAN_CHARS = 60_000;
    /**
     * Zeichen einer geplanten Teilaufgabe: kleine Häppchen, damit die Teilaufgaben auch mit Nachdenken im
     * Ausgabelimit bleiben (Angelo 22.11.2026).
     */
    static final int PLANNED_PART_CHARS = 12_000;

    /**
     * Was eine Materialart dem Modell sagt.
     *
     * @param instructions Anweisung für einen Teil
     * @param combine      Anweisung für das Zusammenführen von Teilantworten
     * @param plan         Anweisung für den Plan vorab
     * @param listField    Feld der Teil-Eingabe mit der Liste der Seiten
     * @param planFields   Felder einer Seite, die in den Plan gehen
     * @param restTopic    Titel des Themas für Seiten, die der Plan keinem Thema zuordnet
     */
    record Prompts(String instructions, String combine, String plan, String listField, List<String> planFields,
                   String restTopic) {

        Prompts {
            Objects.requireNonNull(instructions, "instructions");
            Objects.requireNonNull(combine, "combine");
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(listField, "listField");
            planFields = List.copyOf(planFields);
            Objects.requireNonNull(restTopic, "restTopic");
        }
    }

    /** Ein geplantes Thema mit seinen Seiten. */
    record Planned(String title, List<Material> materials) {
    }

    /** Das Ergebnis, bereit zur Ablage. */
    record Result(String text, List<StoredSummary.Statement> statements, int unreadable,
                  List<StoredSummary.InputPage> inputPages, int lost) {

        Result(String text, List<StoredSummary.Statement> statements, int unreadable,
               List<StoredSummary.InputPage> inputPages) {
            this(text, statements, unreadable, inputPages, 0);
        }
    }

    /**
     * Eine Seite, wie sie an das Modell geht.
     *
     * @param page      die Seite
     * @param id        ihr Alias für das Modell ({@link #aliases}); steht so in {@code node} und in den Themen
     * @param spaceKey  ihr Space, für die Rechteprüfung beim Lesen
     * @param spaceName Name ihres Space für das Zusammenführen, sonst der Schlüssel
     * @param title     ihr Titel, {@code null} wenn unbekannt
     * @param node      was das Modell von ihr bekommt
     * @param unknown   ob ihr Inhalt unbekannt war; zählt wie nicht lesbar
     * @param content   ob Inhalt von ihr einging; dann muss der Leser den Inhalt lesen dürfen
     */
    record Material(PageKey page, String id, String spaceKey, String spaceName, String title, ObjectNode node,
                    boolean unknown, boolean content) {

        Material {
            Objects.requireNonNull(page, "page");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(node, "node");
        }
    }

    /**
     * Wie die Anfragen eines Laufs ausgeführt werden: im Auftrag selbst (bis {@code ai.parallel} zugleich) oder als
     * Unteraufträge unter dem Auftrag „Zusammenfassen“ (Angelo 22.11.2026: Oberauftrag mit Unteraufträgen).
     */
    interface Runner {

        /**
         * Wendet {@code work} auf jeden Eintrag an und liefert die Ergebnisse in der Reihenfolge der Einträge; der
         * erste Fehler beendet den Lauf. {@code work} bekommt den Auftrag, in dem es läuft, für Fortschritt und Abbruch.
         */
        <T, R> List<R> run(List<T> items, IntFunction<String> title, BiFunction<T, JobContext, R> work,
                           JobContext context);

        /**
         * Wie {@link #run(List, IntFunction, BiFunction, JobContext)}; {@code size} sagt, wie viel ein Eintrag umfasst
         * (Seiten eines Teils, Teilergebnisse eines Zusammenführens). Ein Unterauftrag zählt damit seinen echten
         * Fortschritt statt „1/1“ (Angelo 27.11.2026); im Auftrag selbst zählen weiter die Anfragen.
         */
        default <T, R> List<R> run(List<T> items, IntFunction<String> title, java.util.function.ToIntFunction<T> size,
                                   BiFunction<T, JobContext, R> work, JobContext context) {
            return run(items, title, work, context);
        }
    }

    private final SummaryModel model;
    private final Runner runner;
    private final SummaryModel planner;
    private final Prompts prompts;

    /**
     * @param runner  wie die Anfragen laufen, {@code null} im Auftrag selbst
     * @param planner Modell für den Plan vorab (eigene Einstellungszeile „Zusammenfassung – Plan“), {@code null} ohne
     *                Plan
     */
    ThematicSummarizer(SummaryModel model, Runner runner, SummaryModel planner, Prompts prompts) {
        this.model = Objects.requireNonNull(model, "model");
        this.runner = runner;
        this.planner = planner;
        this.prompts = Objects.requireNonNull(prompts, "prompts");
    }

    /**
     * Aliase der Seiten für das Modell: die Seiten-ID, solange sie unter den Seiten eindeutig ist, sonst
     * {@code <Quelle>:<Seite>}. Seiten einer einzigen Quelle heißen also wie bisher.
     */
    static Map<PageKey, String> aliases(java.util.Collection<PageKey> pages) {
        Map<String, Long> counts = new java.util.HashMap<>();
        new LinkedHashSet<>(pages).forEach(page -> counts.merge(page.pageId(), 1L, Long::sum));
        Map<PageKey, String> aliases = new LinkedHashMap<>();
        for (PageKey page : pages) {
            aliases.put(page, counts.get(page.pageId()) == 1 ? page.pageId() : page.sourceId() + ":" + page.pageId());
        }
        return aliases;
    }

    /**
     * Fasst die Seiten zusammen.
     *
     * @param materials die Seiten in der Reihenfolge, in der sie auf Teile verteilt werden
     * @param frame     der Rahmen jeder Teilanfrage
     * @param combineFrame der Rahmen beim Zusammenführen
     * @param withheld  Seiten, die nur als Zahl eingingen; zählen mit den unbekannten als nicht lesbar
     */
    Result summarize(List<Material> materials, ObjectNode frame, ObjectNode combineFrame, int withheld,
                     JobContext context) {
        return summarize(materials, frame, combineFrame, withheld, context, null);
    }

    /**
     * Wie {@link #summarize(List, ObjectNode, ObjectNode, int, JobContext)}; {@code partial} bekommt nach jedem fertigen
     * Teil den vorläufigen Stand: die Themen aller bisher fertigen Teile, noch nicht zusammengeführt, ohne Überblickstext
     * (Angelo: „Teilergebnisse sofort“). {@code null} ohne. Ein Fehler darin hält den Lauf nicht auf.
     */
    Result summarize(List<Material> materials, ObjectNode frame, ObjectNode combineFrame, int withheld,
                     JobContext context, java.util.function.Consumer<Result> partial) {
        int unknown = (int) materials.stream().filter(Material::unknown).count();
        context.phase(COMPONENT, PHASE);
        List<Planned> planned = plan(materials, context);
        List<List<Material>> parts = new ArrayList<>();
        List<String> topics = new ArrayList<>();
        if (planned == null) {
            for (List<Material> part : parts(materials, frame.toString().length(), model.parallel())) {
                parts.add(part);
                topics.add(null);
            }
        } else {
            for (Planned topic : planned) {
                for (List<Material> part : parts(topic.materials(), frame.toString().length(), 1,
                        PLANNED_PART_CHARS)) {
                    parts.add(part);
                    topics.add(topic.title());
                }
            }
        }
        context.discovered(parts.size());
        Map<String, String> pageSpaces = new LinkedHashMap<>();
        Map<String, String> pageTitles = new LinkedHashMap<>();
        materials.forEach(material -> {
            pageSpaces.put(material.id(), material.spaceName());
            if (material.title() != null) {
                pageTitles.put(material.id(), cut(material.title(), MAX_COMBINE_TITLE_CHARS));
            }
        });
        // Die Teile sind unabhängig: bis zu ai.parallel zugleich (Angelo, 22.11.2026); nur das Zusammenführen wartet.
        AtomicReference<RuntimeException> lost = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger lostPages = new java.util.concurrent.atomic.AtomicInteger();
        // Hälften eines geteilten Teils bleiben eigene Antworten und gehen durch das begrenzte, stufenweise Zusammenführen.
        int count = parts.size();
        List<Integer> indexes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            indexes.add(i);
        }
        java.util.NavigableMap<Integer, List<Answer>> done = new java.util.TreeMap<>();
        int withheldAll = withheld + unknown;
        List<Answer> answers = runner().run(indexes, index -> "Teil " + (index + 1) + " von " + count
                                + (topics.get(index) == null ? "" : ": " + cut(topics.get(index), 60)),
                        index -> parts.get(index).size(),
                        (index, lane) -> {
                            List<Answer> answered = part(parts.get(index), framed(frame, topics.get(index)), lane,
                                    lost, lostPages);
                            if (partial != null && count > 1) {
                                publish(partial, done, index, answered, parts, withheldAll);
                            }
                            return answered;
                        }, context).stream()
                .flatMap(List::stream)
                .filter(partial -> !partial.text().isBlank() || !partial.statements().isEmpty()).toList();
        if (answers.isEmpty()) {
            // Kein Teil kam durch: der Auftrag scheitert mit dem echten Grund.
            throw lost.get() != null && !(lost.get() instanceof UnusableAnswerException) ? lost.get()
                    : new JobFailedException("SUMMARY_UNUSABLE", "Die Antwort des Modells war keine Zusammenfassung");
        }
        Answer answer = combine(answers, combineFrame, new Pages(pageSpaces, pageTitles), context);
        List<StoredSummary.InputPage> inputs = materials.stream()
                .map(material -> new StoredSummary.InputPage(material.page().sourceId(), material.spaceKey(),
                        material.page().pageId(), material.content()))
                .toList();
        return new Result(answer.text(), answer.statements(), withheld + unknown, inputs, lostPages.get());
    }

    /**
     * Meldet den vorläufigen Stand nach einem fertigen Teil: die Themen der fertigen Teile in der Reihenfolge der Teile,
     * die Seiten genau dieser Teile. Teile laufen gleichzeitig; die Meldungen sind deshalb geordnet.
     */
    private static void publish(java.util.function.Consumer<Result> partial,
                                java.util.NavigableMap<Integer, List<Answer>> done, int index, List<Answer> answered,
                                List<List<Material>> parts, int withheld) {
        Result snapshot;
        synchronized (done) {
            done.put(index, answered);
            List<StoredSummary.Statement> statements = new ArrayList<>();
            List<StoredSummary.InputPage> inputs = new ArrayList<>();
            done.forEach((part, answers) -> {
                answers.forEach(answer -> statements.addAll(answer.statements()));
                parts.get(part).forEach(material -> inputs.add(new StoredSummary.InputPage(
                        material.page().sourceId(), material.spaceKey(), material.page().pageId(),
                        material.content())));
            });
            snapshot = new Result("", List.copyOf(statements), withheld, List.copyOf(inputs));
            try {
                partial.accept(snapshot);
            } catch (RuntimeException e) {
                // Der vorläufige Stand ist nur eine Anzeige; der Lauf geht weiter.
            }
        }
    }

    /**
     * Führt Teilantworten zusammen, stufenweise, solange die Eingabe größer als das Budget wäre. Das Modell schreibt
     * Überblick, Titel und Text und nennt je Thema, aus welchen Themen der Teile es besteht; Seiten und Details werden
     * hier vereinigt. Themen, die das Modell keinem Thema zuordnet, bleiben unverändert erhalten.
     */
    private Answer combine(List<Answer> answers, ObjectNode frame, Pages pages, JobContext context) {
        List<Answer> level = answers;
        int frameChars = frame.toString().length();
        while (level.size() > 1) {
            List<List<Answer>> groups = new ArrayList<>();
            List<Answer> group = new ArrayList<>();
            int chars = frameChars;
            for (Answer partial : level) {
                int size = node(partial, 0, pages).toString().length() + 1;
                if (group.size() >= 2 && chars + size > MAX_REQUEST_CHARS) {
                    groups.add(group);
                    group = new ArrayList<>();
                    chars = frameChars;
                }
                group.add(partial);
                chars += size;
            }
            groups.add(group);
            context.discovered(groups.stream().filter(candidate -> candidate.size() > 1).count());
            // Die Gruppen einer Stufe sind unabhängig voneinander und laufen ebenfalls gleichzeitig.
            int merges = (int) groups.stream().filter(candidate -> candidate.size() > 1).count();
            int[] merge = {0};
            List<String> titles = new ArrayList<>();
            for (List<Answer> candidate : groups) {
                titles.add(candidate.size() > 1 ? "Zusammenführen " + (++merge[0]) + " von " + merges : "");
            }
            level = runner().run(groups, titles::get, List::size, (members, lane) -> {
                if (members.size() == 1) {
                    return members.getFirst();
                }
                ObjectNode input = frame.deepCopy();
                ArrayNode summaries = input.putArray("teile");
                List<StoredSummary.Statement> topics = new ArrayList<>();
                for (Answer partial : members) {
                    summaries.add(node(partial, topics.size(), pages));
                    topics.addAll(partial.statements());
                }
                String raw;
                try {
                    raw = combineAsking(input.toString(), lane);
                } catch (AiServiceException e) {
                    if (!splittable(e)) {
                        throw e;
                    }
                    // Auch nach Wiederholungen nicht durchgekommen oder am Ausgabelimit abgeschnitten: die Themen der
                    // Teile bleiben unverändert.
                    failure(lane, "Zusammenführen gescheitert, Themen der Teile bleiben", e);
                    raw = null;
                    lane.incomplete();
                }
                lane.processed(1);
                return merge(raw, topics, members);
            }, context);
        }
        return level.getFirst();
    }

    /**
     * Liest die Antwort des Zusammenführens: Je Thema die ids der zusammengelegten Themen; Seiten und Details kommen
     * aus diesen. Nicht zugeordnete Themen werden angehängt. Ohne brauchbare Antwort bleiben die Themen der Teile.
     */
    static Answer merge(String raw, List<StoredSummary.Statement> topics, List<Answer> members) {
        JsonNode root = json(raw);
        List<StoredSummary.Statement> merged = new ArrayList<>();
        boolean[] used = new boolean[topics.size()];
        JsonNode list = root == null ? null : root.path("themen");
        if (list != null && list.isArray()) {
            for (JsonNode node : list) {
                List<Integer> from = new ArrayList<>();
                JsonNode ids = node.path("aus");
                if (ids.isArray()) {
                    for (JsonNode id : ids) {
                        int index = topicIndex(id);
                        if (index >= 0 && index < topics.size() && !used[index]) {
                            from.add(index);
                        }
                    }
                }
                if (from.isEmpty()) {
                    continue;
                }
                from.forEach(index -> used[index] = true);
                String text = cut(string(node, "text"), MAX_STATEMENT_CHARS);
                String title = cut(string(node, "titel"), MAX_TOPIC_TITLE_CHARS);
                StoredSummary.Statement first = topics.get(from.getFirst());
                Set<String> pages = new LinkedHashSet<>();
                List<String> details = new ArrayList<>();
                for (int index : from) {
                    StoredSummary.Statement topic = topics.get(index);
                    topic.pageIds().forEach(pageId -> {
                        if (pages.size() < MAX_STATEMENT_PAGES) {
                            pages.add(pageId);
                        }
                    });
                    if (topic.details() != null && !topic.details().isBlank()) {
                        details.add(from.size() > 1 && topic.title() != null
                                ? topic.title() + ": " + topic.details() : topic.details());
                    }
                }
                merged.add(new StoredSummary.Statement(text.isEmpty() ? first.text() : text, List.copyOf(pages),
                        title.isEmpty() ? first.title() : title,
                        details.isEmpty() ? null : cut(String.join("\n\n", details), MAX_DETAILS_CHARS)));
            }
        }
        for (int index = 0; index < topics.size(); index++) {
            if (!used[index]) {
                merged.add(topics.get(index));
            }
        }
        if (merged.size() > MAX_TOPICS) {
            merged = new ArrayList<>(merged.subList(0, MAX_TOPICS));
        }
        String text = root == null ? "" : cut(string(root, "zusammenfassung"), MAX_TEXT_CHARS);
        if (text.isEmpty()) {
            text = String.join(" ", members.stream().map(Answer::text).filter(part -> !part.isBlank()).toList());
            text = cut(text, MAX_TEXT_CHARS);
        }
        return new Answer(text, List.copyOf(merged));
    }

    private static int topicIndex(JsonNode id) {
        String value = id.isString() ? id.asString().strip() : id.isNumber() ? id.asString() : "";
        if (value.startsWith("t") || value.startsWith("T")) {
            value = value.substring(1);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Space-Namen und Titel der Seiten, für die Themen beim Zusammenführen. */
    private record Pages(Map<String, String> spaces, Map<String, String> titles) {
    }

    /**
     * Eine Teilantwort für das Zusammenführen: Überblick und Themen mit fortlaufender id ab {@code offset}. Wird sie
     * größer als ein halbes Budget (viele Themen nach einem früheren Zusammenführen), geht sie knapp ein: Titel, kurzer
     * Text und Spaces, ohne Beispielseiten. So passen immer zwei Teile in einen Aufruf.
     */
    private static ObjectNode node(Answer partial, int offset, Pages pages) {
        ObjectNode full = node(partial, offset, pages, MAX_COMBINE_TEXT_CHARS, MAX_COMBINE_EXAMPLES);
        return full.toString().length() <= MAX_REQUEST_CHARS / 2 ? full
                : node(partial, offset, pages, MAX_COMBINE_BRIEF_CHARS, 0);
    }

    private static ObjectNode node(Answer partial, int offset, Pages pages, int textChars, int examples) {
        ObjectNode node = JSON.createObjectNode();
        node.put("zusammenfassung", cut(partial.text(), examples == 0 ? textChars : MAX_PARTIAL_TEXT_CHARS));
        ArrayNode topics = node.putArray("themen");
        int index = offset;
        for (StoredSummary.Statement statement : partial.statements()) {
            ObjectNode entry = topics.addObject();
            entry.put("id", "t" + index++);
            if (statement.title() != null) {
                entry.put("titel", statement.title());
            }
            entry.put("text", cut(statement.text(), textChars));
            ArrayNode spaces = entry.putArray("spaces");
            new java.util.TreeSet<>(statement.pageIds().stream().map(pages.spaces()::get).filter(Objects::nonNull)
                    .toList()).forEach(spaces::add);
            if (examples > 0) {
                ArrayNode titles = entry.putArray("beispielSeiten");
                statement.pageIds().stream().map(pages.titles()::get).filter(Objects::nonNull).limit(examples)
                        .forEach(titles::add);
            }
        }
        return node;
    }

    /** Der Rahmen einer Teilaufgabe, mit dem geplanten Thema, wenn es einen Plan gibt. */
    private static ObjectNode framed(ObjectNode frame, String topic) {
        if (topic == null) {
            return frame;
        }
        ObjectNode copy = frame.deepCopy();
        copy.put("geplantesThema", topic);
        return copy;
    }

    /**
     * Der Plan: Themen mit ihren Seiten, in der Reihenfolge der Antwort; Seiten, die das Modell keinem Thema zuordnet,
     * kommen in ein Thema „Weitere Änderungen“. {@code null}, wenn es keinen Plan gibt (kein Planer, nur eine Seite,
     * zu viele Seiten für eine Plan-Anfrage) oder der Plan auch nach {@link #ATTEMPTS} Versuchen mit Grund und den
     * üblichen Wiederholungen nicht zustande kommt: dann teilt der Auftrag wie bisher nach Space und Pfad, statt zu
     * scheitern.
     */
    private List<Planned> plan(List<Material> materials, JobContext context) {
        if (planner == null || materials.size() < 2) {
            return null;
        }
        String input = planInput(materials, true);
        if (input.length() > MAX_PLAN_CHARS) {
            input = planInput(materials, false);
        }
        if (input.length() > MAX_PLAN_CHARS) {
            return null;
        }
        Map<String, Material> byId = new LinkedHashMap<>();
        materials.forEach(material -> byId.put(material.id(), material));
        String problem = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            context.checkpoint();
            String raw;
            try {
                raw = completeRetrying(planner, prompts.plan() + againPlan(problem), input, context);
            } catch (AiOutputTruncatedException e) {
                problem = TOO_LONG_PLAN;
                continue;
            } catch (AiServiceException e) {
                if (!transientFailure(e)) {
                    throw e;
                }
                return null;
            }
            List<Planned> planned = planned(raw, byId, prompts.restTopic());
            if (planned != null) {
                return planned;
            }
            problem = NOT_PLAN;
        }
        return null;
    }

    static final String TOO_LONG_PLAN = "sie war zu lang und wurde am Ausgabelimit abgeschnitten. Nenne nur Titel "
            + "und ids, ohne Begründung";
    static final String NOT_PLAN = "sie enthielt kein vollständiges JSON-Objekt mit \"themen\", in denen jede id "
            + "der Eingabe steht";

    private static String againPlan(String problem) {
        return problem == null ? "" : "\n\nDeine vorige Antwort war unbrauchbar: " + problem
                + ". Halte dich genau an die Regeln und antworte nur mit dem korrigierten JSON.";
    }

    private String planInput(List<Material> materials, boolean teaser) {
        ObjectNode input = JSON.createObjectNode();
        ArrayNode pages = input.putArray("seiten");
        for (Material material : materials) {
            ObjectNode node = material.node();
            ObjectNode page = pages.addObject();
            for (String field : prompts.planFields()) {
                if (node.has(field)) {
                    page.set(field, node.get(field));
                }
            }
            if (teaser) {
                String text = node.path("inhalt").isString() ? node.path("inhalt").asString() : "";
                if (text.isBlank() && node.path("absaetze").isArray() && !node.path("absaetze").isEmpty()) {
                    JsonNode first = node.path("absaetze").get(0);
                    text = first.path("nachher").isString() ? first.path("nachher").asString()
                            : first.path("vorher").isString() ? first.path("vorher").asString() : "";
                }
                if (!text.isBlank()) {
                    page.put("anriss", cut(text.strip(), MAX_PLAN_TEASER_CHARS));
                }
            }
        }
        return input.toString();
    }

    /**
     * Liest den Plan. {@code null}, wenn er unbrauchbar ist: kein JSON, keine Themen oder weniger als die Hälfte der
     * Seiten zugeordnet. Doppelt genannte Seiten bleiben im ersten Thema, nicht genannte kommen in „Weitere
     * Änderungen“.
     */
    static List<Planned> planned(String raw, Map<String, Material> byId, String restTopic) {
        JsonNode root = json(raw);
        if (root == null || !root.path("themen").isArray()) {
            return null;
        }
        Set<String> seen = new java.util.HashSet<>();
        List<Planned> planned = new ArrayList<>();
        for (JsonNode topic : root.path("themen")) {
            List<Material> members = new ArrayList<>();
            for (JsonNode id : topic.path("seiten")) {
                String pageId = id.isString() ? id.asString() : id.isNumber() ? id.asText() : null;
                Material material = pageId == null ? null : byId.get(pageId);
                if (material != null && seen.add(pageId)) {
                    members.add(material);
                }
            }
            if (!members.isEmpty()) {
                String title = string(topic, "titel");
                planned.add(new Planned(title.isBlank() ? "Thema " + (planned.size() + 1)
                        : cut(title, MAX_TOPIC_TITLE_CHARS), members));
            }
            if (planned.size() >= MAX_TOPICS) {
                break;
            }
        }
        if (planned.isEmpty() || seen.size() * 2 < byId.size()) {
            return null;
        }
        List<Material> rest = byId.values().stream().filter(material -> !seen.contains(material.id()))
                .toList();
        if (!rest.isEmpty()) {
            planned.add(new Planned(restTopic, new ArrayList<>(rest)));
        }
        return planned;
    }

    /** Teile bis zum Budget, in der Reihenfolge nach Space; jeder Teil hat mindestens eine Seite. */
    private static List<List<Material>> parts(List<Material> materials, int frameChars) {
        return parts(materials, frameChars, 1);
    }

    /**
     * Teilt die Seiten so, dass jede gleichzeitige Anfrage ({@code ai.parallel}) etwas zu tun hat: Statt wenige volle
     * Teile nacheinander gibt es mindestens so viele kleinere Teile, wie zugleich laufen dürfen, jedes nicht kleiner
     * als {@link #MIN_PART_CHARS} und nie größer als {@link #MAX_REQUEST_CHARS} (Angelo, 22.11.2026).
     */
    static List<List<Material>> parts(List<Material> materials, int frameChars, int parallel) {
        return parts(materials, frameChars, parallel, MAX_REQUEST_CHARS);
    }

    /** Wie {@link #parts(List, int, int)}, höchstens {@code maxChars} je Teil. */
    static List<List<Material>> parts(List<Material> materials, int frameChars, int parallel, int maxChars) {
        long total = 0;
        for (Material material : materials) {
            total += material.node().toString().length() + 1;
        }
        long share = parallel <= 1 ? Long.MAX_VALUE : frameChars + (total + parallel - 1) / parallel;
        int budget = (int) Math.min(Math.max(maxChars, frameChars + 1), Math.max(MIN_PART_CHARS, share));
        List<List<Material>> parts = new ArrayList<>();
        List<Material> part = new ArrayList<>();
        int chars = frameChars;
        for (Material material : materials) {
            int size = material.node().toString().length() + 1;
            if (!part.isEmpty() && chars + size > budget) {
                parts.add(part);
                part = new ArrayList<>();
                chars = frameChars;
            }
            part.add(material);
            chars += size;
        }
        parts.add(part);
        return parts;
    }
    private static Set<String> ids(List<Material> part) {
        Set<String> ids = new LinkedHashSet<>();
        part.forEach(material -> ids.add(material.id()));
        return ids;
    }
    record Answer(String text, List<StoredSummary.Statement> statements) {
    }

    /**
     * Wendet {@code work} auf jeden Eintrag an, höchstens {@link SummaryModel#parallel()} zugleich, und liefert die
     * Ergebnisse in der Reihenfolge der Einträge. Nach Abbruch beginnt kein neuer; der erste Fehler beendet den Lauf.
     */
    private Runner runner() {
        return runner != null ? runner : inJob(model);
    }

    /** Alle Anfragen im Auftrag selbst, höchstens {@link SummaryModel#parallel()} zugleich. */
    static Runner inJob(SummaryModel model) {
        return new Runner() {
            @Override
            public <T, R> List<R> run(List<T> items, IntFunction<String> title, BiFunction<T, JobContext, R> work,
                                      JobContext context) {
                return com.aresstack.spaceharvester.application.jobs.ParallelWork.map(Math.max(1, model.parallel()), items,
                        context, "summary-", item -> work.apply(item, context));
            }
        };
    }

    /**
     * Ein Teil der Seiten. Kommt die Anfrage auch nach Wiederholungen nicht durch (etwa HTTP 504 eines Gateways bei
     * großer Eingabe), wird der Teil halbiert und jede Hälfte für sich gefragt; eine einzelne Seite, die nie
     * durchkommt, fehlt in der Zusammenfassung, der Auftrag gilt dann als unvollständig, scheitert aber nicht
     * (Angelo, 22.11.2026: ein einzelner Fehler kippt nicht den Auftrag).
     */
    private List<Answer> part(List<Material> part, ObjectNode frame, JobContext context,
                              AtomicReference<RuntimeException> lost, java.util.concurrent.atomic.AtomicInteger lostPages) {
        context.checkpoint();
        ObjectNode input = frame.deepCopy();
        ArrayNode changes = input.putArray(prompts.listField());
        part.forEach(material -> changes.add(material.node()));
        try {
            List<Answer> answered = List.of(ask(prompts.instructions(), input.toString(), ids(part), context));
            answeredParts.incrementAndGet();
            return answered;
        } catch (AiServiceException e) {
            if (!splittable(e) || part.isEmpty()) {
                throw e;
            }
            lost.set(e);
            if (part.size() == 1) {
                // Sichtbar im Auftrag, warum die Seite fehlt (nur Art und Status, nie Text der Anfrage).
                failure(context, "Seite " + part.getFirst().id() + " ausgelassen", e);
                context.processed(1);
                context.failed(1);
                context.incomplete();
                if (lostPages.incrementAndGet() >= FAIL_FAST_PAGES && answeredParts.get() == 0) {
                    // Jede Anfrage scheitert gleich (Angelo 22.11.2026: „jeder einzelne Step schlägt fehl“): nicht
                    // unsichtbar weiterrechnen, sondern mit dem echten Grund abbrechen.
                    throw new JobFailedException("SUMMARY_ALL_FAILED", "Zusammenfassen abgebrochen: " + FAIL_FAST_PAGES
                            + " Seiten nacheinander gescheitert, keine Anfrage kam durch. Letzter Grund: "
                            + e.getMessage());
                }
                return List.of();
            }
            context.checkpoint();
            context.discovered(1);
            int half = part.size() / 2;
            List<Answer> answers = new ArrayList<>(part(part.subList(0, half), frame, context, lost, lostPages));
            answers.addAll(part(part.subList(half, part.size()), frame, context, lost, lostPages));
            return answers;
        }
    }

    /**
     * Ein gescheiterter Aufruf als Fehler in der Diagnose (Angelo 22.11.2026: „In der Diagnose steht nicht mal
     * irgendwelche Informationen“): Komponente, Code, HTTP-Status, Grund und Exception-Klasse; nie Text der Anfrage.
     */
    static void failure(JobContext context, String what, AiServiceException e) {
        java.util.regex.Matcher status = java.util.regex.Pattern.compile("HTTP (\\d{3})").matcher(
                String.valueOf(e.getMessage()));
        context.record(new com.aresstack.spaceharvester.application.jobs.JobEvent.Draft(
                com.aresstack.spaceharvester.application.jobs.JobEvent.Severity.ERROR,
                com.aresstack.spaceharvester.application.jobs.JobEvent.Category.JOB, "RemoteAi",
                "AI_" + e.reason().name(), what + ": " + e.getMessage(),
                status.find() ? Integer.valueOf(status.group(1)) : null, null, e));
    }

    /**
     * Ein gescheiterter KI-Aufruf als Fehler des Auftrags mit verständlichem Grund statt „Unerwarteter Fehler
     * (AiServiceException)“: fester Text je Grund, dazu der HTTP-Status, wenn die Meldung einen nennt; nie die Meldung
     * selbst (sie könnte Adressen enthalten).
     */
    static JobFailedException jobFailure(AiServiceException e) {
        java.util.regex.Matcher status = java.util.regex.Pattern.compile("HTTP (\\d{3})").matcher(
                String.valueOf(e.getMessage()));
        String reason = switch (e.reason()) {
            case NOT_CONFIGURED -> "Kein KI-Dienst oder kein Modell eingerichtet";
            case UNAUTHORIZED -> "Der KI-Dienst lehnt den Zugang ab";
            case UNREACHABLE -> "Der KI-Dienst ist nicht erreichbar oder lehnt die Anfrage ab";
            case TIMEOUT -> "Der KI-Dienst hat nicht rechtzeitig geantwortet";
            case INVALID_RESPONSE -> "Die Antwort des KI-Dienstes war unbrauchbar";
        };
        return new JobFailedException("AI_" + e.reason().name(),
                reason + (status.find() ? " (HTTP " + status.group(1) + ")" : ""), e);
    }

    /** So viele verlorene Seiten ohne eine einzige gelungene Anfrage brechen den Auftrag mit dem Grund ab. */
    static final int FAIL_FAST_PAGES = 3;
    /** Gelungene Teilanfragen dieses Laufs; solange keine gelang, zählt jeder Verlust zum frühen Abbruch. */
    private final java.util.concurrent.atomic.AtomicInteger answeredParts =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Vorübergehend: keine Verbindung, überlastet oder 5xx (UNREACHABLE) und Fristen; nie Anmeldung oder Format. */
    static boolean transientFailure(AiServiceException e) {
        return ModelRetry.transientFailure(e);
    }

    /**
     * Was ein kleinerer Teil lösen kann: vorübergehende Fehler und eine am Ausgabelimit abgeschnittene Antwort
     * (Angelo 22.11.2026: finish_reason=length kippte den Auftrag). Abgeschnittenes wird nicht gleich wiederholt, die
     * gleiche Eingabe gäbe die gleiche zu lange Antwort; der Teil wird halbiert.
     */
    static boolean splittable(AiServiceException e) {
        return transientFailure(e) || e instanceof AiOutputTruncatedException || e instanceof UnusableAnswerException;
    }

    /** Wartezeit vor der n-ten Wiederholung, verdoppelt je Versuch; in Tests kürzer. */
    static long backoffMillis = 2_000;

    /** Fragt das Modell und wiederholt vorübergehende Fehler bis zu {@link SummaryModel#retries()} Mal. */
    private String completeRetrying(String instructions, String input, JobContext context) {
        return completeRetrying(model, instructions, input, context);
    }

    private static String completeRetrying(SummaryModel model, String instructions, String input,
                                           JobContext context) {
        return ModelRetry.call(model.retries(), backoffMillis, new ModelRetry.Listener() {
            @Override
            public void retrying(int nextAttempt, AiServiceException cause) {
                if (context.cancelRequested()) {
                    throw cause;
                }
                failure(context, "Anfrage gescheitert, Versuch " + nextAttempt + " folgt", cause);
                context.checkpoint();
            }

            @Override
            public RuntimeException interrupted() {
                return new JobCancelledException(context.jobId());
            }
        }, () -> model.complete(instructions, input));
    }

    /** Das Zusammenführen; eine abgeschnittene Antwort wird mit dem Grund erneut erfragt, bis {@link #ATTEMPTS}. */
    private String combineAsking(String input, JobContext context) {
        String problem = null;
        AiOutputTruncatedException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            context.checkpoint();
            try {
                return completeRetrying(prompts.combine() + again(problem), input, context);
            } catch (AiOutputTruncatedException e) {
                last = e;
                problem = TOO_LONG;
            }
        }
        throw last;
    }

    /** Versuche je Anfrage, wenn die Antwort unbrauchbar oder abgeschnitten ist (wie die Teile in 3d4j). */
    static final int ATTEMPTS = 3;

    /**
     * Fragt und prüft die Antwort. Ist sie unbrauchbar (kein JSON mit Überblick und Themen) oder am Ausgabelimit
     * abgeschnitten, wird erneut gefragt, und das Modell erfährt, was an der vorigen Antwort falsch war (Vorbild:
     * 3d4j, „Your previous answer was unusable: …“). Erst nach {@link #ATTEMPTS} Versuchen gibt die Anfrage auf; ein
     * Teil wird dann halbiert ({@link #splittable}), und erst wenn kein Teil durchkam, scheitert der Auftrag.
     */
    private Answer ask(String instructions, String input, Set<String> known, JobContext context) {
        String problem = null;
        AiServiceException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            context.checkpoint();
            String raw;
            try {
                raw = completeRetrying(instructions + again(problem), input, context);
            } catch (AiOutputTruncatedException e) {
                last = e;
                problem = TOO_LONG;
                continue;
            }
            Answer answer = parse(raw, known);
            if (!answer.text().isBlank() || !answer.statements().isEmpty()) {
                context.processed(1);
                return answer;
            }
            problem = NOT_JSON;
            last = new UnusableAnswerException();
        }
        throw last;
    }

    /** Was an einer abgeschnittenen Antwort falsch war, für den nächsten Versuch. */
    static final String TOO_LONG = "sie war zu lang und wurde am Ausgabelimit abgeschnitten. Schreibe knapper: "
            + "kürzere Texte, ähnliche Themen zusammenlegen, Details nur in Stichworten";
    /** Was an einer Antwort ohne verwertbares JSON falsch war, für den nächsten Versuch. */
    static final String NOT_JSON = "sie enthielt kein vollständiges JSON-Objekt mit \"zusammenfassung\" und "
            + "\"themen\"";

    static String again(String problem) {
        return problem == null ? "" : "\n\nDeine vorige Antwort war unbrauchbar: " + problem
                + ". Halte dich genau an die Regeln und antworte nur mit dem korrigierten JSON.";
    }

    /** Auch nach allen Versuchen keine Zusammenfassung; ein kleinerer Teil kann gelingen. */
    static final class UnusableAnswerException extends AiServiceException {
        private static final long serialVersionUID = 1L;

        UnusableAnswerException() {
            super(Reason.INVALID_RESPONSE, "Die Antwort des Modells war keine Zusammenfassung");
        }
    }

    private static JsonNode json(String raw) {
        if (raw == null || raw.length() > MAX_ANSWER_CHARS) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return JSON.readTree(raw.substring(start, end + 1));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String string(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.asString().strip() : "";
    }

    /**
     * Liest die Antwort; unbekannte Seiten, leere Themen und Überlängen fallen weg. Versteht {@code themen} und, von
     * älteren Antworten, {@code aussagen}.
     */
    static Answer parse(String raw, Set<String> known) {
        JsonNode root = json(raw);
        if (root == null) {
            return new Answer("", List.of());
        }
        List<StoredSummary.Statement> statements = new ArrayList<>();
        JsonNode list = root.path("themen").isArray() ? root.path("themen") : root.path("aussagen");
        if (list.isArray()) {
            for (JsonNode node : list) {
                if (statements.size() >= MAX_STATEMENTS) {
                    break;
                }
                String text = cut(string(node, "text"), MAX_STATEMENT_CHARS);
                if (text.isEmpty()) {
                    continue;
                }
                Set<String> pages = new LinkedHashSet<>();
                JsonNode ids = node.path("seiten");
                if (ids.isArray()) {
                    for (JsonNode id : ids) {
                        String pageId = id.isString() ? id.asString() : id.isNumber() ? id.asString() : null;
                        if (pageId != null && known.contains(pageId) && pages.size() < MAX_STATEMENT_PAGES) {
                            pages.add(pageId);
                        }
                    }
                }
                String title = cut(string(node, "titel"), MAX_TOPIC_TITLE_CHARS);
                String details = cut(string(node, "details"), MAX_DETAILS_CHARS);
                statements.add(new StoredSummary.Statement(text, List.copyOf(pages), title.isEmpty() ? null : title,
                        details.isEmpty() ? null : details));
            }
        }
        String text = cut(string(root, "zusammenfassung"), MAX_TEXT_CHARS);
        if (text.isEmpty() && !statements.isEmpty()) {
            text = String.join("\n", statements.stream().map(StoredSummary.Statement::text).toList());
        }
        return new Answer(text, statements);
    }

    static String cut(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int end = max - 1;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + "…";
    }
}
