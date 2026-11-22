package com.aresstack.spaceharvester.application.podcast;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.changelog.SummaryPage;
import com.aresstack.spaceharvester.application.changelog.SummaryStatement;
import com.aresstack.spaceharvester.application.changelog.UpdateSummary;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.stepmodel.StepModel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

/**
 * Die Update-Zusammenfassung als Podcast (Angelo 21.11.2026): ein lockeres Gespräch zweier Stimmen über das, was sich
 * getan hat, im Stil von NotebookLM. Der Podcast entsteht als Auftrag {@link JobType#UPDATE_PODCAST} im Hintergrund und
 * Schritt für Schritt, damit niemand auf sein Update warten muss:
 * <ol>
 *     <li>die Grobübersicht der Themen ({@link PodcastWriter#outline}),</li>
 *     <li>je Thema die Abschnitte mit ihren Sprecherwechseln, alle Themen parallel ({@code ai.parallel}),</li>
 *     <li>je Abschnitt der gesprochene Text, ebenfalls parallel,</li>
 *     <li>das Audio Abschnitt für Abschnitt in Sendereihenfolge, damit das Abspielen beginnt, sobald der erste fertig
 *     ist.</li>
 * </ol>
 * Jede Stufe liegt sofort auf der Platte ({@link PodcastStore}); ein neuer Lauf, auch nach Abbruch oder Neustart, macht
 * dort weiter, wo der letzte aufhörte. Jede abgelegte Stufe meldet {@link #subscribe} (ADR 0027, kein Polling).
 *
 * <p>Selbstheilend: Planer und Sprecher haben ihre eigenen Auswege ({@link PodcastWriter}, {@link PodcastSpeaker}).
 * Scheitert ein Abschnitt trotzdem, geht der Auftrag zum nächsten weiter und versucht die gescheiterten am Ende noch
 * einmal; der Podcast bleibt hörbar, die Wiedergabe überspringt die Lücke.
 *
 * <p>Der Schlüssel eines Podcasts folgt aus der Zusammenfassung, die der Aufrufer sehen durfte; eine neue Zusammenfassung
 * ergibt einen neuen Podcast, und der alte derselben Ebene fällt weg. An das Modell geht nur der Text der Zusammenfassung,
 * die selbst nur Seiten mit KI-Freigabe enthält (ADR 0028).
 */
public final class UpdatePodcasts {

    /** Parameter des Auftrags: der Schlüssel des Podcasts. */
    public static final String PODCAST_PARAMETER = "podcast";
    /** Parameter des Auftrags: das Update. */
    public static final String UPDATE_PARAMETER = "update";
    /** Kennung des Verfahrens; Teil des Schlüssels, damit eine neue Fassung neu plant. */
    public static final String VERSION = "update-podcast-v2";

    private final Jobs jobs;
    private final PodcastProduction production;
    private final PodcastStore store;
    private final Ancestors ancestors;
    private final Object starting = new Object();

    /**
     * Die übergeordneten Seiten einer Seite von oben, nur solche, die der Aufrufer sehen darf und die an einen KI-Dienst
     * dürfen (für den kurzen Verweis, wo eine Meldung steht).
     */
    @FunctionalInterface
    public interface Ancestors {
        List<String> of(AccessContext caller, String sourceId, String pageId);

        Ancestors NONE = (caller, sourceId, pageId) -> List.of();
    }

    /**
     * @param model     das Chat-Modell der Planung und der Texte
     * @param ancestors die übergeordneten Seiten einer Seite für den Verweis, wo eine Meldung steht
     * @param parallel  so viele Anfragen schickt der Auftrag höchstens zugleich an das Chat-Modell ({@code ai.parallel})
     */
    public UpdatePodcasts(Jobs jobs, StepModel model, PodcastSpeech speech, PodcastStore store, PodcastVoices voices,
                          Ancestors ancestors, IntSupplier parallel) {
        this(jobs, model, speech, store, voices, ancestors, parallel, PodcastProduction::pauseBefore);
    }

    UpdatePodcasts(Jobs jobs, StepModel model, PodcastSpeech speech, PodcastStore store, PodcastVoices voices,
                   Ancestors ancestors, IntSupplier parallel,
                   java.util.function.ObjIntConsumer<JobContext> waits) {
        this.ancestors = ancestors == null ? Ancestors.NONE : ancestors;
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.production = new PodcastProduction(model, speech, store, voices, parallel, waits);
        this.store = store;
    }

    /** Der Schlüssel des Podcasts einer Zusammenfassung. */
    public static String key(UpdateSummary summary) {
        return key(summary.sourceId(), summary.updateId(), summary.spaceKey(), summary.text(),
                String.valueOf(summary.createdAt()));
    }

    /** Der Schlüssel aus den Teilen einer Zusammenfassung, auch einer abgelegten. */
    public static String key(String sourceId, String updateId, String spaceKey, String text, String createdAt) {
        String material = VERSION + '\n' + sourceId + '\n' + updateId + '\n' + (spaceKey == null ? "" : spaceKey)
                + '\n' + createdAt + '\n' + text;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Ob Chat-Modell und ein Dienst mit Sprachausgabe eingerichtet sind. */
    public boolean available() {
        return production.available();
    }

    /** Der Stand des Podcasts einer Zusammenfassung; leer, solange nichts erzeugt ist. */
    public Optional<Podcast> podcast(UpdateSummary summary) {
        Objects.requireNonNull(summary, "summary");
        String key = key(summary);
        return production.podcast(key, open(key) != null);
    }

    /**
     * Startet die Erzeugung oder setzt sie fort; wartet oder läuft für diesen Podcast schon ein Auftrag, ist er die
     * Antwort. Podcasts älterer Zusammenfassungen derselben Ebene fallen weg.
     *
     * @throws IllegalStateException ohne Chat-Modell oder Sprachausgabe
     */
    public JobStatus start(AccessContext caller, UpdateSummary summary) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(summary, "summary");
        if (!available()) {
            throw new IllegalStateException("Kein Chat-Modell oder keine Sprachausgabe eingerichtet");
        }
        String key = key(summary);
        PodcastWriter.Material material = material(summary, page -> places(caller, summary, page));
        synchronized (starting) {
            JobStatus running = open(key);
            if (running != null) {
                return running;
            }
            store.delete(meta -> !meta.key().equals(key) && meta.sourceId().equals(summary.sourceId())
                    && summary.updateId().equals(meta.updateId())
                    && Objects.equals(meta.spaceKey(), summary.spaceKey()));
            Map<String, String> parameters = Map.of(PODCAST_PARAMETER, key, UPDATE_PARAMETER, summary.updateId());
            return jobs.submit(JobType.UPDATE_PODCAST, summary.sourceId(), summary.spaceKey(), null,
                    caller.jobOwner(), parameters, JobTask.blocking(context -> production.run(context,
                            new PodcastStore.Meta(key, summary.sourceId(), summary.updateId(), summary.spaceKey(),
                                    null, null, material.pageIds(), null, null, null), material)));
        }
    }

    /** Meldet jede abgelegte Stufe dieses Podcasts. */
    public Subscription subscribe(String key, Runnable listener) {
        return production.subscribe(key, listener);
    }

    /**
     * Löscht die Podcasts, deren Zusammenfassung nicht mehr gilt (etwa weil eine Seite die KI-Freigabe verlor und die
     * Zusammenfassung wegfiel, ADR 0028).
     *
     * @param valid ob die Zusammenfassung zu diesem Podcast noch abgelegt ist
     */
    public int prune(Predicate<PodcastStore.Meta> valid) {
        int removed = 0;
        for (PodcastStore.Meta meta : store.all()) {
            if (meta.updateId() == null || valid.test(meta)) {
                continue;
            }
            JobStatus running = open(meta.key());
            if (running != null) {
                // Läuft der Auftrag noch, bricht er ab und löscht seine Ablage beim Ende selbst (ADR 0028).
                production.revoke(meta.key());
                jobs.cancel(running.id());
            } else {
                store.delete(found -> found.key().equals(meta.key()));
            }
            removed++;
        }
        return removed;
    }

    private JobStatus open(String key) {
        return open(jobs, key);
    }

    /** Der offene Auftrag eines Podcasts, {@code null} ohne. */
    static JobStatus open(Jobs jobs, String key) {
        for (JobStatus status : jobs.listAll(JobType.UPDATE_PODCAST)) {
            if (!status.state().terminal() && !status.cancelRequested()
                    && key.equals(status.parameters().get(PODCAST_PARAMETER))) {
                return status;
            }
        }
        return null;
    }

    /**
     * Die Meldungen: je Thema der Zusammenfassung eines, mit der ausführlichen Fassung (ab {@code update-summary-v2})
     * und dem Ort. Ältere Zusammenfassungen ohne Themen ergeben je Absatz eine Meldung.
     *
     * @param places wo eine Seite steht, als kurzer Halbsatz
     */
    static PodcastWriter.Material material(UpdateSummary summary,
                                           java.util.function.Function<SummaryPage, String> places) {
        List<PodcastWriter.Story> stories = new ArrayList<>();
        for (SummaryStatement statement : summary.statements()) {
            String text = blank(statement.details()) ? statement.text() : statement.details();
            if (blank(text)) {
                continue;
            }
            Set<String> where = new LinkedHashSet<>();
            for (SummaryPage page : statement.pages()) {
                String place = places.apply(page);
                if (!blank(place)) {
                    where.add(place);
                }
            }
            stories.add(new PodcastWriter.Story(blank(statement.title()) ? headline(statement.text())
                    : statement.title(), text, List.copyOf(where),
                    statement.pages().stream().map(SummaryPage::pageId).toList()));
        }
        if (stories.isEmpty()) {
            for (String paragraph : summary.text().split("\\n\\s*\\n")) {
                if (!paragraph.isBlank()) {
                    stories.add(new PodcastWriter.Story(headline(paragraph), paragraph, List.of()));
                }
            }
        }
        String space = summary.spaceKey() == null ? null : summary.spaceName(summary.spaceKey());
        return new PodcastWriter.Material(summary.text(), stories,
                summary.spaceKey() == null ? null : "Space " + (blank(space) ? summary.spaceKey() : space));
    }

    /** „auf der Seite „Titel“ im Space Name, unter Eltern › Seite“; der Space fehlt, wenn die Ebene ein Space ist. */
    private String places(AccessContext caller, UpdateSummary summary, SummaryPage page) {
        if (blank(page.title())) {
            return "";
        }
        StringBuilder place = new StringBuilder("auf der Seite „").append(page.title().strip()).append('“');
        String spaceKey = page.spaceKey();
        if (spaceKey != null && !spaceKey.equals(summary.spaceKey())) {
            place.append(" im Space ").append(summary.spaceName(spaceKey));
        }
        List<String> path;
        try {
            path = ancestors.of(caller, summary.sourceId(), page.pageId());
        } catch (RuntimeException e) {
            path = List.of();
        }
        if (!path.isEmpty()) {
            List<String> near = path.subList(Math.max(0, path.size() - 2), path.size());
            place.append(", unter ").append(String.join(" › ", near));
        }
        return place.toString();
    }

    private static String headline(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        int end = flat.indexOf(". ");
        String first = end > 0 ? flat.substring(0, end) : flat;
        return PodcastWriter.cut(first, 60);
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }
}
