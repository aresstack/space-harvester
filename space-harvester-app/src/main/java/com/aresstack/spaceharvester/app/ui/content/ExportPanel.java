package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.app.ui.Background;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Downloads;
import com.aresstack.spaceharvester.app.ui.Facts;
import com.aresstack.spaceharvester.app.ui.Feedback;
import com.aresstack.spaceharvester.app.ui.FormatIcon;
import com.aresstack.spaceharvester.app.ui.IconButton;
import com.aresstack.spaceharvester.app.ui.SettingsView;
import com.aresstack.spaceharvester.app.ui.Texts;
import com.aresstack.spaceharvester.application.convert.ConversionNotice;
import com.aresstack.spaceharvester.application.export.ExportConversions;
import com.aresstack.spaceharvester.application.export.ExportDatasetInfo;
import com.aresstack.spaceharvester.application.jobs.JobListener;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.holdings.HoldingsListener;
import com.aresstack.spaceharvester.application.holdings.PageSelection;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.sources.Partition;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Reiter „Export“ der Seitenansicht (Angelo, 13.11.2026): Was links im Explorer gewählt ist, bestimmt den Inhalt,
 * eine eigene Auswahl gibt es nicht. Für einen Space oder eine Seite samt Unterseiten steht oben die Karte
 * <b>Stand</b> (jüngster fertiger Download, laufender Auftrag, Laden bzw. Aktualisieren) und darunter die Karte
 * <b>Ausgabe</b> mit den Format-Knöpfen PDF, Word, Markdown, JSON und Analyse, dem Ausgabeordner, den erzeugten
 * Dateien und den Hinweisen der Konvertierung; beides zog aus „Aufträge“ hierher um, dort bleibt nur der
 * Download-Manager. Für eine Wissensquelle stehen ihre Spaces mit ihrem Stand untereinander.
 *
 * <p>Übergang bis zur Bestandsübersicht des Core: „Stand“ ist der jüngste fertige Download genau dieser Auswahl
 * (ganzer Space bzw. dieselbe Startseite); die Ausgabe arbeitet auf seinem Datenbestand. Änderungen kommen über die
 * Auftragsmeldungen des Core (ADR 0027), nicht über einen Zeitgeber.
 */
public final class ExportPanel extends JPanel {

    /** Die Auswahl links im Explorer: Quelle, Space oder Seite (samt Unterseiten); {@code title} für die Überschrift. */
    public record Selection(String sourceId, String spaceKey, String pageId, String title) {

        boolean page() {
            return pageId != null;
        }

        boolean space() {
            return spaceKey != null && pageId == null;
        }

        /** Dieselbe Auswahl für den Core (ADR 0029): Quelle, Space oder Seite samt allen Unterseiten. */
        com.aresstack.spaceharvester.application.holdings.Selection toCore() {
            com.aresstack.spaceharvester.application.holdings.Selection.Root root = pageId != null
                    ? com.aresstack.spaceharvester.application.holdings.Selection.Root.subtree(sourceId, spaceKey, pageId)
                    : spaceKey != null
                    ? com.aresstack.spaceharvester.application.holdings.Selection.Root.space(sourceId, spaceKey)
                    : com.aresstack.spaceharvester.application.holdings.Selection.Root.source(sourceId);
            return com.aresstack.spaceharvester.application.holdings.Selection.of(root);
        }

        /** Ob ein Download genau diese Auswahl geladen hat: derselbe Space und dieselbe Startseite (oder keine). */
        boolean matches(JobStatus job) {
            return job.type() == JobType.EXPORT && Objects.equals(job.sourceId(), sourceId)
                    && Objects.equals(job.partitionKey(), spaceKey) && Objects.equals(job.rootPageId(), pageId);
        }
    }

    /** Formate der Knöpfe, in der Reihenfolge aus Angelos Skizze. */
    public static final List<String> FORMATS = List.of("pdf", "docx", "markdown-package", "json-package", "analysis");
    private static final Map<String, String> EXPORT_TIPS = Map.of("pdf", "Als PDF exportieren", "docx",
            "Als Word-Dokument exportieren", "markdown-package",
            "Als Markdown exportieren (ZIP mit Seiten und Bildern)", "json-package",
            "Als JSON exportieren (ZIP mit Seiten, Bildern und Diagrammen)", "analysis",
            "Analyse exportieren (analysis.json: Analysedaten je Seite, Originaldaten unverändert)");
    static final String NOT_LOADED = "Noch nicht geladen";
    /** Ein berührter Space ohne Inventur: der Cache kennt dort nur einzeln geladene Seiten. */
    static final String INCOMPLETE = "lückenhaft, Space ohne Inventur";
    static final String LOAD_SPACE = "Ganzen Space laden";
    static final String REFRESH = "Aktualisieren";
    static final String LOAD_SUBTREE = "Samt Unterseiten laden";
    static final String LOAD_PAGE = "Nur diese Seite laden";
    static final String NO_DATASET = "Noch nichts ausgegeben; die Format-Knöpfe geben die Auswahl aus dem Bestand "
            + "aus, ohne die Quelle zu lesen.";
    static final String NO_HOLDINGS = "Kein Bestand für diese Auswahl: erst laden.";
    static final String NOT_AVAILABLE = "kein Datenbestand vorhanden";

    private final AppContext context;
    private final JLabel title = new JLabel(" ");
    private final JLabel hint = Comic.muted(" ");
    private final Feedback feedback = new Feedback();
    private final Facts stand = new Facts();
    private final ComposerButton load = Comic.button(LOAD_SPACE, "Startet den Download sofort; der Auftrag läuft "
            + "unter Aufträge");
    private final ComposerButton loadPage = Comic.button(LOAD_PAGE, "Nur diese Seite laden, ohne Unterseiten");
    private final ComposerButton showJob = Comic.button("Auftrag anzeigen", "Den Auftrag unter Aufträge zeigen");
    private final JComponent standPlate;
    private final Map<String, IconButton> formatButtons = new LinkedHashMap<>();
    /** „Bilder: …“ mit Hinweis, solange sie noch beschafft werden; bricht um, damit der Hinweis nie abgeschnitten wird. */
    final JTextArea cardImages = Comic.note("");
    /** Die Dateien dieses Downloads im Ausgabeordner und laufende Exporte, eine Zeile je Datei. */
    final JTextArea files = Comic.note("");
    private final JLabel output = new JLabel();
    private final ComposerButton openOutput = Comic.button("Ordner öffnen", "Den Ausgabeordner anzeigen");
    /** Hinweise der letzten Konvertierung des gezeigten Downloads (fehlende Bilder, Diagramme …), aufklappbar. */
    final ComposerButton toggleNotices = Comic.button("Hinweise anzeigen", "Was in der Datei fehlt oder vereinfacht ist");
    final JTextArea noticeList = Comic.note("");
    private final JComponent outputPlate;
    /** Für eine Wissensquelle: ihre Spaces mit Stand, einer je Zeile. */
    private final Facts spaces = new Facts();
    private final JComponent spacesPlate;

    private Selection selection;
    /** Was der Reiter gerade zeigt. */
    private Data shown = Data.NONE;
    /** Datenbestand, den die Karte „Ausgabe“ gerade zeigt. */
    private ExportDatasetInfo shownDataset;
    /** Dateien des gezeigten Downloads im Ausgabeordner (Name enthält die Export-ID). */
    private List<String> outputFiles = List.of();
    /** Format, das je Download gerade erzeugt wird. */
    private final Map<String, String> convertingFormat = new HashMap<>();
    /** Downloads, deren Konvertierung gerade läuft; ihre Format-Knöpfe bleiben gesperrt. */
    private final Set<String> converting = new HashSet<>();
    /** Letzte erzeugte Datei je Download, für ihre Hinweise. */
    private final Map<String, ExportConversions.ConvertedFile> lastFile = new HashMap<>();
    /** Letzte Rückmeldung von „Exportieren“ je Download; sie bleibt stehen, während die Ansicht auffrischt. */
    private final Map<String, Consumer<Feedback>> converted = new HashMap<>();
    /** Anmeldung bei den Auftragsmeldungen des Core; je Laufzeit eine. */
    private Subscription subscription;
    private AppRuntime subscribed;
    /** Anmeldung bei den Bestandsmeldungen des Core für Quelle und Space der Auswahl (die Zeile „Bestand“). */
    private Subscription holdingsSubscription;
    private String holdingsKey;
    /** Eine Auffrischung ist schon angestoßen; weitere Meldungen bis dahin verfallen. */
    private boolean refreshPending;
    /** Der Bestand der gezeigten Auswahl; {@code null} ohne Auswahl oder für eine Quelle. */
    private PageSelection shownHoldings;
    /** Eine Ausgabe aus dem Bestand läuft: ihr Auftrag und das Format, das danach aus dem Datenbestand entsteht. */
    private String outputJob;
    private String outputFormat;
    /** Zählt Abfragen; nur das Ergebnis der jüngsten wird gezeigt. */
    private int request;

    public ExportPanel(AppContext context) {
        super(new BorderLayout(0, 8));
        this.context = context;
        setOpaque(false);
        title.setFont(Comic.semiBold(17f));
        title.setForeground(Comic.INK);
        output.setFont(Comic.regular(13f));
        output.setForeground(Comic.INK);
        load.addActionListener(e -> load(true));
        loadPage.addActionListener(e -> load(false));
        showJob.addActionListener(e -> {
            JobStatus job = shown.running() != null ? shown.running() : shown.newest();
            if (job != null) {
                context.navigator().showJob(job.id());
            }
        });
        openOutput.addActionListener(e -> {
            if (context.settings() != null) {
                SettingsView.open(context.settings().outputDirectory(), feedback);
            }
        });
        toggleNotices.addActionListener(e -> {
            noticeList.setVisible(!noticeList.isVisible());
            renderNotices();
        });

        JPanel standBody = new JPanel(new BorderLayout(0, 8));
        standBody.setOpaque(false);
        standBody.add(stand, BorderLayout.CENTER);
        standBody.add(Comic.row(load, loadPage, showJob), BorderLayout.SOUTH);
        standPlate = Comic.plate("Stand", Comic.PETROL, standBody);

        JPanel formats = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        formats.setOpaque(false);
        for (String format : FORMATS) {
            IconButton button = new IconButton(FormatIcon.of(format.equals("markdown-package") ? "markdown"
                    : format.equals("json-package") ? "json" : format), EXPORT_TIPS.get(format));
            button.addActionListener(e -> output(format));
            formatButtons.put(format, button);
            formats.add(button);
        }
        JPanel outputHead = new JPanel(new BorderLayout(0, 4));
        outputHead.setOpaque(false);
        outputHead.add(formats, BorderLayout.NORTH);
        outputHead.add(cardImages, BorderLayout.CENTER);
        JPanel produced = new JPanel(new BorderLayout(0, 4));
        produced.setOpaque(false);
        produced.add(files, BorderLayout.NORTH);
        produced.add(Comic.row(toggleNotices), BorderLayout.CENTER);
        produced.add(noticeList, BorderLayout.SOUTH);
        JPanel outputBody = new JPanel(new BorderLayout(0, 6));
        outputBody.setOpaque(false);
        outputBody.add(outputHead, BorderLayout.NORTH);
        outputBody.add(produced, BorderLayout.CENTER);
        outputBody.add(Comic.row(output, openOutput), BorderLayout.SOUTH);
        outputPlate = Comic.plate("Ausgabe", Comic.GREEN, outputBody);

        spacesPlate = Comic.plate("Spaces", Comic.PETROL, spaces);

        JPanel head = new JPanel(new BorderLayout(0, 4));
        head.setOpaque(false);
        head.add(title, BorderLayout.NORTH);
        head.add(hint, BorderLayout.CENTER);
        head.add(feedback, BorderLayout.SOUTH);
        add(head, BorderLayout.NORTH);
        add(Comic.column(standPlate, outputPlate, spacesPlate), BorderLayout.CENTER);
        toggleNotices.setVisible(false);
        noticeList.setVisible(false);
        render(Data.NONE);
        context.onChange(ignored -> {
            subscribe();
            refresh();
        });
    }

    /** Die Auswahl links hat gewechselt (Explorer); {@code null} ohne Auswahl. */
    public void selection(Selection selection) {
        if (Objects.equals(selection, this.selection)) {
            return;
        }
        this.selection = selection;
        feedback.clear();
        noticeList.setVisible(false);
        // Eine laufende Ausgabe gehört zur alten Auswahl; ihr Auftrag läuft unter Aufträge weiter.
        outputJob = null;
        outputFormat = null;
        refresh();
    }

    Selection selection() {
        return selection;
    }

    /** Meldet sich bei den Auftragsmeldungen der aktuellen Laufzeit an (ADR 0027): Downloads ändern den Stand. */
    private void subscribe() {
        AppRuntime runtime = context.runtime();
        subscribeHoldings(runtime);
        if (runtime == subscribed) {
            return;
        }
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
        subscribed = runtime;
        if (runtime == null) {
            return;
        }
        subscription = runtime.workspace().subscribeJobs(new JobListener() {
            @Override
            public void changed(JobStatus status) {
                if (affects(status)) {
                    scheduleRefresh();
                }
            }

            @Override
            public void removed(JobStatus last) {
                if (affects(last)) {
                    scheduleRefresh();
                }
            }
        });
    }

    /**
     * Meldet sich für den Bestand von Quelle und Space der Auswahl an, damit die Zeile „Bestand“ auch nach Abgleich,
     * Import oder Bereinigung stimmt (ADR 0027); eine andere Auswahl oder Laufzeit meldet neu an.
     */
    private void subscribeHoldings(AppRuntime runtime) {
        Selection current = selection;
        String key = runtime == null || current == null || current.spaceKey() == null ? null
                : current.sourceId() + "\u0000" + current.spaceKey();
        if (runtime == subscribed && Objects.equals(key, holdingsKey)) {
            return;
        }
        if (holdingsSubscription != null) {
            holdingsSubscription.close();
            holdingsSubscription = null;
        }
        holdingsKey = key;
        if (key == null) {
            return;
        }
        try {
            holdingsSubscription = runtime.workspace().subscribeHoldings(current.sourceId(), current.spaceKey(),
                    new HoldingsListener() {
                        @Override
                        public void pagesChanged(String sourceId, String spaceKey, Set<String> pageIds) {
                            scheduleRefresh();
                        }

                        @Override
                        public void spaceChanged(String sourceId, String spaceKey) {
                            scheduleRefresh();
                        }
                    });
        } catch (RuntimeException gone) {
            // Die Quelle gibt es in dieser Laufzeit nicht mehr; der Baum räumt die Auswahl gleich weg.
            holdingsSubscription = null;
        }
    }

    /**
     * Ob ein Auftrag den Stand ändern kann: Exporte und ihre Folgeaufträge (Bilder, Anhänge), deren Stand und Zähler
     * der Datensatz aus den Aufträgen ableitet.
     */
    static boolean affects(JobStatus status) {
        return status.type() == JobType.EXPORT || status.type().download();
    }

    /** Vom Zustell-Thread des Core: einmal auf dem EDT auffrischen, auch bei mehreren Meldungen hintereinander. */
    private void scheduleRefresh() {
        synchronized (this) {
            if (refreshPending) {
                return;
            }
            refreshPending = true;
        }
        SwingUtilities.invokeLater(() -> {
            synchronized (this) {
                refreshPending = false;
            }
            refresh();
        });
    }

    /** Liest den Stand der Auswahl im Hintergrund und zeichnet ihn; nur das Ergebnis der jüngsten Abfrage zählt. */
    void refresh() {
        // Die Laufzeit kann schon vor dem Reiter da sein: beim ersten Lesen anmelden, dann liest kein Abruf zu früh.
        subscribe();
        AppRuntime runtime = context.runtime();
        Selection current = selection;
        // Jede Anfrage entwertet die vorige, auch die leere: ein noch laufender Abruf darf die Auswahl nicht zurückholen.
        int ticket = ++request;
        if (runtime == null || current == null) {
            render(Data.NONE);
            return;
        }
        Path directory = context.settings() == null ? null : context.settings().outputDirectory();
        String pending = outputJob;
        Background.run(() -> gather(runtime, current, directory, pending), data -> {
            if (ticket == request) {
                render(data);
            }
        }, error -> {
            if (ticket == request) {
                render(Data.NONE);
                feedback.problem(error);
            }
        });
    }

    /** Der Stand der Auswahl: jüngster fertiger Download, laufender Auftrag, Datenbestand, Dateien; je Space einer Quelle. */
    private static Data gather(AppRuntime runtime, Selection selection, Path directory, String pendingJob) {
        List<JobStatus> jobs = runtime.workspace().jobs();
        JobStatus pending = pendingJob == null ? null
                : jobs.stream().filter(job -> job.id().equals(pendingJob)).findFirst().orElse(null);
        if (selection.spaceKey() == null) {
            List<SpaceRow> rows = new ArrayList<>();
            for (Partition space : runtime.workspace().spaces(selection.sourceId())) {
                Selection whole = new Selection(selection.sourceId(), space.key(), null, space.name());
                rows.add(new SpaceRow(space, newest(jobs, whole), running(jobs, whole)));
            }
            return new Data(selection, null, null, null, List.of(), rows, null, pending);
        }
        JobStatus newest = newest(jobs, selection);
        ExportDatasetInfo dataset = newest == null ? null
                : runtime.workspace().exportDataset(newest.id()).orElse(null);
        List<String> files = dataset == null ? List.of() : outputFiles(directory, dataset.exportId());
        // Der Bestand der Auswahl aus dem Core (ADR 0029): Seite samt Unterseiten, nicht nur die Startseite.
        PageSelection holdings = runtime.workspace().resolveSelection(selection.toCore());
        return new Data(selection, newest, running(jobs, selection), dataset, files, List.of(), holdings, pending);
    }

    /** „120 von 127 Seiten lokal“, bei lückenhafter Auflösung mit Hinweis; ohne bekannte Seiten „keine Seite bekannt“. */
    static String holdingsText(PageSelection holdings) {
        if (holdings.size() == 0) {
            return holdings.complete() ? "keine Seite bekannt" : "keine Seite bekannt · " + INCOMPLETE;
        }
        return holdings.counts().stored() + " von " + Texts.count(holdings.size(), "Seite", "Seiten") + " lokal"
                + (holdings.complete() ? "" : " · " + INCOMPLETE);
    }

    /** Der jüngste erfolgreich beendete Download genau dieser Auswahl. */
    static JobStatus newest(List<JobStatus> jobs, Selection selection) {
        return jobs.stream().filter(selection::matches).filter(job -> job.state() == JobState.COMPLETED)
                .max(Comparator.comparing((JobStatus job) -> job.finishedAt() == null ? Instant.EPOCH
                        : job.finishedAt()).thenComparing(JobStatus::createdAt)).orElse(null);
    }

    /** Ein noch nicht beendeter Download dieser Auswahl (wartend, laufend, angehalten). */
    static JobStatus running(List<JobStatus> jobs, Selection selection) {
        return jobs.stream().filter(selection::matches).filter(job -> !job.state().terminal())
                .max(Comparator.comparing(JobStatus::createdAt)).orElse(null);
    }

    /** Alles, was {@link #render} zeigt; {@link #NONE} ohne Auswahl oder Laufzeit. */
    record Data(Selection selection, JobStatus newest, JobStatus running, ExportDatasetInfo dataset,
                List<String> files, List<SpaceRow> spaces, PageSelection holdings, JobStatus pending) {
        static final Data NONE = new Data(null, null, null, null, List.of(), List.of(), null, null);
    }

    /** Ein Space einer Wissensquelle mit seinem Stand. */
    record SpaceRow(Partition space, JobStatus newest, JobStatus running) {
    }

    private void render(Data data) {
        shown = data;
        shownHoldings = data.holdings();
        Selection current = data.selection();
        boolean none = current == null;
        boolean source = !none && current.spaceKey() == null;
        standPlate.setVisible(!none && !source);
        outputPlate.setVisible(!none && !source);
        spacesPlate.setVisible(source);
        if (none) {
            title.setText(context.runtime() == null ? "Startet …" : "Export");
            hint.setText("Wähle links im Explorer eine Wissensquelle, einen Space oder eine Seite; der Export bezieht "
                    + "sich immer auf die Auswahl samt Unterseiten.");
            shownDataset = null;
            revalidate();
            repaint();
            return;
        }
        title.setText(current.page() ? "Export · " + current.title() + " samt Unterseiten"
                : "Export · " + current.title());
        if (source) {
            hint.setText("Die Spaces dieser Wissensquelle mit dem Stand ihres jüngsten Downloads; ein Space oder eine "
                    + "Seite links zeigt Stand und Ausgabe.");
            renderSpaces(data.spaces());
        } else {
            hint.setText(current.page() ? "Stand und Ausgabe der Seite samt Unterseiten."
                    : "Stand und Ausgabe des ganzen Space.");
            renderStand(data);
            renderOutput(data);
        }
        revalidate();
        repaint();
        finishOutput(data.pending());
    }

    private void renderSpaces(List<SpaceRow> rows) {
        spaces.reset();
        if (rows.isEmpty()) {
            spaces.add("Spaces", "keine bekannt");
            return;
        }
        for (SpaceRow row : rows) {
            String label = row.space().name() == null || row.space().name().isBlank() ? row.space().key()
                    : row.space().name();
            spaces.add(label, standText(row.newest(), row.running()),
                    row.newest() == null && row.running() == null ? Comic.MUTED : Comic.INK);
        }
    }

    /** „Geladen 13.11.2026 19:05 · 120 Seiten“, „Download läuft · 90 von 127 Seiten“ oder „Noch nicht geladen“. */
    static String standText(JobStatus newest, JobStatus running) {
        if (running != null) {
            return "Download " + Texts.state(running).toLowerCase(Locale.GERMAN) + " · " + progress(running);
        }
        if (newest == null) {
            return NOT_LOADED;
        }
        return "Geladen " + Texts.time(newest.finishedAt()) + " · "
                + Texts.count(newest.progress().written(), "Seite", "Seiten")
                + (newest.incomplete() ? " · unvollständig" : "");
    }

    private static String progress(JobStatus job) {
        JobProgress p = job.progress();
        return p.discovered() == 0 ? "noch nichts gefunden" : p.processed() + " von " + p.discovered() + " Seiten";
    }

    private void renderStand(Data data) {
        Selection current = data.selection();
        JobStatus newest = data.newest();
        JobStatus running = data.running();
        ExportDatasetInfo dataset = data.dataset();
        stand.reset();
        if (running != null) {
            stand.add("Auftrag", Texts.state(running) + " · " + progress(running), Comic.tone(Texts.tone(running)));
        }
        PageSelection holdings = data.holdings();
        if (holdings != null) {
            boolean all = holdings.size() > 0 && holdings.counts().stored() >= holdings.size();
            stand.add("Bestand", holdingsText(holdings), holdings.size() == 0 ? Comic.MUTED
                    : all && holdings.complete() ? Comic.GREEN : Comic.ORANGE);
        }
        if (newest == null) {
            stand.add("Stand", NOT_LOADED, Comic.MUTED);
        } else {
            stand.add("Stand vom", Texts.time(newest.finishedAt()));
            stand.add("Seiten", dataset == null ? String.valueOf(newest.progress().written())
                    : String.valueOf(dataset.pages()));
            if (dataset != null) {
                stand.add("Bilder", images(dataset.images()),
                        dataset.images() == ExportDatasetInfo.Images.INCOMPLETE ? Comic.RED : Comic.INK);
                if (dataset.media().total() > 0) {
                    stand.add("Medien", media(dataset.media()),
                            dataset.media().missing() + dataset.media().signInRequired() > 0 ? Comic.RED : Comic.INK);
                }
                stand.add("Vollständig", dataset.complete() ? "ja" : "nein",
                        dataset.complete() ? Comic.GREEN : Comic.ORANGE);
            } else {
                stand.add("Datenbestand", "nicht mehr vorhanden", Comic.ORANGE);
            }
        }
        load.setText(current.page() ? LOAD_SUBTREE : newest == null ? LOAD_SPACE : REFRESH);
        load.setToolTipText(current.page() ? "Lädt die Seite samt Unterseiten erneut; Vorhandenes nur bei Änderung"
                : newest == null ? "Lädt den ganzen Space" : "Lädt den ganzen Space erneut; Vorhandenes nur bei Änderung");
        loadPage.setVisible(current.page());
        load.setEnabled(running == null);
        loadPage.setEnabled(running == null);
        showJob.setVisible(running != null || newest != null);
    }

    private void renderOutput(Data data) {
        ExportDatasetInfo dataset = data.dataset();
        Path directory = context.settings() == null ? null : context.settings().outputDirectory();
        output.setText("Ausgabeordner: " + (directory == null ? "–" : name(directory)));
        output.setToolTipText(directory == null ? null : directory.toString());
        outputFiles = data.files();
        boolean other = dataset == null || shownDataset == null
                || !shownDataset.exportId().equals(dataset.exportId());
        shownDataset = dataset;
        if (other) {
            noticeList.setVisible(false);
        }
        renderButtons();
        if (dataset == null) {
            cardImages.setVisible(false);
            files.setText(outputJob != null && outputFormat != null ? Format.title(outputFormat)
                    + ": Ausgabe aus dem Bestand läuft …"
                    : shownHoldings == null || shownHoldings.size() == 0 ? NO_HOLDINGS : NO_DATASET);
            files.setForeground(Comic.MUTED);
            toggleNotices.setVisible(false);
            noticeList.setVisible(false);
            return;
        }
        // Die Zahlen stehen schon in „Stand“; hier nur, was die Ausgabe gerade einschränkt.
        boolean limited = dataset.images() != ExportDatasetInfo.Images.COMPLETE
                || dataset.media().missing() + dataset.media().signInRequired() > 0;
        cardImages.setVisible(limited);
        cardImages.setText(limited ? cardImages(dataset.images(), dataset.media()) : " ");
        cardImages.setForeground(dataset.images() == ExportDatasetInfo.Images.INCOMPLETE
                || dataset.media().missing() + dataset.media().signInRequired() > 0 ? Comic.RED : Comic.INK);
        renderFiles(dataset.exportId());
        renderNotices();
        Consumer<Feedback> conversion = converted.get(dataset.exportId());
        if (conversion != null) {
            conversion.accept(feedback);
        }
    }

    /** Erzeugte Dateien und ein laufender Export des gezeigten Downloads. */
    private void renderFiles(String exportId) {
        StringBuilder text = new StringBuilder();
        String running = convertingFormat.get(exportId);
        if (running != null) {
            text.append(Format.title(running)).append(": wird erzeugt …");
        }
        for (String name : outputFiles) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(name);
        }
        files.setText(text.isEmpty() ? "Noch nichts exportiert; die Format-Knöpfe erzeugen die Datei."
                : text.toString());
        files.setForeground(text.isEmpty() ? Comic.MUTED : Comic.INK);
    }

    /** Startet den Download der Auswahl sofort; der Auftrag läuft unter Aufträge, die Ansicht bleibt hier. */
    private void load(boolean withChildren) {
        Selection current = selection;
        if (current == null || current.spaceKey() == null) {
            return;
        }
        feedback.working("Download wird gestartet …");
        load.setEnabled(false);
        loadPage.setEnabled(false);
        Consumer<Downloads.Started> started = result -> {
            feedback.working(current.page() ? "Download der Seite" + (withChildren ? " samt Unterseiten" : "")
                    + " gestartet" : "Download des Space gestartet");
            context.navigator().followJobs();
            refresh();
        };
        Consumer<Throwable> failed = error -> {
            feedback.problem(error);
            load.setEnabled(true);
            loadPage.setEnabled(true);
        };
        if (current.page()) {
            Downloads.startPage(context, current.sourceId(), current.spaceKey(), current.pageId(), withChildren,
                    started, failed);
        } else {
            Downloads.start(context, current.sourceId(), List.of(current.spaceKey()), started, failed);
        }
    }

    /**
     * Dateien dieses Downloads im Ausgabeordner, neueste zuerst: Name {@code <Space>-<erste 8 Zeichen der
     * Export-ID>[-n].<Endung>} wie {@code ExportConversions}; halbfertige {@code .part}-Dateien zählen nicht.
     */
    static List<String> outputFiles(Path directory, String exportId) {
        if (directory == null || !java.nio.file.Files.isDirectory(directory)) {
            return List.of();
        }
        String mark = "-" + exportId.substring(0, Math.min(8, exportId.length()));
        try (java.util.stream.Stream<Path> entries = java.nio.file.Files.list(directory)) {
            return entries.filter(java.nio.file.Files::isRegularFile)
                    .filter(file -> !file.getFileName().toString().startsWith(".")
                            && file.getFileName().toString().contains(mark))
                    .sorted(Comparator.comparing((Path file) -> file.toFile().lastModified()).reversed())
                    .map(file -> file.getFileName().toString()).toList();
        } catch (java.io.IOException e) {
            return List.of();
        }
    }

    /** Warum dieses Format für den gezeigten Datenbestand gerade nicht geht, oder {@code null}. */
    String exportBlocked(String format) {
        if (outputJob != null) {
            return Format.title(outputFormat == null ? format : outputFormat) + " wird gerade erzeugt";
        }
        if (shownDataset == null && (shownHoldings == null || shownHoldings.size() == 0)) {
            return NOT_AVAILABLE;
        }
        if (shownDataset != null && converting.contains(shownDataset.exportId())) {
            return Format.title(convertingFormat.getOrDefault(shownDataset.exportId(), format)) + " wird gerade erzeugt";
        }
        return shownDataset == null || shownDataset.formats().contains(format) ? null : "Format nicht angeboten";
    }

    /**
     * Ausgabe der Auswahl aus dem Bestand (ADR 0029, Angelo: „Ausgabe nur aus dem Bestand“): startet
     * {@code Workspace.startExportFromHoldings} für Space bzw. Seite samt Unterseiten, der nur den Seiten-Cache liest;
     * hat sich nichts geändert, kommt der fertige Auftrag zurück. Sobald er beendet ist, entsteht die Datei im Format
     * aus seinem Datenbestand ({@link #exportAs}). Je Auswahl läuft höchstens eine Ausgabe zugleich.
     */
    void output(String format) {
        AppRuntime runtime = context.runtime();
        Selection current = selection;
        if (runtime == null || context.settings() == null || current == null || current.spaceKey() == null
                || outputJob != null) {
            return;
        }
        outputJob = "";
        outputFormat = format;
        feedback.working(Format.title(format) + " wird aus dem Bestand erzeugt …");
        renderButtons();
        Background.run(() -> {
            List<JobStatus> jobs = runtime.workspace().startExportFromHoldings(current.toCore(),
                    List.of(Downloads.FORMAT));
            if (jobs.isEmpty()) {
                throw new IllegalStateException("Kein Space in der Auswahl");
            }
            return jobs.get(0);
        }, job -> {
            if (!Objects.equals(current, selection)) {
                // Die Auswahl hat inzwischen gewechselt: die Ausgabe gehört zur alten, nicht mehr hier zeigen.
                outputJob = null;
                outputFormat = null;
                renderButtons();
                return;
            }
            outputJob = job.id();
            finishOutput(job);
            if (outputJob != null) {
                refresh();
            }
        }, error -> {
            outputJob = null;
            outputFormat = null;
            feedback.problem(error);
            renderButtons();
        });
    }

    /** Ist der Auftrag der laufenden Ausgabe beendet, entsteht jetzt die Datei; scheiterte er, steht hier der Grund. */
    private void finishOutput(JobStatus job) {
        if (job == null || outputJob == null || !job.id().equals(outputJob) || !job.state().terminal()) {
            return;
        }
        String format = outputFormat;
        outputJob = null;
        outputFormat = null;
        if (job.state() == JobState.COMPLETED) {
            exportAs(job.id(), format);
        } else {
            feedback.warning("Ausgabe aus dem Bestand " + Texts.state(job).toLowerCase(Locale.GERMAN), null);
            renderButtons();
        }
    }

    private void renderButtons() {
        for (Map.Entry<String, IconButton> entry : formatButtons.entrySet()) {
            String blocked = exportBlocked(entry.getKey());
            entry.getValue().setEnabled(blocked == null);
            entry.getValue().setToolTipText(blocked == null ? EXPORT_TIPS.get(entry.getKey())
                    : EXPORT_TIPS.get(entry.getKey()) + " · " + blocked);
        }
    }

    /** Der gezeigte Datenbestand, für Tests. */
    ExportDatasetInfo shownDataset() {
        return shownDataset;
    }

    /**
     * Exportiert den Datenbestand sofort im Format: die Datei entsteht im Ausgabeordner, ohne die Quelle erneut zu
     * lesen. Je Download läuft höchstens ein Export; weitere Klicks sind gesperrt, bis er endet.
     */
    void exportAs(String exportId, String format) {
        AppRuntime runtime = context.runtime();
        if (runtime == null || context.settings() == null || !converting.add(exportId)) {
            return;
        }
        Path directory = context.settings().outputDirectory();
        convertingFormat.put(exportId, format);
        showConverted(exportId, f -> f.working(Format.title(format) + " wird erzeugt …"));
        Background.run(() -> runtime.workspace().convertExport(exportId, format, directory), file -> {
            converting.remove(exportId);
            convertingFormat.remove(exportId);
            lastFile.put(exportId, file);
            String notices = file.noticeCount() == 0 ? ""
                    : ", " + Texts.count(file.noticeCount(), "Hinweis", "Hinweise");
            showConverted(exportId, f -> f.success(file.path().getFileName() + " gespeichert in " + name(directory)
                    + notices));
            refresh();
        }, error -> {
            converting.remove(exportId);
            convertingFormat.remove(exportId);
            showConverted(exportId, f -> f.problem(error));
        });
    }

    /** Merkt die Rückmeldung für ihren Download und zeigt sie nur, wenn die Karte gerade diesen Download zeigt. */
    private void showConverted(String exportId, Consumer<Feedback> show) {
        converted.put(exportId, show);
        if (shownDataset == null || !shownDataset.exportId().equals(exportId)) {
            return;
        }
        for (Map.Entry<String, IconButton> entry : formatButtons.entrySet()) {
            entry.getValue().setEnabled(exportBlocked(entry.getKey()) == null);
        }
        renderFiles(exportId);
        noticeList.setVisible(false);
        renderNotices();
        show.accept(feedback);
    }

    /** Knopf und Liste der Hinweise zur zuletzt erzeugten Datei des gezeigten Downloads. */
    private void renderNotices() {
        ExportConversions.ConvertedFile file = shownDataset == null ? null : lastFile.get(shownDataset.exportId());
        boolean any = file != null && file.noticeCount() > 0;
        toggleNotices.setVisible(any);
        if (!any) {
            noticeList.setVisible(false);
            return;
        }
        toggleNotices.setText(noticeList.isVisible() ? "Hinweise ausblenden"
                : Texts.count(file.noticeCount(), "Hinweis", "Hinweise") + " anzeigen");
        noticeList.setText(notices(file));
    }

    /** Die Hinweise in verständlichen Worten, eine Zeile je Hinweis. */
    static String notices(ExportConversions.ConvertedFile file) {
        StringBuilder text = new StringBuilder();
        for (ConversionNotice notice : file.notices()) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append("Seite ").append(notice.pageId()).append(": ").append(kind(notice.kind()))
                    .append(" „").append(notice.reference()).append('“');
            String reason = reason(notice.reason());
            if (reason != null) {
                text.append(" (").append(reason).append(')');
            }
        }
        int more = file.noticeCount() - file.notices().size();
        if (more > 0) {
            text.append("\n… und ").append(Texts.count(more, "weiterer Hinweis", "weitere Hinweise"));
        }
        return text.toString();
    }

    private static String kind(ConversionNotice.Kind kind) {
        return switch (kind) {
            case MISSING_IMAGE -> "fehlendes Bild";
            case MISSING_DIAGRAM -> "fehlendes Diagramm";
            case MISSING_ATTACHMENT -> "fehlender Anhang";
            case OMITTED_CONTENT -> "ausgelassener Inhalt";
        };
    }

    private static String reason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        return switch (reason) {
            case "missing" -> "bei der Quelle nicht gefunden";
            case "forbidden" -> "keine Berechtigung";
            case "too-large" -> "zu groß";
            case "unavailable" -> "Quelle nicht erreichbar";
            case "not-collected" -> "Bilder noch nicht beschafft";
            case "render-failed" -> "Darstellung fehlgeschlagen";
            case "not-rendered" -> "von der Quelle nicht dargestellt";
            case "view-count-mismatch" -> "Darstellung der Quelle unvollständig";
            default -> reason;
        };
    }

    private static String name(Path directory) {
        Path name = directory.getFileName();
        return name == null ? directory.toString() : name.toString();
    }

    /** Zeile der Karte; Exportieren bleibt in jedem Stand möglich. */
    static String cardImages(ExportDatasetInfo.Images images) {
        return switch (images) {
            case NOT_COLLECTED -> "Bilder: nicht beschafft, Dokumente enthalten nur Verweise";
            case COLLECTING -> "Bilder: werden beschafft · Dokumente, die du jetzt exportierst, entstehen noch ohne "
                    + "Bilder";
            case COMPLETE -> "Bilder: vollständig";
            case INCOMPLETE -> "Bilder: unvollständig · fehlende Bilder nennt das Dokument";
        };
    }

    /** Wie {@link #cardImages(ExportDatasetInfo.Images)}, dazu die Zählung der Medien, sobald welche bekannt sind. */
    static String cardImages(ExportDatasetInfo.Images images, ExportDatasetInfo.Media media) {
        String line = cardImages(images);
        return media.total() == 0 ? line : line + "\nMedien: " + media(media);
    }

    /** Die Medien eines Downloads in Worten; nur Zähler über null erscheinen. */
    static String media(ExportDatasetInfo.Media media) {
        List<String> parts = new ArrayList<>();
        if (media.stored() > 0) {
            parts.add(media.stored() + " gesichert");
        }
        if (media.missing() > 0) {
            parts.add(media.missing() + (media.missing() == 1 ? " fehlt" : " fehlen"));
        }
        if (media.signInRequired() > 0) {
            parts.add(media.signInRequired() + " Anmeldung erforderlich");
        }
        // Bewusst Ausgelassenes getrennt nach Grund, damit es nicht wie ein Fehler wirkt (Angelo, 13.11.2026).
        if (media.redundant() > 0) {
            parts.add(media.redundant() + (media.redundant() == 1 ? " Diagramm-Vorschau" : " Diagramm-Vorschauen")
                    + " nicht verwendet (aus Quelle gezeichnet)");
        }
        if (media.excluded() > 0) {
            parts.add(media.excluded() + " nach Einstellung ausgelassen");
        }
        return parts.isEmpty() ? "keine" : String.join(" · ", parts);
    }

    private static String images(ExportDatasetInfo.Images images) {
        return switch (images) {
            case NOT_COLLECTED -> "noch nicht beschafft, Dokumente enthalten nur Verweise";
            case COLLECTING -> "werden beschafft …";
            case COMPLETE -> "vollständig";
            case INCOMPLETE -> "unvollständig, fehlende Bilder nennt das Dokument";
        };
    }

    /** Zielformat mit Anzeigenamen in der Reihenfolge aus Angelos Skizze; unbekannte folgen mit ihrem Kürzel. */
    record Format(String id, String title) {

        private static final List<String> ORDER = List.of("pdf", "docx", "markdown", "markdown-package", "json-package",
                "json", "analysis");
        private static final Map<String, String> TITLES = Map.of("pdf", "PDF-Dokument", "docx", "Word-Dokument",
                "markdown", "Markdown", "markdown-package", "Markdown mit Medien", "json-package", "JSON mit Medien",
                "json", "JSON", "analysis", "Analyse");

        static String title(String format) {
            return TITLES.getOrDefault(format, format.toUpperCase(Locale.ROOT));
        }

        static List<Format> ordered(List<String> formats) {
            return formats.stream().sorted(Comparator.comparingInt((String f) -> ORDER.contains(f) ? ORDER.indexOf(f)
                    : ORDER.size())).map(f -> new Format(f, TITLES.getOrDefault(f, f.toUpperCase(Locale.ROOT))))
                    .toList();
        }

        @Override
        public String toString() {
            return title;
        }
    }
}
