package com.aresstack.spaceharvester.app.ui.deck.visual;

import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.app.ui.controls.MessagePlate;
import com.aresstack.spaceharvester.mainframe.diagram.OutlineToMermaidConverter.DiagramType;
import com.aresstack.spaceharvester.mainframe.diagram.SourceDiagrams;
import com.aresstack.spaceharvester.mainframe.outline.SourceLanguage;

import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * „Visuell“ in der JES-Spool-Ansicht, wie MainframeMate {@code JobDetailTab}: ein Umschalter „Diagramm ↔ Text“ und die
 * kleinen Diagrammtyp-Knöpfe in der Zeile über dem Spool-Text; das Diagramm ist immer schreibgeschützt (Spool), die
 * Sprache wird wie dort am Inhalt erkannt (Natural, COBOL, JCL), ein leerer Typ fällt auf die Struktur zurück. Die Suche
 * der Spool-Ansicht sucht bei sichtbarem Diagramm in den Knoten ({@link #search(String)}, {@link #step(int)}).
 */
public final class SpoolDiagram {

    static final String SHOW = "Interaktives Diagramm anzeigen (schreibgeschützt)";
    static final String BACK = "Zurück zur Text-Ansicht";
    private static final String TEXT_CARD = "text";
    private static final String DIAGRAM_CARD = "diagram";

    private final Supplier<String> text;
    private final Supplier<String> name;
    private final CardLayout cards = new CardLayout();
    private final JPanel holder = new JPanel(cards);
    private final MermaidDiagramPanel diagram = new MermaidDiagramPanel(false);
    private final MessagePlate plate = new MessagePlate();
    private final JToggleButton toggle = new JToggleButton("Diagramm", VisualIcons.eye());
    private final JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 2, 0));
    private final Map<DiagramType, JToggleButton> typeButtons = new EnumMap<>(DiagramType.class);
    private DiagramType type = DiagramType.STRUCTURE;
    private boolean active;
    private long ticket;

    /**
     * @param text     der angezeigte Spool-Text (JESJCL bereits bereinigt)
     * @param name     Name für die Gliederung (Jobname)
     * @param textView die Text-Ansicht, die das Diagramm im Wechsel ersetzt
     */
    public SpoolDiagram(Supplier<String> text, Supplier<String> name, JComponent textView) {
        this.text = text;
        this.name = name;
        diagram.setAutoRefreshThresholdPercent(Math.max(0, AppState.shared().getInt(DiagramView.REFRESH_KEY, 1)));
        JPanel diagramCard = new JPanel(new BorderLayout());
        diagramCard.setOpaque(false);
        diagramCard.add(plate, BorderLayout.NORTH);
        diagramCard.add(diagram, BorderLayout.CENTER);
        holder.setOpaque(false);
        holder.add(textView, TEXT_CARD);
        holder.add(diagramCard, DIAGRAM_CARD);

        toggle.setFocusable(false);
        toggle.setToolTipText(SHOW);
        toggle.getAccessibleContext().setAccessibleName("Diagramm");
        toggle.addActionListener(e -> toggle());
        controls.setOpaque(false);
        controls.add(toggle);
        ButtonGroup group = new ButtonGroup();
        for (DiagramType each : DiagramType.values()) {
            JToggleButton button = new JToggleButton(each.getIcon());
            button.setFont(button.getFont().deriveFont(Font.PLAIN, 14f));
            button.setMargin(new Insets(2, 4, 2, 4));
            Dimension size = new Dimension(28, 28);
            button.setPreferredSize(size);
            button.setMinimumSize(size);
            button.setMaximumSize(size);
            button.setFocusable(false);
            button.setToolTipText(each.getLabel());
            button.getAccessibleContext().setAccessibleName(each.getLabel());
            button.setSelected(each == type);
            button.addActionListener(e -> {
                type = each;
                if (active) {
                    generate();
                }
            });
            group.add(button);
            typeButtons.put(each, button);
            controls.add(button);
        }
    }

    /** Ersetzt die Text-Ansicht im Layout der Spool-Ansicht: Text oder Diagramm. */
    public JComponent view() {
        return holder;
    }

    /** Umschalter und Diagrammtyp-Knöpfe für die Zeile über dem Spool-Text. */
    public JComponent controls() {
        return controls;
    }

    public boolean active() {
        return active;
    }

    /** Schaltet Text ↔ Diagramm (MainframeMate „📈 Diagramm“ / „📝 Text“). */
    public void toggle() {
        if (!active) {
            String content = text.get();
            if (content == null || content.isBlank()) {
                toggle.setSelected(false);
                return;
            }
            active = true;
            showLabel();
            cards.show(holder, DIAGRAM_CARD);
            generate();
        } else {
            showText();
        }
    }

    /** Zurück zum Text, etwa wenn eine andere Spool-Datei gewählt wird. */
    public void showText() {
        active = false;
        ticket++;
        diagram.clearSearch();
        plate.dismiss();
        showLabel();
        cards.show(holder, TEXT_CARD);
    }

    /** Neuer Spool-Text: bei sichtbarem Diagramm neu zeichnen. */
    public void contentChanged() {
        if (active) {
            generate();
        }
    }

    /** Sucht im Diagramm; Anzahl der Treffer. */
    public int search(String query) {
        return active && diagram.hasDiagram() ? diagram.searchAndHighlight(query) : 0;
    }

    /** Nächster ({@code +1}) oder voriger Treffer; Anzahl der Treffer. */
    public int step(int direction) {
        if (!active || !diagram.hasDiagram()) {
            return 0;
        }
        return direction < 0 ? diagram.searchPrev() : diagram.searchNext();
    }

    /** Stelle des aktuellen Treffers (ab 1) für „x / y“. */
    public int searchIndex() {
        return diagram.getSearchResultIndex();
    }

    private void showLabel() {
        toggle.setSelected(active);
        toggle.setText(active ? "Text" : "Diagramm");
        toggle.setToolTipText(active ? BACK : SHOW);
    }

    private void generate() {
        plate.dismiss();
        diagram.setLoading();
        long mine = ++ticket;
        String content = text.get();
        String jobName = name.get();
        DiagramType wanted = type;
        new SwingWorker<SourceDiagrams.Result, Void>() {
            @Override
            protected SourceDiagrams.Result doInBackground() {
                VisualAction.takeLabelProblem();
                Optional<SourceLanguage> language = SourceLanguage.byContent(content)
                        .filter(found -> found != SourceLanguage.DDM);
                if (language.isEmpty()) {
                    return new SourceDiagrams.Result(Optional.empty(), wanted, false);
                }
                return SourceDiagrams.generate(content, jobName, language, wanted, 0, false, null);
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
                    plate.show("Diagramm nicht erzeugbar: " + cause.getMessage(), BACK, SpoolDiagram.this::showText);
                    return;
                }
                if (result.mermaid().isEmpty()) {
                    // MainframeMate meldet das und kehrt zum Text zurück
                    showText();
                    plate.dismiss();
                    noStructure.run();
                    return;
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

    private Runnable noStructure = () -> { };

    /**
     * Was geschieht, wenn der Spool keine erkennbare Struktur hat (MainframeMate: Hinweis „Kein Diagramm erzeugbar“);
     * die Spool-Ansicht meldet es in ihrem Statusbereich.
     */
    public void onNoStructure(Runnable action) {
        noStructure = action == null ? () -> { } : action;
    }

    // ---- für Tests -------------------------------------------------------------------------------------------------

    MermaidDiagramPanel diagram() {
        return diagram;
    }

    JToggleButton toggleButton() {
        return toggle;
    }

    Map<DiagramType, JToggleButton> typeButtons() {
        return typeButtons;
    }
}
