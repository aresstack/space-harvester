package com.aresstack.spaceharvester.application.podcast;

import com.aresstack.spaceharvester.application.podcast.PodcastPlan.Beat;
import com.aresstack.spaceharvester.application.podcast.PodcastPlan.Segment;
import com.aresstack.spaceharvester.application.podcast.PodcastPlan.Speaker;
import com.aresstack.spaceharvester.application.podcast.PodcastPlan.Topic;
import com.aresstack.spaceharvester.application.podcast.PodcastPlan.Turn;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Die Ablage der Podcasts auf der Platte (Angelo 21.11.2026: nicht nur im Arbeitsspeicher, nach Abbruch oder Neustart
 * dort weitermachen, wo die Erzeugung war). Je Podcast ein Ordner unter {@code directory} mit dem Schlüssel als Namen:
 * {@code podcast.json} (wofür, mit welchen Stimmen), {@code plan.json} (Grobübersicht), {@code topic-NN.json}
 * (Abschnitte eines Themas), {@code seg-NN-MM.json} (Text) und {@code seg-NN-MM.wav} (Audio). Jede Datei wird erst
 * vollständig geschrieben und dann an ihren Platz verschoben; eine halb geschriebene Datei gibt es also nie.
 */
public final class PodcastStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Path directory;

    public PodcastStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    /** Wofür ein Podcast entstand; der Schlüssel folgt aus der Zusammenfassung. */
    public record Meta(String key, String sourceId, String updateId, String spaceKey, String model, String host,
                       String cohost) {
        public Meta {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(updateId, "updateId");
        }
    }

    Path folder(String key) {
        if (!key.matches("[0-9a-f]{8,64}")) {
            throw new IllegalArgumentException("Ungültiger Podcast-Schlüssel");
        }
        return directory.resolve(key);
    }

    public Optional<Meta> meta(String key) {
        return read(folder(key).resolve("podcast.json")).map(node -> new Meta(key, text(node, "sourceId"),
                text(node, "updateId"), nullable(node, "spaceKey"), nullable(node, "model"), nullable(node, "host"),
                nullable(node, "cohost")));
    }

    public void saveMeta(Meta meta) {
        ObjectNode node = JSON.createObjectNode();
        node.put("sourceId", meta.sourceId());
        node.put("updateId", meta.updateId());
        node.put("spaceKey", meta.spaceKey());
        node.put("model", meta.model());
        node.put("host", meta.host());
        node.put("cohost", meta.cohost());
        write(folder(meta.key()).resolve("podcast.json"), node);
    }

    public Optional<PodcastPlan> plan(String key) {
        return read(folder(key).resolve("plan.json")).map(node -> {
            List<Topic> topics = new ArrayList<>();
            for (JsonNode topic : node.path("topics")) {
                List<String> points = new ArrayList<>();
                topic.path("points").forEach(point -> points.add(point.asString()));
                List<Integer> stories = new ArrayList<>();
                topic.path("stories").forEach(story -> stories.add(story.asInt()));
                topics.add(new Topic(text(topic, "title"), text(topic, "angle"), topic.path("energy").asDouble(0.5),
                        points, stories));
            }
            List<String> repairs = new ArrayList<>();
            node.path("repairs").forEach(repair -> repairs.add(repair.asString()));
            return new PodcastPlan(text(node, "title"), topics, repairs);
        }).filter(plan -> !plan.topics().isEmpty());
    }

    public void savePlan(String key, PodcastPlan plan) {
        ObjectNode node = JSON.createObjectNode();
        node.put("title", plan.title());
        ArrayNode topics = node.putArray("topics");
        for (Topic topic : plan.topics()) {
            ObjectNode entry = topics.addObject();
            entry.put("title", topic.title());
            entry.put("angle", topic.angle());
            entry.put("energy", topic.energy());
            ArrayNode points = entry.putArray("points");
            topic.points().forEach(points::add);
            ArrayNode stories = entry.putArray("stories");
            topic.stories().forEach(stories::add);
        }
        ArrayNode repairs = node.putArray("repairs");
        plan.repairs().forEach(repairs::add);
        write(folder(key).resolve("plan.json"), node);
    }

    public Optional<List<Segment>> segments(String key, int topic) {
        return read(folder(key).resolve(String.format(Locale.ROOT, "topic-%02d.json", topic))).map(node -> {
            List<Segment> segments = new ArrayList<>();
            for (JsonNode segment : node.path("segments")) {
                List<Beat> beats = new ArrayList<>();
                for (JsonNode beat : segment.path("beats")) {
                    beats.add(new Beat(Speaker.parse(text(beat, "speaker"), Speaker.HOST), text(beat, "intent")));
                }
                segments.add(new Segment(text(segment, "title"), beats));
            }
            return List.copyOf(segments);
        }).filter(segments -> !segments.isEmpty());
    }

    public void saveSegments(String key, int topic, List<Segment> segments) {
        ObjectNode node = JSON.createObjectNode();
        ArrayNode list = node.putArray("segments");
        for (Segment segment : segments) {
            ObjectNode entry = list.addObject();
            entry.put("title", segment.title());
            ArrayNode beats = entry.putArray("beats");
            for (Beat beat : segment.beats()) {
                beats.addObject().put("speaker", beat.speaker().name().toLowerCase(Locale.ROOT))
                        .put("intent", beat.intent());
            }
        }
        write(folder(key).resolve(String.format(Locale.ROOT, "topic-%02d.json", topic)), node);
    }

    public Optional<List<Turn>> script(String key, int topic, int segment) {
        return read(segmentFile(key, topic, segment, "json")).map(node -> {
            List<Turn> turns = new ArrayList<>();
            for (JsonNode turn : node.path("turns")) {
                turns.add(new Turn(Speaker.parse(text(turn, "speaker"), Speaker.HOST), text(turn, "text")));
            }
            return List.copyOf(turns);
        }).filter(turns -> !turns.isEmpty());
    }

    public void saveScript(String key, int topic, int segment, List<Turn> turns) {
        ObjectNode node = JSON.createObjectNode();
        ArrayNode list = node.putArray("turns");
        for (Turn turn : turns) {
            list.addObject().put("speaker", turn.speaker().name().toLowerCase(Locale.ROOT)).put("text", turn.text());
        }
        write(segmentFile(key, topic, segment, "json"), node);
    }

    /** Die fertige WAV-Datei eines Abschnitts, falls vorhanden. */
    public Optional<Path> audio(String key, int topic, int segment) {
        Path file = segmentFile(key, topic, segment, "wav");
        return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }

    public void saveAudio(String key, int topic, int segment, byte[] wav) {
        writeBytes(segmentFile(key, topic, segment, "wav"), wav);
    }

    /** Alle abgelegten Podcasts. */
    public List<Meta> all() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<Meta> found = new ArrayList<>();
        try (DirectoryStream<Path> folders = Files.newDirectoryStream(directory)) {
            for (Path folder : folders) {
                String name = folder.getFileName().toString();
                if (Files.isDirectory(folder) && name.matches("[0-9a-f]{8,64}")) {
                    meta(name).ifPresent(found::add);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }

    /** Löscht die Podcasts, die {@code remove} annimmt; gibt zurück, wie viele es waren. */
    public int delete(Predicate<Meta> remove) {
        int removed = 0;
        for (Meta meta : all()) {
            if (remove.test(meta)) {
                deleteTree(folder(meta.key()));
                removed++;
            }
        }
        return removed;
    }

    private Path segmentFile(String key, int topic, int segment, String extension) {
        return folder(key).resolve(String.format(Locale.ROOT, "seg-%02d-%02d.%s", topic, segment, extension));
    }

    private static Optional<JsonNode> read(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(JSON.readTree(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (IOException | JacksonException e) {
            return Optional.empty(); // unlesbar zählt als fehlend: der Auftrag erzeugt die Stufe neu
        }
    }

    private static void write(Path file, JsonNode node) {
        writeBytes(file, JSON.writeValueAsString(node).getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(Path file, byte[] bytes) {
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteTree(Path folder) {
        if (!Files.exists(folder)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.asString() : "";
    }

    private static String nullable(JsonNode node, String field) {
        String value = text(node, field);
        return value.isEmpty() ? null : value;
    }
}
