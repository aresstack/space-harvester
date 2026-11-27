package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.research.BookEditions;
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
import com.aresstack.spaceharvester.application.jobs.JobProgress;
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
    static final String LOADING = "Überblick wird gelesen …";
    static final String PARTIAL = "Vorläufig: die Themen der schon fertigen Teile; am Ende werden sie zusammengeführt.";
    static final String EMPTY = "Dieser Bereich hat noch keine geladene Seite, die sich zusammenfassen lässt.";
    static final String NO_MODEL = "Für den Überblick ist kein Chat-Modell eingestellt. Unter Einstellungen › "
            + "Modelle wählen; danach entsteht er beim Öffnen von selbst.";
    static final String NOT_RUNNING = "Der Überblick erscheint, sobald der Dienst läuft.";
    static final String STALE = "Der Bereich hat sich seither geändert; „Neu zusammenfassen“ erstellt den Überblick "
            + "neu.";
    static final String SUMMARIZE_TIP = "Bereich zusammenfassen";
    static final String FAILURE_HINT = "Einzelheiten stehen unter Aufträge.";
    static final String RESUMMARIZE_TIP = "Überblick neu erstellen";
    static final String HELP = "„Bereich“ fasst den ganzen gewählten Bereich zusammen, nicht nur die Änderungen: "
            + "worum es geht, wie er aufgebaut ist und wo man anfängt, zum Einstieg für Neue. Fehlt der Überblick, "
            + "erstellt das eingestellte Chat-Modell ihn beim Öffnen von selbst; „Zusammenfassen“ erstellt ihn neu. "
            + "Es liest alle geladenen Seiten mit KI-Freigabe, in Teilen nebeneinander, und führt die Teile danach "
            + "zusammen.\n\n"
            + "In der Podcast-Ansicht erklärt ein Podcast denselben Überblick; der Text darunter zeigt je Thema die "
            + "ausführliche Erklärung, gelb, was gerade erzählt wird.";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", Locale.GERMAN);
    /**
     * Bereiche, die schon einmal sichtbar geladen wurden (je Sitzung, für beide Ansichten): nur beim ersten Mal entsteht
     * ein fehlender Überblick von selbst. Ein Ordner zählt nach seiner ID, so startet eine Änderung an ihm (andere
     * Favoriten, „mit Unterseiten“) nie von selbst einen KI-Lauf (ADR 0034 §7).
     */
    private static final Set<String> AUTO_CONSIDERED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * Der laufende Überblick-Auftrag je Bereich ({@link Target#identity()}), für beide Ansichten (Lese- und
     * Podcast-Ansicht) und über einen Wechsel der Auswahl hinweg (Angelo 27.11.2026: der Ring verschwand, der Auftrag
     * lief weiter, das fertige Ergebnis erschien nicht). Kehrt man zum Bereich zurück, zeigt die Ansicht den Ring wieder
     * und liest beim Ende neu. Nur Kennungen; der Stand kommt vom Core.
     */
    private static final java.util.Map<String, String> RUNNING = new java.util.concurrent.ConcurrentHashMap<>();
    /** Zählt die Kennungen für den Vorrang der offenen Ansicht ({@link #view}). */
    private static final java.util.concurrent.atomic.AtomicLong VIEWS = new java.util.concurrent.atomic.AtomicLong();
    static final String NO_FAVORITES = "Der Ordner hat noch keine Favoriten.";

    /** Was die Ansicht zeigt: zum Lesen oder zum Hören. */
    enum Mode { READ, LISTEN }

    /**
     * Was zusammengefasst wird: eine Quelle ({@code spaceKey == null}), ein Space, der Teilbaum unter einem Knoten ohne
     * eigenen Inhalt ({@code rootPageId}, etwa ein Ordner in SharePoint, BSCW oder lokalen Dateien, Angelo 26.11.2026)
     * oder ein Favoriten-Ordner ({@code folder}, ADR 0034 §7 Paket 5c); dieselbe Ansicht für alle.
     */
    record Target(String sourceId, String spaceKey, String rootPageId, ExportPanel.Selection.Collection folder) {

        static Target area(String sourceId, String spaceKey) {
            return subtree(sourceId, spaceKey, null);
        }

        /** Der Teilbaum unter {@code rootPageId}; ohne Wurzel Quelle bzw. Space. */
        static Target subtree(String sourceId, String spaceKey, String rootPageId) {
            return new Target(Objects.requireNonNull(sourceId, "sourceId"),
                    spaceKey == null || spaceKey.isBlank() ? null : spaceKey,
                    rootPageId == null || rootPageId.isBlank() ? null : rootPageId, null);
        }

        static Target folder(ExportPanel.Selection.Collection folder) {
            return new Target(null, null, null, Objects.requireNonNull(folder, "folder"));
        }

        /** Die Auswahl für den Core: die aufgelösten Wurzeln des Ordners, sonst Quelle, Space oder Teilbaum. */
        com.aresstack.spaceharvester.application.holdings.Selection core() {
            return folder != null ? new com.aresstack.spaceharvester.application.holdings.Selection(folder.roots())
                    : com.aresstack.spaceharvester.application.holdings.Selection.of(
                    new com.aresstack.spaceharvester.application.holdings.Selection.Root(sourceId, spaceKey,
                            rootPageId));
        }

        /**
         * Ob ein fehlender Überblick beim ersten Öffnen von selbst entsteht. Für einen Ordner im Baum nie: Man klickt
         * sich durch viele davon, der Lauf startet nur per Klick auf „Zusammenfassen“ (Angelo 26.11.2026).
         */
        boolean automatic() {
            return rootPageId == null;
        }

        /** Die Quellen, deren Meldungen die Ansicht hört (der Core meldet nach dem Ablegen jede Quelle der Auswahl). */
        Set<String> sources() {
            Set<String> found = new LinkedHashSet<>();
            if (folder != null) {
                folder.roots().forEach(root -> found.add(root.sourceId()));
            } else {
                found.add(sourceId);
            }
            return found;
        }

        /** Woran „schon einmal geöffnet“ hängt: der Ordner nach ID und Unterordner-Stellung, sonst der Bereich. */
        String identity() {
            return folder != null ? "ordner\u0000" + folder.folderId() + '\u0000' + folder.withSubfolders()
                    : sourceId + '\u0000' + spaceKey + '\u0000' + rootPageId;
        }

        boolean empty() {
            return folder != null && folder.roots().isEmpty();
        }
    }

    /** Was die Ansicht vom Core braucht. */
    interface Actions {

        /** Die Bereichszusammenfassung, {@code null} ohne. */
        UpdateSummary area(Target target);

        /** Ob ein Chat-Modell eingestellt ist. */
        boolean available();

        /** Der vorläufige Stand eines laufenden Überblicks (Themen der fertigen Teile), {@code null} ohne. */
        default UpdateSummary partial(Target target) {
            return null;
        }

        /** Startet „Zusammenfassen“ für den Bereich; {@code interactive} für einen Klick. */
        JobStatus summarize(Target target, boolean interactive);

        /** Aufträge, Meldungen der Quelle, Podcast und Bilder wie bei den Updates. */
        SourceUpdatesView.Actions shared();

        static Actions of(Workspace workspace) {
            if (workspace == null) {
                return null;
            }
            SourceUpdatesView.Actions shared = SourceUpdatesView.Actions.of(workspace);
            return new Actions() {
                @Override
                public UpdateSummary area(Target target) {
                    return workspace.areaSummary(target.core()).orElse(null);
                }

                @Override
                public boolean available() {
                    return workspace.areaSummariesAvailable();
                }

                @Override
                public UpdateSummary partial(Target target) {
                    return workspace.areaPartial(target.core()).orElse(null);
                }

                @Override
                public JobStatus summarize(Target target, boolean interactive) {
                    return workspace.summarizeArea(target.core(), interactive);
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

    private Target target;
    private UpdateSummary summary;
    /**
     * Die neueste Auflage des Buchs am gezeigten Ordner, {@code null} ohne (Architekt nach Angelo 27.11.2026: das Buch
     * ist die erweiterte KI-Zusammenfassung des Ordners). Die Leseansicht zeigt sie statt des Überblicks.
     */
    private BookEditions.Edition edition;
    private java.util.function.Function<String, BookEditions.Edition> books = folderId -> null;
    /**
     * Der vorläufige Stand, solange der Überblick entsteht: die Themen der schon fertigen Teile, sofort gezeigt statt
     * hinter dem Schleier (Angelo: „Teilergebnisse sofort“); {@code null} ohne.
     */
    private UpdateSummary partial;
    /** Abbrechen, solange der vorläufige Stand ohne Schleier zu sehen ist. */
    private final com.aresstack.spaceharvester.app.ui.controls.EscCircle stopPartial =
            new com.aresstack.spaceharvester.app.ui.controls.EscCircle(this::cancel, BusyOverlay.CANCEL);
    private boolean available = true;
    private boolean empty;
    /**
     * Warum der letzte Lauf nicht zum Überblick führte, {@code null} ohne (Fehler 24.11.2026: der Lauf endete sofort,
     * die Ansicht stand wieder auf „Noch kein Überblick“, der Grund nur kurz in der Statuszeile). Steht in der Ansicht,
     * bis neu gestartet oder ein anderer Bereich gewählt wird.
     */
    private String failure;
    private boolean loaded;
    private String runningJob;
    private int generation;
    private Subscription subscription = Subscription.NONE;
    private Subscription jobWatch = Subscription.NONE;
    /**
     * Die offene Ansicht für den Vorrang ({@code Foreground}), solange dieser Bereich sichtbar ist: Ein Überblick, den
     * sie startet, wartet nicht hinter anderen Aufträgen (etwa „Buch schreiben“) auf einen Platz; verlässt man sie,
     * läuft er ohne Vorrang weiter (Angelo 24.11.2026). {@code null} ohne.
     */
    private String view;

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
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0) {
                if (isShowing()) {
                    becameVisible();
                } else {
                    leaveView();
                }
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
        area(id, space, null);
    }

    /** Wie {@link #area(String, String)}, mit {@code rootPageId} der Teilbaum unter diesem Knoten. */
    void area(String id, String space, String rootPageId) {
        target(id == null ? null : Target.subtree(id, space, rootPageId));
    }

    /** Zeigt den Überblick über einen Favoriten-Ordner (ADR 0034 §7, Paket 5c); {@code null} leert die Ansicht. */
    void folder(ExportPanel.Selection.Collection folder) {
        target(folder == null ? null : Target.folder(folder));
    }

    private void target(Target next) {
        if (Objects.equals(next, target)) {
            return;
        }
        subscription.close();
        subscription = Subscription.NONE;
        jobWatch.close();
        jobWatch = Subscription.NONE;
        leaveView();
        generation++;
        target = next;
        summary = null;
        edition = null;
        partial = null;
        empty = next != null && next.empty();
        failure = null;
        loaded = false;
        // Der Ring gehört zum Bereich, nicht zur Ansicht: läuft für ihn schon ein Überblick, zeigt sie ihn wieder.
        runningJob = next == null ? null : RUNNING.get(next.identity());
        busy.setText(null);
        podcast.summary(null);
        offer();
        if (next == null) {
            message(" ");
            return;
        }
        if (empty) {
            loaded = true;
            render();
            bookOfEmptyFolder(next);
            return;
        }
        Actions current = actions.get();
        if (current == null) {
            message(NOT_RUNNING);
            return;
        }
        // Der Core meldet jede Quelle der Auswahl, sobald der Überblick abgelegt ist oder sich ihr Bestand ändert.
        List<Subscription> subscriptions = new ArrayList<>();
        Set<String> sources = next.sources();
        for (String source : sources) {
            try {
                subscriptions.add(current.shared().subscribe(source, changed -> SwingUtilities.invokeLater(() -> {
                    if (target != null && target.sources().contains(changed)) {
                        reload();
                    }
                })));
            } catch (RuntimeException e) {
                // Eine Quelle, die es nicht mehr gibt, meldet nichts; der Überblick liest ohne sie.
            }
        }
        if (next.folder() != null) {
            // Eine neue Auflage des Buchs am Ordner zeigt die Leseansicht sofort (Beobachter, kein Abfragen).
            String folderId = next.folder().folderId();
            try {
                AutoCloseable books = BookEditions.onAdded(added -> SwingUtilities.invokeLater(() -> {
                    if (target != null && target.folder() != null && folderId.equals(added)) {
                        reload();
                    }
                }));
                subscriptions.add(() -> {
                    try {
                        books.close();
                    } catch (Exception ignored) {
                        // nichts zu tun
                    }
                });
            } catch (RuntimeException ignored) {
                // ohne Meldung zeigt das nächste Öffnen die Auflage
            }
        }
        subscription = () -> subscriptions.forEach(Subscription::close);
        if (runningJob != null) {
            watch(current, runningJob, next);
        }
        reload();
    }

    /** Quelle des gezeigten Bereichs; {@code null} bei einem Ordner oder ohne Bereich. */
    String sourceId() {
        return target == null ? null : target.sourceId();
    }

    String spaceKey() {
        return target == null ? null : target.spaceKey();
    }

    /** Der gezeigte Ordner, {@code null} ohne. */
    ExportPanel.Selection.Collection folder() {
        return target == null ? null : target.folder();
    }

    /** Die gezeigte Zusammenfassung, {@code null} ohne. */
    UpdateSummary summary() {
        return summary;
    }

    /** Liest den Überblick neu. */
    void reload() {
        Actions current = actions.get();
        Target of = target;
        if (of != null && of.empty()) {
            bookOfEmptyFolder(of);
            return;
        }
        if (current == null || of == null) {
            return;
        }
        int mine = ++generation;
        java.util.function.Function<String, BookEditions.Edition> bookOf = books;
        boolean running = runningJob != null;
        runner.run(() -> new Loaded(current.area(of), current.available(), running ? current.partial(of) : null,
                mode == Mode.READ && of.folder() != null ? bookOf.apply(of.folder().folderId()) : null),
                found -> {
            if (mine != generation || !Objects.equals(of, target)) {
                return;
            }
            loaded = true;
            available = found.available();
            boolean same = found.summary() != null && found.summary().equals(summary)
                    && Objects.equals(found.edition(), edition);
            summary = found.summary();
            edition = found.edition();
            UpdateSummary interim = runningJob == null ? null : found.partial();
            boolean interimChanged = !Objects.equals(interim, partial);
            if (interimChanged) {
                partial = interim;
                offer();
            }
            if (!same && mode == Mode.LISTEN) {
                podcast.summary(summary);
            }
            if (!same || interimChanged) {
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

    /**
     * Ein Ordner ohne Favoriten kann trotzdem ein Buch haben (die Auflage gehört zum Ordner, nicht zu seinen jetzigen
     * Favoriten): dann zeigt die Leseansicht es; einen Überblick gibt es ohne Favoriten nicht.
     */
    private void bookOfEmptyFolder(Target of) {
        if (mode != Mode.READ || of.folder() == null) {
            return;
        }
        int mine = ++generation;
        java.util.function.Function<String, BookEditions.Edition> bookOf = books;
        String folderId = of.folder().folderId();
        runner.run(() -> java.util.Optional.ofNullable(bookOf.apply(folderId)), found -> {
            if (mine != generation || !Objects.equals(of, target) || found.isEmpty()) {
                return;
            }
            edition = found.get();
            offer();
            render();
        }, failure -> { });
    }

    private record Loaded(UpdateSummary summary, boolean available, UpdateSummary partial,
                          BookEditions.Edition edition) {
    }

    /**
     * Woher die Leseansicht das Buch eines Ordners nimmt: die neueste Auflage zur Ordner-ID, {@code null} ohne. Läuft im
     * Hintergrund.
     */
    void books(java.util.function.Function<String, BookEditions.Edition> latest) {
        books = Objects.requireNonNull(latest, "latest");
    }

    /** Die gezeigte Auflage des Buchs, {@code null} ohne. */
    BookEditions.Edition edition() {
        return edition;
    }

    private void becameVisible() {
        // Die andere Ansicht (Lese- bzw. Podcast-Ansicht) hat den Überblick inzwischen gestartet: Ring auch hier.
        Actions current = actions.get();
        String running = target == null ? null : RUNNING.get(target.identity());
        if (runningJob == null && running != null && current != null) {
            runningJob = running;
            offer();
            render();
            watch(current, running, target);
            return;
        }
        if (autoSummarize()) {
            render();
        }
    }

    /** Öffnet die Ansicht für den Vorrang, solange ein Bereich sichtbar gezeigt wird; die Kennung, sonst {@code null}. */
    private String enterView() {
        if (target == null || !showing.getAsBoolean()) {
            return null;
        }
        if (view == null) {
            view = "ueberblick-" + VIEWS.incrementAndGet();
            com.aresstack.spaceharvester.application.jobs.Foreground.open(view);
        }
        return view;
    }

    /** Verlässt die Ansicht: ein laufender Überblick verliert den Vorrang und läuft normal weiter. */
    private void leaveView() {
        if (view != null) {
            com.aresstack.spaceharvester.application.jobs.Foreground.close(view);
            view = null;
        }
    }

    /**
     * Fehlt der Überblick beim ersten sichtbaren Öffnen des Bereichs, entsteht er einmal von selbst (ADR 0034 §7), mit
     * dem Vorrang der offenen Ansicht; nur mit Chat-Modell. Wer den Bereich schon einmal gesehen hat, bekommt bei einer
     * Änderung nur „veraltet“, nie einen KI-Lauf von selbst. {@code true}, wenn gestartet.
     */
    private boolean autoSummarize() {
        if (!startsByItself()) {
            return false;
        }
        summarize(false);
        return true;
    }

    /**
     * Die einzige Stelle, die entscheidet, ob der Überblick beim Öffnen von selbst startet (weitere Bedingungen gehören
     * hierher, etwa „der Knoten hat ein Buch“). Merkt sich den Bereich, wenn ja, so startet er je Sitzung höchstens
     * einmal von selbst.
     */
    private boolean startsByItself() {
        // Den ersten sichtbaren Besuch merken, auch wenn schon ein Überblick da ist: Ändert sich danach der Ordner,
        // zeigt er nur „veraltet“ bzw. bietet den Klick an, statt von selbst zu starten.
        // Hat der Ordner ein Buch, zeigt die Leseansicht das Buch; der Überblick startet nur per Klick (Architekt).
        return loaded && available && runningJob == null && target != null && target.automatic() && edition == null
                && showing.getAsBoolean() && AUTO_CONSIDERED.add(target.identity()) && summary == null && !empty;
    }

    private void summarize(boolean interactive) {
        Actions current = actions.get();
        Target of = target;
        if (current == null || of == null || runningJob != null) {
            return;
        }
        runningJob = "";
        failure = null;
        offer();
        render();
        if (interactive) {
            onStatus.accept(SUMMARIZING);
        }
        String priority = enterView();
        runner.run(() -> {
            JobStatus[] started = new JobStatus[1];
            com.aresstack.spaceharvester.application.jobs.Foreground.run(priority,
                    () -> started[0] = current.summarize(of, interactive));
            return started[0];
        }, status -> {
            RUNNING.put(of.identity(), status.id());
            if (!Objects.equals(of, target)) {
                // Inzwischen ein anderer Bereich gewählt: Kehrt man zurück, zeigt er den Ring (RUNNING).
                return;
            }
            runningJob = status.id();
            watch(current, status.id(), of);
            progressed(status, of);
        }, problem -> {
            if (!Objects.equals(of, target)) {
                return;
            }
            runningJob = null;
            if (problem instanceof SummaryUnavailableException unavailable
                    && unavailable.reason() == SummaryUnavailableException.Reason.EMPTY_AREA) {
                empty = true;
            } else {
                // Der Core nennt den Grund selbst (etwa „keine Seite geladen“); sonst die Beschreibung des Fehlers.
                failure = problem instanceof SummaryUnavailableException unavailable ? unavailable.getMessage() + "."
                        : "Zusammenfassen nicht möglich: " + Problems.describe(problem).title();
                onStatus.accept(failure);
            }
            offer();
            render();
        });
    }

    /** Erst anmelden, dann den Stand lesen: so geht das Ende des Auftrags nicht verloren (ADR 0027). */
    private void watch(Actions current, String jobId, Target of) {
        jobWatch.close();
        try {
            jobWatch = current.shared().subscribeJobs(new JobListener() {
                @Override
                public void changed(JobStatus status) {
                    if (status.id().equals(jobId)) {
                        SwingUtilities.invokeLater(() -> progressed(status, of));
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
        runner.run(() -> current.shared().job(jobId), status -> {
            if (status != null) {
                progressed(status, of);
            } else {
                forget(jobId, of);
            }
        }, failure -> forget(jobId, of));
    }

    /** Der Auftrag ist nicht mehr bekannt (etwa aus der Liste entfernt): kein Ring mehr, der Stand neu gelesen. */
    private void forget(String jobId, Target of) {
        RUNNING.remove(of.identity(), jobId);
        if (!Objects.equals(of, target) || !jobId.equals(runningJob)) {
            return;
        }
        runningJob = null;
        jobWatch.close();
        jobWatch = Subscription.NONE;
        offer();
        reload();
    }

    private void progressed(JobStatus status, Target of) {
        if (status.state().terminal()) {
            RUNNING.remove(of.identity(), status.id());
        }
        if (!Objects.equals(of, target) || !status.id().equals(runningJob)) {
            return;
        }
        if (!status.state().terminal()) {
            // Teilfortschritt sofort: wie viele Teile schon gelesen sind, unter dem Ring.
            JobProgress progress = status.progress();
            String before = busy.text();
            busy.setText(progress != null && progress.discovered() > 0
                    ? SUMMARIZING + " " + progress.processed() + "/" + progress.discovered() : SUMMARIZING);
            if (partial != null && !before.equals(busy.text())) {
                render();
            }
            return;
        }
        runningJob = null;
        partial = null;
        jobWatch.close();
        jobWatch = Subscription.NONE;
        String outcome = switch (status.state()) {
            case COMPLETED -> "Überblick erstellt";
            case CANCELLED -> "Abgebrochen; der bisherige Überblick bleibt.";
            case PAUSED -> "Überblick angehalten; fortsetzen unter Aufträge.";
            default -> "Überblick fehlgeschlagen" + (status.error() == null ? "" : ": " + status.error().message());
        };
        onStatus.accept(outcome);
        if (status.state() == JobState.FAILED) {
            failure = outcome + (outcome.endsWith(".") ? "" : ".") + " " + FAILURE_HINT;
        }
        offer();
        if (status.state() == JobState.COMPLETED) {
            reload();
        } else {
            render();
        }
    }

    private void cancel() {
        Actions current = actions.get();
        String job = runningJob;
        Target of = target;
        if (current == null || job == null || job.isEmpty() || of == null) {
            return;
        }
        busy.setText("Wird abgebrochen …");
        runner.run(() -> current.shared().cancelJob(job), status -> progressed(status, of), failure -> { });
    }

    /** Knopf „Zusammenfassen“, Einstieg und Ring nach dem Stand. */
    private void offer() {
        boolean possible = target != null && available && !empty && runningJob == null;
        // Mit Buch zeigt die Leseansicht das Buch; „Zusammenfassen“ kommt mit dem Umschalter „Buch“ zurück.
        summarize.setVisible(mode == Mode.READ && target != null && edition == null);
        summarize.setEnabled(possible);
        summarize.setToolTipText(summary == null ? SUMMARIZE_TIP : RESUMMARIZE_TIP);
        summarize.getAccessibleContext().setAccessibleName(summary == null ? SourceUpdatesView.SUMMARIZE
                : SourceUpdatesView.RESUMMARIZE);
        boolean working = runningJob != null && target != null;
        if (!working) {
            busy.setText(null);
        } else if (!busy.isBusy() || busy.text().isEmpty()) {
            busy.setText(SUMMARIZING);
        }
        // Mit vorläufigem Stand kein Schleier: die fertigen Teile sind zu lesen, der Fortschritt steht darüber.
        busy.setBusy(working && partial == null);
    }

    private void render() {
        reportBody.removeAll();
        summaryReport.clear();
        pictures.placed(List.of());
        if (failure != null && target != null) {
            SummaryReport.add(reportBody, Comic.wrapped(failure, Comic.RED, 13.5f));
            reportBody.add(Box.createVerticalStrut(8));
        }
        if (edition != null) {
            renderBook(edition);
        } else if (partial != null && runningJob != null) {
            // Ohne Schleier bleibt der Abbruch erreichbar: der Kreis „Esc“ vor dem Fortschritt, wie im Ring.
            JPanel head = new JPanel(new BorderLayout(8, 0));
            head.setOpaque(false);
            head.add(stopPartial, BorderLayout.WEST);
            head.add(Comic.wrapped(busy.text().isEmpty() ? PARTIAL : busy.text() + " " + PARTIAL, Comic.BLUE, 13.5f),
                    BorderLayout.CENTER);
            SummaryReport.add(reportBody, head);
            reportBody.add(Box.createVerticalStrut(8));
            summaryReport.render(partial, () -> { });
        } else if (summary != null) {
            summaryReport.render(summary, () -> {
                summaryReport.markFollowed();
                showPictures();
                loadPictures();
            });
        } else if (target == null) {
            SummaryReport.add(reportBody, Comic.wrapped(" ", Comic.MUTED, 13.5f));
        } else if (!loaded) {
            // Nicht leer stehen lassen, solange der gespeicherte Überblick gelesen wird (Angelo 27.11.2026).
            SummaryReport.add(reportBody, Comic.wrapped(LOADING, Comic.MUTED, 13.5f));
        } else if (empty) {
            SummaryReport.add(reportBody, Comic.wrapped(target.empty() ? NO_FAVORITES : EMPTY, Comic.MUTED, 13.5f));
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

    /** Das Buch mit derselben Markdown-Darstellung wie die Antworten im Chat, darüber Auflage und Zeit. */
    private void renderBook(BookEditions.Edition shown) {
        SummaryReport.add(reportBody, Comic.wrapped(bookMeta(shown), Comic.MUTED, 12.5f));
        reportBody.add(Box.createVerticalStrut(8));
        com.aresstack.spaceharvester.app.ui.markdown.MarkdownTheme theme =
                com.aresstack.spaceharvester.app.ui.markdown.MarkdownTheme.forSurface(reportScroll.getViewport()
                        .getBackground());
        com.aresstack.spaceharvester.app.ui.markdown.MarkdownMessageView view =
                new com.aresstack.spaceharvester.app.ui.markdown.MarkdownMessageView(theme,
                        com.aresstack.spaceharvester.app.ui.markdown.DesktopLinkOpener.systemDefault(),
                        com.aresstack.spaceharvester.app.ui.markdown.CachingMermaidImageRenderer.forChat());
        view.setMarkdown(shown.book());
        view.setName("book.text");
        SummaryReport.add(reportBody, new WidthFitted(view));
    }

    /**
     * Gibt der Markdown-Darstellung die Höhe für die Breite der Spalte (wie die Sprechblasen im Chat); die Spalte unten
     * fragt nur die bevorzugte Größe, ohne Breite wäre ein umbrochener Absatz abgeschnitten.
     */
    private static final class WidthFitted extends JPanel {
        private final com.aresstack.spaceharvester.app.ui.markdown.MarkdownMessageView view;

        WidthFitted(com.aresstack.spaceharvester.app.ui.markdown.MarkdownMessageView view) {
            super(new BorderLayout());
            this.view = view;
            setOpaque(false);
            add(view, BorderLayout.CENTER);
        }

        @Override
        public java.awt.Dimension getPreferredSize() {
            int width = getWidth() > 0 ? getWidth() : getParent() != null ? getParent().getWidth() : 0;
            if (width <= 0) {
                return super.getPreferredSize();
            }
            return new java.awt.Dimension(width, view.preferredHeightForWidth(width));
        }

        @Override
        public java.awt.Dimension getMaximumSize() {
            return new java.awt.Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    private String bookMeta(BookEditions.Edition shown) {
        return "Buch, " + shown.number() + ". Auflage"
                + (shown.createdAt() == null ? "" : " vom " + WHEN.format(shown.createdAt().atZone(zone)))
                + ", aus " + shown.sources().size() + (shown.sources().size() == 1 ? " Seite" : " Seiten");
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
                String source = page.sourceIdOr(sourceId());
                return source == null ? null : () -> onOpen.accept(source, page.pageId());
            }

            @Override
            public void openSpace(String space) {
                // Im Ordner kennt die Seite ihre Quelle (SummaryPage.sourceId); sonst die des Bereichs.
                String source = pagesOf(space).map(page -> page.sourceIdOr(sourceId())).filter(Objects::nonNull)
                        .findFirst().orElse(sourceId());
                if (source != null) {
                    onOpenSpace.accept(source, space);
                }
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
        return summary != null || edition != null;
    }

    /** Der Überblick als Text für die Zwischenablage. */
    String copyText() {
        if (edition != null) {
            return bookMeta(edition) + "\n\n" + edition.book();
        }
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
