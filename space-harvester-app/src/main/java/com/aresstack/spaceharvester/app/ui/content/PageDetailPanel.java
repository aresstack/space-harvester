package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.app.ui.Background;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Facts;
import com.aresstack.spaceharvester.app.ui.Feedback;
import com.aresstack.spaceharvester.app.ui.PillTabs;
import com.aresstack.spaceharvester.app.ui.Problems;
import com.aresstack.spaceharvester.app.ui.Texts;
import com.aresstack.spaceharvester.application.assets.AssetHarvest;
import com.aresstack.spaceharvester.application.assets.PageAssets;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.PageAcronyms;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.query.DeliveredPage;
import com.aresstack.spaceharvester.application.query.PageBookmark;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.snapshot.SnapshotWarning;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.source.confluence.ConfluenceStorageTextProjector;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.ToolTipManager;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Eine Seite: zuerst ihr lesbarer Text (statische Projektion, kein ausgeführtes HTML und keine nachgeladenen Bilder),
 * daneben Eigenschaften und Anhänge; Analyse, Originaldaten, technische Angaben und die Aktionen dazu liegen unter
 * „Erweitert“. Ohne gewählte Seite steht dort nur ein Hinweis. Fehlt die Seite im lokalen Bestand, lädt sie live aus
 * der Quelle, ohne dass der Bediener etwas tun muss. Im Lesetext
 * sind die Akronyme markiert, die die Core-Analyse {@code acronyms} für genau diesen Text liefert ({@link PageAcronyms},
 * #115); Tooltip, Cursor und Alt+→/Alt+← zeigen ihre Ausschreibung.
 */
public final class PageDetailPanel extends JPanel {

    private static final String READ = "Text";
    private static final String READING = "Leseansicht";
    private java.util.function.BiConsumer<String, String> pageShown = (source, space) -> { };
    private static final String PROPERTIES = "Eigenschaften";
    private static final String ATTACHMENTS = "Anhänge";
    private static final String REFERENCES = "Verweise";
    private static final String TERMS = "Begriffe";
    private static final String ADVANCED = "Erweitert";
    private static final String ANALYSIS = "Analyse";
    private static final String RAW = "Originaldaten";
    private static final String TECHNICAL = "Technische Angaben";
    private static final String EMPTY = "empty";
    private static final String PAGE = "page";

    private final AppContext context;
    private final JLabel title = new JLabel(" ");
    private final java.awt.CardLayout cards = new java.awt.CardLayout();
    private final JPanel deck = new JPanel(cards);
    private final JLabel empty = Comic.muted("Wähle links im Explorer eine Seite aus, um ihren Inhalt zu lesen.");
    private final JLabel path = Comic.muted(" ");
    private final Breadcrumbs breadcrumbs = new Breadcrumbs();
    private final References references = new References();
    private final TermsTab terms = new TermsTab();
    private final JLabel state = Comic.muted(" ");
    private final Feedback feedback = new Feedback();
    private final JTextPane reader = new JTextPane() {
        @Override
        public String getToolTipText(MouseEvent event) {
            return acronymTooltip(event.getPoint());
        }
    };
    private final JLabel acronymHint = Comic.muted(" ");
    private final JLabel acronymDetail = Comic.muted(" ");
    private final ComposerButton recomputeReader = Comic.button("Analyse neu berechnen",
            "Berechnet die Analysen dieser Seite neu und aktualisiert die Markierungen");
    private final Facts properties = new Facts();
    private final Facts technical = new Facts();
    private final JTextArea raw = Comic.code();
    private final JTextArea storage = Comic.code();
    private final AssetsModel assetsModel = new AssetsModel();
    private final JTable assets = Comic.table(new JTable(assetsModel));
    private final JLabel assetsHint = Comic.muted(" ");
    private final EnrichmentModel enrichmentModel = new EnrichmentModel();
    private final JTable enrichments = Comic.table(new JTable(enrichmentModel));
    private final JTextArea enrichmentResult = Comic.code();
    private final PillTabs tabs = new PillTabs();
    private final ComposerButton refresh = Comic.button("Aktualität prüfen",
            "Vergleicht die Seite live mit der Quelle (refresh); der Bestand ändert sich erst beim Abgleich");
    private final ComposerButton saveRaw = Comic.button("Original speichern …", "Native Payload Byte für Byte");
    private final ComposerButton browse = Comic.button("Im Browser öffnen", "Öffnet die Seite in der Quelle im Browser");
    private final ComposerButton bookmark = Comic.button("Lesezeichen kopieren",
            "Stabile Adresse dieser Seite und Version (space-harvester://…); unter Suche wieder zu öffnen");
    private final ComposerButton harvest = Comic.button("Alle Anhänge ablegen",
            "Lädt die Anhänge dieser Seite in die lokale Anhangsablage");
    private final ComposerButton saveAsset = Comic.button("Anhang speichern …", "Datei des gewählten Anhangs");
    private final ComposerButton recompute = Comic.button("Neu berechnen", "Analysen dieser Seite neu berechnen");

    private String sourceId;
    private String pageId;
    private DeliveredPage page;
    private boolean assetsLoaded;
    private boolean readingLoaded;
    private boolean referencesLoaded;
    private final ReadingPane readingPane = new ReadingPane();
    private List<Enrichments.View> views = List.of();
    private ReaderDocument shown;
    /** Zählt Lade- und Neuberechnungsaufträge; nur das Ergebnis des letzten wird angezeigt. */
    private int request;

    public PageDetailPanel(AppContext context) {
        super(new BorderLayout(0, 8));
        this.context = context;
        setBackground(Comic.SURFACE);
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 14));
        title.setFont(Comic.semiBold(17f));
        title.setForeground(Comic.INK);

        reader.setEditable(false);
        reader.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        reader.setFont(Comic.regular(13.5f));
        reader.getCaret().setVisible(true);
        ToolTipManager.sharedInstance().registerComponent(reader);
        reader.addCaretListener(e -> showAcronymAt(Math.min(e.getDot(), e.getMark())));
        bind("alt RIGHT", "nextAcronym", true);
        bind("alt LEFT", "previousAcronym", false);
        acronymDetail.setForeground(Comic.INK);

        JPanel readerPanel = new JPanel(new BorderLayout(0, 6));
        readerPanel.setOpaque(false);
        JPanel acronymBar = new JPanel(new BorderLayout(8, 0));
        acronymBar.setOpaque(false);
        acronymBar.add(acronymHint, BorderLayout.CENTER);
        acronymBar.add(Comic.row(recomputeReader), BorderLayout.EAST);
        readerPanel.add(acronymBar, BorderLayout.NORTH);
        readerPanel.add(Comic.scroll(reader), BorderLayout.CENTER);
        readerPanel.add(acronymDetail, BorderLayout.SOUTH);

        JPanel attachmentsPanel = new JPanel(new BorderLayout(0, 6));
        attachmentsPanel.setOpaque(false);
        attachmentsPanel.add(assetsHint, BorderLayout.NORTH);
        attachmentsPanel.add(Comic.scroll(assets), BorderLayout.CENTER);
        attachmentsPanel.add(Comic.row(harvest, saveAsset), BorderLayout.SOUTH);

        JPanel analysis = new JPanel(new BorderLayout(0, 6));
        analysis.setOpaque(false);
        JComponent table = Comic.scroll(enrichments);
        table.setPreferredSize(new Dimension(300, 170));
        analysis.add(table, BorderLayout.NORTH);
        analysis.add(Comic.scroll(enrichmentResult), BorderLayout.CENTER);
        analysis.add(Comic.row(recompute), BorderLayout.SOUTH);
        enrichments.getSelectionModel().addListSelectionListener(e -> showEnrichment());

        PillTabs rawTabs = new PillTabs()
                .tab("Payload", Comic.scroll(raw))
                .tab("Storage-Format", Comic.scroll(storage));
        PillTabs advancedTabs = new PillTabs()
                .tab(TECHNICAL, Comic.scroll(padded(technical)))
                .tab(ANALYSIS, analysis)
                .tab(RAW, rawTabs);
        JPanel advanced = new JPanel(new BorderLayout(0, 8));
        advanced.setOpaque(false);
        advanced.add(Comic.row(refresh, saveRaw), BorderLayout.NORTH);
        advanced.add(advancedTabs, BorderLayout.CENTER);

        // Leseansicht zuerst und damit Standard; ein gewählter Reiter bleibt beim Laden der nächsten Seite.
        tabs.tab(READING, readingPane)
                .tab(READ, readerPanel)
                .tab(PROPERTIES, Comic.scroll(padded(properties)))
                .tab(ATTACHMENTS, attachmentsPanel)
                .tab(REFERENCES, references)
                .tab(TERMS, terms)
                .tab(ADVANCED, advanced);
        tabs.onSelect(name -> {
            if (ATTACHMENTS.equals(name)) {
                loadAssets(false);
            }
            if (READING.equals(name)) {
                loadReading();
            }
            if (REFERENCES.equals(name)) {
                loadReferences();
            }
        });
        breadcrumbs.onOpen(id -> load(sourceId, id, false));
        references.onOpen(id -> load(sourceId, id, false));
        terms.onJump(this::jumpTo);

        refresh.addActionListener(e -> load(sourceId, pageId, true));
        saveRaw.addActionListener(e -> saveRaw());
        browse.addActionListener(e -> browse());
        bookmark.addActionListener(e -> copyBookmark());
        harvest.addActionListener(e -> harvestAssets());
        saveAsset.addActionListener(e -> saveAsset());
        recompute.addActionListener(e -> recompute());
        recomputeReader.addActionListener(e -> recompute());

        JPanel header = new JPanel(new BorderLayout(0, 4));
        header.setOpaque(false);
        JPanel lines = new JPanel(new BorderLayout(0, 2));
        lines.setOpaque(false);
        lines.add(title, BorderLayout.NORTH);
        JPanel where = new JPanel(new BorderLayout(0, 2));
        where.setOpaque(false);
        where.add(breadcrumbs, BorderLayout.NORTH);
        where.add(path, BorderLayout.CENTER);
        lines.add(where, BorderLayout.CENTER);
        lines.add(state, BorderLayout.SOUTH);
        JPanel titleRow = new JPanel(new BorderLayout(8, 0));
        titleRow.setOpaque(false);
        titleRow.add(lines, BorderLayout.CENTER);
        titleRow.add(Comic.row(bookmark, browse), BorderLayout.EAST);
        header.add(titleRow, BorderLayout.NORTH);
        header.add(feedback, BorderLayout.SOUTH);
        JPanel page = new JPanel(new BorderLayout(0, 8));
        page.setOpaque(false);
        page.add(header, BorderLayout.NORTH);
        page.add(tabs, BorderLayout.CENTER);
        empty.setHorizontalAlignment(JLabel.CENTER);
        empty.setFont(Comic.regular(14f));
        deck.setOpaque(false);
        deck.add(empty, EMPTY);
        deck.add(page, PAGE);
        add(deck, BorderLayout.CENTER);
        clear();
    }

    /**
     * Keine Seite gewählt (z. B. nach dem Wechsel von Quelle oder Space): leert alles und verwirft laufende Lade- und
     * Neuberechnungsaufträge, damit keine Seite einer anderen Quelle nachträglich erscheint.
     */
    public void clear() {
        request++;
        sourceId = null;
        pageId = null;
        page = null;
        assetsLoaded = false;
        readingLoaded = false;
        referencesLoaded = false;
        readingPane.clear(" ");
        breadcrumbs.clear();
        references.message(" ");
        views = List.of();
        showReader(null, false);
        acronymHint.setText(" ");
        assetsModel.set(null);
        enrichmentModel.set(List.of());
        properties.reset();
        technical.reset();
        raw.setText("");
        storage.setText("");
        title.setText(" ");
        path.setText(" ");
        state.setText(" ");
        feedback.clear();
        setActionsEnabled(false);
        cards.show(deck, EMPTY);
    }

    /** Ob gerade keine Seite angezeigt oder geladen wird. */
    public boolean isEmpty() {
        return pageId == null;
    }

    /**
     * Lädt eine Seite. Ohne {@code refresh} aus dem Bestand; fehlt sie dort, holt der Core sie bei der Quelle, legt sie
     * im Bestand ab und liefert sie aus. Mit {@code refresh} wird sie live geprüft und, wenn sie neuer ist, live geladen.
     */
    public void load(String sourceId, String pageId, boolean refresh) {
        AppRuntime runtime = context.runtime();
        if (runtime == null || sourceId == null || pageId == null) {
            return;
        }
        this.sourceId = sourceId;
        this.pageId = pageId;
        this.assetsLoaded = false;
        this.readingLoaded = false;
        this.referencesLoaded = false;
        this.page = null;
        readingPane.clear(" ");
        breadcrumbs.clear();
        references.message(" ");
        int current = ++request;
        cards.show(deck, PAGE);
        showReader(null, false);
        acronymHint.setText(" ");
        assetsModel.set(null);
        title.setText("Seite " + pageId);
        path.setText(sourceId);
        state.setText(" ");
        properties.reset();
        technical.reset();
        raw.setText("");
        storage.setText("");
        feedback.working(refresh ? "Seite wird mit der Quelle verglichen …" : "Seite wird geladen …");
        setActionsEnabled(false);
        Font font = reader.getFont();
        Background.run(() -> {
            DeliveredPage delivered = runtime.workspace().page(sourceId, pageId, refresh);
            List<Enrichments.View> enrichments = runtime.workspace().enrichments(delivered);
            PageText text = project(delivered.snapshot());
            return new Loaded(delivered, enrichments, document(sourceId, pageId, enrichments, text, font),
                    Payloads.display(delivered.snapshot().payload()),
                    Payloads.storage(delivered.snapshot().payload()).orElse(null), refresh);
        }, loaded -> {
            if (current != request) {
                return;
            }
            show(loaded);
            if (ATTACHMENTS.equals(tabs.selected())) {
                loadAssets(false);
            }
            if (READING.equals(tabs.selected())) {
                loadReading();
            }
            if (REFERENCES.equals(tabs.selected())) {
                loadReferences();
            }
            loadPosition();
        }, error -> {
            if (current != request) {
                return;
            }
            feedback.problem(error, Problems.Step.PAGE);
            page = null;
            acronymHint.setText(" ");
        });
    }

    /** Lädt eine Seite über ihre Quell-URL (enthält die Seiten-ID). */
    public void loadByUrl(String sourceId, String url) {
        AppRuntime runtime = context.runtime();
        if (runtime == null) {
            return;
        }
        cards.show(deck, PAGE);
        feedback.working("Seite zur Adresse wird gesucht …");
        Background.run(() -> runtime.workspace().pageByUrl(sourceId, url, false),
                found -> load(sourceId, found.header().key().pageId(), false),
                error -> Background.run(() -> runtime.workspace().pageByUrl(sourceId, url, true),
                        found -> load(sourceId, found.header().key().pageId(), true),
                        e -> feedback.problem(e, Problems.Step.PAGE)));
    }

    /** Meldet nach dem Laden Quelle und Space der angezeigten Seite. */
    public void onShown(java.util.function.BiConsumer<String, String> listener) {
        this.pageShown = listener;
    }

    private void show(Loaded loaded) {
        page = loaded.page();
        views = loaded.enrichments();
        PageSnapshot snapshot = page.snapshot();
        PageMetadata metadata = page.header().metadata();
        pageShown.accept(sourceId, metadata.partitionKey());
        title.setText(metadata.title());
        path.setText(sourceId + " · Space " + metadata.partitionKey() + " · Seite " + metadata.pageId()
                + (metadata.version() == null ? "" : " · Version " + metadata.version()));
        state.setText(origin(page));
        state.setForeground(Comic.TEXT);
        feedback.clear();
        if (page.refresh() != null && loaded.checked()) {
            switch (page.refresh()) {
                case CURRENT -> feedback.success("Aktuell: Bestand und Quelle stimmen überein");
                case HEADER_CHANGED -> feedback.info("Kopfdaten geändert",
                        "Inhalt unverändert, Titel/Labels/Eltern sind live; der Bestand folgt beim Abgleich.");
                case CONTENT_CHANGED -> feedback.warning("Neuere Fassung live geladen",
                        "Die Quelle hat eine neue Revision oder die Seite fehlt im Bestand. Angezeigt wird die "
                                + "Live-Fassung; der Bestand bleibt bis zum nächsten Abgleich.");
                case GONE -> feedback.warning("In der Quelle nicht mehr sichtbar",
                        "Angezeigt wird der letzte Stand aus dem Bestand.");
            }
        }

        showReader(loaded.reader(), page.live());
        raw.setText(loaded.display());
        raw.setCaretPosition(0);
        storage.setText(loaded.storage() == null ? "Diese Payload hat kein body.storage." : loaded.storage());
        storage.setCaretPosition(0);

        properties.reset();
        properties.add("Seiten-ID", metadata.pageId())
                .add("Titel", metadata.title())
                .add("Space", metadata.partitionKey())
                .add("Elternseite", metadata.parentPageId())
                .add("Version", metadata.version())
                .add("Geändert", Texts.time(metadata.modifiedAt()))
                .add("Adresse", metadata.url() == null ? null : metadata.url().toString())
                .add("Ressource", metadata.resourceId());
        metadata.attributes().forEach((key, value) -> properties.add(key, value));

        technical.reset();
        NativePayload payload = snapshot.payload();
        technical.add("Herkunft", page.live() ? "live aus der Quelle" : "Bestand (Cache)")
                .add("Prüfung", page.refresh() == null ? "keine" : page.refresh().name().toLowerCase()
                        + ", " + Texts.time(page.checkedAt()))
                .add("Cache-Generation", page.generation() == null ? null : page.generation().id())
                .add("Cache-Stand", Texts.generation(page.generation()))
                .add("Revision", snapshot.revision())
                .add("Medientyp", payload.mediaType())
                .add("Natives Format", payload.nativeFormat())
                .add("Größe", Texts.bytes(payload.length()))
                .add("Raw-SHA-256", payload.rawSha256())
                .add("Metadaten-Hash", snapshot.metadataHash())
                .add("Attribut-Hash", snapshot.attributesHash())
                .add("Abgerufen", Texts.time(snapshot.retrievedAt()))
                .add("Beobachtet", Texts.time(page.header().observedAt()));
        for (SnapshotWarning warning : snapshot.warnings()) {
            technical.add("Warnung", warning.code() + (warning.detail() == null ? "" : ": " + warning.detail()),
                    Comic.ORANGE);
        }

        enrichmentModel.set(views);
        enrichmentResult.setText(views.isEmpty() ? "Noch keine Analysen für diese Seite. Sie entstehen nach jedem "
                + "bestätigten Abgleich oder über „Neu berechnen“." : "");
        setActionsEnabled(true);
        browse.setEnabled(metadata.url() != null && Desktop.isDesktopSupported());
    }

    /** Zeigt einen fertig gebauten Lesetext; {@code null} leert ihn samt Markierungen. */
    private void showReader(ReaderDocument document, boolean live) {
        shown = document;
        terms.show(document == null ? null : document.acronyms());
        acronymDetail.setText(" ");
        if (document == null) {
            reader.setText("");
            recomputeReader.setVisible(false);
            return;
        }
        reader.setStyledDocument(document.document());
        reader.setCaretPosition(0);
        acronymHint.setText(document.hint(live));
        recomputeReader.setVisible(document.text() != null && !live
                && document.acronyms().state() != PageAcronyms.State.CURRENT);
    }

    /** Baut den Lesetext mit den Akronymen der Seite {@code sourceId}/{@code pageId}; außerhalb des EDT. */
    private static ReaderDocument document(String sourceId, String pageId, List<Enrichments.View> views, PageText text,
                                           Font font) {
        return ReaderDocument.build(text, PageAcronyms.read(new PageKey(sourceId, pageId), views, text), font);
    }

    private String acronymTooltip(Point point) {
        ReaderDocument document = shown;
        if (document == null) {
            return null;
        }
        int offset = reader.viewToModel2D(point);
        PageAcronyms.Mark mark = offset < 0 ? null : document.markAt(offset);
        return mark == null ? null : ReaderDocument.tooltip(mark.acronym());
    }

    private void showAcronymAt(int offset) {
        ReaderDocument document = shown;
        PageAcronyms.Mark mark = document == null ? null : document.markAt(offset);
        acronymDetail.setText(mark == null ? " " : ReaderDocument.detail(mark.acronym()));
    }

    private void bind(String key, String name, boolean forward) {
        reader.getInputMap().put(KeyStroke.getKeyStroke(key), name);
        reader.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                jumpToAcronym(forward);
            }
        });
    }

    /**
     * Springt aus dem Reiter „Begriffe“ zur nächsten Fundstelle dieses Akronyms im Lesetext, nach der letzten wieder
     * zur ersten.
     */
    void jumpTo(PageAcronyms.Acronym acronym) {
        ReaderDocument document = shown;
        if (document == null) {
            return;
        }
        int caret = Math.max(reader.getCaret().getDot(), reader.getCaret().getMark());
        PageAcronyms.Mark first = null;
        PageAcronyms.Mark next = null;
        for (PageAcronyms.Mark mark : document.marks()) {
            if (!mark.acronym().acronym().equals(acronym.acronym())) {
                continue;
            }
            if (first == null) {
                first = mark;
            }
            if (next == null && mark.occurrence().start() >= caret) {
                next = mark;
            }
        }
        PageAcronyms.Mark target = next != null ? next : first;
        if (target == null) {
            return;
        }
        tabs.select(READ);
        reader.requestFocusInWindow();
        reader.select(target.occurrence().start(), target.occurrence().end());
        showAcronymAt(target.occurrence().start());
    }

    /** Markiert die nächste bzw. vorige Fundstelle und zeigt ihre Ausschreibung. */
    void jumpToAcronym(boolean forward) {
        ReaderDocument document = shown;
        if (document == null) {
            return;
        }
        int caret = Math.min(reader.getCaret().getDot(), reader.getCaret().getMark());
        PageAcronyms.Mark mark = forward ? document.next(caret) : document.previous(caret);
        if (mark != null) {
            reader.select(mark.occurrence().start(), mark.occurrence().end());
            showAcronymAt(mark.occurrence().start());
        }
    }

    private static PageText project(PageSnapshot snapshot) {
        try {
            return new ConfluenceStorageTextProjector().project(snapshot).orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String origin(DeliveredPage page) {
        if (page.live()) {
            return "Live aus der Quelle";
        }
        return "Aus dem lokalen Bestand · " + Texts.generation(page.generation());
    }

    private void loadAssets(boolean force) {
        AppRuntime runtime = context.runtime();
        if (runtime == null || page == null || (assetsLoaded && !force)) {
            return;
        }
        assetsLoaded = true;
        assetsHint.setText("Anhänge werden live geladen …");
        String source = sourceId;
        String id = pageId;
        // Eigene Gültigkeit statt request: Eine Neuberechnung der Analysen verwirft die Anhangsliste nicht.
        DeliveredPage shown = page;
        Background.run(() -> runtime.workspace().assets(source, id), overview -> {
            if (shown != page) {
                return;
            }
            assetsModel.set(overview);
            assetsHint.setText(Texts.count(overview.entries().size(), "Anhang", "Anhänge")
                    + (overview.listComplete() ? "" : " (Liste unvollständig)") + " · live, "
                    + Texts.time(overview.retrievedAt()));
        }, error -> {
            if (shown != page) {
                return;
            }
            assetsLoaded = false;
            assetsHint.setText("Anhänge nicht geladen: " + com.aresstack.spaceharvester.app.ui.Problems
                    .describe(error).title());
        });
    }

    /**
     * Ein Bild der Leseansicht: die Vorschau eines draw.io-Diagramms als Grafik des Core (wie Mermaid), alle übrigen
     * und jede Vorschau, die sich nicht zeichnen lässt, wie die Quelle sie zeigt. Außerhalb des EDT.
     */
    public static byte[] image(AppRuntime runtime, com.aresstack.spaceharvester.application.reading.ReadingView view,
                        com.aresstack.spaceharvester.application.sources.HarvestSource.ViewImage image) {
        try {
            var drawn = runtime.workspace().viewDiagram(view, image);
            if (drawn.isPresent()) {
                return drawn.get().content();
            }
        } catch (RuntimeException e) {
            // Quelldaten nicht abrufbar: dann bleibt das Vorschaubild der Quelle.
        }
        return runtime.workspace().viewImage(view, image).content();
    }

    /**
     * Lädt die Leseansicht (#192) über den Core: HTML aus dem Bestand oder live, Bilder aus dem AssetStore bzw. einmalig
     * abgelegt. Wie die Anhänge gilt das Ergebnis nur für die Seite, für die es angefordert wurde.
     */
    private void loadReading() {
        AppRuntime runtime = context.runtime();
        if (runtime == null || page == null || readingLoaded) {
            return;
        }
        readingLoaded = true;
        readingPane.clear("Leseansicht wird geladen …");
        String source = sourceId;
        String id = pageId;
        DeliveredPage shown = page;
        Background.run(() -> {
            var view = runtime.workspace().readingView(source, id, false);
            return ReadingPane.prepare(view, image -> image(runtime, view, image),
                    diagram -> runtime.workspace().mermaid(view, diagram).orElse(null));
        }, prepared -> {
            if (shown == page) {
                readingPane.show(prepared);
            }
        }, error -> {
            if (shown != page) {
                return;
            }
            readingLoaded = false;
            readingPane.clear("Leseansicht nicht geladen: " + Problems.describe(error).title());
        });
    }

    /** Lädt den Pfad der angezeigten Seite; ohne Seitenbaum der Quelle bleibt er leer. */
    private void loadPosition() {
        AppRuntime runtime = context.runtime();
        if (runtime == null || page == null) {
            return;
        }
        String source = sourceId;
        String id = pageId;
        DeliveredPage shown = page;
        Background.run(() -> runtime.workspace().position(source, id), position -> {
            if (shown == page) {
                breadcrumbs.show(position);
            }
        }, error -> {
            // Quellen ohne Seitenbaum und gesperrte Vorfahren: der Pfad fehlt, die Seite bleibt lesbar.
        });
    }

    /** Lädt die Querverweise einmal je Seite, wenn der Reiter „Verweise“ gewählt ist. */
    private void loadReferences() {
        AppRuntime runtime = context.runtime();
        if (runtime == null || page == null || referencesLoaded) {
            return;
        }
        referencesLoaded = true;
        references.message("Verweise werden geladen …");
        String source = sourceId;
        String id = pageId;
        DeliveredPage shown = page;
        Background.run(() -> runtime.workspace().references(source, id), found -> {
            if (shown == page) {
                references.show(found);
            }
        }, error -> {
            if (shown != page) {
                return;
            }
            referencesLoaded = false;
            references.message("Verweise nicht geladen: " + Problems.describe(error).title());
        });
    }

    private void harvestAssets() {
        AppRuntime runtime = context.runtime();
        if (runtime == null || page == null) {
            return;
        }
        String source = sourceId;
        String id = pageId;
        feedback.working("Anhänge werden abgelegt …");
        Background.run(() -> runtime.workspace().harvestAssets(source, id), result -> {
            long failed = result.entries().stream().filter(e -> e.status() == AssetHarvest.Status.FAILED).count();
            long stored = result.entries().stream().filter(e -> e.status() == AssetHarvest.Status.STORED).count();
            long reused = result.entries().stream().filter(e -> e.status() == AssetHarvest.Status.REUSED).count();
            String summary = stored + " abgelegt, " + reused + " schon vorhanden, " + failed + " fehlgeschlagen";
            if (failed == 0 && result.listComplete()) {
                feedback.success("Anhänge abgelegt: " + summary);
            } else {
                feedback.warning("Anhänge nur teilweise abgelegt", summary
                        + (result.listComplete() ? "" : "; die Anhangsliste war unvollständig"));
            }
            loadAssets(true);
        }, feedback::problem);
    }

    private void saveAsset() {
        AppRuntime runtime = context.runtime();
        int row = assets.getSelectedRow();
        if (runtime == null || row < 0) {
            return;
        }
        PageAssets.Entry entry = assetsModel.rows.get(row);
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(safeName(entry.attachment().title())));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File target = chooser.getSelectedFile();
        String source = sourceId;
        String id = pageId;
        feedback.working("Anhang wird geladen …");
        Background.run(() -> {
            byte[] content = runtime.workspace().assetContent(source, id, entry.attachment().attachmentId()).content();
            Files.write(target.toPath(), content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            return content.length;
        }, bytes -> feedback.success("Gespeichert: " + target + " (" + Texts.bytes(bytes) + ")"), feedback::problem);
    }

    private void saveRaw() {
        if (page == null) {
            return;
        }
        NativePayload payload = page.snapshot().payload();
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(safeName(page.header().metadata().title())
                + (Payloads.isJson(payload) ? ".json" : ".bin")));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File target = chooser.getSelectedFile();
        Background.execute(() -> Files.write(target.toPath(), payload.bytes()),
                () -> feedback.success("Original gespeichert: " + target + " (" + Texts.bytes(payload.length())
                        + ", SHA-256 " + Texts.shortHash(payload.rawSha256()) + ")"), feedback::problem);
    }

    /** Legt das Lesezeichen des Core ({@code PageBookmark}, #178) in die Zwischenablage. */
    private void copyBookmark() {
        if (page == null) {
            return;
        }
        String value = PageBookmark.of(page.header()).value();
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new java.awt.datatransfer.StringSelection(value), null);
        feedback.success("Lesezeichen kopiert: " + value);
    }

    private void browse() {
        if (page == null || page.header().metadata().url() == null) {
            return;
        }
        try {
            Desktop.getDesktop().browse(page.header().metadata().url());
        } catch (IOException | UnsupportedOperationException e) {
            feedback.problem(e);
        }
    }

    void recompute() {
        AppRuntime runtime = context.runtime();
        if (runtime == null || page == null) {
            return;
        }
        String source = sourceId;
        String id = pageId;
        PageText text = shown == null ? null : shown.text();
        boolean live = page.live();
        Font font = reader.getFont();
        int current = ++request;
        feedback.working("Analysen werden berechnet …");
        Background.run(() -> {
            Enrichments.Run run = runtime.workspace().recomputeEnrichments(source, id, null);
            DeliveredPage cached = runtime.workspace().page(source, id, false);
            List<Enrichments.View> updated = runtime.workspace().enrichments(cached);
            return new Recomputed(run, updated, document(source, id, updated, text, font));
        }, result -> {
            if (current != request) {
                return;
            }
            Enrichments.Run run = result.run();
            views = result.views();
            enrichmentModel.set(views);
            enrichmentResult.setText("");
            int caret = reader.getCaretPosition();
            showReader(result.reader(), live);
            reader.setCaretPosition(Math.min(caret, reader.getDocument().getLength()));
            if (!run.supported()) {
                feedback.info("Keine Analysen für dieses Format", null);
            } else if (run.failed() > 0) {
                feedback.warning("Analysen teilweise fehlgeschlagen", run.computed() + " berechnet, " + run.failed()
                        + " fehlgeschlagen");
            } else {
                feedback.success(run.computed() + " Analysen berechnet");
            }
        }, error -> {
            if (current == request) {
                feedback.problem(error);
            }
        });
    }

    private void showEnrichment() {
        int row = enrichments.getSelectedRow();
        if (row < 0 || row >= views.size()) {
            return;
        }
        Enrichments.View view = views.get(row);
        StringBuilder text = new StringBuilder();
        if (view.enrichment().errorCode() != null) {
            text.append("Fehler: ").append(view.enrichment().errorCode()).append('\n');
        }
        for (String warning : view.enrichment().warnings()) {
            text.append("Warnung: ").append(warning).append('\n');
        }
        text.append(Payloads.prettyJson(view.enrichment().payloadJson()));
        enrichmentResult.setText(text.toString());
        enrichmentResult.setCaretPosition(0);
    }

    private void setActionsEnabled(boolean enabled) {
        refresh.setEnabled(enabled || pageId != null);
        saveRaw.setEnabled(enabled);
        browse.setEnabled(enabled);
        bookmark.setEnabled(enabled);
        harvest.setEnabled(enabled);
        saveAsset.setEnabled(enabled);
        recompute.setEnabled(enabled);
        recomputeReader.setEnabled(enabled);
    }

    Breadcrumbs breadcrumbs() {
        return breadcrumbs;
    }

    References references() {
        return references;
    }

    TermsTab terms() {
        return terms;
    }

    PillTabs tabs() {
        return tabs;
    }

    /** Für Tests: die angezeigte Seite oder {@code null} ohne Seite und während des Ladens. */
    DeliveredPage shownPage() {
        return page;
    }

    /** Für Tests: die Rückmeldung über der Seite. */
    Feedback feedback() {
        return feedback;
    }

    /** Für Tests: der angezeigte Lesetext oder {@code null} während des Ladens. */
    ReaderDocument readerDocument() {
        return shown;
    }

    JTextPane reader() {
        return reader;
    }

    String acronymHint() {
        return acronymHint.getText();
    }

    String acronymDetail() {
        return acronymDetail.getText();
    }

    private static JPanel padded(JComponent content) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(java.awt.Color.WHITE);
        panel.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        panel.add(content, BorderLayout.NORTH);
        return panel;
    }

    static String safeName(String title) {
        String name = title == null ? "seite" : title.replaceAll("[\\p{C}\\\\/:*?\"<>|]", "_").trim();
        return name.isEmpty() ? "seite" : name.length() > 120 ? name.substring(0, 120) : name;
    }

    /** @param checked ob der Bediener ausdrücklich „Aktualität prüfen“ gewählt hat */
    private record Loaded(DeliveredPage page, List<Enrichments.View> enrichments, ReaderDocument reader,
                          String display, String storage, boolean checked) {
    }

    private record Recomputed(Enrichments.Run run, List<Enrichments.View> views, ReaderDocument reader) {
    }

    private static final class AssetsModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"Titel", "Art", "Größe", "Version", "Abgelegt", "Vorschau von"};
        private List<PageAssets.Entry> rows = new ArrayList<>();

        void set(PageAssets overview) {
            rows = overview == null ? new ArrayList<>() : new ArrayList<>(overview.entries());
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            PageAssets.Entry entry = rows.get(row);
            return switch (column) {
                case 0 -> entry.attachment().title();
                case 1 -> switch (entry.kind()) {
                    case DIAGRAM -> "draw.io-Diagramm";
                    case VECTOR -> "SVG";
                    case IMAGE -> "Bild";
                    case OTHER -> "Datei";
                };
                case 2 -> entry.attachment().fileSize() == null ? "–" : Texts.bytes(entry.attachment().fileSize());
                case 3 -> entry.attachment().version();
                case 4 -> entry.stored() == null ? "nein" : "ja, " + Texts.shortHash(entry.stored().sha256());
                default -> Optional.ofNullable(entry.previewOf()).orElse("");
            };
        }
    }

    private static final class EnrichmentModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"Analyse", "Ergebnis", "Stand", "Berechnet"};
        private List<Enrichments.View> rows = new ArrayList<>();

        void set(List<Enrichments.View> views) {
            rows = new ArrayList<>(views);
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            Enrichments.View view = rows.get(row);
            return switch (column) {
                case 0 -> view.enrichment().processorId() + " " + view.enrichment().processorVersion();
                case 1 -> view.enrichment().status().name().equals("OK") ? "ok" : "fehlgeschlagen";
                case 2 -> view.freshness().name().equals("CURRENT") ? "aktuell" : "veraltet";
                default -> Texts.time(view.enrichment().computedAt());
            };
        }
    }
}
