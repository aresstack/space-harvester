package com.aresstack.spaceharvester.application.images;

import com.aresstack.spaceharvester.application.assets.AssetStoreException;
import com.aresstack.spaceharvester.application.assets.AssetTooLargeException;
import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.assets.Describable;
import com.aresstack.spaceharvester.application.assets.ImageDescriber;
import com.aresstack.spaceharvester.application.assets.ImageDescriptionException;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.marks.VideoMark;
import com.aresstack.spaceharvester.application.marks.VideoMarks;
import com.aresstack.spaceharvester.application.reading.ReadingViewStore;
import com.aresstack.spaceharvester.application.reading.StoredReadingView;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageAttachment;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Beschreibt die Bilder der Lese-Ansicht einer Seite mit dem Vision-Modell (ADR 0021), auf Abruf und wie der
 * Einzelbild-Weg des Enterprise AI Client ({@code SourceImageAnalysisUseCase.describe}): je Bild nur die Beschreibung,
 * je Bildbytes und Modell nur einmal. Rechte prüft der Aufrufer wie für die Lese-Ansicht und das Bild; Bilder aus
 * Quellen ohne Freigabe für den KI-Dienst gehen nie an das Modell.
 *
 * <p>Beschrieben wird alles Beschreibbare der Seite ({@link Describable}), jedes mit seinem eigenen Prompt: die Bilder
 * der Lese-Ansicht und die Marken eines Videos mit ihrem Standbild ({@link VideoMark}).</p>
 *
 * <p>Übersprungen werden Vorschaubilder von draw.io-Diagrammen (die textuelle Quelle gilt, Angelo 16.10.), SVG und
 * andere Nicht-Rasterbilder sowie Bilder über der Grenze der Asset-Ablage.</p>
 */
public class PageImageAnalysis {

    /** Höchstens so viele Bilder je Seite; weitere stehen als Warnung {@value #TRUNCATED} im Ergebnis. */
    public static final int MAX_IMAGES = 50;
    /**
     * Warnung: Die Seite hat mehr Bilder als {@link #MAX_IMAGES} (bei einem Video mehr Marken, als
     * eingestellt sind).
     */
    public static final String TRUNCATED = "images-truncated";
    /** Warnung: Die Quelle hat keine Lese-Ansicht, also keine Bilder. */
    public static final String NO_READING_VIEW = "no-reading-view";
    /**
     * Warnung: Die Lese-Ansicht der Quelle gehörte nicht zur gespeicherten Fassung der Seite (neuer oder nicht
     * bestätigt), die Bilder also womöglich zu einer anderen; das Ergebnis gilt dann als veraltet, wie die abgelegte
     * Ansicht im Export ({@code page-changed}).
     */
    public static final String PAGE_CHANGED = "page-changed";

    private static final System.Logger LOG = System.getLogger(PageImageAnalysis.class.getName());

    private final Assets assets;
    private final Enrichments enrichments;
    private final Clock clock;
    private final ModelPriority priority = new ModelPriority();

    public PageImageAnalysis(Assets assets, Enrichments enrichments, Clock clock) {
        this.assets = Objects.requireNonNull(assets, "assets");
        this.enrichments = Objects.requireNonNull(enrichments, "enrichments");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Ob gerade ein Vision-Modell eingestellt ist. */
    public boolean available() {
        return assets.describerInUse().isPresent();
    }

    /** Wie viele Beschreibungen zugleich an das eingestellte Modell gehen dürfen ({@code ai.parallel}); ohne Modell 1. */
    public int parallel() {
        return assets.describerInUse().map(model -> Math.max(1, model.parallel())).orElse(1);
    }

    /**
     * Ob die Quelle für das Vision-Modell freigegeben ist ({@code external-processing-sources}); ohne Modell
     * {@code true}, weil dann nicht die Freigabe fehlt. Unabhängig davon, ob es schon Beschreibungen gibt: Nach
     * „Nur diese Seite einmalig beschreiben“ bleibt die Quelle gesperrt, und die App bietet die Knöpfe weiter an.
     */
    public boolean released(String sourceId) {
        return assets.describerInUse().map(model -> model.mayDescribe(sourceId)).orElse(true);
    }

    /**
     * Wie {@link #released(String)} für eine Seite: auch eine Seite auf der Liste freigegebener Seiten (ADR 0028) ist
     * für das Vision-Modell freigegeben.
     */
    public boolean released(PageKey key) {
        return assets.describerInUse().map(model -> mayDescribe(model, key)).orElse(true);
    }

    private boolean mayDescribe(ImageDescriber model, PageKey key) {
        return model.mayDescribe(key.sourceId()) || assets.pageReleased(key);
    }

    /**
     * Die gespeicherten Bilder und Beschreibungen der Seite; beschreibt nichts und fragt die Quelle nicht. Ohne
     * Ergebnis nennt {@link PageImages#reason()}, ob ein Modell oder die Freigabe fehlt. Nennt die zuletzt geladene
     * Lese-Ansicht für einen Bildanhang eine andere Version als beim Beschreiben, ist das Ergebnis
     * {@link PageImages.State#STALE} mit {@value PageImages#ATTACHMENT_CHANGED}.
     */
    public PageImages read(PageSnapshot snapshot) {
        Input input = input(snapshot);
        PageImages images = PageImages.read(snapshot.key(), input.views(), input.sha256());
        if (images.state() == PageImages.State.CURRENT && attachmentChanged(snapshot.key(), images.result())) {
            return new PageImages(PageImages.State.STALE, PageImages.ATTACHMENT_CHANGED, images.result(),
                    images.images(), images.warnings());
        }
        if (images.state() == PageImages.State.MISSING) {
            // Noch nicht beschrieben: dieselbe Liste wie nach dem Beschreiben, nur ohne Text.
            String reason = images.reason();
            Optional<ImageDescriber> model = assets.describerInUse();
            if (model.isEmpty()) {
                reason = PageImages.VISION_MODEL_MISSING;
            } else if (!mayDescribe(model.get(), snapshot.key())) {
                reason = PageImages.SOURCE_NOT_RELEASED;
            }
            return new PageImages(PageImages.State.MISSING, reason, null, listed(snapshot), List.of());
        }
        return images;
    }

    /**
     * Beschreibt alle Bilder der Lese-Ansicht dieser Seite, die für ihre Bytes und das eingestellte Modell noch keine
     * Beschreibung haben, legt die Zuordnung als Ergebnis {@code images} ab und liefert sie. Lädt die Lese-Ansicht und
     * fehlende Bilder über die Anmeldung der Quelle; läuft im aufrufenden Thread und kann je Bild so lange dauern wie
     * die Lesefrist des KI-Dienstes.
     *
     * @throws ImageDescriptionException {@code NOT_CONFIGURED} ohne Vision-Modell, {@code NOT_RELEASED} für eine nicht
     *                                   freigegebene Quelle
     * @throws SourceAccessException     wenn die Lese-Ansicht nicht ladbar ist
     */
    public PageImages describe(PageSnapshot snapshot) {
        try (ModelPriority.Interactive ignored = priority.interactive()) {
            return describe(snapshot, false, entry -> { }, null);
        }
    }

    /**
     * Wie {@link #describe(PageSnapshot)}; {@code progress} erfährt jeden Eintrag, sobald er fertig ist, im
     * aufrufenden Thread, damit die Oberfläche Beschreibungen schon während des Laufs zeigt.
     */
    public PageImages describe(PageSnapshot snapshot, java.util.function.Consumer<ImagePayload.Entry> progress) {
        try (ModelPriority.Interactive ignored = priority.interactive()) {
            return describe(snapshot, false, progress, null);
        }
    }

    /**
     * Wie {@link #describe(PageSnapshot, java.util.function.Consumer)} für den Hintergrundlauf „Bilder beschreiben“:
     * zählt nicht als interaktive Beschreibung ({@link #priority()}), damit der Lauf vor den Klicks des Benutzers
     * zurücktritt statt vor sich selbst.
     */
    public PageImages describeInBackground(PageSnapshot snapshot,
                                           java.util.function.Consumer<ImagePayload.Entry> progress) {
        return describe(snapshot, false, progress, null);
    }

    /**
     * Wie {@link #describeInBackground(PageSnapshot, java.util.function.Consumer)}, die Bilder der Seite aber
     * gleichzeitig über {@code images} (Angelo 21.11.2026: „nicht nur ein Bild nach dem anderen“): Jedes Bild geht als
     * eigene Aufgabe an den Läufer, der Kontingent, Spur und Vorrang regelt. {@code progress} erfährt die Einträge in
     * ihrer Reihenfolge im aufrufenden Thread. Scheitert ein Eintrag oder wirft {@code progress}, beginnt kein weiteres
     * Bild; die laufenden enden, bevor die Ausnahme weitergeht.
     */
    public PageImages describeInBackground(PageSnapshot snapshot,
                                           java.util.function.Consumer<ImagePayload.Entry> progress,
                                           ImageRunner images) {
        return describe(snapshot, false, progress, Objects.requireNonNull(images, "images"));
    }

    /** Führt die Beschreibung eines Bildes aus, etwa in einem eigenen Thread mit eigenem Kontingent. */
    @FunctionalInterface
    public interface ImageRunner {
        java.util.concurrent.CompletableFuture<ImagePayload.Entry> run(
                java.util.function.Supplier<ImagePayload.Entry> image);
    }

    /**
     * Ein Bild, das der Benutzer selbst mitgibt (Chat-Anhang): Es gehört zu keiner Seite und keiner Quelle, also gilt
     * keine Quellenfreigabe, und nichts wird abgelegt. {@code instruction} ist der Prompt des Aufrufers; die Antwort
     * kommt gestreamt vom eingestellten Vision-Modell ({@link ImageDescriber#describe(byte[], String, String)}) und
     * hat Vorrang vor dem Hintergrundlauf; es gilt die Grenze gleichzeitiger Anfragen ({@code ai.parallel}).
     *
     * @throws ImageDescriptionException {@code NOT_CONFIGURED} ohne Vision-Modell
     */
    public String describeAttachment(byte[] image, String mediaType, String instruction) {
        Objects.requireNonNull(image, "image");
        ImageDescriber model = assets.describerInUse().orElseThrow(() -> new ImageDescriptionException(
                ImageDescriptionException.Reason.NOT_CONFIGURED, "Kein Vision-Modell eingestellt", null));
        try (ModelPriority.Interactive ignored = priority.interactive()) {
            prepare(model);
            // Dieselbe Grenze gleichzeitiger Anfragen wie jede Bildbeschreibung (ai.parallel).
            return assets.describeAttachment(image, mediaType, instruction);
        }
    }

    /** Das eingestellte Vision-Modell, leer ohne Modell. */
    public Optional<String> visionModel() {
        return assets.describerInUse().map(ImageDescriber::modelVersion);
    }

    /** Vorrang der interaktiven Beschreibungen vor dem Hintergrundlauf. */
    public ModelPriority priority() {
        return priority;
    }

    /**
     * Wie {@link #describe(PageSnapshot)} für genau diese Seite einer nicht freigegebenen Quelle, weil der Benutzer es
     * ausdrücklich erlaubt hat („Nur diese Seite einmalig beschreiben“): Nur die Bilder dieser Seite gehen an das
     * Modell, eine Freigabe wird nicht gespeichert; die nächste Seite derselben Quelle bleibt gesperrt.
     */
    public PageImages describeOnce(PageSnapshot snapshot) {
        try (ModelPriority.Interactive ignored = priority.interactive()) {
            return describe(snapshot, true, entry -> { }, null);
        }
    }

    /** Wie {@link #describeOnce(PageSnapshot)} mit {@code progress} wie bei {@link #describe(PageSnapshot, java.util.function.Consumer)}. */
    public PageImages describeOnce(PageSnapshot snapshot, java.util.function.Consumer<ImagePayload.Entry> progress) {
        try (ModelPriority.Interactive ignored = priority.interactive()) {
            return describe(snapshot, true, progress, null);
        }
    }

    private PageImages describe(PageSnapshot snapshot, boolean releasedOnce,
                                java.util.function.Consumer<ImagePayload.Entry> progress, ImageRunner runner) {
        PageKey key = snapshot.key();
        ImageDescriber model = assets.describerInUse().orElseThrow(() -> new ImageDescriptionException(
                ImageDescriptionException.Reason.NOT_CONFIGURED, "Kein Vision-Modell eingestellt", null));
        if (!releasedOnce && !mayDescribe(model, key)) {
            throw new ImageDescriptionException(ImageDescriptionException.Reason.NOT_RELEASED,
                    "Quelle nicht für den KI-Dienst freigegeben", null);
        }
        Input input = input(snapshot);
        if (input.sha256() == null) {
            return PageImages.missing(PageImages.NO_TEXT);
        }
        // Die Version steht vor dem ersten Bild fest: wechselt das Modell währenddessen, ist das Ergebnis veraltet.
        String version = ImageProcessor.version(model);
        List<String> warnings = new ArrayList<>();
        List<Describable> items = describables(snapshot, warnings);
        List<ImagePayload.Entry> entries = new ArrayList<>(items.size());
        Map<String, String> versions = new LinkedHashMap<>();
        for (Describable item : items) {
            if (item.id() != null && item.version() != null) {
                versions.putIfAbsent(item.id(), item.version());
            }
        }
        prepare(model);
        if (runner == null) {
            for (int index = 0; index < items.size(); index++) {
                ImagePayload.Entry entry = describe(index, snapshot, items.get(index), releasedOnce, false,
                        model.retries());
                entries.add(entry);
                progress.accept(entry);
            }
        } else {
            entries.addAll(describeTogether(snapshot, items, releasedOnce, model.retries(), progress, runner));
        }
        Enrichment result = new Enrichment(key, ImagePayload.PROCESSOR, version, input.sha256(),
                snapshot.revision(), Enrichment.Status.OK, ImagePayload.write(entries, versions), warnings, null,
                clock.instant());
        enrichments.store().save(result);
        enrichments.flush();
        // ADR 0028: Verlor die Seite ihre Freigabe, während das Modell beschrieb, ist die Rücknahme womöglich schon
        // gelaufen und sähe dieses Ergebnis nie. Erst nach dem Speichern geprüft, damit keine Lücke bleibt.
        if (!releasedOnce && !mayDescribe(model, key)) {
            withdraw(List.of(snapshot), page -> mayDescribe(model, page));
            throw new ImageDescriptionException(ImageDescriptionException.Reason.NOT_RELEASED,
                    "Freigabe während der Beschreibung entzogen", null);
        }
        Input after = input(snapshot);
        return PageImages.read(key, after.views(), after.sha256());
    }

    /**
     * Die Bilder einer Seite gleichzeitig über {@code runner}. Erst wenn jede Aufgabe beendet ist, kehrt der Aufruf
     * zurück oder wirft: Kein Bild arbeitet über die Seite hinaus weiter.
     */
    private List<ImagePayload.Entry> describeTogether(PageSnapshot snapshot, List<Describable> items,
                                                      boolean releasedOnce, int retries,
                                                      java.util.function.Consumer<ImagePayload.Entry> progress,
                                                      ImageRunner runner) {
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        List<java.util.concurrent.CompletableFuture<ImagePayload.Entry>> running = new ArrayList<>(items.size());
        RuntimeException failure = null;
        for (int index = 0; index < items.size() && failure == null; index++) {
            int at = index;
            Describable item = items.get(index);
            try {
                running.add(runner.run(() -> stop.get() ? null
                        : describe(at, snapshot, item, releasedOnce, false, retries)));
            } catch (RuntimeException e) {
                failure = e;
                stop.set(true);
            }
        }
        List<ImagePayload.Entry> entries = new ArrayList<>(items.size());
        for (java.util.concurrent.CompletableFuture<ImagePayload.Entry> image : running) {
            try {
                ImagePayload.Entry entry = image.join();
                if (failure == null && entry != null) {
                    entries.add(entry);
                    progress.accept(entry);
                }
            } catch (java.util.concurrent.CompletionException | java.util.concurrent.CancellationException e) {
                if (failure == null) {
                    failure = e.getCause() instanceof RuntimeException cause ? cause
                            : new IllegalStateException(e.getCause());
                    stop.set(true);
                }
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                    stop.set(true);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
        return entries;
    }

    /** Worauf sich das Ergebnis bezieht und die gespeicherten Ergebnisse mit ihrer Frische dazu. */
    private record Input(String sha256, List<Enrichments.View> views) {
    }

    /**
     * Der Seitentext, oder bei einem Video ohne Text seine Marken mit ihren Standbildern: Neue Marken oder Standbilder
     * machen das Ergebnis dann veraltet wie ein geänderter Text. Ohne beides {@code null}.
     */
    private Input input(PageSnapshot snapshot) {
        Optional<PageText> text = enrichments.text(snapshot);
        if (text.isPresent()) {
            return new Input(text.get().inputSha256(), enrichments.view(snapshot));
        }
        PageKey key = snapshot.key();
        Optional<String> frames = assets.videoMarksInput(key.sourceId(), key.pageId(), snapshot.revision(),
                VideoMarks.slides(enrichments.store(), assets).find(key, snapshot.revision()));
        if (frames.isEmpty()) {
            return new Input(null, enrichments.view(snapshot));
        }
        String processor = enrichments.processorVersion(ImagePayload.PROCESSOR).orElse(null);
        List<Enrichments.View> views = new ArrayList<>();
        for (Enrichment enrichment : enrichments.store().find(key)) {
            if (ImagePayload.PROCESSOR.equals(enrichment.processorId())) {
                views.add(new Enrichments.View(enrichment, enrichment.freshness(frames.get(), processor)));
            }
        }
        return new Input(frames.get(), views);
    }

    /**
     * Nimmt die Bildbeschreibungen der Seiten zurück, die jetzt nicht mehr für den KI-Dienst freigegeben sind
     * (ADR 0028, Abschnitt 4: von der Liste freigegebener Seiten entfernt oder Freigabe der Quelle entzogen): ihr
     * Ergebnis {@code images}, die Beschreibungstexte ihrer Bildanhänge im Volltextindex und die gespeicherten
     * Beschreibungen ihrer Bilder, sofern keine freigegebene Seite dieselben Bytes trägt. Seiten, die {@code released}
     * jetzt zulässt, bleiben unberührt. Fragt weder Quelle noch Modell. Fehler bleiben hier und nennen im Log nur ihre
     * Klasse.
     *
     * @param released ob eine Seite jetzt freigegeben ist, etwa {@code AiRelease::mayProcess}
     * @return wie viele Seiten ein Ergebnis {@code images} verloren
     */
    public int withdraw(java.util.Collection<PageSnapshot> pages, java.util.function.Predicate<PageKey> released) {
        Objects.requireNonNull(released, "released");
        Map<PageKey, String> withdrawn = new LinkedHashMap<>();
        java.util.Set<String> images = new java.util.HashSet<>();
        int results = 0;
        try {
            for (PageSnapshot snapshot : pages) {
                PageKey key = snapshot.key();
                if (released.test(key)) {
                    continue;
                }
                withdrawn.put(key, snapshot.metadata().partitionKey());
                Optional<Enrichment> result = enrichments.store().find(key).stream()
                        .filter(enrichment -> ImagePayload.PROCESSOR.equals(enrichment.processorId())).findFirst();
                if (result.isEmpty()) {
                    continue;
                }
                try {
                    ImagePayload.parse(result.get().payloadJson()).forEach(entry -> {
                        if (entry.sha256() != null) {
                            images.add(entry.sha256());
                        }
                    });
                } catch (IllegalArgumentException e) {
                    // Unlesbar: das Ergebnis fällt trotzdem weg, die Bildanhänge der Seite kennt die Ablage.
                }
                enrichments.store().remove(key, ImagePayload.PROCESSOR);
                results++;
            }
            if (results > 0) {
                enrichments.flush();
            }
            assets.withdrawDescriptions(withdrawn, images, released);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Bildbeschreibungen nach Rücknahme der Freigabe nicht vollständig "
                    + "entfernt ({0})", e.getClass().getName());
        }
        return results;
    }

    /**
     * Die Bilder der Seite ohne Beschreibung, ohne die Quelle zu fragen: die Marken ihres Videos, sonst die Bilder der
     * zuletzt gespeicherten Lese-Ansicht; mit Hash, wenn ihre Bytes schon abliegen (dann hat der Reiter eine Vorschau).
     */
    /** Ob die Lese-Ansicht der Seite gespeichert ist; dann ist eine leere Bildliste wirklich leer. Fragt die Quelle nicht. */
    boolean readingViewStored(PageKey key) {
        return new ReadingViewStore(enrichments.store()).find(key).isPresent();
    }

    private List<ImagePayload.Entry> listed(PageSnapshot snapshot) {
        PageKey key = snapshot.key();
        List<ImagePayload.Entry> entries = new ArrayList<>();
        for (long millis : VideoMarks.slides(enrichments.store(), assets).find(key, snapshot.revision())) {
            String sha = assets.videoFrameAsset(key.sourceId(), key.pageId(), snapshot.revision(), millis)
                    .map(com.aresstack.spaceharvester.application.assets.Asset::sha256).orElse(null);
            entries.add(ImagePayload.Entry.without(entries.size(), Assets.markAttachmentId(key.pageId(), millis), sha,
                    Assets.markFileName(millis), Assets.markTitle(millis), "image/png", ImageStatus.PENDING, null));
        }
        if (!entries.isEmpty()) {
            return entries;
        }
        List<HarvestSource.ViewImage> images = new ReadingViewStore(enrichments.store()).find(key)
                .map(StoredReadingView::images).orElse(List.of());
        for (HarvestSource.ViewImage image : images.subList(0, Math.min(images.size(), MAX_IMAGES))) {
            Optional<com.aresstack.spaceharvester.application.assets.Asset> stored =
                    assets.storedViewImage(key.sourceId(), snapshot.revision(), image);
            entries.add(ImagePayload.Entry.without(entries.size(),
                    stored.map(com.aresstack.spaceharvester.application.assets.Asset::attachmentId)
                            .orElse(image.attachmentId()),
                    stored.map(com.aresstack.spaceharvester.application.assets.Asset::sha256).orElse(null),
                    Assets.viewFileName(image), image.title(), image.mediaType(), ImageStatus.PENDING, null));
        }
        return entries;
    }

    /** Lädt das Modell, falls es noch nicht geladen ist; Fehler melden dann die Bilder selbst. */
    private static void prepare(ImageDescriber model) {
        try {
            if (!model.loaded()) {
                model.load();
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "Vision-Modell nicht geladen ({0})", e.getClass().getName());
        }
    }

    /**
     * Ob das Vision-Modell geladen ist und sofort antwortet (bei Ollama laut {@code /api/ps}); ohne Modell
     * {@code true}.
     */
    public boolean modelLoaded() {
        return assets.describerInUse().map(ImageDescriber::loaded).orElse(true);
    }

    /** Lädt das Vision-Modell und wartet darauf, höchstens die eingestellte Ladefrist. */
    public void loadModel() {
        assets.describerInUse().ifPresent(ImageDescriber::load);
    }

    /**
     * Beschreibt genau ein Bild der Seite ({@code index} wie in der Liste) und legt es im Ergebnis {@code images} ab;
     * die übrigen Einträge bleiben, wie sie waren (noch nicht beschriebene ohne Text). Mit {@code fresh} neu
     * beschrieben statt aus dem Speicher der Beschreibungen.
     *
     * @throws ImageDescriptionException {@code NOT_CONFIGURED} ohne Vision-Modell, {@code NOT_RELEASED} ohne Freigabe
     */
    public PageImages describeOne(PageSnapshot snapshot, int index, boolean releasedOnce, boolean fresh) {
        try (ModelPriority.Interactive ignored = priority.interactive()) {
            return describeOneNow(snapshot, index, releasedOnce, fresh);
        }
    }

    private PageImages describeOneNow(PageSnapshot snapshot, int index, boolean releasedOnce, boolean fresh) {
        PageKey key = snapshot.key();
        ImageDescriber model = assets.describerInUse().orElseThrow(() -> new ImageDescriptionException(
                ImageDescriptionException.Reason.NOT_CONFIGURED, "Kein Vision-Modell eingestellt", null));
        if (!releasedOnce && !mayDescribe(model, key)) {
            throw new ImageDescriptionException(ImageDescriptionException.Reason.NOT_RELEASED,
                    "Quelle nicht für den KI-Dienst freigegeben", null);
        }
        Input input = input(snapshot);
        if (input.sha256() == null) {
            return PageImages.missing(PageImages.NO_TEXT);
        }
        List<String> warnings = new ArrayList<>();
        List<Describable> items = describables(snapshot, warnings);
        if (index < 0 || index >= items.size()) {
            return read(snapshot);
        }
        prepare(model);
        // Erst das Bild beschreiben (dauert), dann unter der Sperre mit dem gespeicherten Ergebnis zusammenführen:
        // Laufen mehrere Einzelbeschreibungen zugleich, geht keine verloren.
        ImagePayload.Entry described = describe(index, snapshot, items.get(index), releasedOnce, fresh,
                model.retries());
        synchronized (merging) {
            // Was schon beschrieben ist, bleibt; der Rest steht ohne Text da.
            List<ImagePayload.Entry> stored = new ArrayList<>();
            enrichments.store().find(key).stream()
                    .filter(found -> ImagePayload.PROCESSOR.equals(found.processorId())).findFirst()
                    .ifPresent(found -> {
                        try {
                            stored.addAll(ImagePayload.parse(found.payloadJson()));
                        } catch (IllegalArgumentException e) {
                            // unlesbar: alles gilt als noch nicht beschrieben
                        }
                    });
            Map<String, ImagePayload.Entry> byId = new LinkedHashMap<>();
            stored.forEach(entry -> byId.putIfAbsent(entry.assetId(), entry));
            List<ImagePayload.Entry> entries = new ArrayList<>(items.size());
            Map<String, String> versions = new LinkedHashMap<>();
            for (int at = 0; at < items.size(); at++) {
                Describable item = items.get(at);
                if (item.id() != null && item.version() != null) {
                    versions.putIfAbsent(item.id(), item.version());
                }
                ImagePayload.Entry known = known(item, at, byId, stored);
                if (at == index) {
                    entries.add(described);
                } else if (known != null) {
                    entries.add(new ImagePayload.Entry(at, known.assetId(), known.sha256(), known.filename(),
                            known.title(), known.mediaType(), known.status(), known.reason(), known.description(),
                            known.describer(), known.model(), known.describedAt()));
                } else {
                    entries.add(ImagePayload.Entry.without(at, item.id(), null, item.filename(), item.title(),
                            item.mediaType(), ImageStatus.PENDING, null));
                }
            }
            Enrichment result = new Enrichment(key, ImagePayload.PROCESSOR, ImageProcessor.version(model),
                    input.sha256(), snapshot.revision(), Enrichment.Status.OK, ImagePayload.write(entries, versions),
                    warnings, null, clock.instant());
            enrichments.store().save(result);
        }
        enrichments.flush();
        if (!releasedOnce && !mayDescribe(model, key)) {
            withdraw(List.of(snapshot), page -> mayDescribe(model, page));
            throw new ImageDescriptionException(ImageDescriptionException.Reason.NOT_RELEASED,
                    "Freigabe während der Beschreibung entzogen", null);
        }
        Input after = input(snapshot);
        return PageImages.read(key, after.views(), after.sha256());
    }

    /** Hält das Zusammenführen einzelner Beschreibungen mit dem gespeicherten Ergebnis an. */
    private final Object merging = new Object();

    /** Der gespeicherte Eintrag zu einem Bild: nach seiner Kennung, sonst an derselben Stelle mit demselben Namen. */
    private static ImagePayload.Entry known(Describable item, int index, Map<String, ImagePayload.Entry> byId,
                                            List<ImagePayload.Entry> stored) {
        ImagePayload.Entry known = item.id() == null ? null : byId.get(item.id());
        if (known == null && index < stored.size()
                && Objects.equals(stored.get(index).filename(), item.filename())) {
            known = stored.get(index);
        }
        return known;
    }

    /** Beschreibt ein Bild; scheitert das Modell, bis zu {@code retries} weitere Male. */
    private ImagePayload.Entry describe(int index, PageSnapshot snapshot, Describable item, boolean releasedOnce,
                                        boolean fresh, int retries) {
        ImagePayload.Entry entry = describeOnce(index, snapshot, item, releasedOnce, fresh);
        for (int retry = 0; retry < retries && entry.status() == ImageStatus.FAILED
                && modelFailure(entry.reason()); retry++) {
            entry = describeOnce(index, snapshot, item, releasedOnce, fresh);
        }
        return entry;
    }

    /** Ein Modellaufruf zum Bild ist gescheitert (nicht erreichbar, Frist, leer oder unbrauchbar). */
    public static final String MODEL_FAILED = "model-failed";
    /** Das Modell hat innerhalb der Lesefrist ({@code ai.timeout}) nicht geantwortet. */
    public static final String MODEL_TIMEOUT = "model-timeout";
    /** Das Modell hat leer geantwortet. */
    public static final String MODEL_EMPTY = "model-empty";
    /** Der KI-Dienst war nicht erreichbar. */
    public static final String MODEL_UNREACHABLE = "model-unreachable";

    /** Ob ein Grund ein gescheiterter Modellaufruf ist, den ein neuer Versuch beheben kann. */
    public static boolean modelFailure(String reason) {
        return MODEL_FAILED.equals(reason) || MODEL_TIMEOUT.equals(reason) || MODEL_EMPTY.equals(reason)
                || MODEL_UNREACHABLE.equals(reason);
    }

    /** Der Grund eines gescheiterten Modellaufrufs, so genau es die Ausnahme sagt. */
    static String failureReason(ImageDescriptionException e) {
        if (e.getCause() instanceof com.aresstack.spaceharvester.ai.AiServiceException cause) {
            return switch (cause.reason()) {
                case TIMEOUT -> MODEL_TIMEOUT;
                case UNREACHABLE -> MODEL_UNREACHABLE;
                default -> MODEL_FAILED;
            };
        }
        return e.getCause() == null && "Leere Beschreibung".equals(e.getMessage()) ? MODEL_EMPTY : MODEL_FAILED;
    }

    private ImagePayload.Entry describeOnce(int index, PageSnapshot snapshot, Describable item, boolean releasedOnce,
                                            boolean fresh) {
        PageKey key = snapshot.key();
        String filename = item.filename();
        String title = item.title();
        if (!item.ready()) {
            return ImagePayload.Entry.without(index, item.id(), null, filename, title, item.mediaType(),
                    ImageStatus.PENDING, "not-ready");
        }
        String skip = item.skipReason();
        if (skip != null) {
            return ImagePayload.Entry.without(index, item.id(), null, filename, title, item.mediaType(),
                    ImageStatus.SKIPPED, skip);
        }
        try {
            Assets.DescribedImage described = assets.describe(key, item, releasedOnce, fresh);
            // B2: Ist das Bild ein Anhang der Seite, steht seine Beschreibung sofort im Volltextindex, nicht erst beim
            // nächsten Ablegen der Anhänge. Nur mit Freigabe (ADR 0028): „einmalig“ Beschriebenes bleibt draußen.
            if (!releasedOnce && item.instruction() == null) {
                assets.indexDescription(key, snapshot.spaceKey(), described);
            }
            return new ImagePayload.Entry(index, described.asset().attachmentId(), described.asset().sha256(),
                    filename, title, described.asset().mediaType(), ImageStatus.DESCRIBED, null,
                    described.description().text(), described.description().describer(),
                    described.description().modelVersion(), described.description().createdAt());
        } catch (ImageDescriptionException e) {
            return switch (e.reason()) {
                case NOT_AN_IMAGE -> ImagePayload.Entry.without(index, item.id(), null, filename, title,
                        item.mediaType(), ImageStatus.SKIPPED, "not-a-raster-image");
                case BUSY -> ImagePayload.Entry.without(index, item.id(), null, filename, title,
                        item.mediaType(), ImageStatus.PENDING, "model-busy");
                case NOT_CONFIGURED, NOT_RELEASED -> throw e;
                default -> ImagePayload.Entry.without(index, item.id(), null, filename, title,
                        item.mediaType(), ImageStatus.FAILED, failureReason(e));
            };
        } catch (AssetTooLargeException e) {
            return ImagePayload.Entry.without(index, item.id(), null, filename, title, item.mediaType(),
                    ImageStatus.SKIPPED, "too-large");
        } catch (SourceAccessException e) {
            return ImagePayload.Entry.without(index, item.id(), null, filename, title, item.mediaType(),
                    ImageStatus.FAILED, code(e.reason()));
        } catch (AssetStoreException e) {
            return ImagePayload.Entry.without(index, item.id(), null, filename, title, item.mediaType(),
                    ImageStatus.FAILED, "asset-store-unavailable");
        }
    }

    /**
     * Alles Beschreibbare der Seite: die Marken ihres Videos mit gezogenem Standbild (höchstens so viele, wie ein Video
     * Marken haben darf), sonst die Bilder ihrer Lese-Ansicht (höchstens {@link #MAX_IMAGES}).
     */
    private List<Describable> describables(PageSnapshot snapshot, List<String> warnings) {
        PageKey key = snapshot.key();
        List<Describable> items = new ArrayList<>();
        for (long millis : VideoMarks.slides(enrichments.store(), assets).find(key, snapshot.revision())) {
            // Auch Marken ohne Standbild: Sie stehen ohne Beschreibung in der Liste.
            items.add(new VideoMark(millis, Assets.markAttachmentId(key.pageId(), millis), snapshot.revision(),
                    assets.videoFrame(key.sourceId(), key.pageId(), snapshot.revision(), millis).orElse(null)));
        }
        if (!items.isEmpty()) {
            return truncated(items, assets.slideThresholds().maxFrames(), warnings);
        }
        List<PageAttachment>[] attachments = new List[1];
        java.util.function.Supplier<List<PageAttachment>> lazy = () -> {
            if (attachments[0] == null) {
                attachments[0] = attachments(key);
            }
            return attachments[0];
        };
        for (HarvestSource.ViewImage image : images(snapshot, warnings)) {
            items.add(new ViewImageDescribable(assets, key, snapshot.revision(), image, lazy));
        }
        return truncated(items, MAX_IMAGES, warnings);
    }

    private static List<Describable> truncated(List<Describable> items, int limit, List<String> warnings) {
        if (items.size() <= limit) {
            return items;
        }
        warnings.add(TRUNCATED);
        return items.subList(0, limit);
    }

    /**
     * Die Bilder der Lese-Ansicht mit ihrer Fassung, wie {@code ExportAssetCollection.readingView}: Nennt die Quelle
     * eine andere Fassung als die gespeicherte (oder keine bestätigte), steht {@value #PAGE_CHANGED} in den Warnungen.
     * Eine Quelle ohne Lese-Ansicht kann ihre Bilder trotzdem kennen; dann gilt die Fassung als unbekannt.
     */
    private List<HarvestSource.ViewImage> images(PageSnapshot snapshot, List<String> warnings) {
        PageKey key = snapshot.key();
        HarvestSource.ReadingPage live;
        try {
            live = assets.readingPage(key.sourceId(), key.pageId());
        } catch (SourceAccessException e) {
            if (e.reason() != SourceAccessException.Reason.UNSUPPORTED) {
                throw e;
            }
            try {
                return assets.viewImages(key.sourceId(), key.pageId());
            } catch (SourceAccessException unsupported) {
                if (unsupported.reason() != SourceAccessException.Reason.UNSUPPORTED) {
                    throw unsupported;
                }
                warnings.add(NO_READING_VIEW);
                return List.of();
            }
        }
        if (live.version() == null || !live.version().equals(snapshot.revision())) {
            warnings.add(PAGE_CHANGED);
        }
        return live.images();
    }

    /**
     * Ob die zuletzt abgelegte Lese-Ansicht (#176, jünger als das Ergebnis) für einen beim Beschreiben gesehenen
     * Bildanhang eine andere Version nennt oder ihn nicht mehr zeigt. Fragt die Quelle nie: Confluence hebt beim
     * Ersetzen eines Anhangs weder die Seitenversion noch den Text an, also zeigt sich ein neues Bild erst, wenn die
     * Lese-Ansicht neu geladen wurde.
     */
    private boolean attachmentChanged(PageKey key, Enrichment result) {
        Map<String, String> described;
        try {
            described = ImagePayload.attachmentVersions(result.payloadJson());
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (described.isEmpty()) {
            return false;
        }
        Optional<StoredReadingView> view = new ReadingViewStore(enrichments.store()).find(key)
                .filter(stored -> stored.renderedAt().isAfter(result.computedAt()));
        if (view.isEmpty()) {
            return false;
        }
        Map<String, String> shown = new LinkedHashMap<>();
        for (HarvestSource.ViewImage image : view.get().images()) {
            if (image.attachmentId() != null) {
                shown.putIfAbsent(image.attachmentId(), image.version());
            }
        }
        // Ersetzt (andere Version) oder aus der Ansicht verschwunden: die Beschreibung gilt nicht mehr sicher.
        return described.entrySet().stream().anyMatch(entry -> !shown.containsKey(entry.getKey())
                || shown.get(entry.getKey()) != null && !shown.get(entry.getKey()).equals(entry.getValue()));
    }

    private List<PageAttachment> attachments(PageKey key) {
        try {
            return assets.pageAttachments(key.sourceId(), key.pageId());
        } catch (SourceAccessException e) {
            // Ohne Anhangsliste erkennt die Analyse keine Diagrammvorschau; das Bild wird dann beschrieben.
            return List.of();
        }
    }


    private static String code(SourceAccessException.Reason reason) {
        return switch (reason) {
            case NOT_FOUND -> "source-object-not-found";
            case RATE_LIMITED -> "source-rate-limited";
            case UNAVAILABLE -> "source-unavailable";
            case ACCESS_DENIED -> "source-access-denied";
            case UNSUPPORTED -> "source-unsupported";
            default -> "source-invalid-response";
        };
    }
}
