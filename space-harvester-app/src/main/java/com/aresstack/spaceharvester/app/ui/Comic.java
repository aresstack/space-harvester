package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.border.ComicBorder;
import com.aresstack.enterpriseai.ui.comic.control.ComicComboBoxUI;
import com.aresstack.enterpriseai.ui.comic.control.ComicScrollPane;
import com.aresstack.enterpriseai.ui.comic.control.ComicSectionPanel;
import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.enterpriseai.ui.comic.theme.ComicPalette;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiTypography;

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

    public static ComicScrollPane scroll(Component view) {
        ComicScrollPane scroll = new ComicScrollPane(view, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED, PALETTE);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getViewport().setBackground(view instanceof JTable ? Color.WHITE : SURFACE);
        scroll.setBorder(BorderFactory.createEmptyBorder());
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

    /** Nebenaktion: flach mit Hover-Schleier. */
    public static ComposerButton button(String text, String tooltip) {
        return new ComposerButton(null, text, false, tooltip);
    }

    public static ComposerButton button(Icon icon, String text, String tooltip) {
        return new ComposerButton(icon, text, false, tooltip);
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

    /** Mehrzeiliger, umbrechender Hinweistext ohne Rahmen. */
    public static JTextArea note(String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setFocusable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setFont(regular(12.5f));
        area.setForeground(TEXT);
        area.setBorder(BorderFactory.createEmptyBorder());
        return area;
    }

    public static JTextField field(int columns) {
        JTextField field = new JTextField(columns);
        style(field);
        return field;
    }

    public static void style(JTextComponent field) {
        field.setBorder(ComicBorder.roundedBorder(PALETTE, 4));
        field.setForeground(INK);
        field.setCaretColor(INK);
        field.setBackground(Color.WHITE);
        field.setFont(regular(13f));
        field.setOpaque(true);
    }

    /** Nur lesbarer Text in Festbreitenschrift, z. B. Originaldaten. */
    public static JTextArea code() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setForeground(INK);
        area.setBackground(Color.WHITE);
        area.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        return area;
    }

    public static <T> JComboBox<T> combo() {
        JComboBox<T> combo = new JComboBox<>();
        combo.setFont(regular(13f));
        return ComicComboBoxUI.install(combo);
    }

    /** Tabelle im Comic-Stil: weiße Fläche, feine Linien, gelbe Auswahl mit Tinte. */
    public static JTable table(JTable table) {
        table.setFont(regular(13f));
        table.setRowHeight(26);
        table.setForeground(INK);
        table.setBackground(Color.WHITE);
        table.setGridColor(new Color(0xE6E8EC));
        table.setShowVerticalLines(false);
        table.setSelectionBackground(YELLOW);
        table.setSelectionForeground(INK);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(false);
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
