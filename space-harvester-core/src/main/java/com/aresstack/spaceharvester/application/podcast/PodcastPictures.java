package com.aresstack.spaceharvester.application.podcast;

import com.aresstack.spaceharvester.application.stepmodel.StepModel;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Ein Bild je Thema des Podcasts (Angelo 21.11.2026, Stil der „Video Overviews“): Während gesprochen wird, zeigt der
 * Inhaltsbereich zum laufenden Thema ein Bild. Die Reihenfolge:
 * <ol>
 *     <li>ein echtes Bild aus den Seiten des Themas, dessen gespeicherte Bildbeschreibung zum Thema passt (das
 *     Chat-Modell wählt aus den Beschreibungen, nie aus den Bildern selbst);</li>
 *     <li>sonst ein schlichtes Schaubild, das {@link DiagramComposer} aus Einzelteilen zeichnet (Modell der
 *     Bildgenerierung, eigene Tokenlänge);</li>
 *     <li>sonst kein Bild.</li>
 * </ol>
 * Selbstheilend: Jeder Fehler führt zur nächsten Stufe, nie zum Abbruch; die Wiedergabe wartet nie auf ein Bild. Jedes
 * Bild liegt im Ordner des Podcasts ({@code pic-NN.json} und {@code pic-NN.png|jpg|gif|svg}, Zwischenstufen des
 * Schaubilds als {@code pic-NN-*}); ein neuer Lauf nimmt, was schon da ist. An die Modelle geht nur Material, das auch
 * der Podcast verwendet, und Beschreibungen von Bildern aus Seiten mit KI-Freigabe (ADR 0028, liefert
 * {@link PageImageSource}).
 */
public final class PodcastPictures {

    /** So viele Bilder eines Themas stehen höchstens zur Wahl. */
    static final int MAX_CANDIDATES = 12;
    /** Kleinere Bilder sind meist Symbole oder Logos und kommen nicht in den Inhaltsbereich. */
    static final int MIN_WIDTH = 240;
    static final int MIN_HEIGHT = 140;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static final String CHOOSE_STYLE = "Du wählst Bilder für einen deutschen Erklär-Podcast aus. Du kennst die Bilder "
            + "nur aus ihren Beschreibungen. Antworte nur mit gültigem JSON, ohne Markdown.";

    /** Woher das Bild eines Themas kommt. */
    public enum Kind {
        /** Ein Bild aus einer Seite des Themas. */
        PAGE,
        /** Ein gezeichnetes Schaubild. */
        DIAGRAM,
        /** Kein Bild; der Inhaltsbereich bleibt, wie er ist. */
        NONE
    }

    /**
     * Das Bild eines Themas.
     *
     * @param file      die Bilddatei, {@code null} bei {@link Kind#NONE}
     * @param mediaType etwa {@code image/png} oder {@code image/svg+xml}
     * @param caption   Bildunterschrift: Titel des Seitenbilds oder des Schaubilds
     * @param pageId    die Seite des Bildes, nur bei {@link Kind#PAGE}
     * @param reason    bei {@link Kind#NONE} der Grund, ohne Inhalte
     * @param sha256    Fingerabdruck des Seitenbilds, nur bei {@link Kind#PAGE}
     */
    public record Picture(Kind kind, Path file, String mediaType, String caption, String pageId, String reason,
                          String sha256) {
        public Picture {
            Objects.requireNonNull(kind, "kind");
            caption = caption == null ? "" : caption;
        }

        static Picture none(String reason) {
            return new Picture(Kind.NONE, null, null, "", null, reason, null);
        }
    }

    /**
     * Wofür ein Bild entsteht: ein Thema des Plans.
     *
     * @param topic    Index des Themas
     * @param title    Titel des Themas
     * @param material worum es geht (die Meldungen des Themas, beim Einstieg der Überblick)
     * @param pageIds  die Seiten des Themas in ihrer Reihenfolge; leer bei Einstieg und Abschluss
     */
    public record Brief(int topic, String title, String material, List<String> pageIds) {
        public Brief {
            title = title == null ? "" : title.strip();
            material = material == null ? "" : material;
            pageIds = List.copyOf(pageIds);
        }
    }

    /** Die beschriebenen Bilder von Seiten; nur Seiten mit KI-Freigabe. */
    public interface PageImageSource {

        /**
         * Ein Bild einer Seite mit seiner gespeicherten Beschreibung.
         *
         * @param assetId die Anhangs-ID des Bildes in der Ablage
         * @param sha256  Fingerabdruck der Bytes; dasselbe Bild kommt in einer Folge nur einmal vor
         */
        record Candidate(String pageId, String assetId, String sha256, String title, String description,
                         String mediaType) {
        }

        /** Die beschriebenen Bilder dieser Seiten, in Seitenreihenfolge; nie Bilder ohne Beschreibung. */
        List<Candidate> candidates(String sourceId, List<String> pageIds);

        /** Die Bytes eines Bildes aus der Ablage; leer, wenn es nicht (mehr) da ist. Fragt die Quelle nicht. */
        Optional<byte[]> content(String sourceId, Candidate candidate);

        /** Keine Seitenbilder. */
        PageImageSource NONE = new PageImageSource() {
            @Override
            public List<Candidate> candidates(String sourceId, List<String> pageIds) {
                return List.of();
            }

            @Override
            public Optional<byte[]> content(String sourceId, Candidate candidate) {
                return Optional.empty();
            }
        };
    }

    private final PodcastStore store;
    private final StepModel chooser;
    private final StepModel drawer;
    private final PageImageSource images;
    private final Executor parts;
    private final PodcastWriter.Pause pause;

    /**
     * @param chooser wählt anhand der Beschreibungen ein Seitenbild (das Chat-Modell des Podcasts)
     * @param drawer  zeichnet die Schaubilder (Modell der Bildgenerierung mit eigener Tokenlänge)
     * @param parts   führt die Teile eines Schaubilds aus; seine Größe begrenzt die gleichzeitigen Aufrufe
     */
    public PodcastPictures(PodcastStore store, StepModel chooser, StepModel drawer, PageImageSource images,
                           Executor parts, PodcastWriter.Pause pause) {
        this.store = Objects.requireNonNull(store, "store");
        this.chooser = Objects.requireNonNull(chooser, "chooser");
        this.drawer = Objects.requireNonNull(drawer, "drawer");
        this.images = Objects.requireNonNull(images, "images");
        this.parts = Objects.requireNonNull(parts, "parts");
        this.pause = Objects.requireNonNull(pause, "pause");
    }

    /** Das abgelegte Bild eines Themas; leer, solange es noch nicht entschieden ist. */
    public Optional<Picture> picture(String key, int topic) {
        Path folder = store.folder(key);
        Path meta = folder.resolve(name(topic, "json"));
        if (!Files.isRegularFile(meta)) {
            return Optional.empty();
        }
        try {
            JsonNode node = JSON.readTree(Files.readString(meta, StandardCharsets.UTF_8));
            Kind kind = Kind.valueOf(node.path("kind").asString("NONE"));
            if (kind == Kind.NONE) {
                return Optional.of(Picture.none(text(node, "reason")));
            }
            String file = text(node, "file");
            Path path = folder.resolve(file);
            if (!file.matches("pic-\\d{2}\\.(png|jpg|gif|svg)") || !Files.isRegularFile(path)) {
                return Optional.empty(); // Bild fehlt: neu entscheiden
            }
            return Optional.of(new Picture(kind, path, text(node, "mediaType"), text(node, "caption"),
                    node.path("pageId").isString() ? node.path("pageId").asString() : null, null,
                    node.path("sha256").isString() ? node.path("sha256").asString() : null));
        } catch (IOException | JacksonException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Sucht oder zeichnet das Bild eines Themas und legt es ab; ist es schon da, gilt es. Wirft nie wegen eines Modells
     * oder eines Bildes; im schlimmsten Fall ist das Ergebnis {@link Kind#NONE}.
     *
     * @param used Fingerabdrücke der Seitenbilder, die andere Themen dieser Folge schon zeigen; wird ergänzt
     */
    public Picture make(String key, String sourceId, Brief brief, Set<String> used, List<String> repairs) {
        Optional<Picture> stored = picture(key, brief.topic());
        if (stored.isPresent()) {
            if (stored.get().sha256() != null) {
                synchronized (used) {
                    used.add(stored.get().sha256()); // nach Neustart: dieses Bild zeigt schon ein Thema
                }
            }
            return stored.get();
        }
        Picture picture;
        try {
            Optional<Picture> page;
            try {
                page = pagePicture(key, sourceId, brief, used, repairs);
            } catch (RuntimeException e) {
                // Ablage der Seitenbilder gestört: das Schaubild bleibt möglich
                repairs.add("Bild " + (brief.topic() + 1) + ": Seitenbilder nicht lesbar (" + e.getClass()
                        .getSimpleName() + ")");
                page = Optional.empty();
            }
            picture = page.or(() -> diagram(key, brief, repairs))
                    .orElseGet(() -> Picture.none("kein passendes Bild"));
        } catch (RuntimeException e) {
            repairs.add("Bild " + (brief.topic() + 1) + " gescheitert (" + e.getClass().getSimpleName() + ")");
            picture = Picture.none("gescheitert (" + e.getClass().getSimpleName() + ")");
        }
        save(key, brief.topic(), picture);
        return picture;
    }

    // --- Stufe 1: Seitenbild ------------------------------------------------------------------------------------

    private Optional<Picture> pagePicture(String key, String sourceId, Brief brief, Set<String> used,
                                          List<String> repairs) {
        if (brief.pageIds().isEmpty() || !chooser.available()) {
            return Optional.empty();
        }
        List<PageImageSource.Candidate> candidates = new ArrayList<>();
        for (PageImageSource.Candidate candidate : images.candidates(sourceId, brief.pageIds())) {
            boolean free;
            synchronized (used) {
                free = candidate.sha256() == null || !used.contains(candidate.sha256());
            }
            if (free && candidate.description() != null && !candidate.description().isBlank()) {
                candidates.add(candidate);
            }
            if (candidates.size() == MAX_CANDIDATES) {
                break;
            }
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        int choice = choose(brief, candidates, repairs);
        if (choice < 0) {
            return Optional.empty();
        }
        PageImageSource.Candidate chosen = candidates.get(choice);
        Optional<Normalized> image = images.content(sourceId, chosen).flatMap(PodcastPictures::normalize);
        if (image.isEmpty()) {
            repairs.add("Bild " + (brief.topic() + 1) + ": gewähltes Seitenbild nicht lesbar oder zu klein");
            return Optional.empty();
        }
        synchronized (used) {
            if (chosen.sha256() != null && !used.add(chosen.sha256())) {
                return Optional.empty(); // ein paralleles Thema war schneller
            }
        }
        String extension = image.get().extension();
        Path file = store.folder(key).resolve(name(brief.topic(), extension));
        writeBytes(file, image.get().bytes());
        String caption = chosen.title() == null || chosen.title().isBlank() ? "" : chosen.title().strip();
        return Optional.of(new Picture(Kind.PAGE, file, "image/" + (extension.equals("jpg") ? "jpeg" : extension),
                caption, chosen.pageId(), null, chosen.sha256()));
    }

    /** Index des gewählten Bildes oder {@code -1}, wenn keins passt oder das Modell nicht antwortet. */
    int choose(Brief brief, List<PageImageSource.Candidate> candidates, List<String> repairs) {
        StringBuilder prompt = new StringBuilder("# THEMA\n").append(brief.title()).append("\n\n# MATERIAL\n")
                .append(DiagramComposer.cut(brief.material(), 2000)).append("\n\n# BILDER AUS DEN SEITEN\n");
        for (int i = 0; i < candidates.size(); i++) {
            PageImageSource.Candidate candidate = candidates.get(i);
            prompt.append(i + 1).append(". ").append(DiagramComposer.cut(candidate.description()
                    .replaceAll("\\s+", " ").strip(), 400)).append('\n');
        }
        prompt.append("\n# AUFGABE\nWährend ein Podcast über dieses Thema spricht, wird ein Bild gezeigt. Welches der "
                + "Bilder zeigt am besten, worum es geht? Nimm nur ein Bild, das inhaltlich wirklich passt, etwa ein "
                + "Schaubild, ein Bildschirmfoto oder ein Foto zum Thema. Logos, Symbole, Zierbilder und Bilder ohne "
                + "Bezug zum Thema zählen nicht. Passt keins, antworte mit 0.\nAntworte mit genau einem JSON-Objekt: "
                + "{\"choice\":1}");
        for (int attempt = 1; attempt <= PodcastWriter.ATTEMPTS; attempt++) {
            try {
                JsonNode answer = PodcastWriter.json(chooser.complete(CHOOSE_STYLE,
                        prompt.toString()));
                if (answer != null && answer.path("choice").canConvertToInt()) {
                    int choice = answer.path("choice").asInt();
                    return choice >= 1 && choice <= candidates.size() ? choice - 1 : -1;
                }
                repairs.add("Bild " + (brief.topic() + 1) + ": Auswahl " + attempt + " unbrauchbar");
            } catch (RuntimeException e) {
                repairs.add("Bild " + (brief.topic() + 1) + ": Auswahl-Versuch " + attempt + " gescheitert ("
                        + e.getClass().getSimpleName() + ")");
            }
            if (attempt < PodcastWriter.ATTEMPTS) {
                pause.before(attempt + 1);
            }
        }
        return -1;
    }

    /** Ein Bild in einem Format, das jede Oberfläche zeigt: PNG, JPEG oder GIF, mit passender Endung. */
    record Normalized(byte[] bytes, String extension) {
    }

    /**
     * Ein lesbares, ausreichend großes Bild als PNG, JPEG oder GIF; andere Formate (etwa BMP, TIFF) werden als PNG neu
     * geschrieben, damit Endung, Medientyp und Inhalt übereinstimmen. Leer, wenn es nicht lesbar oder zu klein ist.
     */
    static Optional<Normalized> normalize(byte[] bytes) {
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
        if (image == null || image.getWidth() < MIN_WIDTH || image.getHeight() < MIN_HEIGHT) {
            return Optional.empty();
        }
        if (bytes.length > 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return Optional.of(new Normalized(bytes, "png"));
        }
        if (bytes.length > 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
            return Optional.of(new Normalized(bytes, "jpg"));
        }
        if (bytes.length > 3 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
            return Optional.of(new Normalized(bytes, "gif"));
        }
        try {
            java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", png)) {
                return Optional.empty();
            }
            return Optional.of(new Normalized(png.toByteArray(), "png"));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    // --- Stufe 2: Schaubild -------------------------------------------------------------------------------------

    private Optional<Picture> diagram(String key, Brief brief, List<String> repairs) {
        if (!drawer.available()) {
            return Optional.empty();
        }
        Path folder = store.folder(key);
        String prefix = String.format(Locale.ROOT, "pic-%02d-", brief.topic());
        DiagramComposer.Memory memory = new DiagramComposer.Memory() {
            @Override
            public String load(String name) {
                Path file = folder.resolve(prefix + name);
                try {
                    return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
                } catch (IOException e) {
                    return null;
                }
            }

            @Override
            public void save(String name, String content) {
                writeBytes(folder.resolve(prefix + name), content.getBytes(StandardCharsets.UTF_8));
            }
        };
        Optional<DiagramComposer.Diagram> diagram = new DiagramComposer(drawer, parts, pause)
                .compose(brief.title(), brief.material(), memory);
        if (diagram.isEmpty()) {
            repairs.add("Bild " + (brief.topic() + 1) + ": kein Schaubild");
            return Optional.empty();
        }
        diagram.get().repairs().forEach(repair -> repairs.add("Bild " + (brief.topic() + 1) + ": " + repair));
        Path file = folder.resolve(name(brief.topic(), "svg"));
        writeBytes(file, diagram.get().svg().getBytes(StandardCharsets.UTF_8));
        String caption = diagram.get().plan().title().isBlank() ? brief.title() : diagram.get().plan().title();
        return Optional.of(new Picture(Kind.DIAGRAM, file, "image/svg+xml", caption, null, null, null));
    }

    // --- Ablage -------------------------------------------------------------------------------------------------

    private void save(String key, int topic, Picture picture) {
        ObjectNode node = JSON.createObjectNode();
        node.put("kind", picture.kind().name());
        if (picture.file() != null) {
            node.put("file", picture.file().getFileName().toString());
            node.put("mediaType", picture.mediaType());
        }
        node.put("caption", picture.caption());
        if (picture.pageId() != null) {
            node.put("pageId", picture.pageId());
        }
        if (picture.reason() != null) {
            node.put("reason", picture.reason());
        }
        if (picture.sha256() != null) {
            node.put("sha256", picture.sha256());
        }
        writeBytes(store.folder(key).resolve(name(topic, "json")),
                JSON.writeValueAsString(node).getBytes(StandardCharsets.UTF_8));
    }

    private static String name(int topic, String extension) {
        return String.format(Locale.ROOT, "pic-%02d.%s", topic, extension);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.asString() : "";
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
}
