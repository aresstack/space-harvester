package com.aresstack.spaceharvester.app.ui.deck.visual;

import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Glyph;
import com.aresstack.spaceharvester.app.ui.IconButton;
import com.aresstack.spaceharvester.app.ui.controls.MessagePlate;
import com.aresstack.spaceharvester.app.ui.deck.editor.DocumentEditor;
import com.aresstack.spaceharvester.deck.editor.EditorSessions;
import com.aresstack.spaceharvester.mainframe.diagram.OutlineToMermaidConverter.DiagramType;
import com.aresstack.spaceharvester.mainframe.diagram.SourceDiagrams;
import com.aresstack.spaceharvester.mainframe.model.JclElement;
import com.aresstack.spaceharvester.mainframe.outline.SourceLanguage;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.util.Optional;

/**
 * Diagramm-Ansicht „Visuell“ eines Editors, übernommen aus MainframeMates {@code SplitPreviewTab} (Diagramm-Modus):
 * Großrechner-Quelltext wird über die Gliederung zum Diagramm (Struktur, Ablauf, Sequenz, Mindmap, ER-Diagramm),
 * Mermaid-Quelltext direkt gezeichnet und ist dann im Diagramm bearbeitbar. Rechts die Seitenleiste wie in MainframeMate:
 * Bearbeiten, Übersicht/Details, Diagrammtyp, Mindmap-Tiefe, Details zum gewählten Element, Bearbeiten-Felder, Status
 * und darunter die Gliederung (Auswahl zeigt das Element im Diagramm). Export über den Knopf oben rechts. Die Suche
 * unten im Editor sucht, solange das Diagramm sichtbar ist, darin nach Knoten.
 */
final class DiagramView extends JPanel implements DocumentEditor.CenterSearch, DocumentEditor.CenterTools {

    static final String TYPE_KEY = "deck.visual.type";
    static final String DEPTH_KEY = "deck.visual.mindmap-depth";
    static final String COLLAPSED_KEY = "deck.visual.collapsed";
    /** Neu rastern, wenn sich der Zoom um so viele Prozentpunkte ändert; 0 = aus (MainframeMate: Einstellungen). */
    static final String REFRESH_KEY = "deck.visual.auto-refresh-percent";

    private final DocumentEditor editor;
    private final boolean mermaid;
    private final CallTargets targets;
    private final MermaidDiagramPanel diagram = new MermaidDiagramPanel(false);
    private final MessagePlate plate = new MessagePlate();
    // Symbolknöpfe und Auswahllisten der App statt Emoji-Knöpfen (Angelo 24.11.).
    private final IconButton editToggle = new IconButton(Glyph.PENCIL,
            "Bearbeiten im Diagramm: Knoten umbenennen, Kanten ziehen (rechte Maustaste)");
    private final IconButton collapseToggle = new IconButton(Glyph.OVERVIEW, "Übersicht (kompaktes Diagramm)");
    private final java.util.Map<DiagramType, IconButton> typeButtons = new java.util.EnumMap<>(DiagramType.class);
    private final JComponent[] barTools;
    private final JComponent sideTools;
    private DiagramType type;
    private int mindmapDepth;
    private boolean collapsed;
    private long ticket;

    DiagramView(DocumentEditor editor, EditorSessions editors) {
        super(new BorderLayout());
        this.editor = editor;
        this.mermaid = isMermaid(editor);
        this.targets = new CallTargets(editors, editor.session());
        AppState state = AppState.shared();
        this.type = restoreType(state);
        this.mindmapDepth = Math.max(0, Math.min(10, state.getInt(DEPTH_KEY, 0)));
        this.collapsed = state.getBoolean(COLLAPSED_KEY, false);
        setOpaque(true);
        setBackground(Color.WHITE);
        getAccessibleContext().setAccessibleName("Diagramm " + editor.session().name());

        diagram.setAutoRefreshThresholdPercent(Math.max(0, state.getInt(REFRESH_KEY, 1)));
        diagram.setSourceChangeListener(source -> {
            if (mermaid) {
                editor.replaceText(source);
            }
        });
        diagram.setNodeDoubleClickListener(this::openTarget);

        // Zoom, Einpassen, Neu zeichnen und Export als Symbolknöpfe vorn in der Werkzeugzeile des Editors, keine eigene
        // Zeile und nichts über dem Diagramm.
        JLabel zoom = diagram.getZoomLabel();
        zoom.setFont(Comic.regular(12f));
        zoom.setForeground(Comic.MUTED);
        zoom.setPreferredSize(new Dimension(44, 24));
        zoom.setToolTipText("Vergrößerung");
        barTools = new JComponent[] {
                action(VisualIcons.minus(), "Verkleinern", diagram::zoomOut),
                zoom,
                action(Glyph.PLUS, "Vergrößern", diagram::zoomIn),
                action(Glyph.FIT, "Einpassen", diagram::fit),
                action(Glyph.REFRESH, "Neu zeichnen in dieser Vergrößerung", diagram::reRender),
                action(Glyph.EXPORT, "Diagramm exportieren (SVG oder PNG)", this::export)};
        sideTools = buildSideTools();

        add(plate, BorderLayout.NORTH);
        if (editor.sidePanelsDetached()) {
            // Der Arbeitsbereich hat eine Seitenleiste: dort stehen die Werkzeuge als Reiter, die Gliederung ist schon
            // ein Reiter und zeigt in dieser Ansicht das gewählte Element im Diagramm. Nichts rechts vom Diagramm.
            editor.outlineChooser(this::showElement);
            add(diagram, BorderLayout.CENTER);
        } else {
            JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, diagram, sidebar());
            split.setResizeWeight(1.0);
            split.setBorder(BorderFactory.createEmptyBorder());
            split.setDividerSize(6);
            add(split, BorderLayout.CENTER);
            SwingUtilities.invokeLater(() -> split.setDividerLocation(Math.max(200, split.getWidth() - 300)));
        }
    }

    private static IconButton action(javax.swing.Icon icon, String tooltip, Runnable run) {
        IconButton button = new IconButton(icon, tooltip);
        button.getAccessibleContext().setAccessibleName(tooltip);
        button.addActionListener(e -> run.run());
        return button;
    }

    @Override
    public JComponent[] barTools() {
        return barTools;
    }

    @Override
    public JComponent sideTools() {
        return sideTools;
    }

    /** Ob der Editor Mermaid-Quelltext zeigt (Endung oder erstes Wort), dann wird er direkt gezeichnet. */
    static boolean isMermaid(DocumentEditor editor) {
        String name = editor.session().name().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".mmd") || name.endsWith(".mermaid") || SourceDiagrams.isMermaid(editor.text());
    }

    /** Ob „Visuell“ für diesen Editor angeboten wird: Großrechner-Quelltext oder Mermaid (wie MainframeMate). */
    static boolean applies(DocumentEditor editor) {
        return editor.language().isPresent() || isMermaid(editor);
    }

    // ---- Seitenleiste --------------------------------------------------------------------------------------------

    /** Ohne Seitenleiste des Arbeitsbereichs (etwa FTP): Werkzeuge und Gliederung rechts neben dem Diagramm. */
    private JComponent sidebar() {
        JPanel outer = new JPanel(new BorderLayout());
        outer.setBackground(Comic.SURFACE);
        outer.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, Comic.LINE));
        outer.setPreferredSize(new Dimension(300, 200));
        outer.setMinimumSize(new Dimension(220, 100));
        JComponent center = sideTools;
        if (!mermaid) {
            JComponent outline = editor.outlineView(this::showElement);
            registeredOutline = outline;
            JSplitPane parts = new JSplitPane(JSplitPane.VERTICAL_SPLIT, center, outline);
            parts.setResizeWeight(0.5);
            parts.setBorder(BorderFactory.createEmptyBorder());
            parts.setDividerSize(6);
            center = parts;
        }
        outer.add(center, BorderLayout.CENTER);
        return outer;
    }

    /** Die Werkzeuge der Ansicht: Bearbeiten, Übersicht, Diagrammart, Mindmap-Tiefe, Angaben zum gewählten Element. */
    private JComponent buildSideTools() {
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(4, 4, 4, 4));
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Bearbeiten nur für Mermaid-Quelltext, der gespeichert werden darf; Diagramme aus der Gliederung nur lesen
        editToggle.getAccessibleContext().setAccessibleName("Bearbeiten im Diagramm");
        editToggle.setVisible(mermaid && editor.editable());
        editToggle.addActionListener(e -> {
            editToggle.marked(!editToggle.marked());
            diagram.setEditable(editToggle.marked());
        });
        row.add(editToggle);

        if (!mermaid) {
            collapseToggle.getAccessibleContext().setAccessibleName("Übersicht");
            showCollapse();
            collapseToggle.addActionListener(e -> {
                collapsed = !collapsed;
                // ausdrücklich gewählt: „Details einblenden“ gilt auch für große Quelltexte
                autoCollapse = false;
                AppState.shared().put(COLLAPSED_KEY, collapsed);
                showCollapse();
                generate();
            });
            row.add(collapseToggle);
            row.add(Box.createHorizontalStrut(8));
            for (DiagramType each : DiagramType.values()) {
                IconButton button = new IconButton(VisualIcons.diagramType(each), each.getLabel());
                button.getAccessibleContext().setAccessibleName(each.getLabel());
                button.marked(each == type);
                button.addActionListener(e -> {
                    type = each;
                    AppState.shared().put(TYPE_KEY, each.name());
                    typeButtons.forEach((key, other) -> other.marked(key == each));
                    generate();
                });
                typeButtons.put(each, button);
                row.add(button);
            }
        }
        header.add(row);

        if (!mermaid) {
            JPanel depth = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
            depth.setOpaque(false);
            depth.setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel label = Comic.caption("Mindmap-Tiefe");
            label.setToolTipText("Rekursionstiefe für externe Aufrufe in der Mindmap (0 = keine Rekursion)");
            javax.swing.JComboBox<Integer> choice = Comic.combo();
            for (int value = 0; value <= 10; value++) {
                choice.addItem(value);
            }
            choice.setSelectedItem(mindmapDepth);
            choice.setToolTipText("0 = flach, 1 = direkte Aufrufe, 2+ = rekursiv");
            choice.getAccessibleContext().setAccessibleName("Mindmap-Tiefe");
            choice.addActionListener(e -> {
                int value = choice.getSelectedItem() instanceof Integer chosen ? chosen : mindmapDepth;
                if (value != mindmapDepth) {
                    mindmapDepth = value;
                    AppState.shared().put(DEPTH_KEY, value);
                    if (type == DiagramType.MINDMAP) {
                        generate();
                    }
                }
            });
            depth.add(label);
            depth.add(choice);
            header.add(depth);
        }

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(new EmptyBorder(4, 4, 8, 4));
        body.setOpaque(false);
        javax.swing.JTextArea details = diagram.getDetailArea();
        details.setFont(Comic.regular(12.5f));
        details.setBackground(Color.WHITE);
        details.setBorder(new EmptyBorder(4, 6, 4, 6));
        JComponent detail = Comic.scroll(details);
        detail.setBorder(Comic.fieldBorder());
        detail.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));
        detail.setPreferredSize(new Dimension(200, 90));
        detail.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(detail);
        body.add(Box.createVerticalStrut(6));
        JPanel edit = diagram.getEditPanel();
        edit.setOpaque(false);
        edit.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(edit);
        body.add(Box.createVerticalStrut(4));
        JLabel status = diagram.getStatusLabel();
        status.setFont(Comic.regular(12f));
        status.setForeground(Comic.MUTED);
        status.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(status);
        body.add(Box.createVerticalStrut(6));
        body.add(advanced());

        JPanel tools = new JPanel(new BorderLayout());
        tools.setOpaque(false);
        tools.add(header, BorderLayout.NORTH);
        tools.add(Comic.scroll(body), BorderLayout.CENTER);
        return tools;
    }

    /** „Erweitert“: Neu-Rastern beim Zoomen (in MainframeMate unter Einstellungen › Allgemein). */
    private JComponent advanced() {
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setOpaque(false);
        box.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton disclosure = Comic.disclosure("Erweitert", "Weitere Einstellungen der Diagramm-Ansicht");
        disclosure.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel content = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        content.setOpaque(false);
        content.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel label = Comic.caption("Neu zeichnen ab Zoom-Änderung (%)");
        label.setToolTipText("Nach dem Zoomen schärfer neu zeichnen, wenn sich der Zoom um so viele Prozentpunkte "
                + "geändert hat; 0 = aus");
        int current = Math.max(0, AppState.shared().getInt(REFRESH_KEY, 1));
        javax.swing.JComboBox<Integer> spinner = Comic.combo();
        java.util.TreeSet<Integer> values = new java.util.TreeSet<>(java.util.List.of(0, 1, 2, 5, 10, 20, 50, 100));
        values.add(Math.min(100, current));
        values.forEach(spinner::addItem);
        spinner.setSelectedItem(Math.min(100, current));
        spinner.getAccessibleContext().setAccessibleName("Neu zeichnen ab Zoom-Änderung in Prozent");
        spinner.addActionListener(e -> {
            int value = spinner.getSelectedItem() instanceof Integer chosen ? chosen : current;
            AppState.shared().put(REFRESH_KEY, value);
            diagram.setAutoRefreshThresholdPercent(value);
        });
        content.add(label);
        content.add(spinner);
        content.setVisible(false);
        disclosure.addActionListener(e -> {
            boolean open = !Comic.disclosed(disclosure);
            Comic.disclose(disclosure, open);
            content.setVisible(open);
            box.revalidate();
        });
        box.add(disclosure);
        box.add(content);
        return box;
    }

    private void showCollapse() {
        collapseToggle.marked(collapsed);
        collapseToggle.setToolTipText(collapsed ? "Details einblenden (vollständiges Diagramm)"
                : "Übersicht (kompaktes Diagramm)");
    }

    private JComponent registeredOutline;
    /** Nur die erste Erzeugung darf große Quelltexte von sich aus kompakt zeigen. */
    private boolean autoCollapse = true;

    private Runnable back;

    /** Was „Zurück zum Text“ tut (VisualAction: Knopf zurücksetzen und diese Ansicht freigeben). */
    void onBack(Runnable action) {
        back = action;
    }

    private void goBack() {
        if (back != null) {
            back.run();
        } else {
            editor.showText();
        }
    }

    /**
     * Die Ansicht wird nicht mehr gebraucht (zurück zum Text oder ersetzt): Gliederung abmelden, damit der Editor weder
     * sie noch dieses Diagramm (Bild, SVG, Kacheln) festhält; laufende Erzeugung verwerfen.
     */
    void dispose() {
        if (editor.sidePanelsDetached()) {
            editor.outlineChooser(null);
        }
        if (registeredOutline != null) {
            editor.releaseOutlineView(registeredOutline);
            registeredOutline = null;
        }
        ticket++;
    }

    // ---- Erzeugen --------------------------------------------------------------------------------------------------

    /** Erzeugt das Diagramm aus dem aktuellen Text neu (im Hintergrund) und zeichnet es. */
    void generate() {
        plate.dismiss();
        diagram.setLoading();
        long mine = ++ticket;
        String text = editor.text();
        String name = editor.session().name();
        Optional<SourceLanguage> language = editor.language();
        var preparedOutline = editor.cobolDiagramOutline(text);
        DiagramType wanted = type;
        int depth = mindmapDepth;
        boolean compact = collapsed;
        boolean auto = autoCollapse;
        // Große Quelltexte nur beim Öffnen automatisch kompakt (wie MainframeMate), danach entscheidet der Schalter
        autoCollapse = false;
        new SwingWorker<SourceDiagrams.Result, Void>() {
            @Override
            protected SourceDiagrams.Result doInBackground() {
                if (mermaid) {
                    String trimmed = text == null ? "" : text.trim();
                    return new SourceDiagrams.Result(trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed),
                            wanted, false);
                }
                VisualAction.takeLabelProblem();
                // Erst das Bild ohne KI (sofort da), die KI-Fassung zieht danach nach
                return SourceDiagrams.generate(text, name, language, wanted, depth, compact, auto, targets,
                        preparedOutline.get(), false);
            }

            @Override
            protected void done() {
                if (mine != ticket) {
                    return;
                }
                SourceDiagrams.Result result;
                try {
                    result = get();
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    diagram.setMermaidSource(null);
                    plate.show("Diagramm nicht erzeugbar: " + cause.getMessage(), "Zurück zum Text", DiagramView.this::goBack);
                    return;
                }
                if (result.mermaid().isEmpty()) {
                    diagram.setMermaidSource(null);
                    plate.show("Kein Diagramm erzeugbar – die Datei enthält keine erkennbare Struktur.",
                            "Zurück zum Text", DiagramView.this::goBack);
                    return;
                }
                if (!mermaid && result.collapsed() != collapsed) {
                    // Große Modelle zeigt MainframeMate zuerst kompakt und merkt sich das für die nächsten Typen
                    collapsed = result.collapsed();
                    showCollapse();
                }
                diagram.setMermaidSource(result.mermaid().get());
                if (!mermaid && VisualAction.aiAvailable()) {
                    describe(mine, text, name, language, result, depth, preparedOutline);
                }
                String labelProblem = VisualAction.takeLabelProblem();
                if (result.type() != wanted && language.orElse(null) == SourceLanguage.COBOL
                        && (wanted == DiagramType.FLOWCHART || wanted == DiagramType.SEQUENCE)) {
                    plate.show("COBOL: Statische Struktur und Aufrufbeziehungen – der tatsächliche Ablauf "
                            + "ist noch nicht vollständig analysiert.", null, null);
                } else if (labelProblem != null) {
                    plate.show("KI-Kurzbeschriftung nicht möglich (" + labelProblem
                            + ") – die Blöcke tragen die einfache Beschriftung.", null, null);
                }
            }
        }.execute();
    }

    /**
     * Zweiter Durchgang mit KI: dasselbe Diagramm (gleicher Typ, gleiche Kompaktheit), aber Blöcke, Verzweigungen und
     * aufgerufene Programme von der KI beschrieben. Ersetzt das Bild nur, solange keine neuere Erzeugung läuft.
     */
    private void describe(long mine, String text, String name, Optional<SourceLanguage> language,
            SourceDiagrams.Result first, int depth,
            java.util.function.Supplier<com.aresstack.spaceharvester.mainframe.outline.Outline> outline) {
        diagram.getStatusLabel().setText("KI beschreibt das Diagramm …");
        new SwingWorker<SourceDiagrams.Result, Void>() {
            @Override
            protected SourceDiagrams.Result doInBackground() {
                return SourceDiagrams.generate(text, name, language, first.type(), depth, first.collapsed(), false,
                        targets, outline.get(), true);
            }

            @Override
            protected void done() {
                if (mine != ticket) {
                    return;
                }
                SourceDiagrams.Result described;
                try {
                    described = get();
                } catch (Exception e) {
                    described = null;
                }
                String problem = VisualAction.takeLabelProblem();
                if (described != null && described.mermaid().isPresent()
                        && !described.mermaid().equals(first.mermaid())) {
                    diagram.setMermaidSource(described.mermaid().get());
                    diagram.getStatusLabel().setText("Von der KI beschrieben.");
                } else {
                    diagram.getStatusLabel().setText(" ");
                }
                if (problem != null) {
                    plate.show("KI-Beschreibung nicht möglich (" + problem
                            + ") – das Diagramm trägt die einfache Beschriftung.", null, null);
                }
            }
        }.execute();
    }

    private void showElement(JclElement element) {
        if (element != null && element.getName() != null && !element.getName().isEmpty() && diagram.hasDiagram()) {
            diagram.navigateToElement(element.getName());
        }
    }

    private void openTarget(String label) {
        if (label == null || label.isBlank()) {
            return;
        }
        String name = CallTargets.clean(label);
        var function = SystemFunctionsStore.shared().lookup().get(name.toUpperCase(java.util.Locale.ROOT));
        if (function != null) {
            // Systemfunktion (IDCAMS, IEFBR14, …): Wikipedia-Artikel wie in MainframeMate
            openWikipedia(function);
            return;
        }
        diagram.getStatusLabel().setText("Suche „" + name + "“ …");
        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() {
                return targets.open(label);
            }

            @Override
            protected void done() {
                boolean opened;
                try {
                    opened = get();
                } catch (Exception e) {
                    opened = false;
                }
                diagram.getStatusLabel().setText(opened ? "„" + name + "“ geöffnet."
                        : "„" + name + "“ nicht gefunden (weder offen noch neben dieser Datei).");
            }
        }.execute();
    }

    private void openWikipedia(com.aresstack.spaceharvester.mainframe.diagram.SystemFunction function) {
        String url = function.wikipediaUrl();
        diagram.getStatusLabel().setText("Öffne Wikipedia: " + function.name() + " …");
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                try {
                    if (!java.awt.Desktop.isDesktopSupported()
                            || !java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                        return "kein Browser verfügbar (" + url + ")";
                    }
                    java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
                    return null;
                } catch (Exception e) {
                    return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
            }

            @Override
            protected void done() {
                String problem;
                try {
                    problem = get();
                } catch (Exception e) {
                    problem = e.getMessage();
                }
                diagram.getStatusLabel().setText(problem == null
                        ? function.name() + ": Wikipedia geöffnet."
                        : "Wikipedia nicht geöffnet: " + problem);
            }
        }.execute();
    }

    private void export() {
        if (!diagram.hasDiagram()) {
            diagram.getStatusLabel().setText("Noch kein Diagramm zum Exportieren.");
            return;
        }
        String base = editor.session().name();
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        String exportText = editor.text();
        String exportName = editor.session().name();
        Optional<SourceLanguage> exportLanguage = editor.language();
        var exportOutline = editor.cobolDiagramOutline(exportText);
        Runnable fullRegenerator = mermaid ? null : () -> {
            // Vollständiges (nicht kompaktes) Diagramm für den Export, wie MainframeMate
            SourceDiagrams.Result full = SourceDiagrams.generate(exportText, exportName,
                    exportLanguage, type, mindmapDepth, false, false, targets, exportOutline.get());
            full.mermaid().ifPresent(diagram::setMermaidSource);
        };
        Frame owner = (Frame) SwingUtilities.getAncestorOfClass(Frame.class, this);
        MermaidExportDialog dialog = new MermaidExportDialog(owner, diagram, base, collapsed, fullRegenerator);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
        if (dialog.getResult() == MermaidExportDialog.ExportResult.EXPORTED) {
            diagram.getStatusLabel().setText("✓ Exportiert");
        }
    }

    private static DiagramType restoreType(AppState state) {
        String value = state.get(TYPE_KEY);
        if (value != null) {
            try {
                return DiagramType.valueOf(value);
            } catch (IllegalArgumentException ignored) {
                // Vorgabe
            }
        }
        return DiagramType.STRUCTURE;
    }

    // ---- Suche (Suchzeile des Editors) -----------------------------------------------------------------------------

    @Override
    public int search(String query) {
        return diagram.hasDiagram() ? diagram.searchAndHighlight(query) : 0;
    }

    @Override
    public String step(int direction) {
        int count = direction < 0 ? diagram.searchPrev() : diagram.searchNext();
        return count == 0 ? null : diagram.getSearchResultIndex() + " / " + count;
    }

    @Override
    public void clearSearch() {
        diagram.clearSearch();
        editToggle.marked(false);
        diagram.setEditable(false);
    }

    // ---- für Tests -------------------------------------------------------------------------------------------------

    MermaidDiagramPanel diagram() {
        return diagram;
    }

    DiagramType type() {
        return type;
    }

    void chooseType(DiagramType next) {
        IconButton button = typeButtons.get(next);
        if (button != null) {
            button.doClick();
        }
    }

    String plateSentence() {
        return plate.sentence();
    }

    boolean mermaid() {
        return mermaid;
    }
}
