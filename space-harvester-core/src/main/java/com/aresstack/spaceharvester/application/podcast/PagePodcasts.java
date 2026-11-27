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
import java.util.function.Supplier;

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
    private final PodcastSpeech speech;
    private final PodcastVoices voices;
    private final LongSupplier clock;
    private final Object starting = new Object();
    private final Map<String, Remembered> remembered = new ConcurrentHashMap<>();
    /** Was von der Seite gelesen wird; die Einstellung kann sich jederzeit ändern. */
    private volatile Supplier<Reading> reading = () -> Reading.DEFAULT;

    private record Remembered(long at, Reading reading, Optional<PodcastWriter.Material> material) {
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
        this.speech = Objects.requireNonNull(speech, "speech");
        this.voices = Objects.requireNonNull(voices, "voices");
    }

    /**
     * Der Text, den die Leseansicht einer Seite vorliest (Angelo 23.11.2026): die kanonische Textfassung der Seite wie
     * im Enterprise AI Client ({@code KnowledgeDocument.text()}), nie die Darstellung. Nur für Seiten, die der
     * Aufrufer lesen darf, die lokal liegen und an einen KI-Dienst dürfen (ADR 0028), denn die Sprachausgabe ist ein
     * KI-Dienst; sonst leer. Liest, nie auf dem EDT.
     */
    public Optional<String> readAloudText(AccessContext caller, String sourceId, String pageId) {
        return pages.page(caller, sourceId, pageId).map(page -> page.text().text()).filter(text -> !text.isBlank());
    }

    /** Ob ein Dienst mit Sprachausgabe eingerichtet ist (ohne zu fragen, auch auf dem EDT). */
    public boolean readAloudAvailable() {
        return speech.available();
    }

    /**
     * Vorlesen über die Sprachausgabe mit Modell und Stimme der Einstellung „Vorlesen“ ({@code ai.read-aloud-model},
     * {@code ai.read-aloud-voice}); leer das bevorzugte TTS-Modell des Dienstes ({@link PodcastVoices#ranked}) mit der
     * Standardstimme seiner Familie. Fragt den Dienst, nie auf dem EDT.
     *
     * @throws IllegalStateException ohne Sprachausgabe oder ohne TTS-Modell beim Dienst
     */
    public ReadAloud readAloud(ReadAloud.Playback playback) {
        Objects.requireNonNull(playback, "playback");
        if (!speech.available()) {
            throw new IllegalStateException("Keine Sprachausgabe eingerichtet");
        }
        // Eigene Einstellung „Vorlesen“ (Angelo 24.11.2026); leer: das bevorzugte TTS-Modell und seine Standardstimme.
        com.aresstack.spaceharvester.ai.AiServiceSettings.Speech chosen = speech.choice();
        String model = chosen.readAloudModel();
        if (model == null) {
            List<String> models = voices.ranked(speech.models());
            if (models.isEmpty()) {
                throw new IllegalStateException("Der KI-Dienst bietet kein Sprachmodell (TTS) an");
            }
            model = models.get(0);
        }
        String voice = chosen.readAloudVoice() != null ? chosen.readAloudVoice() : voices.pair(model).host();
        return speech.readAloud(model, voice, playback);
    }

    /**
     * Was der Podcast von der Seite liest (Angelo 27.11.2026: keine festen Grenzen, {@code ai.podcast-section-chars}
     * und {@code ai.podcast-sections}).
     *
     * @param sectionChars so viel Text eines Abschnitts der Seite geht an das Modell
     * @param sections     so viele Abschnitte der Seite selbst werden zu eigenen Teilen; der Rest hängt am letzten
     */
    public record Reading(int sectionChars, int sections) {

        /** Die Vorgaben der Einstellungen. */
        public static final Reading DEFAULT = of(com.aresstack.spaceharvester.ai.AiServiceSettings.Speech.AUTOMATIC);

        public Reading {
            if (sectionChars < 1 || sections < 1) {
                throw new IllegalArgumentException("Lesemengen des Podcasts müssen mindestens 1 sein");
            }
        }

        /** Die wirksamen Lesemengen dieser Einstellungen. */
        public static Reading of(com.aresstack.spaceharvester.ai.AiServiceSettings.Speech speech) {
            return new Reading(speech.podcastSectionLength(), speech.podcastSectionCount());
        }
    }

    /** Woher die Lesemengen kommen; ohne Aufruf gelten die Vorgaben. */
    public void reading(Supplier<Reading> reading) {
        this.reading = Objects.requireNonNull(reading, "reading");
    }

    /** Eine Stelle im Text der Seite ({@link PageText#text()}), {@code [start, end)}. */
    public record Span(int start, int end) {
    }

    /** Der Schlüssel des Podcasts einer Seite für diesen Aufrufer; ohne Lesen, also auch auf dem EDT. */
    public static String key(AccessContext caller, String sourceId, String pageId) {
        return hash(VERSION + '\n' + caller.jobOwner() + '\n' + sourceId + '\n' + pageId);
    }

    /**
     * Der Schlüssel des Podcasts zum Buch eines Favoritenordners (ADR 0044: der Podcast nutzt das Buch); ohne Lesen.
     * Je Ordner ein Podcast: eine neue Auflage ändert das Material, dann entsteht er beim nächsten Start neu.
     */
    public static String bookKey(AccessContext caller, String folderId) {
        return hash(VERSION + '\n' + caller.jobOwner() + "\nbook\n" + Objects.requireNonNull(folderId, "folderId"));
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
        return podcast(key(caller, sourceId, pageId), () -> material(caller, sourceId, pageId));
    }

    /**
     * Der Stand des Podcasts zum Buch eines Ordners; leer, solange nichts erzeugt ist oder wenn das Buch seither eine
     * neue Auflage hat und kein Auftrag läuft. Das Buch hat die App schon gelesen, es ging bereits an das Modell.
     */
    public Optional<Podcast> bookPodcast(AccessContext caller, Book book) {
        return podcast(bookKey(caller, book.folderId()), () -> Optional.of(book.material(reading.get())));
    }

    private Optional<Podcast> podcast(String key, java.util.function.Supplier<Optional<PodcastWriter.Material>> read) {
        boolean running = UpdatePodcasts.open(jobs, key) != null;
        if (!running) {
            Optional<PodcastStore.Meta> meta = production.store().meta(key);
            Optional<PodcastWriter.Material> material = read.get();
            if (material.isEmpty() || meta.isPresent() && !input(material.get()).equals(meta.get().input())) {
                return Optional.empty();
            }
        }
        return production.podcast(key, running);
    }

    /** Der gesprochene Text des Podcasts zum Buch, soweit geschrieben; leer ohne gültigen Podcast. */
    public Optional<String> bookTranscript(AccessContext caller, Book book) {
        return bookPodcast(caller, book).flatMap(podcast -> production.transcript(podcast.key()));
    }

    /**
     * Startet den Podcast zum Buch eines Ordners oder setzt ihn fort; ein offener Auftrag desselben Podcasts ist die
     * Antwort. Eine frühere Fassung zu einer älteren Auflage fällt weg.
     */
    public JobStatus startBook(AccessContext caller, Book book) {
        Objects.requireNonNull(caller, "caller");
        if (!available()) {
            throw new IllegalStateException("Kein Chat-Modell oder keine Sprachausgabe eingerichtet");
        }
        String key = bookKey(caller, book.folderId());
        PodcastWriter.Material material = book.material(reading.get());
        String input = input(material);
        synchronized (starting) {
            JobStatus running = UpdatePodcasts.open(jobs, key);
            if (running != null) {
                return running;
            }
            production.store().delete(meta -> meta.key().equals(key) && !input.equals(meta.input()));
            PodcastStore.Meta origin = new PodcastStore.Meta(key, null, null, null, null, input, material.pageIds(),
                    null, null, null, BOOK_AREA + book.folderId());
            return jobs.submit(JobType.UPDATE_PODCAST, Jobs.ALL_SOURCES, null, null, caller.jobOwner(),
                    Map.of(UpdatePodcasts.PODCAST_PARAMETER, key),
                    JobTask.blocking(context -> production.run(context, origin, material)));
        }
    }

    /** Bereich eines Buch-Podcasts in der Ablage: davor die Ordner-ID. */
    static final String BOOK_AREA = "book:";

    /**
     * Das Buch eines Favoritenordners für den Podcast: Titel (Ordnername) und der Text der neuesten Auflage, wie die
     * Leseansicht ihn zeigt (Markdown); Überschriften gliedern ihn wie die Abschnitte einer Seite.
     */
    public record Book(String folderId, String title, String markdown) {
        public Book {
            Objects.requireNonNull(folderId, "folderId");
            title = title == null || title.isBlank() ? "Buch" : title.strip();
            markdown = markdown == null ? "" : markdown;
        }

        /** Der Text mit seinen Überschriften ({@code #} bis {@code ######}) als Abschnitte. */
        PageText text() {
            List<PageText.Section> sections = new ArrayList<>();
            java.util.regex.Matcher heading = java.util.regex.Pattern.compile("(?m)^(#{1,6})[ \\t]+(.+?)[ \\t#]*$")
                    .matcher(markdown);
            List<int[]> found = new ArrayList<>();
            List<String> titles = new ArrayList<>();
            while (heading.find()) {
                found.add(new int[]{heading.start(), heading.group(1).length()});
                titles.add(heading.group(2).strip());
            }
            for (int i = 0; i < found.size(); i++) {
                int end = markdown.length();
                for (int next = i + 1; next < found.size(); next++) {
                    if (found.get(next)[1] <= found.get(i)[1]) {
                        end = found.get(next)[0];
                        break;
                    }
                }
                sections.add(new PageText.Section(titles.get(i), found.get(i)[1], found.get(i)[0], end));
            }
            return new PageText("book-markdown/v1", markdown, sections);
        }

        PodcastWriter.Material material(Reading reading) {
            return PagePodcasts.material(new Page(folderId, title, null, text()), List.of(), List.of(), reading,
                    Kind.BOOK);
        }
    }

    /** Wie das Material seinen Gegenstand nennt: eine Seite oder ein Buch. */
    enum Kind {
        PAGE("Die Seite", "auf der Seite", "Anfang der Seite", "Seite"),
        BOOK("Das Buch", "im Buch", "Anfang des Buchs", "Buch");

        final String subject;
        final String place;
        final String start;
        final String label;

        Kind(String subject, String place, String start, String label) {
            this.subject = subject;
            this.place = place;
            this.start = start;
            this.label = label;
        }
    }

    /**
     * Der gesprochene Text des Erklär-Podcasts einer Seite, soweit er schon geschrieben ist; leer, wenn es keinen
     * gültigen Podcast gibt ({@link #podcast}). Liest Seiten und Dateien, nie auf dem EDT.
     */
    public Optional<String> transcript(AccessContext caller, String sourceId, String pageId) {
        return podcast(caller, sourceId, pageId).flatMap(podcast -> production.transcript(podcast.key()));
    }

    /**
     * Wovon Thema {@code topic} erzählt, als Stellen im Text der Seite: Die Leseansicht markiert sie gelb, solange das
     * Thema läuft, und stellt das Bild des Themas daneben (Angelo 23.11.2026). Ein gekürzter Teil nur so weit, wie sein
     * Text an das Modell ging. Das sind die Teile der Seite, die das
     * Thema erzählt (Unterseiten haben hier keine Stelle), bei Einstieg und Abschluss der Anfang der Seite, den der
     * Überblick zitiert. Leer ohne Plan oder ohne lesbare Seite. Liest Seite und Datei, nie auf dem EDT.
     */
    public List<Span> focus(AccessContext caller, String sourceId, String pageId, int topic) {
        Optional<PodcastPlan> plan = production.store().plan(key(caller, sourceId, pageId));
        if (plan.isEmpty() || topic < 0 || topic >= plan.get().topics().size()) {
            return List.of();
        }
        Optional<Page> page = pages.page(caller, sourceId, pageId);
        if (page.isEmpty()) {
            return List.of();
        }
        String all = page.get().text().text();
        List<Span> spans = new ArrayList<>();
        Reading now = reading.get();
        List<Part> parts = parts(page.get(), now);
        for (int story : told(plan.get().topics().get(topic), parts)) {
            spans.add(read(all, parts.get(story - 1), now.sectionChars()));
        }
        return List.copyOf(spans);
    }

    /**
     * Was der ganze Podcast von der Seite erzählt, als Stellen im Text der Seite (Angelo 27.11.2026: „was nicht
     * abgedeckt war im Podcast“ grau hinterlegen): die Teile aller Themen wie bei {@link #focus}, jeder aber nur so weit,
     * wie sein Text an das Modell ging – was hinter dem gekürzten Ende steht, hat das Modell nie gesehen. Leer ohne
     * Plan oder ohne lesbare Seite. Liest Seite und Datei, nie auf dem EDT.
     */
    public Optional<List<Span>> coverage(AccessContext caller, String sourceId, String pageId) {
        Optional<PodcastPlan> plan = production.store().plan(key(caller, sourceId, pageId));
        if (plan.isEmpty()) {
            return Optional.empty();
        }
        Optional<Page> page = pages.page(caller, sourceId, pageId);
        if (page.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(coverage(page.get(), plan.get(), reading.get()));
    }

    /** {@link #coverage(AccessContext, String, String)} ohne Lesen, in der Reihenfolge der Teile. */
    static List<Span> coverage(Page page, PodcastPlan plan, Reading reading) {
        String all = page.text().text();
        List<Part> parts = parts(page, reading);
        java.util.SortedSet<Integer> told = new java.util.TreeSet<>();
        plan.topics().forEach(topic -> told.addAll(told(topic, parts)));
        List<Span> spans = new ArrayList<>();
        for (int story : told) {
            spans.add(read(all, parts.get(story - 1), reading.sectionChars()));
        }
        return List.copyOf(spans);
    }

    /**
     * Die Stelle eines Teils, soweit sein Text an das Modell ging: Gelb (laufendes Thema) und Grau (nie erzählt) teilen
     * dieselbe Grenze und überdecken sich nie.
     */
    static Span read(String all, Part part, int limit) {
        String body = all.substring(part.start(), part.end());
        return new Span(part.start(), part.start() + reached(body, sectionText(body, limit)));
    }

    /**
     * Die Nummern (ab 1) der Teile der Seite, von denen das Thema erzählt; ohne eigene Teile (Einstieg, Abschluss)
     * der erste, den der Überblick zitiert.
     */
    private static List<Integer> told(PodcastPlan.Topic topic, List<Part> parts) {
        List<Integer> stories = topic.stories();
        if (stories.isEmpty() && !parts.isEmpty()) {
            stories = List.of(1);
        }
        return stories.stream().filter(story -> story >= 1 && story <= parts.size()).toList();
    }

    /** Der Text eines Teils, wie er an das Modell geht: Zahlentabellen raus, höchstens {@code limit} Zeichen. */
    private static String sectionText(String body, int limit) {
        return PodcastWriter.cut(PodcastTables.prune(body).strip(), limit);
    }

    /**
     * Bis wohin im Text {@code body} der daraus gemachte Text {@code told} reicht ({@link #sectionText}): Das Kürzen
     * lässt Zeilen unverändert, also wird jede Zeile von {@code told} der Reihe nach in {@code body} gesucht. Ohne
     * Kürzung oder wenn eine Zeile nicht zu finden ist, gilt der ganze Teil.
     */
    static int reached(String body, String told) {
        if (!told.endsWith("…")) {
            return body.length();
        }
        String kept = told.substring(0, told.length() - 1);
        int at = 0;
        int end = 0;
        for (String line : kept.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            int found = body.indexOf(line, at);
            if (found < 0) {
                return body.length();
            }
            end = found + line.length();
            at = end;
        }
        return end;
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

    /**
     * Eigene Grenzen der Parallelität aus der Ansicht (Angelo 24.11.2026); gelten ab dem nächsten Lauf.
     *
     * @param texts    wie viele Abschnitte, Texte und Audios zugleich entstehen
     * @param pictures wie viele Modellaufrufe der Bilder zugleich laufen
     */
    public void parallel(java.util.function.IntSupplier texts, java.util.function.IntSupplier pictures) {
        production.parallel(texts, pictures);
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
        Reading current = reading.get();
        Remembered known = remembered.get(key);
        // Geänderte Lesemengen gelten sofort: das gemerkte Material passt nur zu denselben Einstellungen.
        if (known != null && now - known.at() < MATERIAL_MILLIS && known.reading().equals(current)) {
            return known.material();
        }
        Optional<PodcastWriter.Material> material = pages.page(caller, sourceId, pageId)
                .map(page -> material(page, children(caller, sourceId, pageId),
                        safe(() -> pages.ancestors(caller, sourceId, pageId)), current));
        remembered.put(key, new Remembered(now, current, material));
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
     * Ein Teil der Seite selbst: Einleitung, Hauptabschnitt (mit seinen Unterabschnitten) oder, ohne Überschriften, ein
     * Stück aus ganzen Absätzen. {@code start} und {@code end} sind Zeichenpositionen im Text der Seite
     * ({@link PageText#text()}), wie ihn der Lesetext zeigt.
     */
    record Part(String title, int start, int end, String place) {
    }

    /**
     * Die Teile der Seite in Lesereihenfolge; ab dem {@code reading.sections()}-ten Hauptabschnitt (bzw. Stück) hängt
     * der Rest am letzten.
     */
    static List<Part> parts(Page page, Reading reading) {
        return parts(page, reading, Kind.PAGE);
    }

    static List<Part> parts(Page page, Reading reading, Kind kind) {
        int max = reading.sections();
        List<Part> parts = new ArrayList<>();
        String here = kind.place + " „" + page.title() + "“";
        PageText text = page.text();
        String all = text.text();
        List<PageText.Section> sections = text.sections();
        if (sections.isEmpty()) {
            List<int[]> chunks = chunks(all, reading.sectionChars());
            for (int i = 0; i < chunks.size() && i < max; i++) {
                int end = i == max - 1 ? all.length() : chunks.get(i)[1];
                parts.add(new Part(chunks.size() == 1 ? page.title() : page.title() + " (" + (i + 1) + ")",
                        chunks.get(i)[0], end, here));
            }
            return parts;
        }
        int top = sections.stream().mapToInt(PageText.Section::level).min().orElse(1);
        if (kind == Kind.BOOK) {
            // Ein Buch hat oft eine einzige Titelüberschrift; erzählt wird nach seinen Kapiteln darunter.
            int title = top;
            if (sections.stream().filter(section -> section.level() == title).count() == 1) {
                top = sections.stream().mapToInt(PageText.Section::level).filter(level -> level > title).min()
                        .orElse(title);
            }
        }
        int level = top;
        List<PageText.Section> main = sections.stream().filter(section -> section.level() == level).toList();
        // Bis zur ersten Hauptüberschrift: Unterabschnitte davor gehören zur Einleitung.
        int introEnd = Math.min(main.get(0).start(), all.length());
        if (!all.substring(0, introEnd).isBlank()) {
            parts.add(new Part("Einleitung", 0, introEnd, here));
        }
        for (int i = 0; i < main.size(); i++) {
            PageText.Section section = main.get(i);
            boolean rest = i == max - 1;
            int end = rest || i == main.size() - 1 ? all.length() : main.get(i + 1).start();
            parts.add(new Part(section.heading(), Math.min(section.start(), all.length()), end,
                    "im Abschnitt „" + section.heading() + "“"));
            if (rest) {
                break;
            }
        }
        return parts;
    }

    /** Stücke aus ganzen Absätzen mit höchstens {@code limit} Zeichen, als {@code [start, end)}. */
    private static List<int[]> chunks(String text, int limit) {
        List<int[]> chunks = new ArrayList<>();
        java.util.regex.Matcher lines = java.util.regex.Pattern.compile("[^\\n]+").matcher(text);
        int start = -1;
        int end = -1;
        int length = 0;
        while (lines.find()) {
            if (lines.group().isBlank()) {
                continue;
            }
            int lineLength = lines.group().strip().length();
            if (start >= 0 && length > 0 && length + lineLength > limit) {
                chunks.add(new int[]{start, end});
                start = -1;
                length = 0;
            }
            if (start < 0) {
                start = lines.start();
            }
            end = lines.end();
            length += lineLength + (length > 0 ? 1 : 0);
        }
        if (start >= 0) {
            chunks.add(new int[]{start, end});
        }
        return chunks;
    }

    /**
     * Das Material: je Hauptabschnitt der Seite ein Teil (mit seinen Unterabschnitten), davor die Einleitung, danach je
     * Unterseite ein Teil mit dem Anfang ihres Texts und, kurz, ihren eigenen Unterseiten. Tabellen aus stumpfen Zahlen
     * fallen weg. Meldung n (ab 1) bis zur Zahl der Teile ist Teil n−1 der Seite ({@link #parts}).
     */
    static PodcastWriter.Material material(Page page, List<Subpage> subpages, List<String> ancestors,
                                           Reading reading) {
        return material(page, subpages, ancestors, reading, Kind.PAGE);
    }

    static PodcastWriter.Material material(Page page, List<Subpage> subpages, List<String> ancestors,
                                           Reading reading, Kind kind) {
        List<PodcastWriter.Story> stories = new ArrayList<>();
        String all = page.text().text();
        for (Part part : parts(page, reading, kind)) {
            stories.add(new PodcastWriter.Story(part.title(),
                    sectionText(all.substring(part.start(), part.end()), reading.sectionChars()), List.of(part.place()),
                    List.of(page.pageId())));
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
        StringBuilder overview = new StringBuilder(kind.subject).append(" „").append(page.title()).append("“");
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
        String start = PodcastWriter.flat(PodcastTables.prune(all));
        if (!start.isBlank()) {
            overview.append(' ').append(kind.start).append(": ").append(PodcastWriter.cut(start, 600));
        }
        return new PodcastWriter.Material(overview.toString(), stories, kind.label + " „" + page.title() + "“",
                PodcastWriter.Format.EXPLAINER).withStoryChars(reading.sectionChars());
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
