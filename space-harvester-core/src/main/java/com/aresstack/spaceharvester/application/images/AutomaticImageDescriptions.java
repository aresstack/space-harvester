package com.aresstack.spaceharvester.application.images;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.background.BackgroundGroup;
import com.aresstack.spaceharvester.application.background.BackgroundJobs;
import com.aresstack.spaceharvester.application.configuration.ImageSettings;
import com.aresstack.spaceharvester.application.jobs.JobCapacityExceededException;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.release.PageReleases;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Bilder automatisch beschreiben (Fahrplan B1): Folgeschritt nach der Anreicherung einer Quelle und nach neuer
 * KI-Freigabe, gebaut wie die automatischen Synonyme ({@code SynonymDiscovery#automatic}). Nichts geschieht, solange
 * {@code images.automatic} aus ist (Standard). Sonst startet ein Hintergrundlauf „Bilder beschreiben“
 * ({@link ImageDescriptionJobs}, Art {@value ImageDescriptionJobs#KIND}): eine Gruppe je Quelle, ein Unterauftrag je
 * Space mit freigegebenen Seiten, nachrangig und nach einem Neustart fortgesetzt. Er beschreibt über denselben Weg wie
 * der Knopf im Reiter „Bilder“ die Bilder der Seiten, die für das Vision-Modell freigegeben sind (ADR 0028) und für das
 * eingestellte Modell noch keine Beschreibung haben.
 *
 * <p>Je Lauf und Quelle gehen höchstens {@code images.max-per-run} Bilder an das Modell ({@code 0} = unbegrenzt), über
 * alle Spaces zusammen; eine Seite, deren offene Bilder nicht mehr in die Grenze passen, bleibt für den nächsten Lauf.
 * Die Grenze kann um die Bilder einer Seite überzogen werden: Eine Seite ohne gespeicherte Lese-Ansicht zählt vorab als
 * ein Bild, und die erste Seite eines Laufs läuft auch dann, wenn sie allein mehr offene Bilder hat als die ganze
 * Grenze. Ist die Grenze erreicht, nennt ein gelbes Ereignis ({@value #LIMIT_REACHED}) die Zahl der offenen Bilder.
 * Ohne Vision-Modell endet der Auftrag gelb mit {@value #NO_MODEL}, nie rot. Verlangt die Quelle einen Einmalcode,
 * endet er gelb mit {@value #SIGN_IN}. Abbrechen lässt alles stehen, was schon beschrieben ist. Kein Zeitgeber, kein
 * Abfragen: ausgelöst wird nur durch Abgleich und Freigabe.
 */
public final class AutomaticImageDescriptions {

    /** Parameter des Auftrags zum Knopf je Seite, an dem die App ihn als „Bilder beschreiben“ erkennt. */
    public static final String TASK_PARAMETER = "task";
    /** Wert von {@link #TASK_PARAMETER}. */
    public static final String TASK = "images";
    /** Ereignis: kein Vision-Modell eingestellt, bewusst ausgelassen (gelb). */
    public static final String NO_MODEL = "IMAGES_SKIPPED:NO_MODEL";
    /** Ereignis: Obergrenze je Lauf erreicht, offene Bilder folgen beim nächsten Lauf (gelb). */
    public static final String LIMIT_REACHED = "IMAGES_LIMIT_REACHED";
    /** Ereignis: die Quelle verlangt einen Einmalcode, der Lauf ist ausgelassen (gelb). */
    public static final String SIGN_IN = "IMAGES_SKIPPED:SIGN_IN";
    /**
     * Ereignis: das Vision-Modell antwortete nicht (nicht erreichbar, Frist, Fehler, leere Antwort); der Lauf hört auf,
     * statt jedes weitere Bild an ein ausgefallenes Modell zu schicken (rot, ein Fehler).
     */
    public static final String MODEL_FAILED = "IMAGES_MODEL_FAILED";
    /** Ereignis mit den Zahlen des Laufs. */
    public static final String DONE = "IMAGES_DONE";
    /** Phase, solange der Lauf vor einer Beschreibung des Benutzers zurücktritt. */
    public static final String WAITING_PHASE = "Wartet auf freies Modell";
    /** Komponente der Ereignisse. */
    static final String COMPONENT = "Images";

    private static final System.Logger LOG = System.getLogger(AutomaticImageDescriptions.class.getName());

    private final ImageDescriptionJobs images;
    private final Supplier<ImageSettings> settings;
    private final Function<String, Boolean> knownSource;

    /** Für Dienst und Tests: mit eigenen Hintergrundaufträgen ohne Eigentümer. */
    public AutomaticImageDescriptions(Jobs jobs, PageImageAnalysis analysis,
                                      Function<String, Iterator<PageSnapshot>> snapshots,
                                      Supplier<ImageSettings> settings, Function<String, Boolean> knownSource) {
        this(jobs, analysis, snapshots, settings, knownSource, null);
    }

    /** Wie oben, mit dem Eigentümer der Aufträge, etwa {@code Harvester#OWNER}. */
    public AutomaticImageDescriptions(Jobs jobs, PageImageAnalysis analysis,
                                      Function<String, Iterator<PageSnapshot>> snapshots,
                                      Supplier<ImageSettings> settings, Function<String, Boolean> knownSource,
                                      String owner) {
        this(new ImageDescriptionJobs(new BackgroundJobs(jobs, owner), jobs, owner, analysis, snapshots,
                key -> find(snapshots, key), null, settings,
                AccessContext.technicalClient(owner == null ? "space-harvester" : owner)), settings, knownSource);
    }

    /**
     * @param images      der Lauf über das Auftragssystem
     * @param settings    die geltenden Einstellungen, zur Laufzeit gelesen
     * @param knownSource ob die Quelle eingerichtet ist
     */
    public AutomaticImageDescriptions(ImageDescriptionJobs images, Supplier<ImageSettings> settings,
                                      Function<String, Boolean> knownSource) {
        this.images = Objects.requireNonNull(images, "images");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.knownSource = Objects.requireNonNull(knownSource, "knownSource");
    }

    /** Der Lauf über das Auftragssystem, etwa für {@link BackgroundJobs#resumeInterrupted()} im Dienst. */
    public ImageDescriptionJobs images() {
        return images;
    }

    /**
     * Startet den Lauf für eine Quelle, wenn {@code images.automatic} an ist, die Quelle bekannt ist und nicht schon
     * für jeden Space mit freigegebenen Seiten ein Lauf wartet oder läuft.
     *
     * @return die Gruppe, oder leer, wenn keine nötig ist
     */
    public Optional<BackgroundGroup> automatic(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId");
        ImageSettings chosen = settings.get();
        if (chosen == null || !chosen.automatic() || !Boolean.TRUE.equals(knownSource.apply(sourceId))) {
            return Optional.empty();
        }
        try {
            Optional<BackgroundGroup> group = images.startAutomatic(sourceId);
            group.ifPresent(started -> LOG.log(System.Logger.Level.INFO, "Bildbeschreibung für {0} automatisch "
                    + "gestartet: {1} Spaces", sourceId, started.parts().size()));
            return group;
        } catch (JobCapacityExceededException | IllegalStateException e) {
            LOG.log(System.Logger.Level.INFO, "Bildbeschreibung für {0} nicht eingeplant ({1}); der nächste "
                    + "Abgleich holt sie nach", sourceId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Beobachter der Liste freigegebener Seiten (ADR 0028): neu freigegebene Seiten beschreibt der nächste Lauf. */
    public void releasesChanged(PageReleases.Change change) {
        change.added().stream().map(entry -> entry.key().sourceId()).distinct().forEach(sourceId -> {
            try {
                automatic(sourceId);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Bildbeschreibung für {0} nicht eingeplant ({1})", sourceId,
                        e.getClass().getSimpleName());
            }
        });
    }

    /** Ergebnis eines Laufs: beschriebene Seiten und Bilder, offene Bilder, gescheiterte Seiten. */
    record Outcome(int pages, int images, int open, int failed) {
    }

    /** Ein Lauf über alle gespeicherten Seiten der Quelle, ohne Auftrag; für Tests. */
    Outcome run(String sourceId, ImageSettings chosen, JobContext context) {
        return images.run(sourceId, chosen, context);
    }

    private static Optional<PageSnapshot> find(Function<String, Iterator<PageSnapshot>> snapshots,
                                               com.aresstack.spaceharvester.application.snapshot.PageKey key) {
        Iterator<PageSnapshot> all = snapshots.apply(key.sourceId());
        while (all.hasNext()) {
            PageSnapshot snapshot = all.next();
            if (snapshot.key().equals(key)) {
                return Optional.of(snapshot);
            }
        }
        return Optional.empty();
    }

    /** Bilder, deren Laden an einer vorübergehend nicht erreichbaren oder drosselnden Quelle scheiterte. */
    static boolean sourceFailure(String reason) {
        return "source-unavailable".equals(reason) || "source-rate-limited".equals(reason);
    }

    /** Wie viele Bilder der Lauf an dieser Seite neu beschrieben hat (nach Prüfsumme, Bilder mehrfach einmal). */
    static int newlyDescribed(PageImages before, PageImages after) {
        Set<String> known = new HashSet<>();
        if (before.state() == PageImages.State.CURRENT) {
            before.images().stream().filter(image -> image.status() == ImageStatus.DESCRIBED && image.sha256() != null)
                    .forEach(image -> known.add(image.sha256()));
        }
        Set<String> counted = new HashSet<>();
        int count = 0;
        for (ImagePayload.Entry image : after.images()) {
            if (image.status() == ImageStatus.DESCRIBED && image.sha256() != null && !known.contains(image.sha256())
                    && counted.add(image.sha256())) {
                count++;
            }
        }
        return count;
    }

}
