package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Point;
import java.awt.Rectangle;

/**
 * Eine Zeile eines Artefakts, überall gleich (ADR 0047 §5): links unter „Artefakte“ wie im Abschnitt „Aus dieser Seite
 * erzeugt“ unter „Export“. Symbol der Art, Titel (gekürzt mit „…“), die Marke „Veraltet“ wie im Analysebereich und die
 * Zeit rechts gedämpft; reicht die Breite nicht, weicht zuerst die Zeit. Optional eine Überschrift darüber (Gruppe).
 * Als Zeichner einer Liste gedacht, nur auf dem EDT.
 */
public final class ArtifactRow extends JPanel {

    private static final int GAP = 6;

    private final JLabel header = new JLabel();
    private final JPanel line = new JPanel(new BorderLayout(GAP, 0));
    private final JLabel title = new JLabel();
    private final JLabel stale = TabHeader.staleBadge();
    private final JLabel time = new JLabel();
    private final JPanel side = new JPanel(new BorderLayout(GAP, 0));

    public ArtifactRow() {
        super(new BorderLayout(0, 2));
        header.setForeground(Comic.MUTED);
        header.setFont(Comic.semiBold(12f));
        header.setBorder(BorderFactory.createEmptyBorder(GAP, 2, 0, 0));
        line.setOpaque(false);
        line.setBorder(BorderFactory.createEmptyBorder(3, 2, 3, GAP));
        title.setIconTextGap(GAP);
        time.setForeground(Comic.MUTED);
        side.setOpaque(false);
        side.add(stale, BorderLayout.WEST);
        side.add(time, BorderLayout.EAST);
        line.add(title, BorderLayout.CENTER);
        line.add(side, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);
        add(line, BorderLayout.CENTER);
    }

    /**
     * Füllt die Zeile.
     *
     * @param group   die Überschrift über der Zeile, {@code null} ohne
     * @param gone    gelöscht: durchgestrichen und gedämpft, ohne Marke und Zeit
     * @param old     ob die Marke „Veraltet“ steht
     * @param when    die Zeit rechts, {@code null} ohne
     * @param tooltip der Tooltip der Zeile
     */
    public ArtifactRow show(JList<?> owner, boolean selected, String group, Icon icon, String text, boolean gone,
                            boolean old, String when, String tooltip) {
        header.setText(group);
        header.setVisible(group != null);
        title.setFont(owner.getFont());
        time.setFont(owner.getFont().deriveFont(owner.getFont().getSize2D() - 1f));
        title.setForeground(selected ? owner.getSelectionForeground() : gone ? Comic.MUTED : Comic.INK);
        String shown = text == null ? "" : text;
        title.setText(gone ? "<html><s>" + shown.replace("&", "&amp;").replace("<", "&lt;") + "</s></html>" : shown);
        title.setIcon(icon);
        time.setText(when == null ? "" : when);
        stale.setVisible(!gone && old);
        // Die Marke bleibt immer (der Titel kürzt sich davor); die Zeit weicht zuerst.
        time.setVisible(!gone && when != null);
        java.awt.Insets in = line.getInsets();
        int room = owner.getWidth() - in.left - in.right;
        time.setVisible(time.isVisible() && title.getPreferredSize().width + GAP + side.getPreferredSize().width <= room);
        setToolTipText(tooltip);
        // Markiert wird die Zeile, nicht die Überschrift der Gruppe.
        setOpaque(false);
        line.setOpaque(selected);
        line.setBackground(owner.getSelectionBackground());
        return this;
    }

    /** Wann zuletzt geändert: heute als Uhrzeit, sonst als Datum. */
    public static String when(long millis, java.time.ZoneId zone, java.time.LocalDate today) {
        java.time.ZonedDateTime at = java.time.Instant.ofEpochMilli(millis).atZone(zone);
        return at.toLocalDate().equals(today) ? TODAY.format(at) : DAY.format(at);
    }

    private static final java.time.format.DateTimeFormatter TODAY =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.GERMANY);
    private static final java.time.format.DateTimeFormatter DAY =
            java.time.format.DateTimeFormatter.ofPattern("dd.MM.", java.util.Locale.GERMANY);

    /** Ob {@code point} (in Koordinaten der Zelle {@code cell}) auf der Marke „Veraltet“ liegt; die Zeile ist gefüllt. */
    public boolean onStaleBadge(Rectangle cell, Point point) {
        if (!stale.isVisible()) {
            return false;
        }
        setBounds(0, 0, cell.width, cell.height);
        doLayout();
        line.doLayout();
        side.doLayout();
        Rectangle badge = SwingUtilities.convertRectangle(side, stale.getBounds(), this);
        return badge.contains(point.x - cell.x, point.y - cell.y);
    }
}
