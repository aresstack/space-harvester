package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.app.ui.Background;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Facts;
import com.aresstack.spaceharvester.app.ui.Feedback;
import com.aresstack.spaceharvester.app.ui.PillTabs;
import com.aresstack.spaceharvester.app.ui.Texts;
import com.aresstack.spaceharvester.application.assets.AssetHarvest;
import com.aresstack.spaceharvester.application.assets.PageAssets;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.query.DeliveredPage;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.snapshot.SnapshotWarning;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.source.confluence.ConfluenceStorageTextProjector;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.table.AbstractTableModel;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Eine Seite: lesbarer Text (statische Projektion, kein ausgeführtes HTML und keine nachgeladenen Bilder),
 * Eigenschaften, Anhänge, Analyse-Ergebnisse, Originaldaten und technische Details in getrennten Reitern.
 */
public final class PageDetailPanel extends JPanel {

    private static final String READ = "Inhalt";
    private static final String PROPERTIES = "Eigenschaften";
    private static final String ATTACHMENTS = "Anhänge";
    private static final String ANALYSIS = "Analyse";
    private static final String RAW = "Originaldaten";
    private static final String TECHNICAL = "Technik";

    private final AppContext context;
    private final JLabel title = new JLabel("Keine Seite gewählt");
    private final JLabel path = Comic.muted(" ");
    private final JLabel state = Comic.muted(" ");
    private final Feedback feedback = new Feedback();
    private final JTextPane reader = new JTextPane();
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
    private final ComposerButton browse = Comic.button("In der Quelle öffnen", "Öffnet die Seite im Browser");
    private final ComposerButton harvest = Comic.button("Alle Anhänge ablegen",
            "Lädt die Anhänge dieser Seite in die lokale Anhangsablage");
    private final ComposerButton saveAsset = Comic.button("Anhang speichern …", "Datei des gewählten Anhangs");
    private final ComposerButton recompute = Comic.button("Neu berechnen", "Analysen dieser Seite neu berechnen");

    private String sourceId;
    private String pageId;
    private DeliveredPage page;
    private boolean assetsLoaded;
    private List<Enrichments.View> views = List.of();

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

        tabs.tab(READ, Comic.scroll(reader))
                .tab(PROPERTIES, Comic.scroll(padded(properties)))
                .tab(ATTACHMENTS, attachmentsPanel)
                .tab(ANALYSIS, analysis)
                .tab(RAW, rawTabs)
                .tab(TECHNICAL, Comic.scroll(padded(technical)));
        tabs.onSelect(name -> {
            if (ATTACHMENTS.equals(name)) {
                loadAssets(false);
            }
        });

        refresh.addActionListener(e -> load(sourceId, pageId, true));
        saveRaw.addActionListener(e -> saveRaw());
        browse.addActionListener(e -> browse());
        harvest.addActionListener(e -> harvestAssets());
        saveAsset.addActionListener(e -> saveAsset());
        recompute.addActionListener(e -> recompute());

        JPanel header = new JPanel(new BorderLayout(0, 4));
        header.setOpaque(false);
        JPanel lines = new JPanel(new BorderLayout(0, 2));
        lines.setOpaque(false);
        lines.add(title, BorderLayout.NORTH);
        lines.add(path, BorderLayout.CENTER);
        lines.add(state, BorderLayout.SOUTH);
        header.add(lines, BorderLayout.NORTH);
        header.add(Comic.row(refresh, saveRaw, browse), BorderLayout.CENTER);
        header.add(feedback, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
        setActionsEnabled(false);
    }

    /**
     * Lädt eine Seite. Ohne {@code refresh} aus dem Bestand; mit {@code refresh} wird sie live geprüft und, wenn sie
     * fehlt oder neuer ist, live geladen (der Bestand ändert sich erst beim nächsten Abgleich).
     */
    public void load(String sourceId, String pageId, boolean refresh) {
        AppRuntime runtime = context.runtime();
        if (runtime == null || sourceId == null || pageId == null) {
            return;
        }
        this.sourceId = sourceId;
        this.pageId = pageId;
        this.assetsLoaded = false;
        assetsModel.set(null);
        title.setText("Seite " + pageId);
        path.setText(sourceId);
        state.setText(" ");
        feedback.working(refresh ? "Seite wird mit der Quelle verglichen …" : "Seite wird geladen …");
        setActionsEnabled(false);
        Background.run(() -> {
            DeliveredPage delivered = runtime.workspace().page(sourceId, pageId, refresh);
            return new Loaded(delivered, runtime.workspace().enrichments(delivered), project(delivered.snapshot()),
                    Payloads.display(delivered.snapshot().payload()),
                    Payloads.storage(delivered.snapshot().payload()).orElse(null));
        }, loaded -> {
            if (!pageId.equals(this.pageId)) {
                return;
            }
            show(loaded);
            if (ATTACHMENTS.equals(tabs.selected())) {
                loadAssets(false);
            }
        }, error -> {
            feedback.problem(error);
            page = null;
        });
    }

    /** Lädt eine Seite über ihre Quell-URL (enthält die Seiten-ID). */
    public void loadByUrl(String sourceId, String url) {
        AppRuntime runtime = context.runtime();
        if (runtime == null) {
            return;
        }
        feedback.working("Seite zur Adresse wird gesucht …");
        Background.run(() -> runtime.workspace().pageByUrl(sourceId, url, false),
                found -> load(sourceId, found.header().key().pageId(), false),
                error -> Background.run(() -> runtime.workspace().pageByUrl(sourceId, url, true),
                        found -> load(sourceId, found.header().key().pageId(), true), feedback::problem));
    }

    private void show(Loaded loaded) {
        page = loaded.page();
        views = loaded.enrichments();
        PageSnapshot snapshot = page.snapshot();
        PageMetadata metadata = page.header().metadata();
        title.setText(metadata.title());
        path.setText(sourceId + " · Space " + metadata.partitionKey() + " · Seite " + metadata.pageId()
                + (metadata.version() == null ? "" : " · Version " + metadata.version()));
        state.setText(origin(page));
        state.setForeground(page.live() ? Comic.ORANGE : Comic.TEXT);
        feedback.clear();
        if (page.refresh() != null) {
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

        showReader(loaded.text());
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

    private void showReader(PageText text) {
        reader.setText("");
        StyledDocument doc = reader.getStyledDocument();
        try {
            if (text == null) {
                doc.insertString(0, "Für dieses Format gibt es keine Lesevorschau. Die Originaldaten zeigen den "
                        + "vollständigen Inhalt.", null);
                return;
            }
            doc.insertString(0, text.text(), null);
            for (PageText.Section section : text.sections()) {
                SimpleAttributeSet heading = new SimpleAttributeSet();
                StyleConstants.setBold(heading, true);
                StyleConstants.setFontSize(heading, Math.max(14, 22 - 2 * section.level()));
                StyleConstants.setForeground(heading, Comic.INK);
                doc.setCharacterAttributes(section.start(), Math.min(section.heading().length(),
                        doc.getLength() - section.start()), heading, false);
            }
        } catch (BadLocationException e) {
            // Abschnittsgrenzen stammen aus demselben Text; tritt nicht auf.
        }
        reader.setCaretPosition(0);
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
            return "Live aus der Quelle geladen (nicht im Bestand oder neuer)";
        }
        return "Aus dem Bestand · " + Texts.generation(page.generation());
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
        Background.run(() -> runtime.workspace().assets(source, id), overview -> {
            if (!id.equals(pageId)) {
                return;
            }
            assetsModel.set(overview);
            assetsHint.setText(Texts.count(overview.entries().size(), "Anhang", "Anhänge")
                    + (overview.listComplete() ? "" : " (Liste unvollständig)") + " · live, "
                    + Texts.time(overview.retrievedAt()));
        }, error -> {
            assetsLoaded = false;
            assetsHint.setText("Anhänge nicht geladen: " + com.aresstack.spaceharvester.app.ui.Problems
                    .describe(error).title());
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

    private void recompute() {
        AppRuntime runtime = context.runtime();
        if (runtime == null || page == null) {
            return;
        }
        String source = sourceId;
        String id = pageId;
        feedback.working("Analysen werden berechnet …");
        Background.run(() -> {
            Enrichments.Run run = runtime.workspace().recomputeEnrichments(source, id, null);
            DeliveredPage current = runtime.workspace().page(source, id, false);
            return new Object[] {run, runtime.workspace().enrichments(current)};
        }, result -> {
            Enrichments.Run run = (Enrichments.Run) result[0];
            @SuppressWarnings("unchecked")
            List<Enrichments.View> updated = (List<Enrichments.View>) result[1];
            views = updated;
            enrichmentModel.set(updated);
            if (!run.supported()) {
                feedback.info("Keine Analysen für dieses Format", null);
            } else if (run.failed() > 0) {
                feedback.warning("Analysen teilweise fehlgeschlagen", run.computed() + " berechnet, " + run.failed()
                        + " fehlgeschlagen");
            } else {
                feedback.success(run.computed() + " Analysen berechnet");
            }
        }, feedback::problem);
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
        harvest.setEnabled(enabled);
        saveAsset.setEnabled(enabled);
        recompute.setEnabled(enabled);
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

    private record Loaded(DeliveredPage page, List<Enrichments.View> enrichments, PageText text, String display,
                          String storage) {
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
