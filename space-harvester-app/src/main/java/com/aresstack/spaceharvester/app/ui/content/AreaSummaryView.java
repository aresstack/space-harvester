package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.ui.Background;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Problems;
import com.aresstack.spaceharvester.app.ui.controls.BusyOverlay;
import com.aresstack.spaceharvester.app.ui.controls.ScrollColumn;
import com.aresstack.spaceharvester.app.ui.controls.SegmentedToggle;
import com.aresstack.spaceharvester.application.changelog.SummaryPage;
import com.aresstack.spaceharvester.application.changelog.SummaryStatement;
import com.aresstack.spaceharvester.application.changelog.SummaryUnavailableException;
import com.aresstack.spaceharvester.application.changelog.UpdateSummary;
import com.aresstack.spaceharvester.application.jobs.JobListener;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.observe.Subscription;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Der Bereich statt der Änderungen (Angelo 24.11.2026, Umschalter „Änderungen | Bereich“ unten in Lese- und
 * Podcast-Ansicht): Wer neu ist und die Seiten noch nie besucht hat, bekommt eine didaktisch aufbereitete
 * Gesamtzusammenfassung des gewählten Bereichs (Quelle oder Space, ADR 0034 §7). Die Leseansicht zeigt sie zum Lesen,
 * die Podcast-Ansicht einen Erklär-Podcast auf ihrer Grundlage mit eigener Textdarstellung: je Thema die ausführliche
 * Erklärung direkt im Text, gelb, was gerade erzählt wird, die Bilder des Podcasts daneben, nichts in Rot und Grün.
 * Gezeichnet wird mit demselben Baustein wie die Update-Zusammenfassung ({@link SummaryReport}), ohne Wolkenleiste.
 * Fehlt die Zusammenfassung, entsteht sie beim Öffnen einmal von selbst; „Zusammenfassen“ erstellt sie neu. Eine
 * Änderung am Bereich startet nie von selbst einen KI-Lauf, sie zeigt nur „veraltet“.
 */
final class AreaSummaryView extends JPanel {

    /** Die beiden Hälften des Umschalters unten. */
    static final String CHANGES = "Änderungen";
    static final String AREA = "Bereich";
    static final String SCOPE_TIP = "Änderungen: was sich seit dem letzten Update getan hat. Bereich: worum es im "
            + "ganzen Bereich geht, zum Einstieg für Neue";
    /** Im App-State gemerkt, für alle Seiten gleich: 1 = Bereich, 0 = Änderungen (Vorgabe, Angelo 24.11.2026). */
    static final String SCOPE_KEY = "summary.scope.area";

    static final String NO_SUMMARY = "Noch kein Überblick über diesen Bereich.";
    static final String SUMMARIZING = "Überblick wird erstellt …";
    static final String EMPTY = "Dieser Bereich hat noch keine geladene Seite, die sich zusammenfassen lässt.";
    static final String NO_MODEL = "Für den Überblick ist kein Chat-Modell eingestellt. Unter Einstellungen › "
            + "Modelle wählen; danach entsteht er beim Öffnen von selbst.";
    static final String NOT_RUNNING = "Der Überblick erscheint, sobald der Dienst läuft.";
    static final String STALE = "Der Bereich hat sich seither geändert; „Neu zusammenfassen“ erstellt den Überblick "
            + "neu.";
    static final String SUMMARIZE_TIP = "Bereich zusammenfassen";
    static final String RESUMMARIZE_TIP = "Überblick neu erstellen";
    static final String HELP = "„Bereich“ fasst den ganzen gewählten Bereich zusammen, nicht nur die Änderungen: "
            + "worum es geht, wie er aufgebaut ist und wo man anfängt, zum Einstieg für Neue. Fehlt der Überblick, "
            + "erstellt das eingestellte Chat-Modell ihn beim Öffnen von selbst; „Zusammenfassen“ erstellt ihn neu. "
            + "Es liest nur geladene Seiten mit KI-Freigabe, die oberen zuerst, höchstens so viele, wie unter "
            + "Einstellungen › Cache bei „Bereichszusammenfassung – Seiten“ steht.\n\n"
            + "In der Podcast-Ansicht erklärt ein Podcast denselben Überblick; der Text darunter zeigt je Thema die "
            + "ausführliche Erklärung, gelb, was gerade erzählt wird.";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", Locale.GERMAN);
    /** Bereiche, für die schon einmal von selbst zusammengefasst wurde (je Sitzung, für beide Ansichten). */
    private static final Set<String> AUTO_STARTED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Was die Ansicht zeigt: zum Lesen oder zum Hören. */
    enum Mode { READ, LISTEN }

    /** Was die Ansicht vom Core braucht. */
    interface Actions {

        /** Die Bereichszusammenfassung, {@code null} ohne. */
        UpdateSummary area(String sourceId, String spaceKey);

        /** Ob ein Chat-Modell eingestellt ist. */
        boolean available();

        /** Startet „Zusammenfassen“ für den Bereich. */
        JobStatus summarize(String sourceId, String spaceKey, boolean interactive);

        /** Aufträge, Meldungen der Quelle, Podcast und Bilder wie bei den Updates. */
        SourceUpdatesView.Actions shared();

        static Actions of(Workspace workspace) {
            if (workspace == null) {
                return null;
            }
            SourceUpdatesView.Actions shared = SourceUpdatesView.Actions.of(workspace);
            return new Actions() {
                @Override
                public UpdateSummary area(String sourceId, String spaceKey) {
                    return workspace.areaSummary(sourceId, spaceKey).orElse(null);
                }

                @Override
                public boolean available() {
                    return workspace.areaSummariesAvailable();
                }

                @Override
                public JobStatus summarize(String sourceId, String spaceKey, boolean interactive) {
                    return workspace.summarizeArea(sourceId, spaceKey, interactive);
                }

                @Override
                public SourceUpdatesView.Actions shared() {
                    return shared;
                }
            };
        }
    }

    private final Supplier<Actions> actions;
    private final SourceUpdatesView.Runner runner;
    private final Mode mode;
    private final JPanel tools = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 2, 0));
    private final com.aresstack.spaceharvester.app.ui.IconButton summarize =
            new com.aresstack.spaceharvester.app.ui.IconButton(com.aresstack.spaceharvester.app.ui.Glyph.SUMMARIZE,
                    SUMMARIZE_TIP);
    private final JLabel summarizeEntry = new JLabel(SourceUpdatesView.SUMMARIZE,
            com.aresstack.spaceharvester.app.ui.Glyph.SUMMARIZE, JLabel.LEFT);
    private final ScrollColumn report = new ScrollColumn(new BorderLayout());
    private final JPanel reportBody = new JPanel();
    private final javax.swing.JScrollPane reportScroll;
    private final BusyOverlay busy;
    private final SummaryReport summaryReport;
    private final PodcastControl<UpdateSummary> podcast;
    private final ReportPictures pictures;
    private Consumer<String> onStatus = text -> { };
    private BiConsumer<String, String> onOpen = (source, page) -> { };
    private BiConsumer<String, String> onOpenSpace = (source, space) -> { };
    private ZoneId zone = ZoneId.systemDefault();
    private java.util.function.BooleanSupplier showing = this::isShowing;

    private String sourceId;
    private String spaceKey;
    private UpdateSummary summary;
    private boolean available = true;
    private boolean empty;
    private boolean loaded;
    private String runningJob;
    private int generation;
    private Subscription subscription = Subscription.NONE;
    private Subscription jobWatch = Subscription.NONE;

    AreaSummaryView(Supplier<Workspace> workspace, Mode mode) {
        this(() -> Actions.of(workspace.get()), new SourceUpdatesView.Runner() {
            @Override
            public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
                Background.run(work, onSuccess, onFailure);
            }
        }, mode);
    }

    AreaSummaryView(Supplier<Actions> actions, SourceUpdatesView.Runner runner, Mode mode) {
        super(new BorderLayout());
        this.actions = Objects.requireNonNull(actions, "actions");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.podcast = new PodcastControl<>(() -> {
            Actions current = actions.get();
            return current == null ? null : current.shared().podcasts();
        }, runner);
        this.pictures = new ReportPictures(this, runner, () -> onStatus, podcast::pictureClosed);
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(6, 2, 2, 2));
        tools.setOpaque(false);
        if (mode == Mode.READ) {
            tools.add(summarize);
        }
        tools.add(new com.aresstack.spaceharvester.app.ui.controls.CopyButton(this::copyText));
        tools.add(new com.aresstack.spaceharvester.app.ui.controls.HelpButton(24, "Bereich", HELP));
        summarize.addActionListener(e -> summarize(true));
        summarizeEntry.setFont(Comic.semiBold(13f));
        summarizeEntry.setForeground(Comic.BLUE);
        summarizeEntry.setIconTextGap(6);
        summarizeEntry.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
        SummaryReport.clickable(() -> summarize(true), "Das Chat-Modell fasst den ganzen Bereich zusammen; nur Seiten "
                + "mit KI-Freigabe", summarizeEntry);

        reportBody.setLayout(new BoxLayout(reportBody, BoxLayout.Y_AXIS));
        reportBody.setOpaque(false);
        summaryReport = new SummaryReport(reportBody, host()).explained(mode == Mode.LISTEN);
        report.setOpaque(false);
        report.add(reportBody, BorderLayout.NORTH);
        reportScroll = (javax.swing.JScrollPane) Comic.scroll(report);
        busy = new BusyOverlay(reportScroll);
        busy.onCancel(this::cancel);
        if (mode == Mode.LISTEN) {
            podcast.follow(this::follow);
            podcast.cue(new PodcastControl.Cue() {
                @Override
                public boolean open(int topic) {
                    return pictures.openTopic(topic);
                }

                @Override
                public void close() {
                    pictures.closeCue();
                }
            });
            podcast.stored(this::loadPictures);
        }
        add(new ReportLayer(busy, mode == Mode.LISTEN ? podcast.orb() : null, reportScroll), BorderLayout.CENTER);
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                becameVisible();
            }
        });
        area(null, null);
    }

    /** Ob gerade „Bereich“ gewählt ist; gilt für alle Seiten und über den Neustart. */
    static boolean areaChosen() {
        return com.aresstack.spaceharvester.app.ui.WindowSizes.recallInt(SCOPE_KEY, 0) == 1;
    }

    /** Merkt die Wahl des Umschalters global. */
    static void choose(boolean area) {
        com.aresstack.spaceharvester.app.ui.WindowSizes.rememberInt(SCOPE_KEY, area ? 1 : 0);
    }

    /** Der Umschalter „Änderungen | Bereich“ für die Leiste unten, auf die gemerkte Wahl gestellt. */
    static SegmentedToggle scopeToggle() {
        SegmentedToggle toggle = new SegmentedToggle().segment(CHANGES).segment(AREA);
        toggle.setName("summary.scope");
        toggle.setToolTipText(SCOPE_TIP);
        toggle.button(CHANGES).setToolTipText("Was sich seit dem letzten Update getan hat");
        toggle.button(AREA).setToolTipText("Worum es im ganzen Bereich geht, zum Einstieg für Neue");
        toggle.select(areaChosen() ? AREA : CHANGES);
        return toggle;
    }

    JComponent tools() {
        return tools;
    }

    PodcastControl<UpdateSummary> podcast() {
        return podcast;
    }

    void podcastEvents(Consumer<com.aresstack.spaceharvester.app.event.ApplicationEvent<?>> events) {
        podcast.events(events);
    }

    void onStatus(Consumer<String> listener) {
        onStatus = Objects.requireNonNull(listener, "listener");
    }

    /** Öffnet eine Seite (Quelle, Seite) aus dem Überblick. */
    void onOpen(BiConsumer<String, String> listener) {
        onOpen = Objects.requireNonNull(listener, "listener");
    }

    void onOpenSpace(BiConsumer<String, String> listener) {
        onOpenSpace = Objects.requireNonNull(listener, "listener");
    }

    /** Für Tests: Zeitzone von „Erstellt am“. */
    void zone(ZoneId value) {
        zone = Objects.requireNonNull(value, "value");
    }

    /** Für Tests: ob die Ansicht als sichtbar gilt (sonst {@link #isShowing()}). */
    void showingForTest(java.util.function.BooleanSupplier visible) {
        showing = Objects.requireNonNull(visible, "visible");
    }

    /** Zeigt den Bereich einer Quelle ({@code space == null}) oder eines Space; {@code id == null} leert die Ansicht. */
    void area(String id, String space) {
        subscription.close();
        subscription = Subscription.NONE;
        generation++;
        sourceId = id;
        spaceKey = id == null || space == null || space.isBlank() ? null : space;
        summary = null;
        empty = false;
        loaded = false;
        podcast.summary(null);
        offer();
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
            // Der Core meldet die Quelle, sobald der Überblick abgelegt ist oder sich ihr Bestand ändert.
            subscription = current.shared().subscribe(id, changed -> SwingUtilities.invokeLater(() -> {
                if (Objects.equals(changed, sourceId)) {
                    reload();
                }
            }));
        } catch (RuntimeException e) {
            subscription = Subscription.NONE;
        }
        reload();
    }

    String sourceId() {
        return sourceId;
    }

    String spaceKey() {
        return spaceKey;
    }

    /** Die gezeigte Zusammenfassung, {@code null} ohne. */
    UpdateSummary summary() {
        return summary;
    }

    /** Liest den Überblick neu. */
    void reload() {
        Actions current = actions.get();
        String id = sourceId;
        String space = spaceKey;
        if (current == null || id == null) {
            return;
        }
        int mine = ++generation;
        runner.run(() -> new Loaded(current.area(id, space), current.available()), found -> {
            if (mine != generation || !Objects.equals(id, sourceId) || !Objects.equals(space, spaceKey)) {
                return;
            }
            loaded = true;
            available = found.available();
            boolean same = found.summary() != null && found.summary().equals(summary);
            summary = found.summary();
            if (!same) {
                if (mode == Mode.LISTEN) {
                    podcast.summary(summary);
                }
                render();
            }
            autoSummarize();
            offer();
        }, failure -> {
            if (mine == generation) {
                message("Überblick nicht geladen: " + Problems.describe(failure).title());
            }
        });
    }

    private record Loaded(UpdateSummary summary, boolean available) {
    }

    private void becameVisible() {
        if (autoSummarize()) {
            render();
        }
    }

    /**
     * Fehlt der Überblick, entsteht er beim Öffnen einmal von selbst (ADR 0034 §7: als gewöhnlicher Auftrag, ein Klick
     * zieht vor); nur bei sichtbarer Ansicht und mit Chat-Modell. {@code true}, wenn gestartet.
     */
    private boolean autoSummarize() {
        if (!loaded || summary != null || empty || !available || runningJob != null || sourceId == null
                || !showing.getAsBoolean() || !AUTO_STARTED.add(sourceId + '\u0000' + spaceKey)) {
            return false;
        }
        summarize(false);
        return true;
    }

    private void summarize(boolean interactive) {
        Actions current = actions.get();
        String id = sourceId;
        String space = spaceKey;
        if (current == null || id == null || runningJob != null) {
            return;
        }
        runningJob = "";
        offer();
        render();
        if (interactive) {
            onStatus.accept(SUMMARIZING);
        }
        runner.run(() -> current.summarize(id, space, interactive), status -> {
            runningJob = status.id();
            watch(current, status.id(), id, space);
        }, failure -> {
            runningJob = null;
            if (failure instanceof SummaryUnavailableException unavailable
                    && unavailable.reason() == SummaryUnavailableException.Reason.EMPTY_AREA) {
                empty = true;
            } else {
                onStatus.accept("Zusammenfassen nicht möglich: " + Problems.describe(failure).title());
            }
            offer();
            render();
        });
    }

    /** Erst anmelden, dann den Stand lesen: so geht das Ende des Auftrags nicht verloren (ADR 0027). */
    private void watch(Actions current, String jobId, String id, String space) {
        jobWatch.close();
        try {
            jobWatch = current.shared().subscribeJobs(new JobListener() {
                @Override
                public void changed(JobStatus status) {
                    if (status.id().equals(jobId)) {
                        SwingUtilities.invokeLater(() -> progressed(status, id, space));
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
        runner.run(() -> current.shared().job(jobId), status -> progressed(status, id, space), failure -> { });
    }

    private void progressed(JobStatus status, String id, String space) {
        if (!status.id().equals(runningJob) || !status.state().terminal()) {
            return;
        }
        runningJob = null;
        jobWatch.close();
        jobWatch = Subscription.NONE;
        onStatus.accept(switch (status.state()) {
            case COMPLETED -> "Überblick erstellt";
            case CANCELLED -> "Abgebrochen; der bisherige Überblick bleibt.";
            case PAUSED -> "Überblick angehalten; fortsetzen unter Aufträge.";
            default -> "Überblick fehlgeschlagen" + (status.error() == null ? "" : ": " + status.error().message());
        });
        offer();
        if (Objects.equals(id, sourceId) && Objects.equals(space, spaceKey)) {
            if (status.state() == JobState.COMPLETED) {
                reload();
            } else {
                render();
            }
        }
    }

    private void cancel() {
        Actions current = actions.get();
        String job = runningJob;
        if (current == null || job == null || job.isEmpty()) {
            return;
        }
        busy.setText("Wird abgebrochen …");
        runner.run(() -> current.shared().cancelJob(job), status -> progressed(status, sourceId, spaceKey),
                failure -> { });
    }

    /** Knopf „Zusammenfassen“, Einstieg und Ring nach dem Stand. */
    private void offer() {
        boolean possible = sourceId != null && available && !empty && runningJob == null;
        summarize.setVisible(mode == Mode.READ && sourceId != null);
        summarize.setEnabled(possible);
        summarize.setToolTipText(summary == null ? SUMMARIZE_TIP : RESUMMARIZE_TIP);
        summarize.getAccessibleContext().setAccessibleName(summary == null ? SourceUpdatesView.SUMMARIZE
                : SourceUpdatesView.RESUMMARIZE);
        boolean working = runningJob != null && sourceId != null;
        busy.setText(working ? SUMMARIZING : null);
        busy.setBusy(working);
    }

    private void render() {
        reportBody.removeAll();
        summaryReport.clear();
        pictures.placed(List.of());
        if (summary != null) {
            summaryReport.render(summary, () -> {
                summaryReport.markFollowed();
                showPictures();
                loadPictures();
            });
        } else if (sourceId == null) {
            SummaryReport.add(reportBody, Comic.wrapped(" ", Comic.MUTED, 13.5f));
        } else if (!loaded) {
            SummaryReport.add(reportBody, Comic.wrapped(" ", Comic.MUTED, 13.5f));
        } else if (empty) {
            SummaryReport.add(reportBody, Comic.wrapped(EMPTY, Comic.MUTED, 13.5f));
        } else if (!available) {
            SummaryReport.add(reportBody, Comic.wrapped(NO_MODEL, Comic.MUTED, 13.5f));
        } else if (runningJob != null) {
            SummaryReport.add(reportBody, Comic.wrapped(SUMMARIZING, Comic.MUTED, 13.5f));
        } else {
            SummaryReport.add(reportBody, Comic.wrapped(NO_SUMMARY, Comic.MUTED, 13.5f));
            reportBody.add(Box.createVerticalStrut(8));
            SummaryReport.add(reportBody, summarizeEntry);
        }
        reportBody.revalidate();
        reportBody.repaint();
    }

    private void message(String text) {
        reportBody.removeAll();
        summaryReport.clear();
        pictures.placed(List.of());
        SummaryReport.add(reportBody, Comic.wrapped(text, Comic.MUTED, 13.5f));
        reportBody.revalidate();
        reportBody.repaint();
    }

    /** Folgt dem Podcast: rollt zum ersten Thema, von dem er erzählt, und hinterlegt es gelb. */
    private void follow(List<Integer> statements) {
        JComponent first = summaryReport.follow(statements);
        if (first != null) {
            report.validate();
            javax.swing.JViewport port = reportScroll.getViewport();
            java.awt.Rectangle at = SwingUtilities.convertRectangle(first.getParent(), first.getBounds(), report);
            int y = Math.max(0, Math.min(at.y - port.getExtentSize().height / 5,
                    report.getHeight() - port.getExtentSize().height));
            port.setViewPosition(new java.awt.Point(port.getViewPosition().x, y));
        }
    }

    private void loadPictures() {
        Actions current = actions.get();
        UpdateSummary of = summaryReport.rendered();
        if (current == null || of == null || !summaryReport.hasBodies()) {
            return;
        }
        pictures.load(current.shared()::podcastPictures, of, summaryReport::rendered, this::showPictures);
    }

    private void showPictures() {
        pictures.placed(summaryReport.showPictures(pictures.loaded(summaryReport.rendered()),
                position -> pictures.open(position, false)));
    }

    /** Was der Bericht vom Bereich wissen muss: die Seiten der Themen, öffnen über ihre Quelle. */
    private SummaryReport.Host host() {
        return new SummaryReport.Host() {
            @Override
            public int pages(String space) {
                return (int) pagesOf(space).count();
            }

            @Override
            public String counts(String space) {
                return "";
            }

            @Override
            public Collection<String> spaces() {
                Set<String> found = new LinkedHashSet<>();
                if (summary != null) {
                    summary.statements().forEach(statement -> statement.pages()
                            .forEach(page -> found.add(page.spaceKey())));
                }
                return found;
            }

            @Override
            public String title(SummaryPage page) {
                return page.title();
            }

            @Override
            public Runnable opener(SummaryPage page) {
                String source = page.sourceIdOr(sourceId);
                return source == null ? null : () -> onOpen.accept(source, page.pageId());
            }

            @Override
            public void openSpace(String space) {
                onOpenSpace.accept(sourceId, space);
            }

            @Override
            public String staleNote(UpdateSummary shown) {
                return shown.stale() ? STALE : null;
            }

            @Override
            public String when(Instant at) {
                return at == null ? "" : WHEN.format(at.atZone(zone));
            }
        };
    }

    private java.util.stream.Stream<SummaryPage> pagesOf(String space) {
        return summary == null ? java.util.stream.Stream.empty() : summary.statements().stream()
                .flatMap(statement -> statement.pages().stream())
                .filter(page -> Objects.equals(page.spaceKey(), space)).distinct();
    }

    /** Ob ein Überblick gezeigt wird, den man speichern kann. */
    boolean hasSummary() {
        return summary != null;
    }

    /** Der Überblick als Text für die Zwischenablage. */
    String copyText() {
        if (summary == null) {
            return "";
        }
        StringBuilder text = new StringBuilder("Überblick (").append(summaryReport.meta(summary)).append(')')
                .append('\n').append(summary.text());
        for (SummaryStatement statement : summary.statements()) {
            text.append("\n\n");
            if (statement.title() != null && !statement.title().isBlank()) {
                text.append(statement.title()).append('\n');
            }
            text.append(statement.text());
            if (statement.details() != null && !statement.details().isBlank()) {
                text.append('\n').append(statement.details());
            }
        }
        return text.toString();
    }

    // ---- für Tests

    boolean summarizeOffered() {
        return summarize.isVisible() && summarize.isEnabled();
    }

    boolean busy() {
        return busy.isBusy();
    }

    List<String> texts() {
        List<String> found = new ArrayList<>();
        collect(reportBody, found);
        return found;
    }

    private static void collect(java.awt.Container container, List<String> found) {
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof com.aresstack.spaceharvester.app.ui.controls.WrappedText wrapped) {
                found.add(wrapped.getText());
            } else if (child instanceof com.aresstack.spaceharvester.app.ui.controls.TextWithPicture body) {
                found.add(body.text());
            } else if (child instanceof JLabel label && label.getText() != null) {
                found.add(label.getText());
            }
            if (child instanceof java.awt.Container nested) {
                collect(nested, found);
            }
        }
    }

    /** Für Tests: die gelb markierten Teile. */
    List<String> podcastMarked() {
        return summaryReport.marked();
    }

    /** Für Tests: folgt dem Podcast, wie er es beim Abspielen tut. */
    void followForTest(List<Integer> statements) {
        follow(statements);
    }
}
