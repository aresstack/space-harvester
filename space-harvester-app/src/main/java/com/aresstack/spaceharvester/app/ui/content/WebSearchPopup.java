package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.enterpriseai.ui.comic.theme.ComicPalette;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiTypography;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.text.DefaultFormatter;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Color;
import java.awt.Container;
import java.awt.Point;
import java.awt.Shape;
import java.awt.Window;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import javax.swing.JComponent;
import java.awt.IllegalComponentStateException;
import javax.swing.JLayeredPane;
import javax.swing.JRootPane;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Das Menü am Pfeil der Websuche, übernommen aus {@code BrowserBudgetPopup} des Enterprise AI Client samt dessen
 * {@code ComicFormControls} für Umschalter, Zahlenfeld, Schieber und Ankreuzfelder: eine Comic-Sprechblase über der Eingabe, deren Spitze auf
 * den Pfeil zeigt; oben das Profil Extern/Intern, dann je Wert eine Zeile mit Feld und Schieber (Werkzeugrunden je
 * Antwort, parallele Tabs, zufällige Verzögerung je Tab), darunter Human Emulation und Mausbewegungen. Jede Änderung
 * geht sofort an den Empfänger der Zeile.
 */
final class WebSearchPopup extends JPopupMenu {

    static final String TITLE = "Werkzeugrunden je Antwort mit Browser";
    static final String DELAY_TITLE = "Zufällige Verzögerung je Tab (max. ms)";
    static final String HUMAN_TITLE = "Human Emulation";
    static final String HUMAN_TIP = "Unregelmäßiges Tippen, Scrollen in Etappen und Lesepausen (nur im Browser)";
    static final String MOUSE_TITLE = "Mausbewegungen";
    static final String MOUSE_TIP = "Zittern und Umwege des Browser-Zeigers; deine eigene Maus bleibt frei";
    static final String PROFILE_TITLE = "Profil";
    static final String PROFILE_EXTERNAL = "Extern";
    static final String PROFILE_INTERNAL = "Intern";
    static final String PROFILE_TIP = "Welche Whitelist, Blacklist und Such-URLs gelten (Einstellungen › Browser); "
            + "klicken schaltet zwischen Extern und Intern um";
    /** Height of the bubble's pointer below the content. */
    static final int POINTER = 10;
    /** Corner radius of the bubble. */
    static final int RADIUS = 14;
    /** Abstand zwischen den Ankreuzfeldern in ihrer Zeile. */
    static final int CHECK_GAP = 12;
    /** Abstand der Zeilen vom Rand, wie in den Zeilen darüber. */
    static final int ROW_GAP = 6;
    /** Luft links und rechts der Beschriftung im Profil-Umschalter. */
    static final int PILL_PAD = 16;
    /** Where the pointer's tip sits, from the bubble's left edge. */
    private int pointerX = 22;

    /** The definition of one row. */
    static final class Row {

        final String title;
        final int value;
        final int min;
        final int max;
        final int sliderMin;
        final int sliderMax;
        final IntConsumer listener;

        Row(String title, int value, int min, int max, int sliderMin, int sliderMax, IntConsumer listener) {
            this.title = title;
            this.value = value;
            this.min = min;
            this.max = max;
            this.sliderMin = sliderMin;
            this.sliderMax = sliderMax;
            this.listener = listener == null ? v -> { } : listener;
        }
    }

    /** The definition of one checkbox. */
    static final class Check {

        final String title;
        final String tooltip;
        final boolean value;
        final java.util.function.Consumer<Boolean> listener;

        Check(String title, String tooltip, boolean value, java.util.function.Consumer<Boolean> listener) {
            this.title = title;
            this.tooltip = tooltip;
            this.value = value;
            this.listener = listener == null ? v -> { } : listener;
        }
    }

    private javax.swing.JToggleButton profileToggle;
    private final List<javax.swing.JCheckBox> checkBoxes = new ArrayList<javax.swing.JCheckBox>();
    private final List<SpinnerNumberModel> numbers = new ArrayList<SpinnerNumberModel>();
    private final List<JSlider> sliders = new ArrayList<JSlider>();

    WebSearchPopup(int value, int min, int max, int sliderMin, int sliderMax, IntConsumer listener) {
        this(ComicPalette.defaultPalette(), java.util.Collections.singletonList(
                new Row(TITLE, value, min, max, sliderMin, sliderMax, listener)));
    }

    WebSearchPopup(ComicPalette palette, List<Row> rows) {
        this(palette, rows, java.util.Collections.<Check>emptyList());
    }

    WebSearchPopup(final ComicPalette palette, List<Row> rows, List<Check> checks) {
        this(palette, null, rows, checks);
    }

    /**
     * @param profile the chat's profile switch at the top (selected = Intern), {@code null} for none
     */
    WebSearchPopup(final ComicPalette palette, Check profile, List<Row> rows, List<Check> checks) {
        setBorder(BorderFactory.createEmptyBorder());
        setOpaque(false);
        JPanel bubble = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                    Shape shape = bubbleShape(getWidth(), getHeight(), pointerX);
                    g2.setColor(palette.getSurface());
                    g2.fill(shape);
                    g2.setColor(palette.getInk());
                    g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.draw(shape);
                } finally {
                    g2.dispose();
                }
            }
        };
        bubble.setOpaque(false);
        bubble.setBorder(BorderFactory.createEmptyBorder(10, 12, POINTER + 10, 12));
        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        if (profile != null) {
            addProfile(content, profile, palette);
        }
        for (Row row : rows) {
            addRow(content, row, palette);
        }
        // Die Ankreuzfelder nebeneinander in einer Zeile (Angelo 28.11.2026).
        JPanel checkRow = new JPanel(new FlowLayout(FlowLayout.LEFT, ROW_GAP, 2));
        checkRow.setOpaque(false);
        checkRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (final Check check : checks) {
            final javax.swing.JCheckBox box = new javax.swing.JCheckBox(check.title, check.value);
            box.setToolTipText(check.tooltip);
            box.setFont(ResearchUiTypography.semiBold(12f));
            box.setForeground(palette.getInk());
            box.setOpaque(false);
            com.aresstack.enterpriseai.ui.comic.control.ComicFormControls.install(box, palette);
            box.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, CHECK_GAP - ROW_GAP));
            box.addActionListener(event -> check.listener.accept(box.isSelected()));
            checkRow.add(box);
            checkBoxes.add(box);
        }
        if (!checks.isEmpty()) {
            content.add(checkRow);
        }
        bubble.add(content, BorderLayout.CENTER);
        add(bubble);
    }

    private void addProfile(JPanel content, final Check profile, ComicPalette palette) {
        final javax.swing.JToggleButton toggle = new javax.swing.JToggleButton(
                profile.value ? PROFILE_INTERNAL : PROFILE_EXTERNAL, profile.value);
        toggle.setToolTipText(profile.tooltip);
        toggle.setFont(ResearchUiTypography.semiBold(12f));
        toggle.getAccessibleContext().setAccessibleName(profile.title);
        com.aresstack.enterpriseai.ui.comic.control.ComicFormControls.install(toggle, palette);
        // Breit genug für beide Beschriftungen, damit der Knopf beim Umschalten nicht springt oder kürzt.
        java.awt.FontMetrics metrics = toggle.getFontMetrics(toggle.getFont());
        toggle.setPreferredSize(new java.awt.Dimension(Math.max(metrics.stringWidth(PROFILE_INTERNAL),
                metrics.stringWidth(PROFILE_EXTERNAL)) + 2 * PILL_PAD, toggle.getPreferredSize().height));
        toggle.addActionListener(event -> {
            toggle.setText(toggle.isSelected() ? PROFILE_INTERNAL : PROFILE_EXTERNAL);
            profile.listener.accept(toggle.isSelected());
        });
        JLabel title = new JLabel(profile.title);
        title.setFont(ResearchUiTypography.semiBold(12f));
        title.setForeground(palette.getInk());
        JPanel head = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        head.setOpaque(false);
        head.add(title);
        head.add(toggle);
        head.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(head);
        profileToggle = toggle;
    }

    /** The profile switch (selected = Intern), {@code null} without one. */
    javax.swing.JToggleButton profileToggle() {
        return profileToggle;
    }

    private void addRow(JPanel content, final Row row, ComicPalette palette) {
        final SpinnerNumberModel number = new SpinnerNumberModel(clamp(row.value, row.min, row.max), row.min,
                row.max, 1);
        JSpinner field = new JSpinner(number);
        JSpinner.NumberEditor editor = new JSpinner.NumberEditor(field, "#");
        field.setEditor(editor);
        com.aresstack.enterpriseai.ui.comic.control.ComicFormControls.install(field, palette);
        JFormattedTextField text = editor.getTextField();
        text.setColumns(5);
        if (text.getFormatter() instanceof DefaultFormatter) {
            // Typed digits apply while typing, not only on Enter.
            ((DefaultFormatter) text.getFormatter()).setCommitsOnValidEdit(true);
        }
        final JSlider slider = new JSlider(row.sliderMin, row.sliderMax, clamp(row.value, row.sliderMin, row.sliderMax));
        slider.setFont(ResearchUiTypography.semiBold(10f));
        slider.setMajorTickSpacing(Math.max(1, (row.sliderMax - row.sliderMin) / 4));
        slider.setPaintTicks(true);
        slider.setPaintLabels(true);
        slider.setOpaque(false);
        com.aresstack.enterpriseai.ui.comic.control.ComicFormControls.install(slider, palette);
        final boolean[] syncing = {false};
        number.addChangeListener(event -> {
            if (syncing[0]) {
                return;
            }
            int now = number.getNumber().intValue();
            syncing[0] = true;
            slider.setValue(clamp(now, row.sliderMin, row.sliderMax));
            syncing[0] = false;
            row.listener.accept(now);
        });
        slider.addChangeListener(event -> {
            if (syncing[0]) {
                return;
            }
            syncing[0] = true;
            number.setValue(slider.getValue());
            syncing[0] = false;
            row.listener.accept(slider.getValue());
        });
        JLabel title = new JLabel(row.title);
        title.setFont(ResearchUiTypography.semiBold(12f));
        title.setForeground(palette.getInk());
        JPanel head = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        head.setOpaque(false);
        head.add(title);
        head.add(field);
        head.setAlignmentX(Component.LEFT_ALIGNMENT);
        slider.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(head);
        content.add(slider);
        numbers.add(number);
        sliders.add(slider);
    }

    /**
     * Open the bubble above {@code invoker} (the globe's arrow) with its pointer aiming at the invoker's center,
     * shifted left where the screen's right edge would cut it off.
     */
    void showAbove(Component invoker) {
        java.awt.Dimension size = getPreferredSize();
        int target = invoker.getWidth() / 2;
        int x = Math.min(0, target - 22);
        if (invoker.isShowing() && invoker.getGraphicsConfiguration() != null) {
            java.awt.Rectangle screen = invoker.getGraphicsConfiguration().getBounds();
            int right = invoker.getLocationOnScreen().x + x + size.width;
            if (right > screen.x + screen.width) {
                x -= right - (screen.x + screen.width);
            }
        }
        pointerX = clamp(target - x, RADIUS + 8, Math.max(RADIUS + 8, size.width - RADIUS - 8));
        show(invoker, x, -size.height);
    }

    int pointerX() {
        return pointerX;
    }

    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);
        if (visible) {
            clearBackdrop();
        }
    }

    /**
     * Swing hosts a popup in an opaque panel (inside the frame) or an opaque window (beyond it); both would paint
     * square corners around the rounded bubble. Make the host see-through: a transparent window background where
     * the platform allows translucency, else the window shaped to the bubble.
     */
    private void clearBackdrop() {
        Container parent = getParent();
        while (parent != null && !(parent instanceof JLayeredPane) && !(parent instanceof JRootPane)
                && !(parent instanceof Window)) {
            if (parent instanceof JComponent) {
                ((JComponent) parent).setOpaque(false);
            }
            parent = parent.getParent();
        }
        Window window = SwingUtilities.getWindowAncestor(this);
        if (window instanceof JWindow && window != SwingUtilities.getWindowAncestor(getInvoker())) {
            try {
                window.setBackground(new Color(0, 0, 0, 0));
                ((JWindow) window).getContentPane().setBackground(new Color(0, 0, 0, 0));
                ((JComponent) ((JWindow) window).getContentPane()).setOpaque(false);
            } catch (UnsupportedOperationException | IllegalComponentStateException noTranslucency) {
                try {
                    window.setShape(bubbleShape(window.getWidth(), window.getHeight(), pointerX));
                } catch (UnsupportedOperationException | IllegalComponentStateException noShape) {
                    // square corners, still usable
                }
            }
        }
        repaint();
    }

    /** The bubble's outline: a rounded body with the pointer at the bottom, tip at {@code tipX}. */
    static Shape bubbleShape(int width, int height, int tipX) {
        float w = width - 3f;
        float h = height - 3f - POINTER;
        Area area = new Area(new RoundRectangle2D.Float(1.5f, 1.5f, w, h, RADIUS * 2, RADIUS * 2));
        Path2D.Float pointer = new Path2D.Float();
        float base = 1.5f + h - 1f;
        pointer.moveTo(tipX - 9, base);
        pointer.lineTo(tipX, height - 1.5f);
        pointer.lineTo(tipX + 4, base);
        pointer.closePath();
        area.add(new Area(pointer));
        return area;
    }

    /** The value of the first row (tool rounds). */
    int value() {
        return value(0);
    }

    int value(int row) {
        return numbers.get(row).getNumber().intValue();
    }

    JSlider slider() {
        return sliders.get(0);
    }

    JSlider slider(int row) {
        return sliders.get(row);
    }

    SpinnerNumberModel number() {
        return numbers.get(0);
    }

    SpinnerNumberModel number(int row) {
        return numbers.get(row);
    }

    javax.swing.JCheckBox checkBox(int index) {
        return checkBoxes.get(index);
    }

    int rowCount() {
        return numbers.size();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
