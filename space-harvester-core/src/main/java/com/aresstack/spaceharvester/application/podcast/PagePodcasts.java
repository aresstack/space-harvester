package com.aresstack.spaceharvester.application.podcast;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.stepmodel.StepModel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.ObjIntConsumer;

/**
 * Der Erklär-Podcast einer Seite (Angelo 21.11.2026: „jede beliebige Unterseite sich so erklären lassen“): derselbe
 * Ablauf wie der Podcast der Update-Zusammenfassung (Plan, Abschnitte, Auftrag im Hintergrund, Ablage, Zeitleiste), nur
 * mit dem Inhalt der Seite statt der Neuigkeiten. Überblickscharakter: das Wesentliche, nicht alles. Eingabe sind die
 * Abschnitte der Seite, ihre Unterseiten gestapelt darunter (je Unterseite der Anfang ihres Texts, darunter kurz deren
 * Unterseiten) und der Ort (Space, Elternseiten). Tabellen aus stumpfen Zahlen fallen vorher weg ({@link PodcastTables}).
 *
 * <p>An das Modell gehen nur Seiten, die der Aufrufer lesen darf, die lokal liegen und die KI-Freigabe haben (ADR
 * 0028); das prüft {@link Pages}. Der Schlüssel eines Podcasts folgt aus Aufrufer, Quelle und Seite; ändert sich der
 * Inhalt, gilt der abgelegte Podcast als veraltet und der nächste Start erzeugt ihn neu.
 */
public final class PagePodcasts {

    /** Kennung des Verfahrens; Teil des Schlüssels, damit eine neue Fassung neu plant. */
    public static final String VERSION = "page-podcast-v2";
    /** Parameter des Auftrags: die Seite. */
    public static final String PAGE_PARAMETER = "page";

    /** So viele Abschnitte der Seite selbst werden zu eigenen Teilen; der Rest hängt am letzten. */
    static final int MAX_SECTIONS = 10;
    /** So viel Text eines Abschnitts der Seite geht an das Modell. */
    static final int SECTION_CHARS = 3000;
    /** So viele Unterseiten kommen vor. */
    static final int MAX_SUBPAGES = 12;
    /** So viel vom Anfang einer Unterseite geht an das Modell. */
    static final int SUBPAGE_CHARS = 1500;
    /** So viele Unterseiten einer Unterseite werden kurz erwähnt. */
    static final int MAX_GRANDCHILDREN = 6;
    /** So viel vom Anfang einer Unterseite der zweiten Ebene. */
    static final int GRANDCHILD_CHARS = 300;
    /** So lange gilt ein berechnetes Material, bevor es neu gelesen wird (die Wiedergabe fragt oft nach dem Stand). */
    static final long MATERIAL_MILLIS = 15_000;

    /** Die Seiten, wie der Podcast sie sehen darf. */
    public interface Pages {

        /**
         * Die Seite mit ihrem Text, wenn der Aufrufer sie lesen darf, sie lokal liegt und an einen KI-Dienst darf;
         * sonst leer.
         */
        Optional<Page> page(AccessContext caller, String sourceId, String pageId);

        /** Die direkten Unterseiten in Anzeigereihenfolge; {@link #page} prüft je Seite die Rechte. */
        List<String> children(AccessContext caller, String sourceId, String pageId);

        /** Die übergeordneten Seiten von oben, nur sichtbare mit KI-Freigabe. */
        List<String> ancestors(AccessContext caller, String sourceId, String pageId);
    }

    /** Eine Seite für den Podcast. */
    public record Page(String pageId, String title, String spaceKey, PageText text) {
        public Page {
            Objects.requireNonNull(pageId, "pageId");
            title = title == null || title.isBlank() ? "ohne Titel" : title.strip();
            Objects.requireNonNull(text, "text");
        }
    }

    private final Jobs jobs;
    private final PodcastProduction production;
    private final Pages pages;
    private final LongSupplier clock;
    private final Object starting = new Object();
    private final Map<String, Remembered> remembered = new ConcurrentHashMap<>();

    private record Remembered(long at, Optional<PodcastWriter.Material> material) {
    }

    /**
     * @param model    das Chat-Modell der Planung und der Texte
     * @param pages    die Seiten mit Rechte- und Freigabeprüfung
     * @param parallel so viele Anfragen schickt der Auftrag höchstens zugleich an das Chat-Modell ({@code ai.parallel})
     */
    public PagePodcasts(Jobs jobs, StepModel model, PodcastSpeech speech, PodcastStore store, PodcastVoices voices,
                        Pages pages, IntSupplier parallel) {
        this(jobs, model, speech, store, voices, pages, parallel, PodcastProduction::pauseBefore,
                System::currentTimeMillis);
    }

    PagePodcasts(Jobs jobs, StepModel model, PodcastSpeech speech, PodcastStore store, PodcastVoices voices,
                 Pages pages, IntSupplier parallel, ObjIntConsumer<JobContext> waits, LongSupplier clock) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.pages = Objects.requireNonNull(pages, "pages");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.production = new PodcastProduction(model, speech, store, voices, parallel, waits);
    }

    /** Der Schlüssel des Podcasts einer Seite für diesen Aufrufer; ohne Lesen, also auch auf dem EDT. */
    public static String key(AccessContext caller, String sourceId, String pageId) {
        return hash(VERSION + '\n' + caller.jobOwner() + '\n' + sourceId + '\n' + pageId);
    }

    /** Ob Chat-Modell und ein Dienst mit Sprachausgabe eingerichtet sind. */
    public boolean available() {
        return production.available();
    }

    /**
     * Schaltet die Bilder je Thema ein (Angelo 21.11.2026): echte Seitenbilder nach ihrer Beschreibung, sonst ein
     * Schaubild aus Teilen ({@link PodcastPictures}).
     *
     * @param drawer zeichnet die Schaubilder (Plan und Einzelteile mit demselben Modell)
     */
    public void pictures(StepModel drawer, PodcastPictures.PageImageSource images) {
        pictures(drawer, drawer, images);
    }

    /**
     * @param planner entwirft den Plan eines Schaubilds (Bildgenerierung – Plan)
     * @param icons   zeichnet seine Einzelteile (Bildgenerierung – Einzelteile)
     */
    public void pictures(StepModel planner, StepModel icons, PodcastPictures.PageImageSource images) {
        production.pictures(new PodcastProduction.Pictures(new DiagramComposer.Models(planner, icons), images));
    }

    /**
     * Der Stand des Podcasts einer Seite; leer, solange nichts erzeugt ist, oder wenn sich der Inhalt seit der
     * Erzeugung geändert hat und kein Auftrag läuft (dann erzeugt der nächste Start ihn neu).
     */
    public Optional<Podcast> podcast(AccessContext caller, String sourceId, String pageId) {
        String key = key(caller, sourceId, pageId);
        boolean running = UpdatePodcasts.open(jobs, key) != null;
        if (!running) {
            Optional<PodcastStore.Meta> meta = production.store().meta(key);
            Optional<PodcastWriter.Material> material = material(caller, sourceId, pageId);
            if (material.isEmpty() || meta.isPresent() && !input(material.get()).equals(meta.get().input())) {
                return Optional.empty();
            }
        }
        return production.podcast(key, running);
    }

    /**
     * Der gesprochene Text des Erklär-Podcasts einer Seite, soweit er schon geschrieben ist; leer, wenn es keinen
     * gültigen Podcast gibt ({@link #podcast}). Liest Seiten und Dateien, nie auf dem EDT.
     */
    public Optional<String> transcript(AccessContext caller, String sourceId, String pageId) {
        return podcast(caller, sourceId, pageId).flatMap(podcast -> production.transcript(podcast.key()));
    }

    /**
     * Startet die Erzeugung oder setzt sie fort; läuft für diesen Podcast schon ein Auftrag, ist er die Antwort. Ein
     * veralteter Podcast derselben Seite fällt weg.
     *
     * @throws IllegalStateException ohne Chat-Modell oder Sprachausgabe, oder wenn die Seite nicht an einen KI-Dienst
     *                               darf
     */
    public JobStatus start(AccessContext caller, String sourceId, String pageId) {
        Objects.requireNonNull(caller, "caller");
        if (!available()) {
            throw new IllegalStateException("Kein Chat-Modell oder keine Sprachausgabe eingerichtet");
        }
        String key = key(caller, sourceId, pageId);
        remembered.remove(key);
        PodcastWriter.Material material = material(caller, sourceId, pageId).orElseThrow(() ->
                new IllegalStateException("Die Seite liegt nicht lokal vor oder hat keine KI-Freigabe"));
        String input = input(material);
        synchronized (starting) {
            JobStatus running = UpdatePodcasts.open(jobs, key);
            if (running != null) {
                return running;
            }
            PodcastStore store = production.store();
            store.delete(meta -> meta.key().equals(key) && !input.equals(meta.input()));
            String spaceKey = pages.page(caller, sourceId, pageId).map(Page::spaceKey).orElse(null);
            // Die Fassung des Inhalts steht in der Ablage: daran erkennt podcast(), ob er veraltet ist.
            PodcastStore.Meta origin = new PodcastStore.Meta(key, sourceId, null, spaceKey, pageId, input,
                    material.pageIds(), null, null, null);
            return jobs.submit(JobType.UPDATE_PODCAST, sourceId, spaceKey, pageId, caller.jobOwner(),
                    Map.of(UpdatePodcasts.PODCAST_PARAMETER, key, PAGE_PARAMETER, pageId),
                    JobTask.blocking(context -> production.run(context, origin, material)));
        }
    }

    /** Meldet jede abgelegte Stufe dieses Podcasts. */
    public Subscription subscribe(String key, Runnable listener) {
        return production.subscribe(key, listener);
    }

    /**
     * Verliert eine Seite die KI-Freigabe, fallen die Seiten-Podcasts, in die ihr Text einging (ADR 0028). Läuft der
     * Auftrag eines solchen Podcasts noch, wird er abgebrochen und löscht seine Ablage beim Ende selbst.
     */
    public int withdrawn(Collection<PageKey> pages) {
        Set<PageKey> gone = new HashSet<>(pages);
        remembered.clear();
        int removed = 0;
        for (PodcastStore.Meta meta : production.store().all()) {
            if (meta.pageId() == null || !touches(meta, gone)) {
                continue;
            }
            JobStatus running = UpdatePodcasts.open(jobs, meta.key());
            if (running != null) {
                production.revoke(meta.key());
                jobs.cancel(running.id());
            } else {
                production.store().delete(found -> found.key().equals(meta.key()));
            }
            removed++;
        }
        return removed;
    }

    private static boolean touches(PodcastStore.Meta meta, Set<PageKey> gone) {
        if (gone.contains(new PageKey(meta.sourceId(), meta.pageId()))) {
            return true;
        }
        return meta.pages().stream().anyMatch(page -> gone.contains(new PageKey(meta.sourceId(), page)));
    }

    private Optional<PodcastWriter.Material> material(AccessContext caller, String sourceId, String pageId) {
        String key = key(caller, sourceId, pageId);
        long now = clock.getAsLong();
        Remembered known = remembered.get(key);
        if (known != null && now - known.at() < MATERIAL_MILLIS) {
            return known.material();
        }
        Optional<PodcastWriter.Material> material = pages.page(caller, sourceId, pageId)
                .map(page -> material(page, children(caller, sourceId, pageId),
                        safe(() -> pages.ancestors(caller, sourceId, pageId))));
        remembered.put(key, new Remembered(now, material));
        return material;
    }

    /** Die Unterseiten mit ihren Unterseiten, nur lesbare mit Freigabe. */
    private List<Subpage> children(AccessContext caller, String sourceId, String pageId) {
        List<Subpage> result = new ArrayList<>();
        for (String child : safe(() -> pages.children(caller, sourceId, pageId))) {
            if (result.size() == MAX_SUBPAGES) {
                break;
            }
            Optional<Page> page = pages.page(caller, sourceId, child);
            if (page.isEmpty()) {
                continue;
            }
            List<Page> below = new ArrayList<>();
            for (String grandchild : safe(() -> pages.children(caller, sourceId, child))) {
                if (below.size() == MAX_GRANDCHILDREN) {
                    break;
                }
                pages.page(caller, sourceId, grandchild).ifPresent(below::add);
            }
            result.add(new Subpage(page.get(), below));
        }
        return result;
    }

    private static List<String> safe(java.util.function.Supplier<List<String>> read) {
        try {
            List<String> found = read.get();
            return found == null ? List.of() : found;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** Eine Unterseite mit ihren eigenen Unterseiten. */
    record Subpage(Page page, List<Page> children) {
    }

    /**
     * Das Material: je Hauptabschnitt der Seite ein Teil (mit seinen Unterabschnitten), davor die Einleitung, danach je
     * Unterseite ein Teil mit dem Anfang ihres Texts und, kurz, ihren eigenen Unterseiten. Tabellen aus stumpfen Zahlen
     * fallen weg.
     */
    static PodcastWriter.Material material(Page page, List<Subpage> subpages, List<String> ancestors) {
        List<PodcastWriter.Story> stories = new ArrayList<>();
        String here = "auf der Seite „" + page.title() + "“";
        PageText text = page.text();
        List<PageText.Section> sections = text.sections();
        if (sections.isEmpty()) {
            addChunks(stories, page.title(), PodcastTables.prune(text.text()), here, page.pageId());
        } else {
            int top = sections.stream().mapToInt(PageText.Section::level).min().orElse(1);
            List<PageText.Section> main = sections.stream().filter(section -> section.level() == top).toList();
            // Bis zur ersten Hauptüberschrift: Unterabschnitte davor gehören zur Einleitung.
            String intro = text.text().substring(0, Math.min(main.get(0).start(), text.text().length()));
            if (!intro.isBlank()) {
                stories.add(story("Einleitung", intro, here, page.pageId(), SECTION_CHARS));
            }
            for (int i = 0; i < main.size(); i++) {
                PageText.Section section = main.get(i);
                boolean rest = i == MAX_SECTIONS - 1;
                int end = rest || i == main.size() - 1 ? text.text().length() : main.get(i + 1).start();
                String body = text.text().substring(section.start(), end);
                stories.add(story(section.heading(), body, "im Abschnitt „" + section.heading() + "“",
                        page.pageId(), SECTION_CHARS));
                if (rest) {
                    break;
                }
            }
        }
        for (Subpage subpage : subpages) {
            Page child = subpage.page();
            StringBuilder body = new StringBuilder(PodcastWriter.cut(PodcastTables.prune(child.text().text()),
                    SUBPAGE_CHARS));
            List<String> ids = new ArrayList<>(List.of(child.pageId()));
            if (!subpage.children().isEmpty()) {
                body.append("\nDarunter:");
                for (Page grandchild : subpage.children()) {
                    body.append("\n- „").append(grandchild.title()).append("“: ").append(PodcastWriter.cut(
                            PodcastWriter.flat(PodcastTables.prune(grandchild.text().text())), GRANDCHILD_CHARS));
                    ids.add(grandchild.pageId());
                }
            }
            stories.add(new PodcastWriter.Story(child.title(), body.toString(),
                    List.of("auf der Unterseite „" + child.title() + "“"), ids));
        }
        StringBuilder overview = new StringBuilder("Die Seite „").append(page.title()).append("“");
        if (page.spaceKey() != null) {
            overview.append(" im Space ").append(page.spaceKey());
        }
        if (!ancestors.isEmpty()) {
            List<String> near = ancestors.subList(Math.max(0, ancestors.size() - 2), ancestors.size());
            overview.append(", unter ").append(String.join(" › ", near));
        }
        overview.append('.');
        if (!subpages.isEmpty()) {
            overview.append(" Unterseiten: ").append(String.join(", ", subpages.stream()
                    .map(subpage -> "„" + subpage.page().title() + "“").toList())).append('.');
        }
        String start = PodcastWriter.flat(PodcastTables.prune(text.text()));
        if (!start.isBlank()) {
            overview.append(" Anfang der Seite: ").append(PodcastWriter.cut(start, 600));
        }
        return new PodcastWriter.Material(overview.toString(), stories, "Seite „" + page.title() + "“",
                PodcastWriter.Format.EXPLAINER);
    }

    private static PodcastWriter.Story story(String title, String body, String place, String pageId, int limit) {
        return new PodcastWriter.Story(title, PodcastWriter.cut(PodcastTables.prune(body).strip(), limit),
                List.of(place), List.of(pageId));
    }

    /** Eine Seite ohne Überschriften: in Teile an Absatzgrenzen. */
    private static void addChunks(List<PodcastWriter.Story> stories, String title, String text, String place,
                                  String pageId) {
        List<String> chunks = new ArrayList<>();
        StringBuilder chunk = new StringBuilder();
        for (String paragraph : text.split("\\n\\s*\\n|\\n")) {
            if (paragraph.isBlank()) {
                continue;
            }
            if (chunk.length() > 0 && chunk.length() + paragraph.length() > SECTION_CHARS) {
                chunks.add(chunk.toString());
                chunk.setLength(0);
            }
            if (chunk.length() > 0) {
                chunk.append('\n');
            }
            chunk.append(paragraph.strip());
        }
        if (chunk.length() > 0) {
            chunks.add(chunk.toString());
        }
        for (int i = 0; i < Math.min(chunks.size(), MAX_SECTIONS); i++) {
            stories.add(new PodcastWriter.Story(chunks.size() == 1 ? title : title + " (" + (i + 1) + ")",
                    PodcastWriter.cut(chunks.get(i), SECTION_CHARS), List.of(place), List.of(pageId)));
        }
    }

    /** Die Fassung des Materials: ändert sich der Inhalt, ändert sie sich. */
    static String input(PodcastWriter.Material material) {
        StringBuilder text = new StringBuilder(material.overview());
        for (PodcastWriter.Story story : material.stories()) {
            text.append('\n').append(story.title()).append('\n').append(story.text()).append('\n')
                    .append(String.join(";", story.places()));
        }
        return "input-" + hash(text.toString());
    }

    private static String hash(String material) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
