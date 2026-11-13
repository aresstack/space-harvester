package com.aresstack.spaceharvester.application.images;

import com.aresstack.spaceharvester.application.assets.AssetStoreException;
import com.aresstack.spaceharvester.application.assets.AssetTooLargeException;
import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.assets.ImageDescriber;
import com.aresstack.spaceharvester.application.assets.ImageDescriptionException;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.PageText;
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
 * <p>Übersprungen werden Vorschaubilder von draw.io-Diagrammen (die textuelle Quelle gilt, Angelo 16.10.), SVG und
 * andere Nicht-Rasterbilder sowie Bilder über der Grenze der Asset-Ablage.</p>
 */
public class PageImageAnalysis {

    /** Höchstens so viele Bilder je Seite; weitere stehen als Warnung {@value #TRUNCATED} im Ergebnis. */
    public static final int MAX_IMAGES = 50;
    /** Warnung: Die Seite hat mehr Bilder als {@link #MAX_IMAGES}. */
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

    public PageImageAnalysis(Assets assets, Enrichments enrichments, Clock clock) {
        this.assets = Objects.requireNonNull(assets, "assets");
        this.enrichments = Objects.requireNonNull(enrichments, "enrichments");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Ob gerade ein Vision-Modell eingestellt ist. */
    public boolean available() {
        return assets.describerInUse().isPresent();
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
        PageText text = enrichments.text(snapshot).orElse(null);
        PageImages images = PageImages.read(snapshot.key(), enrichments.view(snapshot), text);
        if (images.state() == PageImages.State.CURRENT && attachmentChanged(snapshot.key(), images.result())) {
            return new PageImages(PageImages.State.STALE, PageImages.ATTACHMENT_CHANGED, images.result(),
                    images.images(), images.warnings());
        }
        if (images.state() == PageImages.State.MISSING) {
            Optional<ImageDescriber> model = assets.describerInUse();
            if (model.isEmpty()) {
                return PageImages.missing(PageImages.VISION_MODEL_MISSING);
            }
            if (!mayDescribe(model.get(), snapshot.key())) {
                return PageImages.missing(PageImages.SOURCE_NOT_RELEASED);
            }
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
        return describe(snapshot, false);
    }

    /**
     * Wie {@link #describe(PageSnapshot)} für genau diese Seite einer nicht freigegebenen Quelle, weil der Benutzer es
     * ausdrücklich erlaubt hat („Nur diese Seite einmalig beschreiben“): Nur die Bilder dieser Seite gehen an das
     * Modell, eine Freigabe wird nicht gespeichert; die nächste Seite derselben Quelle bleibt gesperrt.
     */
    public PageImages describeOnce(PageSnapshot snapshot) {
        return describe(snapshot, true);
    }

    private PageImages describe(PageSnapshot snapshot, boolean releasedOnce) {
        PageKey key = snapshot.key();
        ImageDescriber model = assets.describerInUse().orElseThrow(() -> new ImageDescriptionException(
                ImageDescriptionException.Reason.NOT_CONFIGURED, "Kein Vision-Modell eingestellt", null));
        if (!releasedOnce && !mayDescribe(model, key)) {
            throw new ImageDescriptionException(ImageDescriptionException.Reason.NOT_RELEASED,
                    "Quelle nicht für den KI-Dienst freigegeben", null);
        }
        Optional<PageText> text = enrichments.text(snapshot);
        if (text.isEmpty()) {
            return PageImages.missing(PageImages.NO_TEXT);
        }
        // Die Version steht vor dem ersten Bild fest: wechselt das Modell währenddessen, ist das Ergebnis veraltet.
        String version = ImageProcessor.version(model);
        List<String> warnings = new ArrayList<>();
        List<HarvestSource.ViewImage> images = images(snapshot, warnings);
        if (images.size() > MAX_IMAGES) {
            images = images.subList(0, MAX_IMAGES);
            warnings.add(TRUNCATED);
        }
        List<PageAttachment> attachments = null;
        List<ImagePayload.Entry> entries = new ArrayList<>(images.size());
        Map<String, String> versions = new LinkedHashMap<>();
        for (HarvestSource.ViewImage image : images) {
            if (image.attachmentId() != null && image.version() != null) {
                versions.putIfAbsent(image.attachmentId(), image.version());
            }
        }
        for (int index = 0; index < images.size(); index++) {
            HarvestSource.ViewImage image = images.get(index);
            if (attachments == null && Assets.viewFileName(image).toLowerCase(Locale.ROOT).endsWith(".png")) {
                attachments = attachments(key);
            }
            entries.add(describe(index, key, snapshot.revision(), image, attachments == null ? List.of()
                    : attachments, releasedOnce));
        }
        Enrichment result = new Enrichment(key, ImagePayload.PROCESSOR, version, text.get().inputSha256(),
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
        return PageImages.read(key, enrichments.view(snapshot), text.get());
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

    private ImagePayload.Entry describe(int index, PageKey key, String pageVersion, HarvestSource.ViewImage image,
                                        List<PageAttachment> attachments, boolean releasedOnce) {
        String filename = Assets.viewFileName(image);
        String title = image.title();
        if (Assets.diagramPreview(attachments, image)) {
            return ImagePayload.Entry.without(index, image.attachmentId(), null, filename, title, image.mediaType(),
                    ImageStatus.SKIPPED, "diagram-source");
        }
        if (vector(image, filename)) {
            return ImagePayload.Entry.without(index, image.attachmentId(), null, filename, title, image.mediaType(),
                    ImageStatus.SKIPPED, "vector-source");
        }
        try {
            Assets.DescribedImage described = assets.describeViewImage(key.sourceId(), key.pageId(), pageVersion,
                    image, releasedOnce);
            return new ImagePayload.Entry(index, described.asset().attachmentId(), described.asset().sha256(),
                    filename, title, described.asset().mediaType(), ImageStatus.DESCRIBED, null,
                    described.description().text(), described.description().describer(),
                    described.description().modelVersion(), described.description().createdAt());
        } catch (ImageDescriptionException e) {
            return switch (e.reason()) {
                case NOT_AN_IMAGE -> ImagePayload.Entry.without(index, image.attachmentId(), null, filename, title,
                        image.mediaType(), ImageStatus.SKIPPED, "not-a-raster-image");
                case BUSY -> ImagePayload.Entry.without(index, image.attachmentId(), null, filename, title,
                        image.mediaType(), ImageStatus.PENDING, "model-busy");
                case NOT_CONFIGURED, NOT_RELEASED -> throw e;
                default -> ImagePayload.Entry.without(index, image.attachmentId(), null, filename, title,
                        image.mediaType(), ImageStatus.FAILED, "model-failed");
            };
        } catch (AssetTooLargeException e) {
            return ImagePayload.Entry.without(index, image.attachmentId(), null, filename, title, image.mediaType(),
                    ImageStatus.SKIPPED, "too-large");
        } catch (SourceAccessException e) {
            return ImagePayload.Entry.without(index, image.attachmentId(), null, filename, title, image.mediaType(),
                    ImageStatus.FAILED, code(e.reason()));
        } catch (AssetStoreException e) {
            return ImagePayload.Entry.without(index, image.attachmentId(), null, filename, title, image.mediaType(),
                    ImageStatus.FAILED, "asset-store-unavailable");
        }
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

    private static boolean vector(HarvestSource.ViewImage image, String filename) {
        String type = image.mediaType() == null ? "" : image.mediaType().toLowerCase(Locale.ROOT);
        return type.startsWith("image/svg") || filename.toLowerCase(Locale.ROOT).endsWith(".svg");
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
