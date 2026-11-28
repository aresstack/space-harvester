package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.ui.controls.Cursors;
import com.aresstack.enterpriseai.ui.comic.border.ComicBorder;
import com.aresstack.enterpriseai.ui.comic.control.ComicComboBoxUI;
import com.aresstack.enterpriseai.ui.comic.control.ComicScrollPane;
import com.aresstack.enterpriseai.ui.comic.control.ComicSectionPanel;
import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.enterpriseai.ui.comic.theme.ComicPalette;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiTypography;
import com.aresstack.spaceharvester.app.settings.ViewerBackground;
import com.aresstack.spaceharvester.app.ui.controls.ImageViewer;
import com.aresstack.spaceharvester.app.ui.controls.WrappedText;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Rectangle;
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

/**
 * Die Designsprache des Enterprise AI Client für den Harvester: ruhige helle Flächen, Tintenkonturen, Platten mit
 * farbigem Akzentstreifen, dunkle Pillen für die aktive Navigation, Blau für die Hauptaktion, Gelb für Auswahl und
 * Hover, Rot nur für Probleme. Alle Ansichten bauen ihre Bedienelemente hier, nicht mit eigenen Farben.
 */
public final class Comic {

    public static final ComicPalette PALETTE = ComicPalette.defaultPalette();
    public static final Color SURFACE = PALETTE.getSurface();
    public static final Color INK = PALETTE.getInk();
    public static final Color MUTED = ResearchUiPalette.LIGHT_TEXT_MUTED;
    public static final Color TEXT = ResearchUiPalette.LIGHT_CONTROL_TEXT;
    public static final Color BLUE = ResearchUiPalette.ACCENT_BLUE;
    public static final Color PETROL = PALETTE.getAgentPetrol();
    public static final Color YELLOW = PALETTE.getAccentYellow();
    public static final Color ORANGE = PALETTE.getAccentOrange();
    public static final Color RED = PALETTE.getAccentRed();
    public static final Color GREEN = ResearchUiPalette.WAVE_GREEN;
    public static final Color DARK = ResearchUiPalette.SECONDARY_SURFACE;
    public static final Color LINE = ResearchUiPalette.LIGHT_CONTROL_BORDER;
    /**
     * Höhe jeder Fußleiste (Angelo 24.11.2026: überall dieselbe Höhe): die Leiste mit der Lupe unter der Arbeitsfläche
     * ({@link FindBar}), die Fußleiste jeder Seitenleiste ({@link com.aresstack.spaceharvester.app.ui.controls.SideFoot})
     * und die Fußleisten des Command Deck. Samt Linie darüber.
     */
    public static final int FOOT_HEIGHT = 43;
    /** Fläche einer Meldungsplatte mit Hinweis (helles Gelb, Rand {@link #YELLOW}). */
    public static final Color NOTICE = AppColors.of("app.Comic.notice");
    /** Markierung eines neuen Absatzes in der Leseansicht (wie bei Git grün). */
    public static final Color CHANGE_ADDED = AppColors.of("app.Comic.changeAdded");
    /** Markierung eines geänderten Absatzes in der Leseansicht (blau). */
    public static final Color CHANGE_CHANGED = AppColors.of("app.Comic.changeChanged");
    /** Markierung eines entfernten Absatzes in der Leseansicht (rot, durchgestrichen). */
    public static final Color CHANGE_REMOVED = AppColors.of("app.Comic.changeRemoved");
    /**
     * Hinterlegung dessen, wovon der ganze Podcast nie erzählt (Angelo 27.11.2026: „hellgrau hinterlegt …, damit man
     * sofort sehen kann, was nicht abgedeckt war im Podcast“); das Gelb der laufenden Stelle liegt nie darauf.
     */
    public static final Color PODCAST_GAP = AppColors.of("app.Comic.podcastGap");
    /** Stand einer Aussage (ADR 0032): „gilt“ grün. Jede Marke trägt dazu ihr Wort, nie nur die Farbe. */
    public static final Color STANDING_CURRENT = AppColors.of("app.Comic.standingCurrent");
    /** Stand „geplant“: blau. */
    public static final Color STANDING_PLANNED = AppColors.of("app.Comic.standingPlanned");
    /** Stand „unklar“: Ocker, Aufmerksamkeit ohne Fehler. */
    public static final Color STANDING_UNCLEAR = AppColors.of("app.Comic.standingUnclear");
    /** Stand „überholt“: grau und blass, nie Rot (Rot heißt Fehler). */
    public static final Color STANDING_OUTDATED = AppColors.of("app.Comic.standingOutdated");

    /**
     * Die Farben des Bildbetrachters zur Einstellung „Hintergrund des Bildbetrachters“ (Angelo 17.11.2026), alle aus
     * der Palette: „Wie die App“ ist die Oberfläche, „Dunkel“ die Arbeitsfläche des Enterprise AI Client. Auf hellen
     * Flächen sind die Knöpfe im Bild Tinte mit hellem Symbol, auf dunklen helle Pillen mit dunklem Symbol.
     */
    public static ImageViewer.Look viewerLook(ViewerBackground background) {
        return switch (background) {
            case THEME -> light(SURFACE);
            case WHITE -> light(ResearchUiPalette.SCROLL_PAPER);
            case GREY -> light(LINE);
            case DARK -> dark(ResearchUiPalette.WORKSPACE_SURFACE);
            case BLACK -> dark(ResearchUiPalette.DARK_BASE);
        };
    }

    private static ImageViewer.Look light(Color background) {
        return new ImageViewer.Look(background, INK, ResearchUiPalette.SCROLL_PAPER);
    }

    private static ImageViewer.Look dark(Color background) {
        return new ImageViewer.Look(background, ResearchUiPalette.TEXT_PRIMARY, ResearchUiPalette.TEXT_DARK);
    }

    /** Eine Farbe als CSS-Wert {@code #rrggbb}, etwa für HTML der Leseansicht. */
    public static String css(Color color) {
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }

    private Comic() {
    }

    public static Font regular(float size) {
        return ResearchUiTypography.regular(size);
    }

    public static Font semiBold(float size) {
        return ResearchUiTypography.semiBold(size);
    }

    /** Eine Platte mit Überschrift und Akzentstreifen um einen Inhalt (wie die Einstellungen des Clients). */
    public static ComicSectionPanel plate(String heading, Color stripe, JComponent content) {
        ComicSectionPanel plate = new ComicSectionPanel(PALETTE);
        plate.setAccentStripe(stripe);
        plate.setLayout(new BorderLayout(0, 6));
        plate.setBorder(BorderFactory.createEmptyBorder(10, 18, 12, 14));
        if (heading != null) {
            JLabel title = new JLabel(heading);
            title.setForeground(INK);
            title.setFont(semiBold(14f));
            plate.add(title, BorderLayout.NORTH);
        }
        plate.add(content, BorderLayout.CENTER);
        return plate;
    }

    /** Platten untereinander, oben ausgerichtet, so breit wie der sichtbare Bereich, senkrecht scrollbar. */
    public static JScrollPane column(JComponent... plates) {
        JPanel column = new ColumnPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setBackground(SURFACE);
        column.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        for (JComponent plate : plates) {
            JPanel top = new JPanel(new BorderLayout()) {
                @Override
                public Dimension getMaximumSize() {
                    return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
                }
            };
            top.setOpaque(false);
            top.add(plate, BorderLayout.CENTER);
            top.setAlignmentX(Component.LEFT_ALIGNMENT);
            column.add(top);
            column.add(Box.createVerticalStrut(12));
        }
        column.add(Box.createVerticalGlue());
        return scroll(column);
    }

    /** Folgt der Breite des Sichtbereichs, damit Platten umbrechen statt waagerecht zu scrollen. */
    private static final class ColumnPanel extends JPanel implements Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return 24;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(24, visible.height - 48);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() != null && getParent().getHeight() > getPreferredSize().height;
        }
    }

    /**
     * Gibt einer Rollfläche, die nicht aus {@link #scroll} kommt (etwa die des Code-Editors mit Zeilennummern), die
     * Rollbalken der App: schmal, rund, ohne Pfeilknöpfe (Angelo 28.11.2026: keine Swing-Standardbalken mehr).
     */
    public static <T extends JScrollPane> T comicBars(T pane) {
        com.aresstack.enterpriseai.ui.comic.control.ComicScrollBarUI.install(pane.getVerticalScrollBar(), PALETTE);
        com.aresstack.enterpriseai.ui.comic.control.ComicScrollBarUI.install(pane.getHorizontalScrollBar(), PALETTE);
        return pane;
    }

    public static ComicScrollPane scroll(Component view) {
        ComicScrollPane scroll = new ComicScrollPane(view, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED, PALETTE);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getViewport().setBackground(view instanceof JTable ? AppColors.of("comic.fieldBg") : SURFACE);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        // Liegt die Fläche in einer Seite, die selbst scrollt (Einstellungen), reicht sie das Rad weiter, sobald
        // sie nichts mehr zu scrollen hat; sonst verschluckt eine Tabelle das Rad.
        com.aresstack.spaceharvester.app.ui.controls.WheelForwarding.install(scroll);
        return scroll;
    }

    /** Hauptaktion: gefüllte blaue Platte. */
    public static ComposerButton primary(String text, String tooltip) {
        return ComposerButton.primary(null, text, BLUE, tooltip);
    }

    /** Riskante Aktion: gefüllte rote Platte. */
    public static ComposerButton danger(String text, String tooltip) {
        return ComposerButton.primary(null, text, ResearchUiPalette.DANGER_RED, tooltip);
    }

    /** Wie {@link #danger(String, String)}, mit Symbol vor dem Text (weiß auf Rot). */
    public static ComposerButton danger(Icon icon, String text, String tooltip) {
        return ComposerButton.primary(icon, text, ResearchUiPalette.DANGER_RED, tooltip);
    }

    /** Nebenaktion: flach mit Hover-Schleier. */
    public static ComposerButton button(String text, String tooltip) {
        return new ComposerButton(null, text, false, tooltip);
    }

    public static ComposerButton button(Icon icon, String text, String tooltip) {
        return new ComposerButton(icon, text, false, tooltip);
    }

    /**
     * Runder Knopf nur mit Export-Symbol, derselbe wie in den Space-Zeilen der Quellen; der Text steht im Tooltip und
     * als zugänglicher Name.
     */
    public static javax.swing.JButton exportButton(String tooltip) {
        return new IconButton(Glyph.EXPORT, tooltip);
    }

    /** Zeile aus Knöpfen, links ausgerichtet. */
    public static JPanel row(Component... components) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.setOpaque(false);
        for (Component component : components) {
            row.add(component);
        }
        return row;
    }

    /** Kleine Großbuchstaben-Überschrift wie „WISSENSQUELLEN“ im Client. */
    public static JLabel caption(String text) {
        JLabel label = new JLabel(text.toUpperCase(java.util.Locale.ROOT));
        label.setFont(regular(11.5f));
        label.setForeground(MUTED);
        return label;
    }

    public static JLabel text(String text) {
        JLabel label = new JLabel(text);
        label.setFont(regular(13f));
        label.setForeground(INK);
        return label;
    }

    public static JLabel muted(String text) {
        JLabel label = new JLabel(text);
        label.setFont(regular(12f));
        label.setForeground(MUTED);
        return label;
    }

    /**
     * Umbrechender Text ohne Rahmen, der seine Spalte nie breiter macht: Er nimmt die Breite, die er bekommt, und
     * wächst in die Höhe (Hinweise unter Formularfeldern, längere Erklärungen). Anders als {@link #note} drängt er
     * nie nach rechts, deshalb entsteht in einem Formular kein waagerechter Scrollbalken. Seine Höhe misst er selbst
     * für die Breite, die er hat ({@link WrappedText}).
     */
    public static WrappedText wrapped(String text, Color color, float size) {
        WrappedText area = new WrappedText(text);
        area.setFont(regular(size));
        area.setForeground(color);
        return area;
    }

    /** Grauer Hinweis unter einem Feld, umbrechend. */
    public static WrappedText hint(String text) {
        return wrapped(text, MUTED, 12f);
    }

    /** Mehrzeiliger, umbrechender Hinweistext ohne Rahmen. */
    public static JTextArea note(String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setFocusable(false);
        noAutoScroll(area);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setFont(regular(12.5f));
        area.setForeground(TEXT);
        area.setBorder(BorderFactory.createEmptyBorder());
        return area;
    }

    /**
     * Ein neuer Text setzt die Schreibmarke nicht ans Ende: Sonst zöge jedes {@code setText} beim Aktualisieren die
     * umgebende Bildlaufansicht zu diesem Text (Aufträge sprangen alle 1,5 s nach unten, Angelo 10.11.2026).
     */
    public static void noAutoScroll(javax.swing.text.JTextComponent text) {
        if (text.getCaret() instanceof javax.swing.text.DefaultCaret caret) {
            caret.setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE);
        }
    }

    /** Kontrollkästchen im Comic-Stil: runde Box, blau mit Haken, wenn gewählt. */
    public static javax.swing.JCheckBox checkBox(String text) {
        javax.swing.JCheckBox box = new javax.swing.JCheckBox(text);
        box.setOpaque(false);
        box.setFont(regular(13f));
        box.setForeground(INK);
        box.setIcon(new com.aresstack.enterpriseai.ui.comic.paint.ResearchCheckIcon());
        box.setIconTextGap(6);
        return box;
    }

    /**
     * Aufklappbare Überschrift wie „Erweitert“ im Assistenten von AskAI ({@code CollapsiblePanel}): linksbündig,
     * ohne Knopfrahmen, halbfett, mit gezeichnetem Pfeil davor (kein Textzeichen: Segoe UI Semibold hat ▸/▾ nicht und
     * zeigt sonst ein Kästchen). Der Aufrufer schaltet mit {@link #disclose} den Pfeil und die Zeilen darunter.
     */
    public static javax.swing.JButton disclosure(String text, String tooltip) {
        javax.swing.JButton header = new javax.swing.JButton(text);
        header.setIcon(new DisclosureIcon(header));
        header.setIconTextGap(6);
        header.putClientProperty(DISCLOSED, Boolean.FALSE);
        header.setHorizontalAlignment(SwingConstants.LEFT);
        header.setFocusPainted(false);
        header.setBorderPainted(false);
        header.setContentAreaFilled(false);
        header.setOpaque(false);
        header.setForeground(INK);
        header.setFont(semiBold(12.5f));
        header.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 2));
        Cursors.set(header, java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        header.setToolTipText(tooltip);
        return header;
    }

    private static final String DISCLOSED = "spaceharvester.disclosed";

    /** Pfeil einer {@link #disclosure aufklappbaren Überschrift}: rechts zeigend zu, nach unten offen. */
    public static void disclose(javax.swing.JButton header, boolean open) {
        header.putClientProperty(DISCLOSED, open);
        header.repaint();
    }

    /** Ob die {@link #disclosure aufklappbare Überschrift} offen ist. */
    public static boolean disclosed(javax.swing.JButton header) {
        return Boolean.TRUE.equals(header.getClientProperty(DISCLOSED));
    }

    /** Gefülltes Dreieck in Textfarbe, unabhängig von der Schrift. */
    private static final class DisclosureIcon implements javax.swing.Icon {
        private final javax.swing.JButton owner;

        DisclosureIcon(javax.swing.JButton owner) {
            this.owner = owner;
        }

        @Override
        public void paintIcon(java.awt.Component c, java.awt.Graphics graphics, int x, int y) {
            java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
            try {
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(c.isEnabled() ? c.getForeground() : MUTED);
                java.awt.geom.Path2D.Double arrow = new java.awt.geom.Path2D.Double();
                if (disclosed(owner)) {
                    arrow.moveTo(x + 1, y + 3);
                    arrow.lineTo(x + 9, y + 3);
                    arrow.lineTo(x + 5, y + 8);
                } else {
                    arrow.moveTo(x + 3, y + 1);
                    arrow.lineTo(x + 8, y + 5);
                    arrow.lineTo(x + 3, y + 9);
                }
                arrow.closePath();
                g.fill(arrow);
            } finally {
                g.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return 10;
        }

        @Override
        public int getIconHeight() {
            return 10;
        }
    }

    /** Eingabefeld mit Knopf IM Feld (etwa ▾): das Feld füllt die Breite, der Knopf sitzt rechts innen. */
    public static JPanel fieldWithButton(JComponent field, JComponent button) {
        com.aresstack.spaceharvester.app.ui.controls.InputFrame frame =
                new com.aresstack.spaceharvester.app.ui.controls.InputFrame(field);
        if (button instanceof javax.swing.AbstractButton b) {
            com.aresstack.spaceharvester.app.ui.controls.InputFrame.styleFlat(b);
        }
        frame.addButton(button);
        return frame;
    }

    public static JTextField field(int columns) {
        JTextField field = new JTextField(columns);
        style(field);
        return field;
    }

    /** Der Rahmen aller Eingabefelder, auch der Felder mit integrierten Knöpfen ({@code InputFrame}). */
    public static javax.swing.border.Border fieldBorder() {
        return ComicBorder.roundedBorder(PALETTE, 4);
    }

    public static void style(JTextComponent field) {
        field.setBorder(fieldBorder());
        field.setForeground(INK);
        field.setCaretColor(INK);
        field.setBackground(AppColors.of("comic.fieldBg"));
        field.setFont(regular(13f));
        field.setOpaque(true);
    }

    /** Nur lesbarer Text in Festbreitenschrift, z. B. Originaldaten. */
    public static JTextArea code() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setForeground(INK);
        area.setBackground(AppColors.of("comic.fieldBg"));
        area.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        return area;
    }

    public static <T> JComboBox<T> combo() {
        JComboBox<T> combo = new JComboBox<>();
        combo.setFont(regular(13f));
        return ComicComboBoxUI.install(combo);
    }

    /**
     * Auswahlliste im Comic-Stil, deren Einträge ein eigener Text beschriftet (auch der Zustand „nichts gewählt“,
     * dann ist der Wert {@code null}). Ein eigener {@code DefaultListCellRenderer} nähme der Liste ihr Aussehen;
     * hier bleibt der Renderer des Comic-Stils und bekommt nur den Text.
     */
    public static <T> JComboBox<T> combo(java.util.function.Function<Object, String> text) {
        JComboBox<T> combo = combo();
        javax.swing.ListCellRenderer<? super T> look = combo.getRenderer();
        combo.setRenderer((list, value, index, selected, focused) -> {
            java.awt.Component cell = look.getListCellRendererComponent(list, value, index, selected, focused);
            if (cell instanceof JLabel label) {
                String shown = text.apply(value);
                label.setText(shown);
                label.setToolTipText(shown);
            }
            return cell;
        });
        return combo;
    }

    /** Tabelle im Comic-Stil: weiße Fläche, feine Linien, gelbe Auswahl mit Tinte. */
    public static JTable table(JTable table) {
        table.setFont(regular(13f));
        table.setRowHeight(26);
        table.setForeground(INK);
        table.setBackground(AppColors.of("comic.fieldBg"));
        table.setGridColor(AppColors.of("app.Comic.tableGrid"));
        table.setShowVerticalLines(false);
        table.setSelectionBackground(YELLOW);
        table.setSelectionForeground(INK);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(false);
        // Zellen zeigen Text aus den Quellen (Seitentitel) wörtlich: Ein Titel, der mit „<html>“ beginnt, würde sonst
        // als HTML gezeichnet und könnte etwa ein Bild von außen laden.
        DefaultTableCellRenderer plain = new DefaultTableCellRenderer();
        plain.putClientProperty("html.disable", Boolean.TRUE);
        table.setDefaultRenderer(Object.class, plain);
        JTableHeader header = table.getTableHeader();
        header.setFont(semiBold(12.5f));
        header.setForeground(INK);
        header.setBackground(SURFACE);
        header.setReorderingAllowed(false);
        DefaultTableCellRenderer headerRenderer = new DefaultTableCellRenderer();
        headerRenderer.setHorizontalAlignment(SwingConstants.LEFT);
        headerRenderer.setBackground(SURFACE);
        headerRenderer.setFont(semiBold(12.5f));
        headerRenderer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, INK), BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        header.setDefaultRenderer((t, value, selected, focus, row, column) -> {
            JLabel label = (JLabel) headerRenderer.getTableCellRendererComponent(t, value, false, false, row, column);
            label.setFont(semiBold(12.5f));
            label.setForeground(INK);
            label.setOpaque(true);
            return label;
        });
        return table;
    }

    /** Farbe eines Zustands: grün gut, orange Warnung, rot Problem, grau unbekannt. */
    public static Color tone(Tone tone) {
        return switch (tone) {
            case GOOD -> GREEN;
            case WARNING -> ORANGE;
            case PROBLEM -> RED;
            case RUNNING -> BLUE;
            case NEUTRAL -> MUTED;
        };
    }

    public enum Tone {
        GOOD, WARNING, PROBLEM, RUNNING, NEUTRAL
    }
}
