package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.ui.Background;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Problems;
import com.aresstack.spaceharvester.app.ui.controls.BusyOverlay;
import com.aresstack.spaceharvester.app.ui.controls.PodcastOrb;
import com.aresstack.spaceharvester.app.ui.controls.ScrollColumn;
import com.aresstack.spaceharvester.app.ui.controls.ImageViewer;
import com.aresstack.spaceharvester.app.ui.controls.PictureStage;
import com.aresstack.spaceharvester.app.ui.controls.TextWithPicture;
import com.aresstack.spaceharvester.app.ui.controls.ViewerPopup;
import com.aresstack.spaceharvester.app.ui.controls.WrappedText;
import com.aresstack.spaceharvester.application.changelog.ChangeKind;
import com.aresstack.spaceharvester.application.changelog.ChangeLogListener;
import com.aresstack.spaceharvester.application.changelog.ChangeReport;
import com.aresstack.spaceharvester.application.changelog.ChangeUpdate;
import com.aresstack.spaceharvester.application.changelog.ContentChange;
import com.aresstack.spaceharvester.application.changelog.ContentDiff;
import com.aresstack.spaceharvester.application.changelog.ParagraphChange;
import com.aresstack.spaceharvester.application.changelog.UpdateText;
import com.aresstack.spaceharvester.application.changelog.ReportedPage;
import com.aresstack.spaceharvester.application.changelog.SummaryPage;
import com.aresstack.spaceharvester.application.changelog.SummaryStatement;
import com.aresstack.spaceharvester.application.changelog.UpdateSummary;
import com.aresstack.spaceharvester.application.jobs.JobListener;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.podcast.UpdatePodcasts.TopicPicture;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Die Quellenseite (ADR 0030): Wird im Explorer eine Wissensquelle gewählt, zeigen „Leseansicht“ und „Text“ ihre
 * Updates. Links die Updates (das offene „seit dem letzten Update“ zuerst, dann je manueller Synchronisierung), rechts
 * je nach {@link Mode} die KI-Zusammenfassung oder die Änderungen des gewählten Updates nach Art; ein Klick auf eine
 * Seite öffnet sie. Neue Zeilen und Marken meldet der Core (ADR 0027), die Ansicht fragt nie in Abständen nach.
 */
final class SourceUpdatesView extends JPanel {

    /** Kein Hinweistext in der Ansicht; wie Updates entstehen, steht hinter dem ?-Knopf. */
    static final String NO_UPDATES = "Noch keine Updates.";
    static final String NO_SUMMARY = "Noch keine Zusammenfassung.";
    static final String NO_PAGES = "In diesem Update gibt es keine Änderung, die du sehen darfst.";
    static final String NOT_RUNNING = "Die Updates erscheinen, sobald der Dienst läuft.";
    static final String HELP = "Ein Update fasst alle manuellen Synchronisierungen eines Tages zusammen und umfasst "
            + "alles, was sich in der Quelle seit dem vorigen Update geändert hat, auch was im Hintergrund geholt wurde; "
            + "die Kreispfeile hinter gescheiterten Abgleichen wiederholen sie. Die Updates stehen als Wolken "
            + "oben, das jüngste ganz rechts; „Seit dem letzten Update“ ist, was danach dazugekommen ist, es zählt zum "
            + "nächsten und ist vorgewählt, solange es dort Änderungen gibt. Ein Klick auf eine Seite öffnet "
            + "sie.\n\n"
            + "Die Leseansicht zeigt die KI-Zusammenfassung des gewählten Updates. Fehlt sie, erstellt das eingestellte "
            + "Chat-Modell sie beim Öffnen von selbst; „Zusammenfassen“ erstellt sie neu. Es liest nur Seiten, die für "
            + "die KI freigegeben sind; die Zusammenfassung bleibt gespeichert. Die Erstbefüllung und Updates ohne "
            + "Änderung bekommen keine.";
    /** Vor den Zahlen von „Seit dem letzten Update“: kein hängender Auftrag, sondern was seither dazukam. */
    static final String OPEN_COUNTS = "Änderungen seit dem letzten manuellen Update: ";
    static final String RETRY_TIP = "Abgleich wiederholen";
    static final String RETRYING = "Gescheiterte Abgleiche werden wiederholt …";
    static final String SUMMARIZE = "Zusammenfassen";
    static final String RESUMMARIZE = "Neu zusammenfassen";
    static final String SUMMARIZING = "Zusammenfassung wird erstellt …";
    static final String SUMMARIZE_TIP = "Update zusammenfassen";
    static final String RESUMMARIZE_TIP = "Zusammenfassung neu erstellen";
    static final String NO_MODEL = "Kein Chat-Modell eingestellt; unter Einstellungen › Modelle wählen";
    /** Leseansicht ohne Chat-Modell: warum dort keine Zusammenfassung steht. */
    static final String NO_MODEL_PAGE = "Für die KI-Zusammenfassung ist kein Chat-Modell eingestellt. Unter "
            + "Einstellungen › Modelle wählen; danach entsteht sie beim Öffnen von selbst.";
    static final String INITIAL_TIP = "Für die Erstbefüllung gibt es keine Zusammenfassung";
    static final String EMPTY_TIP = "In diesem Update hat sich nichts geändert";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", Locale.GERMAN);

    private final Supplier<Actions> actions;
    private final Runner runner;
    /**
     * Werkzeuge der Ansicht (Zusammenfassen, Kopieren, Hilfe); keine eigene Kopfzeile, sie stehen in der gemeinsamen
     * Kopfzeile links neben dem Favoritenstern (Angelo, 17.11.2026).
     */
    private final JPanel tools = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 2, 0));
    /**
     * Die Updates als eine waagrechte Wolkenreihe oben, dasselbe Steuerelement wie das Inhaltsverzeichnis über dem
     * Reiter „Text“ der Seiten (Angelo, 17.11.2026): je Update eine Wolke, das älteste links, das jüngste rechts.
     */
    private final DocumentNavigationSky sky = new DocumentNavigationSky();
    private final ScrollColumn report = new ScrollColumn(new BorderLayout());
    private final JPanel reportBody = new JPanel();
    private final JComponent reportScroll;
    private final BusyOverlay busy;
    /** Werkzeug oben rechts in der Tab-Zeile; der Tooltip beschreibt nur. */
    private final com.aresstack.spaceharvester.app.ui.IconButton summarize =
            new com.aresstack.spaceharvester.app.ui.IconButton(com.aresstack.spaceharvester.app.ui.Glyph.SUMMARIZE,
                    SUMMARIZE_TIP);
    /** Sichtbarer Einstieg im Bericht, solange das Update noch keine Zusammenfassung hat. */
    private final JLabel summarizeEntry = new JLabel(SUMMARIZE, com.aresstack.spaceharvester.app.ui.Glyph.SUMMARIZE,
            JLabel.LEFT);
    private Consumer<String> onStatus = text -> { };
    private Consumer<ReportedPage> onOpen = page -> { };
    private java.util.function.BiConsumer<String, String> onOpenSpace = (source, space) -> { };
    private java.util.function.BiConsumer<String, List<String>> onRetry;
    private ZoneId zone = ZoneId.systemDefault();

    private String sourceId;
    private Subscription subscription = Subscription.NONE;
    private List<ChangeUpdate> updates = List.of();
    private String selected;
    /** Der gewählte Space der Quelle, {@code null} für die ganze Quelle. */
    private String spaceKey;
    /** „Text“: der Update-Text des Core (ADR 0031), seitenweise nachgeladen; leer in der Leseansicht. */
    private final List<UpdateText> windows = new ArrayList<>();
    /** Seiten je Ausschnitt des Update-Texts: große Updates blockieren den Reiter nicht. */
    static final int TEXT_PAGES = 50;
    private ChangeReport shown;
    /** Die Zusammenfassung des gezeigten Updates, {@code null} ohne. */
    private UpdateSummary summary;
    /** Die gerade gezeigte Zusammenfassung (auch die still mitgenommene), für Space-Namen der Themen. */
    private UpdateSummary rendered;
    /** Laufender Auftrag „Zusammenfassen“; bleibt nach „Abbrechen“ gesetzt, bis er wirklich endet. */
    private Run running;
    /** Der zuletzt gesehene Zustand des laufenden Auftrags. */
    private JobState runState;
    /** Updates, für die die Leseansicht die Zusammenfassung schon von selbst angestoßen hat (je Quelle, Ebene). */
    private final java.util.Set<String> autoStarted = new java.util.HashSet<>();
    /** Alle Auftragskennungen, die „Zusammenfassen“ dieser Ansicht schon geliefert hat. */
    private final java.util.Set<String> seenJobs = new java.util.HashSet<>();
    private Subscription jobWatch = Subscription.NONE;
    /** Abo der laufenden Wiederholung gescheiterter Abgleiche ({@link #followRetry}). */
    private Subscription retryWatch = Subscription.NONE;
    /** Erhöht bei jeder Wiederholung und jedem Ebenenwechsel; ein späteres Ende einer älteren zählt nicht. */
    private int retryGeneration;
    /**
     * Die Wiederholung ist durch und die Leseansicht soll still neu zusammenfassen, sobald sie sichtbar ist (Angelo,
     * 21.11.2026). Solange startet {@link #autoSummarize} nichts mit Ring.
     */
    private boolean quietPending;
    /** Die Wiederholung läuft noch: Meldungen lesen neu, aber nichts fasst mit Ring zusammen. */
    private boolean retrying;
    /** Das Neu-Lesen nach der Wiederholung läuft; erst danach darf {@link #quietRefresh} entscheiden. */
    private boolean quietReading;
    /**
     * Die bisherige Zusammenfassung, die während des stillen Laufs stehen bleibt; das Tages-Update wechselt beim
     * Verlängern seine Kennung, für die neue gibt es noch keine.
     */
    private UpdateSummary carried;
    /**
     * Ob die festgehaltene Zusammenfassung nach dem Wechsel der Update-Kennung noch gilt: {@code null} ungeprüft,
     * dann einmal geprüft ({@link Actions#summaryStillValid}); gezeigt wird sie unter neuer Kennung nur nach „ja“.
     */
    private Boolean carriedValid;
    private boolean carriedChecking;
    /** Abo der KI-Freigabe, solange eine Zusammenfassung festgehalten ist: jede Änderung verwirft sie. */
    private Subscription releaseWatch = Subscription.NONE;
    /** Ob ein Chat-Modell eingestellt ist. */
    private boolean available = true;
    private int generation;
    /** Zähler der Update-Liste; Meldungen des Core lesen sie neu, ohne Bericht oder Textfenster zu verwerfen. */
    private int listGeneration;
    /** Zähler der Textfenster: wechselt nur bei Auswahl oder Ebenenwechsel, nicht bei Meldungen. */
    private int textGeneration;
    /** Meldungen des Core: höchstens ein Lauf zur Zeit und ein vorgemerkter Nachlauf, nur bei sichtbarem Reiter. */
    private boolean messageRunning;
    private boolean messagePending;
    private java.util.function.BooleanSupplier showing = this::isShowing;

    /**
     * Was die Ansicht vom gewählten Update zeigt (Angelo, 17.11.2026): unter „Leseansicht“ die KI-Zusammenfassung,
     * unter „Text“ die Änderungen nach Art, maschinennah und Grundlage der Zusammenfassung.
     */
    enum Mode { SUMMARY, CHANGES }

    private final Mode mode;
    /** Der Podcast der Zusammenfassung: Knopf oben rechts im Himmel, nur in der Leseansicht. */
    private final PodcastControl<UpdateSummary> podcast;
    /** Die Teile jedes gezeigten Themas der Zusammenfassung (Nummer in {@link UpdateSummary#statements()}). */
    private final Map<Integer, List<JComponent>> topicParts = new java.util.HashMap<>();
    /** Nimmt die gelbe Markierung des Themas zurück, dem der Podcast gerade folgt. */
    private final List<Runnable> unmark = new ArrayList<>();
    /** Die Themen, von denen der Podcast gerade erzählt; bleiben markiert, auch wenn der Bericht neu gezeichnet wird. */
    private List<Integer> followed = List.of();
    /** Der Text jedes gezeigten Themas (Nummer in {@link UpdateSummary#statements()}), in der Reihenfolge des Berichts. */
    private final Map<Integer, TextWithPicture> bodies = new java.util.LinkedHashMap<>();
    /** Die Bilder des Podcasts, wie sie im Bericht stehen, in seiner Reihenfolge. */
    private List<PlacedPicture> placed = List.of();
    /** Die zuletzt gelesenen Bilder des Podcasts von {@link #picturesOf}. */
    private List<LoadedPicture> loadedPictures = List.of();
    private UpdateSummary picturesOf;
    private int pictureLoads;
    /** Gelesene Bilder je Datei und Stand: ein neu gezeichneter Bericht liest nichts erneut. */
    private final Map<String, PictureStage.Picture> pictureCache = new java.util.concurrent.ConcurrentHashMap<>();
    /** Der Betrachter, den der Podcast selbst geöffnet hat (und wieder schließt). */
    private ViewerPopup cuePopup;
    private Consumer<String> onSelected = id -> { };

    SourceUpdatesView(Supplier<Workspace> workspace, Mode mode) {
        this(() -> Actions.of(workspace.get()), new Runner() {
            @Override
            public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
                Background.run(work, onSuccess, onFailure);
            }
        }, mode);
    }

    SourceUpdatesView(Supplier<Actions> actions, Runner runner, Mode mode) {
        super(new BorderLayout(12, 8));
        this.actions = Objects.requireNonNull(actions, "actions");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.podcast = new PodcastControl<>(() -> {
            Actions current = actions.get();
            return current == null ? null : current.podcasts();
        }, runner);
        podcast.follow(this::followPodcast);
        if (mode == Mode.SUMMARY) {
            // Das Bild des Kapitels steht neben seiner Meldung und geht von dort im Betrachter auf (Angelo 22.11.2026).
            podcast.cue(new PodcastControl.Cue() {
                @Override
                public boolean open(int topic) {
                    return openTopicPicture(topic);
                }

                @Override
                public void close() {
                    ViewerPopup open = cuePopup;
                    cuePopup = null;
                    if (open != null) {
                        open.close();
                    }
                }
            });
            podcast.stored(this::loadPictures);
        }
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(6, 2, 2, 2));
        // Keine Hinweistexte in der Ansicht: die Erklärung steht hinter dem ?-Knopf oben rechts.
        tools.setOpaque(false);
        if (mode == Mode.SUMMARY) {
            tools.add(summarize);
        }
        tools.add(new com.aresstack.spaceharvester.app.ui.controls.CopyButton(this::copyText));
        summarize.addActionListener(e -> summarize());
        summarizeEntry.setFont(Comic.semiBold(13f));
        summarizeEntry.setForeground(Comic.BLUE);
        summarizeEntry.setIconTextGap(6);
        summarizeEntry.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
        clickable(this::summarize, "Das Chat-Modell fasst dieses Update zusammen; nur Seiten mit KI-Freigabe",
                summarizeEntry);
        tools.add(new com.aresstack.spaceharvester.app.ui.controls.HelpButton(24, "Updates", HELP));

        sky.setName("updates.sky");
        // „Seit dem letzten Update“ ist noch kein Update: gestrichelt am Ende (ADR-0030-Nachtrag, Abschnitt 7).
        sky.setDashed(section -> ChangeUpdate.OPEN.equals(section.id()));
        // Erstbefüllung und Updates ohne Änderung bleiben wählbar, treten aber zurück.
        sky.setPale(section -> {
            ChangeUpdate update = entry(updates, section.id());
            return update != null && (update.initial() || update.pages() == 0);
        });
        // In der Wolke Datum und Benutzer, im Tooltip auch die Zahl der Änderungen (Architekt, 17.11.2026).
        sky.setTooltips(section -> {
            ChangeUpdate update = entry(updates, section.id());
            return update == null ? section.label() : section.label() + " · " + counts(update);
        });
        sky.setListener(section -> {
            select(section.id());
            onSelected.accept(section.id());
        });

        reportBody.setLayout(new BoxLayout(reportBody, BoxLayout.Y_AXIS));
        reportBody.setOpaque(false);
        report.setOpaque(false);
        report.add(reportBody, BorderLayout.NORTH);

        reportScroll = Comic.scroll(report);
        busy = new BusyOverlay(reportScroll);
        busy.onCancel(this::cancelSummary);
        if (mode == Mode.SUMMARY) {
            sky.setTrailingInset(PodcastOrb.SIZE + 2 * SkyLayer.ORB_INSET);
        }
        add(new SkyLayer(busy, sky, report, mode == Mode.SUMMARY ? podcast.orb() : null, reportScroll),
                BorderLayout.CENTER);
        // Beim Sichtbarwerden einmal nachholen, was verdeckt gemeldet wurde.
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                becameVisible();
            }
        });
        source(null, null);
    }

    /** {@code listener} erfährt, welches Update der Benutzer gewählt hat (die andere Ansicht folgt). */
    void onSelected(Consumer<String> listener) {
        onSelected = Objects.requireNonNull(listener, "listener");
    }

    /** Wohin die Zeitleiste des Podcasts gemeldet wird (das Hauptfenster stellt sie über die Suchleiste). */
    void podcastEvents(Consumer<com.aresstack.spaceharvester.app.event.ApplicationEvent<?>> events) {
        podcast.events(events);
    }

    /** Der Podcast der gezeigten Zusammenfassung (für Tests). */
    PodcastControl<UpdateSummary> podcast() {
        return podcast;
    }

    /** Die Werkzeuge für die gemeinsame Kopfzeile. */
    JComponent tools() {
        return tools;
    }

    /** {@code listener} bekommt Meldungen für die Statuszeile des Hauptfensters. */
    void onStatus(Consumer<String> listener) {
        onStatus = Objects.requireNonNull(listener, "listener");
    }

    /** {@code listener} öffnet eine Seite aus dem Bericht. */
    void onOpen(Consumer<ReportedPage> listener) {
        onOpen = Objects.requireNonNull(listener, "listener");
    }

    /** {@code listener} öffnet einen Space (Quelle, Space-Schlüssel), etwa aus der Zusammenfassung. */
    void onOpenSpace(java.util.function.BiConsumer<String, String> listener) {
        onOpenSpace = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Die gewählte Quelle, {@code null} für keine: meldet sich beim Änderungsprotokoll an und lädt die Updates im
     * Hintergrund. Eine vorige Anmeldung endet.
     */
    void source(String id, String title) {
        source(id, null, title);
    }

    /**
     * Die gewählte Ebene (ADR-0030-Nachtrag, TZ5): eine Quelle, oder mit {@code space} ein Space als Ausschnitt
     * derselben Updates. Dieselben Marken, die Zähler gelten nur für die Ebene. {@code id == null}: keine.
     */
    void source(String id, String space, String title) {
        subscription.close();
        subscription = Subscription.NONE;
        generation++;
        listGeneration++;
        textGeneration++;
        messagePending = false;
        sourceId = id;
        spaceKey = id == null || space == null || space.isBlank() ? null : space;
        retryWatch.close();
        retryWatch = Subscription.NONE;
        retryGeneration++;
        quietPending = false;
        retrying = false;
        quietReading = false;
        dropCarried();
        updates = List.of();
        selected = null;
        windows.clear();
        shown = null;
        summary = null;
        podcast.summary(null);
        abandonAutoRun();
        offerSummary();
        renderList();
        if (id == null) {
            message(" ");
            return;
        }
        Actions current = actions.get();
        if (current == null) {
            message(NOT_RUNNING);
            return;
        }
        try {
            subscription = current.subscribe(id, changed -> SwingUtilities.invokeLater(() -> {
                if (Objects.equals(changed, sourceId)) {
                    changed();
                }
            }));
        } catch (RuntimeException e) {
            subscription = Subscription.NONE;
        }
        reload();
    }

    /**
     * Eine Meldung des Core. Während eines Abgleichs kommen viele; gelesen wird höchstens ein Lauf zur Zeit (Liste samt
     * Neu-Lesen des gezeigten Updates) mit einem vorgemerkten Nachlauf, und nur, solange der Reiter sichtbar ist.
     */
    private void changed() {
        if (messageRunning || !showing.getAsBoolean()) {
            messagePending = true;
            return;
        }
        messagePending = false;
        messageRunning = true;
        reload(this::messageDone);
    }

    private void messageDone() {
        messageRunning = false;
        if (messagePending) {
            SwingUtilities.invokeLater(this::changed);
        }
    }

    /** Der Reiter ist sichtbar geworden: einmal nachholen, falls verdeckt etwas gemeldet wurde. */
    void becameVisible() {
        if (messagePending && !messageRunning) {
            changed();
        }
        if (quietPending) {
            quietRefresh();
        }
        if (autoSummarize() && shown != null) {
            renderReport(shown);
        }
    }

    /**
     * Die Leseansicht zeigt immer die KI-Zusammenfassung (Angelo, 17.11.2026): Fehlt sie für das gezeigte Update, wird
     * sie beim Öffnen von selbst erstellt, einmal je Update und Ebene, nur bei sichtbarem Reiter und nur mit
     * Chat-Modell. Die Erstbefüllung und Updates ohne Änderung bekommen keine. {@code true}, wenn gestartet.
     */
    private boolean autoSummarize() {
        if (quietPending || retrying) {
            // Während und nach einer Wiederholung fasst quietRefresh still zusammen, nie zusätzlich mit Ring.
            return false;
        }
        if (mode != Mode.SUMMARY || shown == null || (summary != null && !outdated(summary)) || running != null || !available
                || sourceId == null || selected == null || !shown.update().id().equals(selected)
                || shown.update().initial() || shown.pages().isEmpty() || !showing.getAsBoolean()) {
            return false;
        }
        // Die Zähler der Ebene stehen im Listeneintrag (ein Space kann im Update leer sein).
        ChangeUpdate level = entry(updates, selected);
        if (level != null && (level.initial() || level.pages() == 0)) {
            return false;
        }
        if (!autoStarted.add(autoKey(sourceId, spaceKey, selected))) {
            return false;
        }
        summarize(true);
        return running != null;
    }

    /**
     * Eine Zusammenfassung aus der Zeit vor den Themen (update-summary-v1, Angelo 21.11.2026): Sie zählt nur Seiten.
     * Beim Zeigen entsteht einmal von selbst die inhaltliche; bis dahin bleibt die alte stehen.
     */
    static boolean outdated(UpdateSummary shownSummary) {
        String model = shownSummary.model();
        return model != null && model.startsWith("update-summary-v1")
                && shownSummary.statements().stream().allMatch(statement -> statement.title() == null);
    }

    /** Tests: ob der Reiter als sichtbar gilt (sonst {@link #isShowing()}). */
    void showingForTest(java.util.function.BooleanSupplier visible) {
        showing = Objects.requireNonNull(visible, "visible");
    }

    /** Liest die Updates neu; die Auswahl bleibt, solange es das Update noch gibt. */
    void reload() {
        reload(() -> { });
    }

    /** {@code done} läuft auf dem EDT, sobald Liste und ein etwaiges Neu-Lesen des Updates fertig sind. */
    private void reload(Runnable done) {
        Actions current = actions.get();
        String id = sourceId;
        String space = spaceKey;
        if (current == null || id == null) {
            done.run();
            return;
        }
        int mine = ++listGeneration;
        runner.run(() -> current.updates(id, space), found -> {
            if (mine != listGeneration || !Objects.equals(id, sourceId) || !Objects.equals(space, spaceKey)) {
                done.run();
                return;
            }
            List<ChangeUpdate> fresh = List.copyOf(found);
            boolean changed = !fresh.equals(updates);
            ChangeUpdate before = entry(updates, selected);
            updates = fresh;
            String keep = updates.stream().map(ChangeUpdate::id).filter(u -> u.equals(selected)).findFirst()
                    .orElse(preselected(updates));
            if (keep == null) {
                selected = null;
                shown = null;
                summary = null;
                podcast.summary(null);
                offerSummary();
                renderList();
                message(NO_UPDATES);
                done.run();
                return;
            }
            // Während eines Abgleichs kommen laufend Meldungen: Ein abgeschlossenes Update ändert sich nie mehr, sein
            // Bericht bleibt samt Rollposition stehen; neu gelesen wird nur das offene oder eine neue Auswahl.
            // Ändert sich der Eintrag selbst (etwa „zusammengefasst“ aus einem anderen Fenster), wird er neu gelesen.
            boolean reread = !keep.equals(selected) || shown == null || ChangeUpdate.OPEN.equals(keep)
                    || !Objects.equals(before, entry(fresh, keep));
            if (reread) {
                select(keep, keep.equals(selected), done);
                return;
            }
            if (changed) {
                renderList();
            }
            done.run();
        }, failure -> {
            if (mine == listGeneration) {
                message("Updates nicht geladen: " + Problems.describe(failure).title());
            }
            done.run();
        });
    }

    /**
     * Vorgewählt ist „die letzte Änderung“ (Angelo): „Seit dem letzten Update“, wenn es dort Änderungen gibt (Angelo,
     * 22.11.2026: das Neueste zuerst, nicht die Vergangenheit); sonst das jüngste abgeschlossene Update mit Änderungen
     * in der Ebene, keine Erstbefüllung; sonst das jüngste abgeschlossene; sonst „Seit dem letzten Update“
     * (ADR-0030-Nachtrag, offener Punkt 2). Nach der nächsten manuellen Synchronisierung ist das offene leer und fällt
     * weg; dann ist das neue Update mit seinem Datum vorgewählt.
     */
    static String preselected(List<ChangeUpdate> in) {
        return in.stream().filter(update -> update.open() && !update.initial() && update.pages() > 0).findFirst()
                .or(() -> in.stream().filter(update -> !update.open() && !update.initial() && update.pages() > 0)
                        .findFirst())
                .or(() -> in.stream().filter(update -> !update.open()).findFirst())
                .or(() -> in.stream().findFirst()).map(ChangeUpdate::id).orElse(null);
    }

    /** Wählt ein Update und lädt seinen Bericht. */
    void select(String updateId) {
        if (!Objects.equals(updateId, selected)) {
            dropCarried();
        }
        select(updateId, false, () -> { });
    }

    /**
     * {@code keepPosition}: dasselbe Update neu gelesen, die Rollposition im Bericht bleibt. {@code done} läuft, wenn
     * das Ergebnis da ist (gezeichnet oder verworfen).
     */
    private void select(String updateId, boolean keepPosition, Runnable done) {
        Actions current = actions.get();
        String id = sourceId;
        if (current == null || id == null || updateId == null) {
            done.run();
            return;
        }
        if (!updateId.equals(selected)) {
            summary = null;
            podcast.summary(null);
        }
        if (!keepPosition) {
            textGeneration++;
        }
        boolean switched = !updateId.equals(selected);
        selected = updateId;
        if (switched) {
            abandonAutoRun();
        }
        renderList();
        offerSummary();
        int mine = ++generation;
        boolean summaries = mode == Mode.SUMMARY;
        String space = spaceKey;
        // Neu-Lesen desselben Updates: so viele Seiten wie gezeigt, damit die Ansicht nicht zusammenklappt.
        int wanted = keepPosition ? loadedPages() : 0;
        runner.run(() -> {
            if (!summaries) {
                // „Text“: der Update-Text mit den geänderten Absätzen, fensterweise.
                List<UpdateText> texts = new ArrayList<>();
                List<ReportedPage> pages = new ArrayList<>();
                UpdateText window;
                do {
                    window = current.updateText(id, updateId, space, pages.size(), TEXT_PAGES);
                    texts.add(window);
                    pages.addAll(window.report().pages());
                } while (window.more() && !window.report().pages().isEmpty() && pages.size() < wanted);
                return new Loaded(new ChangeReport(texts.getFirst().report().update(), pages), null, false, texts);
            }
            return new Loaded(current.report(id, updateId, space), current.summary(id, updateId, space),
                    current.summariesAvailable(), List.of());
        }, found -> {
            if (mine != generation || !updateId.equals(selected)) {
                done.run();
                return;
            }
            if (keepPosition && loadedPages() > wanted) {
                // Inzwischen wurden weitere Seiten nachgeladen: mit der neuen Zahl noch einmal lesen.
                select(updateId, true, done);
                return;
            }
            if (keepPosition && found.report().equals(shown) && Objects.equals(found.summary(), summary)
                    && markdown(found.texts()).equals(markdown(windows))) {
                done.run();
                return;
            }
            java.awt.Point position = keepPosition ? viewPosition() : new java.awt.Point();
            windows.clear();
            windows.addAll(found.texts());
            shown = found.report();
            if (found.summary() == null && carried != null && updateId.equals(carried.updateId())) {
                // Dieselbe Kennung ohne Zusammenfassung heißt: der Core hat sie verworfen (Rechte, KI-Freigabe), nicht
                // bloß die Spanne verlängert. Dann bleibt auch die alte nicht stehen.
                dropCarried();
            } else if (found.summary() == null && carried != null && carriedValid == null) {
                // Neue Kennung (Tages-Update verlängert): einmal fragen, ob die alte noch gilt; bis dahin nicht zeigen.
                checkCarried(current, id, space);
            }
            summary = found.summary();
            podcast.summary(summary);
            available = found.available();
            autoSummarize();
            renderReport(shown);
            offerSummary();
            restore(position);
            done.run();
        }, failure -> {
            if (mine == generation) {
                message("Änderungen nicht geladen: " + Problems.describe(failure).title());
            }
            done.run();
        });
    }

    private record Loaded(ChangeReport report, UpdateSummary summary, boolean available, List<UpdateText> texts) {
    }

    /** Die schon geladenen Seiten des Update-Texts. */
    private int loadedPages() {
        return windows.stream().mapToInt(window -> window.report().pages().size()).sum();
    }

    /**
     * Die Textform der Fenster am Stück. Jedes Fenster mit weiteren Seiten endet im Core mit „… weitere Seiten ab“;
     * diese Zeile bleibt nur beim letzten Fenster stehen.
     */
    static String markdown(List<UpdateText> texts) {
        StringBuilder markdown = new StringBuilder();
        for (int i = 0; i < texts.size(); i++) {
            UpdateText window = texts.get(i);
            String text = window.markdown() == null ? "" : window.markdown();
            if (i < texts.size() - 1 && !window.sections().isEmpty()) {
                UpdateText.Section end = window.sections().getLast();
                text = text.substring(0, Math.min(text.length(), end.start() + end.length()));
            }
            markdown.append(text);
        }
        return markdown.toString();
    }

    /** Lädt den nächsten Ausschnitt des Update-Texts und hängt seine Seiten an; die Rollposition bleibt. */
    private void loadMore() {
        Actions current = actions.get();
        UpdateText last = windows.isEmpty() ? null : windows.getLast();
        if (current == null || last == null || !last.more()) {
            return;
        }
        String id = sourceId;
        String space = spaceKey;
        String updateId = last.updateId();
        int offset = loadedPages();
        int mine = textGeneration;
        runner.run(() -> current.updateText(id, updateId, space, offset, TEXT_PAGES), next -> {
            // Meldungen des Core verwerfen das Nachladen nicht; nur Auswahl, Ebenenwechsel oder ein doppelter Klick.
            if (mine != textGeneration || !updateId.equals(selected) || offset != loadedPages()) {
                return;
            }
            java.awt.Point position = viewPosition();
            windows.add(next);
            List<ReportedPage> pages = new ArrayList<>(shown.pages());
            pages.addAll(next.report().pages());
            shown = new ChangeReport(shown.update(), pages);
            renderReport(shown);
            restore(position);
        }, failure -> {
            if (mine == textGeneration) {
                onStatus.accept("Weitere Seiten nicht geladen: " + Problems.describe(failure).title());
            }
        });
    }

    /** Ein gestarteter Auftrag „Zusammenfassen“; {@code jobId} ist {@code null}, solange der Start unterwegs ist. */
    /** {@code quiet}: still nach einer Wiederholung, ohne Ring; die bisherige Zusammenfassung bleibt stehen. */
    private record Run(String jobId, String sourceId, String spaceKey, String updateId, boolean auto, boolean quiet) {
    }

    private static ChangeUpdate entry(List<ChangeUpdate> in, String id) {
        return id == null ? null : in.stream().filter(u -> u.id().equals(id)).findFirst().orElse(null);
    }

    /**
     * „Zusammenfassen“ gibt es für ein gewähltes Update mit Änderungen, nicht für die Erstbefüllung, und nur mit
     * eingestelltem Chat-Modell. Läuft der Auftrag für das gezeigte Update, liegt das BusyOverlay über dem Bericht.
     */
    private void offerSummary() {
        ChangeUpdate update = shown != null && shown.update().id().equals(selected) ? shown.update()
                : entry(updates, selected);
        boolean possible = sourceId != null && update != null && !update.initial() && update.pages() > 0;
        String tip = !available ? NO_MODEL : update != null && update.initial() ? INITIAL_TIP
                : update != null && update.pages() == 0 ? EMPTY_TIP : summary == null ? SUMMARIZE_TIP : RESUMMARIZE_TIP;
        summarize.setVisible(mode == Mode.SUMMARY && sourceId != null);
        summarize.setEnabled(possible && available && running == null);
        summarize.setToolTipText(tip);
        summarize.getAccessibleContext().setAccessibleName(summary == null ? SUMMARIZE : RESUMMARIZE);
        summarizeEntry.setVisible(summary == null && summarize.isEnabled());
        // Ein stiller Lauf nach einer Wiederholung zeigt keinen Ring: die Seite hat schon Inhalt (Angelo, 21.11.2026).
        boolean mine = runningHere() && !running.quiet();
        if (mine != busy.isBusy()) {
            busy.setText(SUMMARIZING);
            busy.setBusy(mine);
        }
    }

    /**
     * Startet den Auftrag „Zusammenfassen“ für das gewählte Update (ADR 0030, Abschnitt 5). Er läuft im Core und steht
     * in der Auftragsliste; fertig meldet er sich über die Auftragsmeldungen, die Zusammenfassung selbst kommt mit der
     * Meldung des Änderungsprotokolls. Nie zwei zugleich aus dieser Ansicht.
     */
    private void summarize() {
        summarize(false);
    }

    /** {@code auto}: von der Leseansicht selbst angestoßen; solange er wartet, bricht ein Wechsel ihn ab. */
    private void summarize(boolean auto) {
        summarize(auto, false);
    }

    private void summarize(boolean auto, boolean quiet) {
        Actions current = actions.get();
        String id = sourceId;
        String space = spaceKey;
        String updateId = selected;
        if (current == null || id == null || updateId == null || running != null) {
            return;
        }
        Run starting = new Run(null, id, space, updateId, auto, quiet);
        running = starting;
        runState = JobState.QUEUED;
        offerSummary();
        if (!quiet) {
            onStatus.accept(SUMMARIZING);
        }
        runner.run(() -> current.summarize(id, updateId, space), status -> {
            if (running != starting) {
                return;
            }
            // Der Core liefert für dasselbe Update und dieselbe Ebene den schon wartenden oder laufenden Auftrag (#842),
            // etwa aus einem anderen Fenster. Als eigener Selbst-Auftrag (abbrechbar) gilt nur eine neue Kennung.
            boolean fresh = seenJobs.add(status.id());
            Run run = new Run(status.id(), id, space, updateId, auto && fresh, quiet);
            running = run;
            runState = status.state();
            watch(current, run);
            if (leftBeforeStart(run)) {
                abandonAutoRun();
            }
        }, failure -> {
            if (running == starting) {
                running = null;
                offerSummary();
                if (shown != null && mode == Mode.SUMMARY) {
                    renderReport(shown);
                }
            }
            onStatus.accept("Zusammenfassen nicht möglich: " + Problems.describe(failure).title());
        });
    }

    /** Erst anmelden, dann den Stand lesen: so geht das Ende des Auftrags nicht verloren (ADR 0027). */
    private void watch(Actions current, Run run) {
        jobWatch.close();
        try {
            jobWatch = current.subscribeJobs(new JobListener() {
                @Override
                public void changed(JobStatus status) {
                    if (status.id().equals(run.jobId())) {
                        SwingUtilities.invokeLater(() -> progressed(run, status));
                    }
                }

                @Override
                public void removed(JobStatus last) {
                    changed(last);
                }
            });
        } catch (RuntimeException e) {
            jobWatch = Subscription.NONE;
        }
        runner.run(() -> current.job(run.jobId()), status -> progressed(run, status), failure -> { });
    }

    /** Ein neuer Stand des Auftrags; erst sein Ende gibt „Zusammenfassen“ wieder frei. */
    private void progressed(Run run, JobStatus status) {
        if (running != run) {
            return;
        }
        runState = status.state();
        if (!status.state().terminal()) {
            return;
        }
        running = null;
        jobWatch.close();
        jobWatch = Subscription.NONE;
        if (run.quiet()) {
            // Ab jetzt gilt, was der Core liefert: die neue Zusammenfassung, sonst der Einstieg.
            dropCarried();
        }
        onStatus.accept(switch (status.state()) {
            case COMPLETED -> run.quiet() ? "Zusammenfassung aktualisiert" : "Zusammenfassung erstellt";
            case CANCELLED -> "Abgebrochen; die bisherige Zusammenfassung bleibt.";
            case PAUSED -> "Zusammenfassung angehalten; fortsetzen unter Aufträge.";
            default -> "Zusammenfassung fehlgeschlagen"
                    + (status.error() == null ? "" : ": " + status.error().message());
        });
        offerSummary();
        boolean here = Objects.equals(run.sourceId(), sourceId) && Objects.equals(run.spaceKey(), spaceKey)
                && Objects.equals(run.updateId(), selected);
        if (status.state() == JobState.COMPLETED && here) {
            select(selected, true, () -> { });
        } else if (here && shown != null && mode == Mode.SUMMARY) {
            // Nicht erstellt: statt „wird erstellt“ wieder der Einstieg zum erneuten Versuch.
            renderReport(shown);
        } else if (autoSummarize() && shown != null) {
            // Höchstens ein Auftrag zur Zeit: erst jetzt bekommt das inzwischen gezeigte Update seinen.
            renderReport(shown);
        }
    }

    /** Ob der Auftrag „Zusammenfassen“ für das gezeigte Update und die gezeigte Ebene läuft. */
    private boolean runningHere() {
        return running != null && Objects.equals(running.sourceId(), sourceId)
                && Objects.equals(running.spaceKey(), spaceKey) && Objects.equals(running.updateId(), selected);
    }

    /** Bricht den laufenden Auftrag ab; die bisherige Zusammenfassung bleibt. Das Ende meldet der Auftrag selbst. */
    private void cancelSummary() {
        Actions current = actions.get();
        Run run = running;
        if (current == null || run == null || run.jobId() == null) {
            return;
        }
        busy.setText("Wird abgebrochen …");
        runner.run(() -> current.cancelJob(run.jobId()), status -> progressed(run, status), failure -> { });
    }

    /**
     * Der Benutzer wechselt Update oder Ebene. Ein selbst angestoßener Auftrag, der noch wartet, wird abgebrochen
     * (Durchklicken im Baum startet so keine Modellaufrufe); beim nächsten Öffnen entsteht er neu. Ein laufender läuft
     * zu Ende, sein Ergebnis bleibt; von Hand gestartete bleiben unberührt. Höchstens ein Auftrag zur Zeit.
     */
    private void abandonAutoRun() {
        Run run = running;
        Actions current = actions.get();
        if (run == null || !run.auto() || run.jobId() == null || runState != JobState.QUEUED || current == null) {
            return;
        }
        runner.run(() -> {
            JobStatus now = current.job(run.jobId());
            return now.state() == JobState.QUEUED ? current.cancelJob(run.jobId()) : now;
        }, status -> {
            if (status.state() == JobState.CANCELLED) {
                autoStarted.remove(autoKey(run.sourceId(), run.spaceKey(), run.updateId()));
            }
            progressed(run, status);
        }, failure -> { });
    }

    /** Ob die Ansicht das Update des Auftrags schon verlassen hat, bevor seine Kennung da war. */
    private boolean leftBeforeStart(Run run) {
        return !(Objects.equals(run.sourceId(), sourceId) && Objects.equals(run.spaceKey(), spaceKey)
                && Objects.equals(run.updateId(), selected));
    }

    private static String autoKey(String sourceId, String spaceKey, String updateId) {
        return sourceId + "\u0000" + spaceKey + "\u0000" + updateId;
    }

    private java.awt.Point viewPosition() {
        javax.swing.JViewport port = reportScroll instanceof javax.swing.JScrollPane pane ? pane.getViewport() : null;
        return port == null ? new java.awt.Point() : port.getViewPosition();
    }

    private void restore(java.awt.Point position) {
        if (reportScroll instanceof javax.swing.JScrollPane pane) {
            report.validate();
            pane.getViewport().setViewPosition(position);
        }
    }

    /** Die Wolkenreihe der Updates; die Auswahl ist markiert und steht in der Mitte. */
    private void renderList() {
        List<Navigation.Section> clouds = new ArrayList<>();
        for (ChangeUpdate update : updates.reversed()) {
            clouds.add(new Navigation.Section(update.id(), heading(update), Navigation.Origin.HEADING, 1, 0, -1, "",
                    null, List.of()));
        }
        // Erst die Auswahl, dann die Reihe: so steht sie ohne Umweg über die erste Wolke gleich in der Mitte.
        if (selected != null) {
            sky.setActive(selected, null);
        }
        sky.setNavigation(clouds.isEmpty() ? null
                : new Navigation(Navigation.State.CURRENT, Navigation.Analysis.HEADINGS, "", clouds));
        if (selected != null) {
            sky.setActive(selected, null);
        }
    }

    /**
     * Bericht unten, die Wolkenreihe als durchsichtige Ebene darüber wie über dem Lesetext der Seiten
     * ({@link NavigableDocumentPage}); oben bekommt der Bericht so viel Platz, wie die Reihe meldet.
     */
    private static final class SkyLayer extends javax.swing.JLayeredPane {

        /** Abstand des Podcast-Knopfs zum Rand, wie der Vorlese-Orb im Enterprise AI Client. */
        static final int ORB_INSET = 10;

        private final JComponent content;
        private final DocumentNavigationSky sky;
        private final JComponent orb;
        private final JComponent scroll;

        SkyLayer(JComponent content, DocumentNavigationSky sky, JComponent padded, JComponent orb,
                 JComponent scroll) {
            this.content = content;
            this.sky = sky;
            this.orb = orb;
            this.scroll = scroll;
            setLayout(null);
            add(content, javax.swing.JLayeredPane.DEFAULT_LAYER);
            add(sky, javax.swing.JLayeredPane.PALETTE_LAYER);
            if (orb != null && scroll instanceof javax.swing.JScrollPane pane) {
                add(orb, javax.swing.JLayeredPane.MODAL_LAYER);
                // Bildlaufleiste erscheint oder verschwindet: der Knopf bleibt links daneben.
                pane.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
                    @Override
                    public void componentResized(java.awt.event.ComponentEvent event) {
                        doLayout();
                    }
                });
            }
            sky.addPropertyChangeListener(DocumentNavigationSky.TOP_INSET_PROPERTY, event -> {
                Object value = sky.getClientProperty(DocumentNavigationSky.TOP_INSET_PROPERTY);
                int inset = value instanceof Integer pixels ? Math.max(0, pixels) : 0;
                padded.setBorder(BorderFactory.createEmptyBorder(inset, 0, 0, 0));
                padded.revalidate();
            });
        }

        @Override
        public void doLayout() {
            content.setBounds(0, 0, getWidth(), getHeight());
            sky.setBounds(0, 0, getWidth(), getHeight());
            if (orb != null && scroll instanceof javax.swing.JScrollPane pane) {
                int bar = pane.getVerticalScrollBar().isVisible() ? pane.getVerticalScrollBar().getWidth() : 0;
                int top = com.aresstack.enterpriseai.ui.comic.theme.ResearchUiMetrics.SKY_PADDING_TOP
                        + (com.aresstack.enterpriseai.ui.comic.theme.ResearchUiMetrics.CLOUD_CHIP_HEIGHT
                        - PodcastOrb.SIZE) / 2;
                orb.setBounds(getWidth() - bar - ORB_INSET - PodcastOrb.SIZE, Math.max(2, top), PodcastOrb.SIZE,
                        PodcastOrb.SIZE);
            }
        }

        @Override
        public Dimension getPreferredSize() {
            return content.getPreferredSize();
        }
    }

    private int reportRenders;

    private void renderReport(ChangeReport found) {
        reportRenders++;
        reportBody.removeAll();
        topicParts.clear();
        unmark.clear();
        bodies.clear();
        placed = List.of();
        ChangeUpdate update = found.update();
        // Eine Zeile für den Zeitraum (Angelo, 21.11.2026): „von bis“ statt Datum und darunter derselbe Zeitraum.
        boolean span = !update.open() && update.from() != null;
        JLabel title = new JLabel(span ? when(update.from()) + " bis " + heading(update) : heading(update));
        title.setFont(Comic.semiBold(15f));
        title.setForeground(Comic.INK);
        add(reportBody, title);
        if (!span) {
            add(reportBody, Comic.wrapped(period(update), Comic.MUTED, 12.5f));
        }
        // Was früher in der Liste unter dem Datum stand; die Wolke oben trägt nur Datum und Benutzer.
        add(reportBody, Comic.wrapped(counts(update), Comic.MUTED, 12.5f));
        if (update.mark() != null && update.mark().failedSyncs() > 0) {
            add(reportBody, failedRow(update));
        }
        reportBody.add(Box.createVerticalStrut(10));
        if (mode == Mode.SUMMARY) {
            renderSummaryPage(found);
            return;
        }
        if (update.initial()) {
            add(reportBody, Comic.wrapped("Erstbefüllung: " + pages(update.pages()) + " kamen in den Bestand. Eine "
                    + "Seitenliste gibt es dafür nicht.", Comic.TEXT, 13.5f));
            for (Map.Entry<String, Integer> space : sortedSpaces(update)) {
                add(reportBody, Comic.wrapped("Space " + space.getKey() + ": " + pages(space.getValue()),
                        Comic.MUTED, 12.5f));
            }
        } else if (found.pages().isEmpty()) {
            add(reportBody, Comic.wrapped(NO_PAGES, Comic.MUTED, 13.5f));
        } else {
            for (Map.Entry<ChangeKind, List<ReportedPage>> group : found.byKind().entrySet()) {
                JLabel kind = new JLabel(kindLabel(group.getKey()) + " (" + kindCount(update, group) + ")");
                kind.setFont(Comic.semiBold(14f));
                kind.setForeground(Comic.INK);
                kind.setBorder(BorderFactory.createEmptyBorder(8, 0, 4, 0));
                add(reportBody, kind);
                for (ReportedPage page : group.getValue()) {
                    add(reportBody, pageRow(page));
                    JComponent diff = diffBlock(page);
                    if (diff != null) {
                        add(reportBody, diff);
                    }
                    reportBody.add(Box.createVerticalStrut(4));
                }
            }
        }
        UpdateText last = windows.isEmpty() ? null : windows.getLast();
        if (last != null && last.more()) {
            int rest = last.totalPages() - loadedPages();
            JLabel more = new JLabel("Weitere " + pages(rest) + " laden");
            more.setFont(Comic.semiBold(13f));
            more.setForeground(Comic.BLUE);
            more.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
            clickable(this::loadMore, "Die nächsten " + Math.min(rest, TEXT_PAGES) + " Seiten des Updates laden", more);
            add(reportBody, more);
        }
        String local = localCounts(update);
        if (!local.isEmpty()) {
            reportBody.add(Box.createVerticalStrut(8));
            add(reportBody, Comic.wrapped(local, Comic.MUTED, 12.5f));
        }
        reportBody.revalidate();
        reportBody.repaint();
    }

    /**
     * Die rote Zeile der gescheiterten Abgleiche, dahinter die Kreispfeile „Abgleich wiederholen“ (Angelo, 21.11.2026):
     * nur am jüngsten Update, denn das Wiederholen gehört zum heutigen Update; was heute schon ein Update hat, wird
     * darin zusammengelegt (ein Update je Tag), sonst entsteht das von heute.
     */
    private JComponent failedRow(ChangeUpdate update) {
        int failed = update.mark().failedSyncs();
        JLabel text = new JLabel(failed == 1 ? "Ein Abgleich ist dabei gescheitert."
                : failed + " Abgleiche sind dabei gescheitert.");
        text.setFont(Comic.regular(12.5f));
        text.setForeground(Comic.RED);
        JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        row.add(text);
        if (onRetry != null && update.id().equals(latestClosed())) {
            List<String> spaces = update.mark().retrySpaces();
            com.aresstack.spaceharvester.app.ui.IconButton retry = new com.aresstack.spaceharvester.app.ui.IconButton(
                    com.aresstack.spaceharvester.app.ui.Glyph.SYNC, retryTip(spaces.size())).compact();
            retry.setName("updates.retry");
            retry.setForeground(Comic.INK);
            retry.addActionListener(e -> {
                // Einmal je Anzeige: das neue Ergebnis legt der Core als Marke ab, die Ansicht liest es per Meldung.
                retry.setEnabled(false);
                onStatus.accept(RETRYING);
                onRetry.accept(sourceId, spaces);
            });
            row.add(Box.createHorizontalStrut(4));
            row.add(retry);
        }
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    static String retryTip(int spaces) {
        return spaces == 0 ? RETRY_TIP + " (alle Spaces der Quelle)"
                : RETRY_TIP + " (" + (spaces == 1 ? "ein Space" : spaces + " Spaces") + ")";
    }

    /** Kennung des jüngsten abgeschlossenen Updates, {@code null} ohne. */
    private String latestClosed() {
        for (ChangeUpdate candidate : updates) {
            if (!candidate.open()) {
                return candidate.id();
            }
        }
        return null;
    }

    /**
     * {@code listener} wiederholt die gescheiterten Abgleiche: Quelle und Spaces, leer für alle Spaces der Quelle. Ohne
     * ihn steht hinter der roten Zeile kein Knopf.
     */
    void onRetry(java.util.function.BiConsumer<String, List<String>> listener) {
        onRetry = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Verfolgt die Wiederholung gescheiterter Abgleiche (Angelo, 21.11.2026): sind alle {@code jobIds} beendet und
     * zeigt die Ansicht noch dieselbe Quelle, liest sie die Updates neu; die Leseansicht fasst dann still neu zusammen,
     * ohne Ring, die bisherige Zusammenfassung bleibt bis dahin stehen. Kein Abfragen: das Ende kommt per
     * Auftragsmeldung (ADR 0027). Aufruf auf dem EDT.
     */
    void followRetry(String source, List<String> jobIds) {
        Actions current = actions.get();
        if (current == null || source == null || !source.equals(sourceId) || jobIds == null || jobIds.isEmpty()) {
            return;
        }
        retryWatch.close();
        int mine = ++retryGeneration;
        retrying = true;
        if (mode == Mode.SUMMARY && carried == null && summary != null) {
            // Das Tages-Update wechselt beim Verlängern seine Kennung: die bisherige Zusammenfassung festhalten.
            carried = summary;
            carriedValid = null;
            watchReleases(current);
        }
        java.util.Set<String> open = java.util.concurrent.ConcurrentHashMap.newKeySet();
        open.addAll(jobIds);
        java.util.function.Consumer<JobStatus> seen = status -> {
            if (status.state().terminal() && open.remove(status.id()) && open.isEmpty()) {
                SwingUtilities.invokeLater(() -> retryFinished(mine, source));
            }
        };
        // Erst anmelden, dann den Stand lesen: so geht kein Ende verloren.
        try {
            retryWatch = current.subscribeJobs(new JobListener() {
                @Override
                public void changed(JobStatus status) {
                    seen.accept(status);
                }

                @Override
                public void removed(JobStatus last) {
                    if (open.remove(last.id()) && open.isEmpty()) {
                        SwingUtilities.invokeLater(() -> retryFinished(mine, source));
                    }
                }
            });
        } catch (RuntimeException e) {
            retryWatch = Subscription.NONE;
        }
        for (String jobId : jobIds) {
            runner.run(() -> current.job(jobId), seen, failure -> {
                if (open.remove(jobId) && open.isEmpty()) {
                    retryFinished(mine, source);
                }
            });
        }
    }

    /** Vergisst die festgehaltene Zusammenfassung samt Prüfung und Abo der KI-Freigabe. */
    private void dropCarried() {
        carried = null;
        carriedValid = null;
        releaseWatch.close();
        releaseWatch = Subscription.NONE;
    }

    /** Jede Änderung der KI-Freigabe verwirft die festgehaltene Zusammenfassung sofort (Abnahme #1037). */
    private void watchReleases(Actions current) {
        releaseWatch.close();
        try {
            releaseWatch = current.subscribeReleases(() -> SwingUtilities.invokeLater(() -> {
                if (carried != null) {
                    dropCarried();
                    // Auch die gezeigte Zusammenfassung neu beim Core lesen: er prüft die Freigabe erneut.
                    if (selected != null) {
                        select(selected, true, () -> { });
                    } else if (shown != null) {
                        renderReport(shown);
                    }
                }
            }));
        } catch (RuntimeException e) {
            releaseWatch = Subscription.NONE;
        }
    }

    /** Einmal je Wechsel der Kennung: gilt die festgehaltene noch? Bei „nein“ verschwindet sie sofort. */
    private void checkCarried(Actions current, String source, String space) {
        UpdateSummary held = carried;
        if (carriedChecking) {
            return;
        }
        carriedChecking = true;
        runner.run(() -> current.summaryStillValid(source, held.updateId(), space), valid -> {
            carriedChecking = false;
            if (carried != held) {
                return;
            }
            if (Boolean.TRUE.equals(valid)) {
                carriedValid = Boolean.TRUE;
            } else {
                dropCarried();
            }
            if (shown != null) {
                renderReport(shown);
            }
        }, failure -> {
            carriedChecking = false;
            if (carried == held) {
                dropCarried();
                if (shown != null) {
                    renderReport(shown);
                }
            }
        });
    }

    /** Ob die Ansicht gerade still aktualisiert: Wiederholung läuft, wartet auf Sichtbarkeit oder fasst still zusammen. */
    private boolean quietPhase() {
        return retrying || quietPending || runningHere() && running.quiet();
    }

    private void retryFinished(int generation, String source) {
        if (generation != retryGeneration || !Objects.equals(source, sourceId)) {
            return;
        }
        retryWatch.close();
        retryWatch = Subscription.NONE;
        retrying = false;
        if (mode != Mode.SUMMARY) {
            reload();
            return;
        }
        quietPending = true;
        quietReading = true;
        // Liste neu, dann das gezeigte Update samt Zusammenfassung neu lesen: ein abgeschlossenes Update liest die
        // Liste sonst nur bei geändertem Eintrag neu, die Zusammenfassung kann aber inzwischen veraltet sein.
        Runnable read = () -> {
            quietReading = false;
            quietRefresh();
        };
        reload(() -> {
            if (selected != null && Objects.equals(source, sourceId)) {
                select(selected, true, read);
            } else {
                read.run();
            }
        });
    }

    /**
     * Nach der Wiederholung: fehlt die Zusammenfassung des gezeigten Updates oder ist sie veraltet, entsteht sie still
     * neu. Ist der Reiter verdeckt, wartet das bis {@link #becameVisible}.
     */
    private void quietRefresh() {
        if (!quietPending || quietReading || !showing.getAsBoolean()) {
            return;
        }
        quietPending = false;
        boolean outdated = summary == null || summary.stale();
        boolean possible = shown != null && selected != null && shown.update().id().equals(selected)
                && !shown.update().initial() && !shown.pages().isEmpty() && available && sourceId != null;
        ChangeUpdate level = entry(updates, selected);
        if (level != null && (level.initial() || level.pages() == 0)) {
            possible = false;
        }
        if (!outdated || !possible || running != null) {
            dropCarried();
            if (shown != null) {
                renderReport(shown);
            }
            offerSummary();
            return;
        }
        // Zählt als selbst angestoßen für dieses Update: autoSummarize startet danach keinen zweiten.
        autoStarted.add(autoKey(sourceId, spaceKey, selected));
        summarize(true, true);
        if (running == null) {
            dropCarried();
        }
        renderReport(shown);
    }

    /** „Leseansicht“: die Zusammenfassung, sonst der Einstieg „Zusammenfassen“; die Änderungen stehen unter „Text“. */
    private void renderSummaryPage(ChangeReport found) {
        ChangeUpdate update = found.update();
        if (summary != null) {
            renderSummary(found, summary);
        } else if (carried != null && quietPhase()
                && (carried.updateId().equals(update.id()) || Boolean.TRUE.equals(carriedValid))) {
            // Still neu zusammenfassen: bis die neue da ist, bleibt die bisherige stehen.
            renderSummary(found, carried);
        } else if (update.initial()) {
            add(reportBody, Comic.wrapped("Erstbefüllung: " + pages(update.pages()) + " kamen in den Bestand. Eine "
                    + "Zusammenfassung gibt es ab dem ersten Update mit Änderungen.", Comic.TEXT, 13.5f));
        } else if (found.pages().isEmpty()) {
            add(reportBody, Comic.wrapped(NO_PAGES, Comic.MUTED, 13.5f));
        } else if (!available) {
            add(reportBody, Comic.wrapped(NO_MODEL_PAGE, Comic.MUTED, 13.5f));
        } else if (runningHere()) {
            add(reportBody, Comic.wrapped(SUMMARIZING, Comic.MUTED, 13.5f));
        } else {
            add(reportBody, Comic.wrapped(NO_SUMMARY, Comic.MUTED, 13.5f));
            reportBody.add(Box.createVerticalStrut(8));
            add(reportBody, summarizeEntry);
        }
        reportBody.revalidate();
        reportBody.repaint();
    }

    /**
     * Die Zusammenfassung (Angelo, 21.11.2026): oben der Überblick, darunter die Themen je Space, Space mit Namen und
     * anklickbar, darunter klein die Zahlen. Themen über mehrere Spaces stehen für sich. Je Thema Titel, kurzer Text,
     * ein Knopf für die ausführliche KI-Zusammenfassung und die Seiten, ab {@link #OPEN_LINKS} zugeklappt. Spaces ohne
     * Thema (etwa ohne KI-Freigabe) stehen aufklappbar am Ende. Die Modellkennung steht nur im Tooltip.
     */
    private void renderSummary(ChangeReport found, UpdateSummary summary) {
        int before = reportBody.getComponentCount();
        try {
            renderTopics(found, summary);
            // Neu gezeichnet, während der Podcast läuft (etwa nach einem Abgleich): die Markierung bleibt.
            markFollowed();
            // Die Bilder stehen sofort wieder da; ob inzwischen weitere fertig sind, fragt der Hintergrund.
            showPictures();
            loadPictures();
        } catch (RuntimeException e) {
            // Nie eine leere Seite: was an der Darstellung scheitert, fällt auf den reinen Text zurück.
            topicParts.clear();
            unmark.clear();
            bodies.clear();
            placed = List.of();
            while (reportBody.getComponentCount() > before) {
                reportBody.remove(reportBody.getComponentCount() - 1);
            }
            add(reportBody, Comic.wrapped("Zusammenfassung", Comic.INK, 14f));
            add(reportBody, Comic.wrapped(summary.text(), Comic.TEXT, 13.5f));
            for (SummaryStatement statement : summary.statements()) {
                add(reportBody, Comic.wrapped((statement.title() == null ? "" : statement.title() + ": ")
                        + statement.text(), Comic.TEXT, 13.5f));
            }
        }
    }

    private void renderTopics(ChangeReport found, UpdateSummary summary) {
        rendered = summary;
        JLabel title = new JLabel("Zusammenfassung");
        title.setFont(Comic.semiBold(14f));
        title.setForeground(Comic.INK);
        add(reportBody, title);
        WrappedText meta = Comic.wrapped(summaryMeta(summary), Comic.MUTED, 12f);
        meta.setName("summary-meta");
        meta.setToolTipText(summaryModelTip(summary));
        add(reportBody, meta);
        // Während des stillen Neu-Zusammenfassens kein Hinweis „veraltet“: die neue kommt gleich.
        if (summary.stale() && !quietPhase()) {
            add(reportBody, Comic.wrapped("Das Update hat sich danach geändert; diese Zusammenfassung kennt nicht "
                    + "alles.", Comic.ORANGE, 12.5f));
        }
        // Ältere Zusammenfassungen (ohne Themen) haben als Text nur ihre Aussagen aneinandergereiht: dann nur diese.
        boolean topical = summary.statements().stream().anyMatch(statement -> statement.title() != null);
        if (summary.text() != null && !summary.text().isBlank() && (topical || summary.statements().isEmpty())) {
            reportBody.add(Box.createVerticalStrut(6));
            add(reportBody, Comic.wrapped(summary.text(), Comic.TEXT, 13.5f));
        }
        Map<String, List<ReportedPage>> bySpace = new java.util.LinkedHashMap<>();
        found.pages().forEach(page -> bySpace.computeIfAbsent(page.spaceKey(), ignored -> new ArrayList<>())
                .add(page));
        Map<String, List<SummaryStatement>> topics = topicsBySpace(summary.statements());
        Map<SummaryStatement, Integer> numbering = new java.util.IdentityHashMap<>();
        for (int i = 0; i < summary.statements().size(); i++) {
            numbering.put(summary.statements().get(i), i);
        }
        // Spaces mit Themen, die mit den meisten Seiten zuerst; danach was mehrere Spaces betrifft.
        List<String> spaces = new ArrayList<>(topics.keySet());
        spaces.remove(ACROSS_SPACES);
        spaces.sort(java.util.Comparator.comparingInt((String key) -> -bySpace.getOrDefault(key, List.of()).size())
                .thenComparing(key -> summary.spaceName(key), String.CASE_INSENSITIVE_ORDER));
        if (topics.containsKey(ACROSS_SPACES)) {
            spaces.add(0, ACROSS_SPACES);
        }
        boolean flat = summary.statements().stream().flatMap(statement -> statement.pages().stream())
                .allMatch(page -> page.spaceKey() == null);
        for (String space : spaces) {
            reportBody.add(Box.createVerticalStrut(flat ? 0 : 12));
            if (!flat) {
                add(reportBody, spaceHeading(space));
            }
            if (!ACROSS_SPACES.equals(space)) {
                String numbers = spaceCounts(bySpace.getOrDefault(space, List.of()));
                if (!numbers.isEmpty()) {
                    add(reportBody, Comic.wrapped(numbers, Comic.MUTED, 12f));
                }
            }
            for (SummaryStatement statement : topics.get(space)) {
                renderTopic(statement, found, ACROSS_SPACES.equals(space), numbering.get(statement));
            }
        }
        List<String> rest = bySpace.keySet().stream().filter(key -> key != null && !topics.containsKey(key))
                .sorted(java.util.Comparator.comparing(key -> summary.spaceName(key), String.CASE_INSENSITIVE_ORDER))
                .toList();
        if (!rest.isEmpty() && !flat) {
            reportBody.add(Box.createVerticalStrut(12));
            JPanel list = new JPanel();
            list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
            list.setOpaque(false);
            for (String key : rest) {
                JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
                row.setOpaque(false);
                row.add(spaceLink(key, 12.5f));
                JLabel numbers = new JLabel("  " + spaceCounts(bySpace.get(key)));
                numbers.setFont(Comic.regular(12f));
                numbers.setForeground(Comic.MUTED);
                row.add(numbers);
                add(list, row);
            }
            add(reportBody, toggle((rest.size() == 1 ? "Ein weiterer Space" : rest.size() + " weitere Spaces")
                    + " ohne Thema in der Zusammenfassung", list, false));
        }
        if (summary.unreadableChanges() > 0) {
            reportBody.add(Box.createVerticalStrut(8));
            add(reportBody, Comic.wrapped(summary.unreadableChanges() == 1
                    ? "Eine Änderung durfte die KI nicht lesen; sie fehlt in der Zusammenfassung."
                    : summary.unreadableChanges() + " Änderungen durfte die KI nicht lesen; sie fehlen in der "
                    + "Zusammenfassung.", Comic.MUTED, 12.5f));
        }
        reportBody.add(Box.createVerticalStrut(12));
    }

    /** Schlüssel der Gruppe „Über mehrere Spaces“; kein Space-Schlüssel kann so heißen. */
    static final String ACROSS_SPACES = "\u0000mehrere";
    /** Bis zu so vielen Seiten stehen die Verweise eines Themas offen da, darüber zugeklappt. */
    static final int OPEN_LINKS = 3;

    /**
     * Die Themen je Space: ein Thema mit Seiten aus genau einem Space gehört zu ihm, eines aus mehreren Spaces zu
     * {@link #ACROSS_SPACES}, eines ohne bekannten Space (ältere Zusammenfassungen) ebenfalls dorthin, ohne Überschrift.
     */
    static Map<String, List<SummaryStatement>> topicsBySpace(List<SummaryStatement> statements) {
        Map<String, List<SummaryStatement>> grouped = new java.util.LinkedHashMap<>();
        for (SummaryStatement statement : statements) {
            java.util.Set<String> spaces = new java.util.TreeSet<>();
            statement.pages().stream().map(SummaryPage::spaceKey).filter(Objects::nonNull).forEach(spaces::add);
            String key = spaces.size() == 1 ? spaces.iterator().next() : ACROSS_SPACES;
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(statement);
        }
        return grouped;
    }

    private JComponent spaceHeading(String space) {
        if (ACROSS_SPACES.equals(space)) {
            JLabel label = new JLabel("Über mehrere Spaces");
            label.setFont(Comic.semiBold(14f));
            label.setForeground(Comic.INK);
            return label;
        }
        return spaceLink(space, 14f);
    }

    /** Der Space mit Namen; ein Klick wählt ihn links im Baum. */
    private JLabel spaceLink(String space, float size) {
        JLabel link = new JLabel(rendered.spaceName(space));
        link.setName("summary-space");
        link.setFont(Comic.semiBold(size));
        link.setForeground(Comic.BLUE);
        String name = rendered.spaceName(space);
        clickable(() -> onOpenSpace.accept(sourceId, space),
                name.equals(space) ? "Space öffnen" : "Space " + space + " öffnen", link);
        return link;
    }

    /** Die Zahlen eines Space klein: „3 neu · 1 geändert“. */
    static String spaceCounts(List<ReportedPage> pages) {
        Map<ChangeKind, Integer> kinds = new java.util.EnumMap<>(ChangeKind.class);
        pages.forEach(page -> kinds.merge(page.kind(), 1, Integer::sum));
        List<String> parts = new ArrayList<>();
        kinds.forEach((kind, count) -> parts.add(count + " " + kindLabel(kind).toLowerCase(Locale.GERMAN)));
        return String.join(" · ", parts);
    }

    /** Gelb wie die Treffer der Suche (ReaderFind). */
    static final java.awt.Color PODCAST_MARK = new java.awt.Color(0xFFF59D);

    /**
     * Folgt dem Podcast (Angelo 22.11.2026): rollt zum ersten Thema, von dem er gerade erzählt, und hinterlegt alle
     * diese Themen gelb wie die Suche; die vorige Markierung verschwindet. Leer = nur die Markierung weg.
     */
    void followPodcast(List<Integer> statements) {
        followed = List.copyOf(statements);
        JComponent first = markFollowed();
        if (first != null && reportScroll instanceof javax.swing.JScrollPane pane) {
            report.validate();
            javax.swing.JViewport port = pane.getViewport();
            java.awt.Rectangle at = javax.swing.SwingUtilities.convertRectangle(first.getParent(), first.getBounds(),
                    report);
            // ein Fünftel der Höhe Luft darüber, damit Überschrift und Kontext darüber sichtbar bleiben
            int y = Math.max(0, Math.min(at.y - port.getExtentSize().height / 5,
                    report.getHeight() - port.getExtentSize().height));
            port.setViewPosition(new java.awt.Point(port.getViewPosition().x, y));
        }
    }

    /** Markiert die Themen, denen der Podcast gerade folgt, und nur diese; das erste markierte Teil oder null. */
    private JComponent markFollowed() {
        unmark.forEach(Runnable::run);
        unmark.clear();
        JComponent first = null;
        for (Integer number : followed) {
            for (JComponent part : topicParts.getOrDefault(number, List.of())) {
                first = first == null ? part : first;
                mark(part);
            }
        }
        return first;
    }

    private void mark(JComponent part) {
        if (part instanceof TextWithPicture body) {
            body.highlight(PODCAST_MARK);
            unmark.add(() -> body.highlight(null));
            return;
        }
        if (part instanceof javax.swing.text.JTextComponent text) {
            try {
                Object tag = text.getHighlighter().addHighlight(0, text.getDocument().getLength(),
                        new javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(PODCAST_MARK));
                unmark.add(() -> text.getHighlighter().removeHighlight(tag));
            } catch (javax.swing.text.BadLocationException e) {
                // ein leerer Text braucht keine Markierung
            }
            return;
        }
        boolean opaque = part.isOpaque();
        java.awt.Color background = part.getBackground();
        part.setOpaque(true);
        part.setBackground(PODCAST_MARK);
        part.repaint();
        unmark.add(() -> {
            part.setOpaque(opaque);
            part.setBackground(background);
            part.repaint();
        });
    }

    /** Gelb markierte Teile (für Tests). */
    List<String> podcastMarked() {
        List<String> marked = new ArrayList<>();
        for (List<JComponent> parts : topicParts.values()) {
            for (JComponent part : parts) {
                if (part instanceof TextWithPicture body) {
                    if (body.highlighted()) {
                        marked.add(body.text());
                    }
                } else if (part instanceof javax.swing.text.JTextComponent text
                        && text.getHighlighter().getHighlights().length > 0) {
                    marked.add(text.getText());
                } else if (part.isOpaque() && PODCAST_MARK.equals(part.getBackground())) {
                    marked.add(((JLabel) part.getComponent(0)).getText());
                }
            }
        }
        return marked;
    }

    /** Ein Thema: Titel mit Details-Knopf, kurzer Text, Seiten. */
    private void renderTopic(SummaryStatement statement, ChangeReport found, boolean across, Integer number) {
        reportBody.add(Box.createVerticalStrut(6));
        List<JComponent> parts = new ArrayList<>();
        if (number != null) {
            topicParts.put(number, parts);
        }
        if (statement.title() != null && !statement.title().isBlank()) {
            JPanel head = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
            head.setOpaque(false);
            JLabel topic = new JLabel(statement.title());
            topic.setName("summary-topic");
            topic.setFont(Comic.semiBold(13.5f));
            topic.setForeground(Comic.INK);
            head.add(topic);
            if (statement.details() != null && !statement.details().isBlank()) {
                com.aresstack.spaceharvester.app.ui.IconButton details =
                        new com.aresstack.spaceharvester.app.ui.IconButton(
                                com.aresstack.spaceharvester.app.ui.Glyph.SUMMARIZE, DETAILS_TIP).compact();
                details.setName("summary-details");
                details.setForeground(Comic.BLUE);
                details.addActionListener(e -> showDetails(statement, found, details));
                head.add(Box.createHorizontalStrut(4));
                head.add(details);
            }
            head.setMaximumSize(new Dimension(Integer.MAX_VALUE, head.getPreferredSize().height));
            add(reportBody, head);
            parts.add(head);
        }
        String text = statement.text();
        if (across) {
            java.util.Set<String> names = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            statement.pages().stream().map(SummaryPage::spaceKey).filter(Objects::nonNull)
                    .forEach(key -> names.add(rendered.spaceName(key)));
            if (!names.isEmpty()) {
                text += " (" + String.join(", ", names) + ")";
            }
        }
        TextWithPicture body = new TextWithPicture(text, Comic.TEXT, 13.5f);
        body.setName("summary-text");
        add(reportBody, body);
        parts.add(body);
        if (number != null) {
            bodies.put(number, body);
        }
        if (statement.pages().isEmpty()) {
            return;
        }
        JPanel links = new JPanel(new com.aresstack.spaceharvester.app.ui.controls.WrapLayout(
                java.awt.FlowLayout.LEFT, 10, 0));
        links.setOpaque(false);
        for (SummaryPage page : statement.pages()) {
            links.add(pageLink(page, found));
        }
        // Die Verweise stehen direkt unter dem Text der Meldung, auch neben ihrem Bild (UX-Manager 22.11.2026).
        body.footer(statement.pages().size() <= OPEN_LINKS ? links
                : toggle(pages(statement.pages().size()), links, false));
    }

    static final String DETAILS_TIP = "Ausführliche KI-Zusammenfassung dieses Themas";

    /** Ein gelesenes Bild eines Themas des Podcasts. */
    private record LoadedPicture(TopicPicture topic, PictureStage.Picture read) {
    }

    /** Ein Bild an seiner Stelle im Bericht. */
    private record PlacedPicture(int topic, TextWithPicture body, ImageViewer.Picture viewer) {
    }

    /** So breit wird ein Schaubild (SVG) für den Bericht gerastert: scharf auch auf Bildschirmen mit Skalierung. */
    static final int VECTOR_RASTER_WIDTH = 2 * TextWithPicture.MAX_PICTURE_WIDTH;

    /**
     * Wo die Bilder des Podcasts im Bericht stehen (UX-Manager, 22.11.2026): jedes genau einmal, neben der ersten
     * Meldung in der Reihenfolge des Berichts, deren Seiten sich mit denen des Themas überschneiden; sonst neben der
     * ersten, deren Titel einem Meldungstitel des Themas gleicht; sonst nirgends, nie gesammelt am Ende. Eine Meldung
     * trägt höchstens ein Bild.
     *
     * @param order      die Nummern der gezeigten Meldungen in der Reihenfolge des Berichts
     * @param statements alle Meldungen der Zusammenfassung
     * @return Nummer der Meldung → Stelle des Bildes in {@code pictures}
     */
    static Map<Integer, Integer> placements(List<Integer> order, List<SummaryStatement> statements,
                                            List<TopicPicture> pictures) {
        Map<Integer, Integer> at = new java.util.HashMap<>();
        for (int p = 0; p < pictures.size(); p++) {
            TopicPicture picture = pictures.get(p);
            java.util.Set<String> ids = new java.util.HashSet<>(picture.pageIds());
            java.util.Set<String> titles = new java.util.HashSet<>();
            picture.storyTitles().stream().filter(Objects::nonNull).map(SourceUpdatesView::comparable)
                    .forEach(titles::add);
            Integer found = null;
            for (Integer number : order) {
                if (!at.containsKey(number) && number >= 0 && number < statements.size() && statements.get(number)
                        .pages().stream().anyMatch(page -> ids.contains(page.pageId()))) {
                    found = number;
                    break;
                }
            }
            if (found == null) {
                for (Integer number : order) {
                    String title = number >= 0 && number < statements.size() ? statements.get(number).title() : null;
                    if (!at.containsKey(number) && title != null && titles.contains(comparable(title))) {
                        found = number;
                        break;
                    }
                }
            }
            if (found != null) {
                at.put(found, p);
            }
        }
        return at;
    }

    private static String comparable(String title) {
        return title.strip().replaceAll("\\s+", " ").toLowerCase(Locale.GERMAN);
    }

    /** Fragt im Hintergrund, welche Bilder der Podcast zur gezeigten Zusammenfassung schon fertig hat. */
    private void loadPictures() {
        Actions current = actions.get();
        UpdateSummary of = rendered;
        if (mode != Mode.SUMMARY || current == null || of == null || bodies.isEmpty()) {
            return;
        }
        if (picturesOf != null && !samePodcast(picturesOf, of)) {
            pictureCache.clear();
        }
        int mine = ++pictureLoads;
        runner.run(() -> readPictures(current, of), loaded -> {
            if (mine == pictureLoads && samePodcast(rendered, of)) {
                picturesOf = of;
                loadedPictures = loaded;
                showPictures();
            }
        }, failure -> {
            // Bilder sind Beiwerk: der Bericht bleibt ohne sie stehen, der Grund steht in der Statuszeile.
            if (mine == pictureLoads) {
                onStatus.accept("Bilder des Podcasts nicht geladen: " + Problems.describe(failure).title());
            }
        });
    }

    private List<LoadedPicture> readPictures(Actions current, UpdateSummary of) {
        List<LoadedPicture> loaded = new ArrayList<>();
        for (TopicPicture topic : current.podcastPictures(of)) {
            java.nio.file.Path file = topic.picture().file();
            String key;
            try {
                key = file + "|" + java.nio.file.Files.getLastModifiedTime(file).toMillis();
            } catch (java.io.IOException | RuntimeException e) {
                continue;
            }
            PictureStage.Picture read = pictureCache.get(key);
            if (read == null) {
                try {
                    read = PodcastControl.read(topic.picture());
                } catch (java.io.IOException | RuntimeException e) {
                    // ein unlesbares Bild fehlt im Bericht; die übrigen stehen da
                    continue;
                }
                pictureCache.put(key, read);
            }
            loaded.add(new LoadedPicture(topic, read));
        }
        return loaded;
    }

    /** Stellt die gelesenen Bilder neben ihre Meldungen; was keine Stelle findet, fehlt. */
    private void showPictures() {
        List<LoadedPicture> loaded = samePodcast(rendered, picturesOf) ? loadedPictures : List.of();
        Map<Integer, Integer> at = rendered == null ? Map.of() : placements(new ArrayList<>(bodies.keySet()),
                rendered.statements(), loaded.stream().map(LoadedPicture::topic).toList());
        List<PlacedPicture> now = new ArrayList<>();
        for (Map.Entry<Integer, TextWithPicture> entry : bodies.entrySet()) {
            TextWithPicture body = entry.getValue();
            Integer index = at.get(entry.getKey());
            java.awt.image.BufferedImage image = index == null ? null : inline(loaded.get(index).read());
            if (image == null) {
                body.picture(null, null);
                continue;
            }
            PictureStage.Picture read = loaded.get(index).read();
            int position = now.size();
            body.picture(image, read.caption());
            body.onOpen(() -> openPicture(position, false));
            now.add(new PlacedPicture(loaded.get(index).topic().topic(), body,
                    new ImageViewer.Picture(image, read.caption(), read.vector())));
        }
        placed = List.copyOf(now);
        reportBody.revalidate();
        reportBody.repaint();
    }

    /** Ob beide Zusammenfassungen denselben Podcast haben (auch neu gelesen, also nicht dasselbe Objekt). */
    private static boolean samePodcast(UpdateSummary a, UpdateSummary b) {
        return a != null && b != null && (a == b || com.aresstack.spaceharvester.application.podcast.UpdatePodcasts
                .key(a).equals(com.aresstack.spaceharvester.application.podcast.UpdatePodcasts.key(b)));
    }

    private static java.awt.image.BufferedImage inline(PictureStage.Picture read) {
        if (read.image() != null) {
            return read.image();
        }
        return read.vector() == null ? null : read.vector().raster(VECTOR_RASTER_WIDTH);
    }

    /** Öffnet das Bild von Kapitel {@code topic} des Podcasts, wenn es im Bericht steht. */
    private boolean openTopicPicture(int topic) {
        for (int i = 0; i < placed.size(); i++) {
            if (placed.get(i).topic() == topic) {
                TextWithPicture body = placed.get(i).body();
                body.scrollRectToVisible(body.pictureBounds());
                return openPicture(i, true);
            }
        }
        return false;
    }

    /**
     * Öffnet das Bild an Stelle {@code position} im rahmenlosen Betrachter, der aus der Bildstelle im Text aufwächst;
     * blättern geht durch alle Bilder des Berichts. Hat der Podcast es geöffnet und schließt der Benutzer es selbst,
     * bleibt es in diesem Kapitel zu.
     */
    private boolean openPicture(int position, boolean cued) {
        if (position < 0 || position >= placed.size() || SwingUtilities.getRootPane(this) == null) {
            return false;
        }
        TextWithPicture body = placed.get(position).body();
        ViewerPopup popup = ViewerPopup.open(this, new ImageViewer(placed.stream().map(PlacedPicture::viewer)
                        .toList(), position, Comic.viewerLook(
                        com.aresstack.spaceharvester.app.settings.ViewerBackground.DEFAULT)),
                () -> SwingUtilities.isDescendingFrom(body, this)
                        ? SwingUtilities.convertRectangle(body, body.pictureBounds(), this) : null);
        if (cued) {
            cuePopup = popup;
            popup.onClosed(() -> {
                if (cuePopup == popup) {
                    cuePopup = null;
                    podcast.pictureClosed();
                }
            });
        }
        return true;
    }

    /** Die Bilder im Bericht, als Bildunterschriften in seiner Reihenfolge (für Tests). */
    List<String> reportPictures() {
        return placed.stream().map(picture -> picture.body().caption()).toList();
    }

    /** Klickt das Bild an Stelle {@code position} im Bericht an (für Tests). */
    boolean clickReportPicture(int position) {
        return openPicture(position, false);
    }

    /** Öffnet das Bild eines Kapitels, wie es der Podcast tut (für Tests). */
    boolean cuePicture(int topic) {
        return openTopicPicture(topic);
    }

    /** Das Fenster mit der ausführlichen Zusammenfassung eines Themas; darunter die Seiten zum Öffnen. */
    private void showDetails(SummaryStatement statement, ChangeReport found, Component owner) {
        com.aresstack.spaceharvester.app.ui.controls.InfoPopup popup =
                new com.aresstack.spaceharvester.app.ui.controls.InfoPopup()
                        .text(statement.title() == null ? "Details" : statement.title(), statement.details());
        for (SummaryPage page : statement.pages()) {
            ReportedPage reported = found.pages().stream().filter(p -> p.pageId().equals(page.pageId())).findFirst()
                    .orElse(null);
            if (reported != null && openable(reported)) {
                String title = page.title() != null && !page.title().isBlank() ? page.title() : page.pageId();
                popup.link("→ " + title, () -> onOpen.accept(reported));
            }
        }
        popup.showAt(owner, 0, owner.getHeight());
    }

    /** Ein aufklappbarer Abschnitt: Pfeil und Beschriftung, darunter der Inhalt. */
    private static JComponent toggle(String label, JComponent content, boolean open) {
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setOpaque(false);
        // Pfeil gezeichnet wie bei „Erweitert“ (Comic.disclosure): die Schrift hat ▸/▾ nicht und zeigte ein Kästchen.
        javax.swing.JButton head = Comic.disclosure(label, "Auf- oder zuklappen");
        head.setName("summary-toggle");
        head.setForeground(Comic.BLUE);
        head.setAlignmentX(LEFT_ALIGNMENT);
        Comic.disclose(head, open);
        content.setVisible(open);
        content.setAlignmentX(LEFT_ALIGNMENT);
        head.addActionListener(e -> {
            content.setVisible(!content.isVisible());
            Comic.disclose(head, content.isVisible());
            box.revalidate();
            box.repaint();
        });
        add(box, head);
        add(box, content);
        return box;
    }

    /** „Erstellt am …“ ohne Technik; das Modell steht im Tooltip ({@link #summaryModelTip}). */
    String summaryMeta(UpdateSummary shownSummary) {
        return "Erstellt am " + when(shownSummary.createdAt());
    }

    static String summaryModelTip(UpdateSummary shownSummary) {
        return shownSummary.model() == null || shownSummary.model().isBlank() ? null
                : "Erstellt mit " + shownSummary.model();
    }

    /** Verweis einer Aussage auf eine Seite; anklickbar, wenn die Seite im Bericht steht und sich öffnen lässt. */
    private JLabel pageLink(SummaryPage page, ChangeReport found) {
        ReportedPage reported = found.pages().stream().filter(p -> p.pageId().equals(page.pageId())).findFirst()
                .orElse(null);
        String title = page.title() != null && !page.title().isBlank() ? page.title()
                : reported != null && reported.title() != null ? reported.title() : page.pageId();
        JLabel link = new JLabel("→ " + title);
        link.setFont(Comic.semiBold(12.5f));
        link.setForeground(Comic.BLUE);
        link.setName("summary-link");
        if (reported != null && openable(reported)) {
            clickable(() -> onOpen.accept(reported), "Seite öffnen", link);
        } else {
            link.setForeground(Comic.MUTED);
        }
        return link;
    }

    private JPanel pageRow(ReportedPage page) {
        WrappedText title = Comic.wrapped(page.title() == null || page.title().isBlank() ? page.pageId()
                : page.title(), Comic.INK, 13.5f);
        title.setFont(Comic.semiBold(13.5f));
        JPanel row = new JPanel(new BorderLayout(0, 2));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        row.add(title, BorderLayout.NORTH);
        String details = details(page);
        WrappedText more = details.isEmpty() ? null : Comic.wrapped(details, Comic.MUTED, 12.5f);
        if (more != null) {
            row.add(more, BorderLayout.CENTER);
        }
        row.setAlignmentX(LEFT_ALIGNMENT);
        if (openable(page)) {
            List<Component> parts = new ArrayList<>(List.of(row, title));
            if (more != null) {
                parts.add(more);
            }
            clickable(() -> onOpen.accept(page), "Seite öffnen", parts.toArray(Component[]::new));
        }
        return row;
    }

    /**
     * Die geänderten Absätze einer Seite (ADR 0031), nach Überschriftenpfad gruppiert: „+“ neu, „−“ entfallen, „~“ alt
     * → neu. Was fehlt, steht als Zustand da; geschätzt wird nichts. {@code null} ohne Unterschied.
     */
    private static JComponent diffBlock(ReportedPage page) {
        ContentDiff diff = page.diff();
        if (diff == null) {
            return null;
        }
        JPanel block = new JPanel();
        block.setLayout(new BoxLayout(block, BoxLayout.Y_AXIS));
        block.setOpaque(false);
        block.setBorder(BorderFactory.createEmptyBorder(0, 20, 4, 8));
        switch (diff.status()) {
            case NOT_PERMITTED -> add(block, Comic.wrapped("Den Inhalt darfst du nicht lesen.", Comic.MUTED, 12.5f));
            case PREVIOUS_MISSING -> add(block, Comic.wrapped("Kein Vergleich möglich: Der bisherige Inhalt war nicht "
                    + "lokal, als der neue kam.", Comic.MUTED, 12.5f));
            default -> {
            }
        }
        List<String> headings = null;
        for (ContentChange step : diff.steps()) {
            for (ParagraphChange paragraph : step.paragraphs()) {
                if (!paragraph.headings().equals(headings)) {
                    headings = paragraph.headings();
                    if (!headings.isEmpty()) {
                        JLabel path = new JLabel(String.join(" › ", headings));
                        path.setFont(Comic.semiBold(12f));
                        path.setForeground(Comic.MUTED);
                        path.setBorder(BorderFactory.createEmptyBorder(4, 0, 1, 0));
                        add(block, path);
                    }
                }
                add(block, switch (paragraph.kind()) {
                    case ADDED -> Comic.wrapped("+ " + paragraph.after(), Comic.GREEN, 12.5f);
                    case REMOVED -> Comic.wrapped("− " + paragraph.before(), Comic.RED, 12.5f);
                    case CHANGED -> Comic.wrapped("~ " + paragraph.before() + " → " + paragraph.after(), Comic.TEXT,
                            12.5f);
                });
            }
            if (step.truncated()) {
                add(block, Comic.wrapped("… gekürzt", Comic.MUTED, 12f));
            }
        }
        return block.getComponentCount() == 0 ? null : block;
    }

    private static boolean openable(ReportedPage page) {
        return page.kind() != ChangeKind.REMOVED && page.kind() != ChangeKind.WITHHELD;
    }

    /** Pfad und was sich an der Seite geändert hat, in einer Zeile. */
    static String details(ReportedPage page) {
        List<String> parts = new ArrayList<>();
        if (!page.path().isEmpty()) {
            parts.add(String.join(" › ", page.path()));
        }
        if (page.kinds().contains(ChangeKind.RENAMED) && page.oldTitle() != null
                && !page.oldTitle().equals(page.title())) {
            parts.add("früher „" + page.oldTitle() + "“");
        }
        if (page.kinds().contains(ChangeKind.MOVED_OUT) && page.newSpaceKey() != null) {
            parts.add("jetzt in Space " + page.newSpaceKey());
        }
        if (page.kinds().contains(ChangeKind.CONTENT_CHANGED) && page.newVersion() != null) {
            parts.add(page.oldVersion() == null ? "Version " + page.newVersion()
                    : "Version " + page.newVersion() + " statt " + page.oldVersion());
        }
        if (page.contentPending()) {
            parts.add("Inhalt noch nicht geladen");
        }
        return String.join(" · ", parts);
    }

    String heading(ChangeUpdate update) {
        if (update.open()) {
            return "Seit dem letzten Update";
        }
        String user = update.mark().user();
        // Der Core selbst (Desktop-App) ist kein Benutzer: dann nur das Datum.
        boolean named = user != null && !user.isBlank() && !com.aresstack.spaceharvester.core.Harvester.OWNER.equals(user);
        return when(update.to()) + (named ? " · " + user : "");
    }

    String counts(ChangeUpdate update) {
        String text;
        if (update.initial()) {
            text = "Erstbefüllung · " + pages(update.pages());
        } else if (update.pages() == 0) {
            text = "keine Änderung";
        } else {
            List<String> parts = new ArrayList<>();
            for (ChangeKind kind : ChangeKind.values()) {
                Integer count = update.kinds().get(kind);
                if (count != null && count > 0 && kind.sourceChange()) {
                    parts.add(count + " " + kindLabel(kind).toLowerCase(Locale.GERMAN));
                }
            }
            text = String.join(" · ", parts);
        }
        if (update.summarized()) {
            text += " · zusammengefasst";
        }
        return update.open() ? OPEN_COUNTS + text : text;
    }

    private String period(ChangeUpdate update) {
        if (update.open()) {
            return update.from() == null ? "Alles, was bisher festgehalten ist"
                    : "Alles nach dem Update vom " + when(update.from());
        }
        return update.from() == null ? "Bis " + when(update.to())
                : "Nach dem Update vom " + when(update.from()) + " bis " + when(update.to());
    }

    private String localCounts(ChangeUpdate update) {
        List<String> parts = new ArrayList<>();
        if (update.loaded() > 0) {
            parts.add(update.loaded() == 1 ? "1 Inhalt erstmals lokal geladen"
                    : update.loaded() + " Inhalte erstmals lokal geladen");
        }
        if (update.reloaded() > 0) {
            parts.add(update.reloaded() == 1 ? "1 geänderter Inhalt nachgeladen"
                    : update.reloaded() + " geänderte Inhalte nachgeladen");
        }
        return parts.isEmpty() ? "" : "Außerdem im Bestand: " + String.join(", ", parts) + ".";
    }

    private static List<Map.Entry<String, Integer>> sortedSpaces(ChangeUpdate update) {
        return update.spaces().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
    }

    private String when(Instant at) {
        return at == null ? "–" : WHEN.format(at.atZone(zone));
    }

    /** Seiten einer Art: solange Seiten fehlen, die Zahl der ganzen Ebene, nicht nur der geladenen. */
    private int kindCount(ChangeUpdate update, Map.Entry<ChangeKind, List<ReportedPage>> group) {
        UpdateText last = windows.isEmpty() ? null : windows.getLast();
        int loaded = group.getValue().size();
        if (last == null || !last.more()) {
            return loaded;
        }
        return Math.max(loaded, update.kinds().getOrDefault(group.getKey(), 0));
    }

    private static String pages(int count) {
        return count == 1 ? "1 Seite" : count + " Seiten";
    }

    static String kindLabel(ChangeKind kind) {
        return switch (kind) {
            case NEW -> "Neu";
            case CONTENT_CHANGED -> "Geändert";
            case RENAMED -> "Umbenannt";
            case MOVED -> "Verschoben";
            case LABELS_CHANGED -> "Labels geändert";
            case REMOVED -> "Entfernt";
            case WITHHELD -> "Gesperrt";
            case MOVED_OUT -> "In anderen Space";
            case LOADED -> "Erstmals geladen";
            case RELOADED -> "Nachgeladen";
        };
    }

    private void message(String text) {
        reportBody.removeAll();
        topicParts.clear();
        unmark.clear();
        bodies.clear();
        placed = List.of();
        add(reportBody, Comic.wrapped(text, Comic.MUTED, 13.5f));
        reportBody.revalidate();
        reportBody.repaint();
    }

    private static void add(JPanel column, JComponent part) {
        part.setAlignmentX(LEFT_ALIGNMENT);
        column.add(part);
    }

    private static void clickable(Runnable action, String tip, Component... parts) {
        MouseAdapter click = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                action.run();
            }
        };
        // Die Textfelder fangen Mausklicks selbst ab; der Klick gilt auf Zeile und Texten gleich.
        for (Component part : parts) {
            part.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            part.addMouseListener(click);
            if (part instanceof JComponent component) {
                component.setToolTipText(tip);
            }
        }
    }

    /** Der gezeigte Bericht als Text für die Zwischenablage. */
    String copyText() {
        if (shown == null) {
            return "";
        }
        StringBuilder text = new StringBuilder(heading(shown.update())).append('\n').append(period(shown.update()));
        if (mode == Mode.SUMMARY) {
            if (summary != null) {
                text.append("\n\nZusammenfassung (").append(summaryMeta(summary)).append(')');
                text.append("\n").append(summary.text());
                for (SummaryStatement statement : summary.statements()) {
                    text.append("\n\n");
                    if (statement.title() != null && !statement.title().isBlank()) {
                        text.append(statement.title()).append('\n');
                    }
                    text.append(statement.text());
                    if (statement.details() != null && !statement.details().isBlank()) {
                        text.append("\n").append(statement.details());
                    }
                }
            }
            return text.toString();
        }
        if (!windows.isEmpty()) {
            // Die feste Textform des Core (update-text/v1) ist die Kopierform des Reiters „Text“.
            return markdown(windows).strip();
        }
        if (shown.update().initial()) {
            text.append('\n').append("Erstbefüllung: ").append(pages(shown.update().pages()));
        }
        for (Map.Entry<ChangeKind, List<ReportedPage>> group : shown.byKind().entrySet()) {
            text.append("\n\n").append(kindLabel(group.getKey()));
            for (ReportedPage page : group.getValue()) {
                text.append("\n- ").append(page.title() == null ? page.pageId() : page.title());
                String details = details(page);
                if (!details.isEmpty()) {
                    text.append(" (").append(details).append(')');
                }
            }
        }
        return text.toString();
    }

    /** Führt Arbeit im Hintergrund aus und meldet auf dem EDT zurück (Tests: synchron). */
    interface Runner {
        <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure);
    }

    /** Das Änderungsprotokoll der App; in Tests ersetzbar. */
    interface Actions {

        /** Die Updates der Ebene: {@code spaceKey == null} die ganze Quelle, sonst ein Space als Ausschnitt. */
        List<ChangeUpdate> updates(String sourceId, String spaceKey);

        ChangeReport report(String sourceId, String updateId, String spaceKey);

        /** Ein Ausschnitt des Update-Texts (ADR 0031): {@code limit} Seiten ab {@code offset}, mit Unterschieden. */
        UpdateText updateText(String sourceId, String updateId, String spaceKey, int offset, int limit);

        Subscription subscribe(String sourceId, ChangeLogListener listener);

        /** Die gespeicherte Zusammenfassung des Updates, {@code null} ohne. */
        UpdateSummary summary(String sourceId, String updateId, String spaceKey);

        /** Ob die abgelegte Zusammenfassung eines (auch früheren) Updates noch gilt; ohne Inhalt. */
        default boolean summaryStillValid(String sourceId, String updateId, String spaceKey) {
            return false;
        }

        /** Meldet Änderungen der KI-Freigabe (ADR 0028), auf irgendeinem Thread. */
        default Subscription subscribeReleases(Runnable listener) {
            return Subscription.NONE;
        }

        /** Ob ein Chat-Modell für Zusammenfassungen eingestellt ist. */
        boolean summariesAvailable();

        /** Startet den Auftrag „Zusammenfassen“; das Ergebnis legt der Core neben dem Protokoll ab. */
        JobStatus summarize(String sourceId, String updateId, String spaceKey);

        JobStatus job(String jobId);

        JobStatus cancelJob(String jobId);

        Subscription subscribeJobs(JobListener listener);

        /** Der Podcast der Zusammenfassungen; ohne bleibt der Knopf aus. */
        default PodcastControl.Source<UpdateSummary> podcasts() {
            return PodcastControl.Source.none();
        }

        /** Die fertigen Bilder der Themen des Podcasts dieser Zusammenfassung; liest Dateien, nie auf dem EDT. */
        default List<TopicPicture> podcastPictures(UpdateSummary summary) {
            return List.of();
        }

        static Actions of(Workspace workspace) {
            if (workspace == null) {
                return null;
            }
            return new Actions() {
                @Override
                public List<ChangeUpdate> updates(String sourceId, String spaceKey) {
                    return workspace.changeUpdates(sourceId, spaceKey);
                }

                @Override
                public ChangeReport report(String sourceId, String updateId, String spaceKey) {
                    return workspace.changeReport(sourceId, updateId, spaceKey);
                }

                @Override
                public UpdateText updateText(String sourceId, String updateId, String spaceKey, int offset,
                                             int limit) {
                    return workspace.updateText(sourceId, updateId, spaceKey, offset, limit);
                }

                @Override
                public Subscription subscribe(String sourceId, ChangeLogListener listener) {
                    return workspace.subscribeChangeLog(sourceId, listener);
                }

                @Override
                public UpdateSummary summary(String sourceId, String updateId, String spaceKey) {
                    return workspace.changeSummary(sourceId, updateId, spaceKey).orElse(null);
                }

                @Override
                public boolean summaryStillValid(String sourceId, String updateId, String spaceKey) {
                    return workspace.changeSummaryStillValid(sourceId, updateId, spaceKey);
                }

                @Override
                public Subscription subscribeReleases(Runnable listener) {
                    return workspace.subscribePageReleases(change -> listener.run());
                }

                @Override
                public boolean summariesAvailable() {
                    return workspace.changeSummariesAvailable();
                }

                @Override
                public JobStatus summarize(String sourceId, String updateId, String spaceKey) {
                    return workspace.summarizeUpdate(sourceId, updateId, spaceKey);
                }

                @Override
                public JobStatus job(String jobId) {
                    return workspace.job(jobId);
                }

                @Override
                public JobStatus cancelJob(String jobId) {
                    return workspace.cancelJob(jobId);
                }

                @Override
                public Subscription subscribeJobs(JobListener listener) {
                    return workspace.subscribeJobs(listener);
                }

                @Override
                public List<TopicPicture> podcastPictures(UpdateSummary summary) {
                    return workspace.podcastPictures(summary);
                }

                @Override
                public PodcastControl.Source<UpdateSummary> podcasts() {
                    return new PodcastControl.Source<>() {
                        @Override
                        public boolean available() {
                            return workspace.podcastsAvailable();
                        }

                        @Override
                        public String key(UpdateSummary summary) {
                            return com.aresstack.spaceharvester.application.podcast.UpdatePodcasts.key(summary);
                        }

                        @Override
                        public java.util.Optional<com.aresstack.spaceharvester.application.podcast.Podcast> podcast(
                                UpdateSummary summary) {
                            return workspace.podcast(summary);
                        }

                        @Override
                        public JobStatus start(UpdateSummary summary) {
                            return workspace.startPodcast(summary);
                        }

                        @Override
                        public Subscription subscribe(String key, Runnable listener) {
                            return workspace.subscribePodcast(key, listener);
                        }

                        @Override
                        public List<Integer> focus(UpdateSummary summary, int topic) {
                            return workspace.podcastStatements(summary, topic);
                        }

                        @Override
                        public Subscription subscribeJobs(JobListener listener) {
                            return workspace.subscribeJobs(listener);
                        }
                    };
                }
            };
        }
    }

    // ---- für Tests

    boolean summarizeOffered() {
        return summarize.isVisible() && summarize.isEnabled();
    }

    String summarizeLabel() {
        return summarize.getAccessibleContext().getAccessibleName();
    }

    /** Ob der Einstieg „Zusammenfassen“ im Bericht zu sehen ist. */
    boolean summarizeEntryShown() {
        return summarizeEntry.isVisible() && summarizeEntry.getParent() == reportBody;
    }

    void clickSummarizeEntry() {
        for (java.awt.event.MouseListener listener : summarizeEntry.getMouseListeners()) {
            listener.mouseClicked(new MouseEvent(summarizeEntry, MouseEvent.MOUSE_CLICKED, 0, 0, 1, 1, 1, 1, 1, false,
                    MouseEvent.BUTTON1));
        }
    }

    void clickSummarize() {
        summarize.doClick();
    }

    boolean busy() {
        return busy.isBusy();
    }

    String summarizeTip() {
        return summarize.getToolTipText();
    }

    void cancelRunningSummary() {
        busy.cancel();
    }

    /** Klickt den Seitenverweis einer Aussage an; {@code false}, wenn es ihn nicht gibt oder er nicht klickbar ist. */
    boolean clickSummaryLink(String title) {
        return clickLink(reportBody, "→ " + title);
    }

    /** Tests: klickt einen Text der Zusammenfassung (Space, Auf-/Zuklappen). */
    boolean clickSummaryText(String text) {
        return clickLink(reportBody, text);
    }

    private static boolean clickLink(java.awt.Container container, String text) {
        for (Component child : container.getComponents()) {
            if (child instanceof JLabel label && text.equals(label.getText())) {
                java.awt.event.MouseListener[] listeners = label.getMouseListeners();
                if (listeners.length == 0) {
                    return false;
                }
                MouseEvent event = new MouseEvent(label, MouseEvent.MOUSE_CLICKED, 0, 0, 1, 1, 1, 1, 1, false,
                        MouseEvent.BUTTON1);
                for (java.awt.event.MouseListener listener : listeners) {
                    listener.mouseClicked(event);
                }
                return true;
            }
            if (child instanceof java.awt.Container nested && clickLink(nested, text)) {
                return true;
            }
        }
        return false;
    }

    void zone(ZoneId zone) {
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    String sourceId() {
        return sourceId;
    }

    String spaceKey() {
        return spaceKey;
    }

    List<String> updateHeadings() {
        return updates.stream().map(this::heading).toList();
    }

    String selectedUpdate() {
        return selected;
    }

    /** Alle Texte des Berichts, in Reihenfolge. */
    List<String> reportTexts() {
        List<String> texts = new ArrayList<>();
        collect(reportBody, texts);
        return texts;
    }

    private static void collect(Component component, List<String> texts) {
        if (component instanceof TextWithPicture body) {
            texts.add(body.text());
            if (!body.caption().isEmpty()) {
                texts.add(body.caption());
            }
            if (body.footer() != null) {
                collect(body.footer(), texts);
            }
        } else if (component instanceof JLabel label) {
            texts.add(label.getText());
        } else if (component instanceof javax.swing.text.JTextComponent text) {
            texts.add(text.getText());
        } else if (component instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) {
                collect(child, texts);
            }
        }
    }

    /** Klickt einen Verweis (etwa „Weitere … laden“) im Bericht an. */
    boolean clickReportLink(String text) {
        return clickLink(reportBody, text);
    }

    /** Klickt die Seite mit diesem Titel im Bericht an; {@code false}, wenn sie nicht anklickbar ist. */
    boolean clickPage(String title) {
        return click(reportBody, title);
    }

    /** Klickt das Update mit dieser Überschrift an. */
    boolean clickUpdate(String heading) {
        sky.doLayout();
        for (int i = 0; i < updates.size(); i++) {
            ChangeUpdate update = updates.reversed().get(i);
            if (heading.equals(heading(update))) {
                sky.clickChapterForTest(i);
                return true;
            }
        }
        return false;
    }

    /** Die markierte Wolke der Reihe. */
    String markedUpdate() {
        return sky.activeChapterId();
    }

    DocumentNavigationSky sky() {
        return sky;
    }

    /** Die Überschriften der blassen Wolken, von links nach rechts. */
    List<String> paleClouds() {
        List<String> pale = new ArrayList<>();
        List<ChangeUpdate> clouds = updates.reversed();
        for (int i = 0; i < clouds.size(); i++) {
            if (sky.paleForTest(i)) {
                pale.add(heading(clouds.get(i)));
            }
        }
        return pale;
    }

    /** Der Tooltip der Wolke dieses Updates. */
    String cloudTooltip(String heading) {
        List<ChangeUpdate> clouds = updates.reversed();
        for (int i = 0; i < clouds.size(); i++) {
            if (heading.equals(heading(clouds.get(i)))) {
                return sky.tooltipForTest(i);
            }
        }
        return null;
    }

    private static boolean click(java.awt.Container container, String text) {
        for (Component child : container.getComponents()) {
            if (child instanceof JPanel row) {
                for (Component part : row.getComponents()) {
                    String shownText = part instanceof JLabel label ? label.getText()
                            : part instanceof javax.swing.text.JTextComponent area ? area.getText() : null;
                    if (text.equals(shownText)) {
                        MouseEvent event = new MouseEvent(row, MouseEvent.MOUSE_CLICKED, 0, 0, 1, 1, 1, 1, 1,
                                false, MouseEvent.BUTTON1);
                        java.awt.event.MouseListener[] listeners = row.getMouseListeners();
                        if (listeners.length == 0) {
                            return false;
                        }
                        for (java.awt.event.MouseListener listener : listeners) {
                            listener.mouseClicked(event);
                        }
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Wie oft der Bericht gezeichnet wurde. */
    int reportRenders() {
        return reportRenders;
    }

    boolean subscribed() {
        return subscription != Subscription.NONE;
    }
}
