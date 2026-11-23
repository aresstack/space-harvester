package com.aresstack.spaceharvester.app.ui.deck.visual;

import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.app.ui.Comic;
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
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JToggleButton;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Insets;
import java.util.Optional;

/**
 * Diagramm-Ansicht „Visuell“ eines Editors, übernommen aus MainframeMates {@code SplitPreviewTab} (Diagramm-Modus):
 * Großrechner-Quelltext wird über die Gliederung zum Diagramm (Struktur, Ablauf, Sequenz, Mindmap, ER-Diagramm),
 * Mermaid-Quelltext direkt gezeichnet und ist dann im Diagramm bearbeitbar. Rechts die Seitenleiste wie in MainframeMate:
 * Bearbeiten, Übersicht/Details, Diagrammtyp, Mindmap-Tiefe, Details zum gewählten Element, Bearbeiten-Felder, Status
 * und darunter die Gliederung (Auswahl zeigt das Element im Diagramm). Export über den Knopf oben rechts. Die Suche
 * unten im Editor sucht, solange das Diagramm sichtbar ist, darin nach Knoten.
 */
final class DiagramView extends JPanel implements DocumentEditor.CenterSearch {

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
    private final JToggleButton editToggle = new JToggleButton("✏️");
    private final JToggleButton collapseToggle = new JToggleButton();
    private final ButtonGroup typeGroup = new ButtonGroup();
    private final java.util.Map<DiagramType, JToggleButton> typeButtons = new java.util.EnumMap<>(DiagramType.class);
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

        JButton export = Comic.exportButton("Diagramm exportieren (SVG oder PNG)");
        export.addActionListener(e -> export());
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        tools.setOpaque(false);
        tools.add(export);
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(plate, BorderLayout.NORTH);
        JPanel toolRow = new JPanel(new BorderLayout());
        toolRow.setOpaque(false);
        toolRow.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        toolRow.add(tools, BorderLayout.EAST);
        top.add(toolRow, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, diagram, sidebar());
        split.setResizeWeight(1.0);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setDividerSize(6);
        add(top, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        SwingUtilities.invokeLater(() -> split.setDividerLocation(Math.max(200, split.getWidth() - 300)));
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

    private JComponent sidebar() {
        JPanel outer = new JPanel(new BorderLayout());
        outer.setBackground(Comic.SURFACE);
        outer.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, Comic.LINE));
        outer.setPreferredSize(new Dimension(300, 200));
        outer.setMinimumSize(new Dimension(220, 100));

        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(8, 12, 4, 12));
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));

        // Bearbeiten nur für Mermaid-Quelltext, der gespeichert werden darf; Diagramme aus der Gliederung nur lesen
        square(editToggle, "Bearbeiten im Diagramm: Knoten umbenennen, Kanten ziehen (rechte Maustaste)");
        editToggle.setEnabled(mermaid && editor.editable());
        editToggle.addActionListener(e -> diagram.setEditable(editToggle.isSelected()));
        row.add(editToggle, BorderLayout.WEST);

        if (!mermaid) {
            square(collapseToggle, null);
            collapseToggle.setSelected(collapsed);
            showCollapse();
            collapseToggle.addActionListener(e -> {
                collapsed = collapseToggle.isSelected();
                AppState.shared().put(COLLAPSED_KEY, collapsed);
                showCollapse();
                generate();
            });
            JPanel collapseCell = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            collapseCell.setOpaque(false);
            collapseCell.add(collapseToggle);
            row.add(collapseCell, BorderLayout.CENTER);

            JPanel types = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
            types.setOpaque(false);
            for (DiagramType each : DiagramType.values()) {
                JToggleButton button = new JToggleButton(each.getIcon());
                square(button, each.getLabel());
                button.getAccessibleContext().setAccessibleName(each.getLabel());
                button.setSelected(each == type);
                button.addActionListener(e -> {
                    type = each;
                    AppState.shared().put(TYPE_KEY, each.name());
                    generate();
                });
                typeGroup.add(button);
                typeButtons.put(each, button);
                types.add(button);
            }
            row.add(types, BorderLayout.EAST);
        }
        header.add(row);

        if (!mermaid) {
            JPanel depth = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            depth.setOpaque(false);
            depth.setAlignmentX(Component.LEFT_ALIGNMENT);
            depth.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
            JLabel label = Comic.caption("Mindmap-Tiefe");
            label.setToolTipText("Rekursionstiefe für externe Aufrufe in der Mindmap (0 = keine Rekursion)");
            JSpinner spinner = new JSpinner(new SpinnerNumberModel(mindmapDepth, 0, 10, 1));
            spinner.setPreferredSize(new Dimension(56, 24));
            spinner.setToolTipText("0 = flach, 1 = direkte Aufrufe, 2+ = rekursiv");
            spinner.getAccessibleContext().setAccessibleName("Mindmap-Tiefe");
            spinner.addChangeListener(e -> {
                int value = (Integer) spinner.getValue();
                if (value != mindmapDepth) {
                    mindmapDepth = value;
                    AppState.shared().put(DEPTH_KEY, value);
                    if (type == DiagramType.MINDMAP) {
                        generate();
                    }
                }
            });
            depth.add(label);
            depth.add(spinner);
            header.add(depth);
        }
        outer.add(header, BorderLayout.NORTH);

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(new EmptyBorder(4, 12, 12, 12));
        body.setOpaque(false);
        JScrollPane detail = new JScrollPane(diagram.getDetailArea());
        detail.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));
        detail.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(detail);
        body.add(Box.createVerticalStrut(6));
        JPanel edit = diagram.getEditPanel();
        edit.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(edit);
        body.add(Box.createVerticalStrut(4));
        JLabel status = diagram.getStatusLabel();
        status.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(status);
        body.add(Box.createVerticalStrut(6));
        body.add(advanced());

        JComponent center = Comic.scroll(body);
        if (!mermaid) {
            JComponent outline = editor.outlineView(this::showElement);
            JSplitPane parts = new JSplitPane(JSplitPane.VERTICAL_SPLIT, center, outline);
            parts.setResizeWeight(0.5);
            parts.setBorder(BorderFactory.createEmptyBorder());
            parts.setDividerSize(6);
            center = parts;
        }
        outer.add(center, BorderLayout.CENTER);
        return outer;
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
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(
                Math.max(0, AppState.shared().getInt(REFRESH_KEY, 1)), 0, 100, 1));
        spinner.setPreferredSize(new Dimension(56, 24));
        spinner.getAccessibleContext().setAccessibleName("Neu zeichnen ab Zoom-Änderung in Prozent");
        spinner.addChangeListener(e -> {
            int value = (Integer) spinner.getValue();
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
        collapseToggle.setText(collapsed ? "🔍" : "🔎");
        collapseToggle.setToolTipText(collapsed ? "Details einblenden (vollständiges Diagramm)"
                : "Übersicht (kompaktes Diagramm)");
    }

    private static void square(javax.swing.AbstractButton button, String tooltip) {
        button.setFont(button.getFont().deriveFont(Font.PLAIN, 14f));
        button.setMargin(new Insets(2, 4, 2, 4));
        Dimension size = new Dimension(30, 28);
        button.setPreferredSize(size);
        button.setMinimumSize(size);
        button.setMaximumSize(size);
        button.setFocusable(false);
        if (tooltip != null) {
            button.setToolTipText(tooltip);
        }
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
        DiagramType wanted = type;
        int depth = mindmapDepth;
        boolean compact = collapsed;
        new SwingWorker<SourceDiagrams.Result, Void>() {
            @Override
            protected SourceDiagrams.Result doInBackground() {
                if (mermaid) {
                    String trimmed = text == null ? "" : text.trim();
                    return new SourceDiagrams.Result(trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed),
                            wanted, false);
                }
                VisualAction.takeLabelProblem();
                return SourceDiagrams.generate(text, name, language, wanted, depth, compact, targets);
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
                    plate.show("Diagramm nicht erzeugbar: " + cause.getMessage(), "Zurück zum Text", editor::showText);
                    return;
                }
                if (result.mermaid().isEmpty()) {
                    diagram.setMermaidSource(null);
                    plate.show("Kein Diagramm erzeugbar – die Datei enthält keine erkennbare Struktur.",
                            "Zurück zum Text", editor::showText);
                    return;
                }
                if (!mermaid && result.collapsed() != collapsed) {
                    // Große Modelle zeigt MainframeMate zuerst kompakt und merkt sich das für die nächsten Typen
                    collapsed = result.collapsed();
                    collapseToggle.setSelected(collapsed);
                    showCollapse();
                }
                diagram.setMermaidSource(result.mermaid().get());
                String labelProblem = VisualAction.takeLabelProblem();
                if (labelProblem != null) {
                    plate.show("KI-Kurzbeschriftung nicht möglich (" + labelProblem
                            + ") – die Blöcke tragen die einfache Beschriftung.", null, null);
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
        Runnable fullRegenerator = mermaid ? null : () -> {
            // Vollständiges (nicht kompaktes) Diagramm für den Export, wie MainframeMate
            SourceDiagrams.Result full = SourceDiagrams.generate(editor.text(), editor.session().name(),
                    editor.language(), type, mindmapDepth, false, targets);
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
        editToggle.setSelected(false);
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
        JToggleButton button = typeButtons.get(next);
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
