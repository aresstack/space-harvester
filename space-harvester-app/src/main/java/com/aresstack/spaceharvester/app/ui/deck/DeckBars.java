package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.content.ChatSidebar;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * Kopf- und Fußleiste der Arbeitsbereiche des Command Deck, so hoch wie die des Space Harvesters (Angelo,
 * 23.11.2026): der Kopf wie die Kopfzeile der Seitenleiste, der Fuß wie die Leiste mit der Lupe. So laufen die Linien
 * beim Wechsel der Perspektive durch. Die Leisten tragen nur Bedienelemente; der Stand eines Bereichs steht in der
 * Statuszeile der App ({@code statusInfo()} des Bereichs).
 */
public final class DeckBars {

    /** Höhe der Kopfleiste samt Linie darunter. */
    public static final int HEAD_HEIGHT = com.aresstack.spaceharvester.app.ui.controls.HeadBar.HEIGHT;
    /** Höhe der Fußleiste samt Linie darüber: wie die Fußleiste des Hauptfensters (Lupe, Suchfeld). */
    public static final int FOOT_HEIGHT = Comic.FOOT_HEIGHT;
    private static final int SIDE = 12;
    private static final int GAP = 12;

    private DeckBars() {
    }

    /** Kopfleiste: {@code main} links (Sitzungen, Reiter), {@code tools} rechts; beide dürfen {@code null} sein. */
    public static JPanel head(JComponent main, JComponent tools) {
        return bar(main, tools, HEAD_HEIGHT, true);
    }

    /**
     * Fußleiste: {@code main} links, {@code actions} rechts; beide dürfen {@code null} sein. Die Fußleiste des Command
     * Deck ist die Leiste mit der Lupe ({@link FootTools}); eine eigene braucht ein Bereich nur innerhalb einer Ansicht.
     */
    public static JPanel foot(JComponent main, JComponent actions) {
        return bar(main, actions, FOOT_HEIGHT, false);
    }

    /**
     * Was ein Bereich in die Fußleiste des Command Deck stellt, neben die Lupe, wie die Umschalter im Space Harvester
     * (Angelo, 23.11.2026: überall dieselbe Leiste mit Lupe). Symbolknöpfe mit Tooltip, keine Textknöpfe; kein Stand,
     * keine Zähler (die stehen in der Statuszeile), kein Trennen, Schließen oder Abmelden (das macht das Kreuz im
     * Reiter, {@link com.aresstack.spaceharvester.app.ui.controls.ClosableTab}).
     */
    public interface FootTools {

        /** Die Bedienelemente der gezeigten Ansicht; {@code null} ohne. Derselbe Baustein über die ganze Laufzeit. */
        JComponent footTools();
    }

    /** Schlüssel am Command Deck: wer ihn trägt, setzt die Leiste mit der Lupe in den sichtbaren {@link FindSlot}. */
    static final String FIND_HOST = "deck.findSlot.host";

    /**
     * Platz für die Leiste mit der Lupe unter der Arbeitsfläche eines Bereichs mit Seitenleiste (Angelo 24.11.2026):
     * zweigeteilt wie im Space Harvester. Unter der Seitenleiste steht ihre eigene Fußleiste
     * ({@link com.aresstack.spaceharvester.app.ui.controls.SideFoot}), unter der Arbeitsfläche dieser Platz; das
     * Command Deck setzt seine eine Leiste mit der Lupe in den Platz, der gerade zu sehen ist. Ohne sichtbaren Platz
     * steht sie unter dem ganzen Bereich.
     */
    public static final class FindSlot extends JPanel {

        public FindSlot() {
            super(new java.awt.BorderLayout());
            setOpaque(false);
            addHierarchyListener(event -> {
                if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0) {
                    for (Container up = getParent(); up != null; up = up.getParent()) {
                        if (up instanceof JComponent host && host.getClientProperty(FIND_HOST) instanceof Runnable r) {
                            javax.swing.SwingUtilities.invokeLater(r);
                            return;
                        }
                    }
                }
            });
        }
    }

    /**
     * Die Arbeitsfläche eines Bereichs mit Seitenleiste: {@code content} oben, darunter der {@link FindSlot} für die
     * Leiste mit der Lupe.
     */
    public static JPanel withFind(JComponent content) {
        JPanel work = new JPanel(new java.awt.BorderLayout());
        work.setOpaque(false);
        work.add(content, java.awt.BorderLayout.CENTER);
        work.add(new FindSlot(), java.awt.BorderLayout.SOUTH);
        return work;
    }

    /** Der sichtbare Platz für die Leiste mit der Lupe unter {@code root}, sonst {@code null}. */
    static FindSlot showingSlot(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof FindSlot slot && slot.isShowing()) {
                return slot;
            }
            if (child instanceof Container inner && child.isVisible()) {
                FindSlot found = showingSlot(inner);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Symbolknopf einer Leiste: nur das Symbol, der Text steht im Tooltip und als zugänglicher Name. */
    public static com.aresstack.spaceharvester.app.ui.IconButton action(javax.swing.Icon icon, String tooltip,
                                                                         Runnable action) {
        var button = new com.aresstack.spaceharvester.app.ui.IconButton(icon, tooltip);
        button.addActionListener(e -> action.run());
        return button;
    }

    /** Zeile aus Bedienelementen für {@link FootTools}, mittig wie die Umschalter im Space Harvester. */
    public static JPanel tools(Component... parts) {
        JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 6, 0));
        row.setOpaque(false);
        for (Component part : parts) {
            row.add(part);
        }
        return row;
    }

    private static JPanel bar(JComponent main, JComponent east, int height, boolean head) {
        JPanel bar;
        if (head) {
            // Die Kopfleiste ist überall dieselbe (HeadBar): Höhe und Linie stehen nur dort.
            bar = new com.aresstack.spaceharvester.app.ui.controls.HeadBar(new Row(main, east, height), SIDE, SIDE);
        } else {
            bar = new JPanel(new Row(main, east, height));
            bar.setOpaque(false);
            bar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Comic.LINE),
                    BorderFactory.createEmptyBorder(0, SIDE, 0, SIDE)));
        }
        if (main != null) {
            bar.add(main);
        }
        if (east != null) {
            bar.add(east);
        }
        return bar;
    }

    /**
     * Feste Höhe; jedes Element behält seine eigene Höhe und sitzt senkrecht mittig. Rechts steht {@code east} in
     * seiner Wunschbreite, {@code main} bekommt den Rest. Ohne {@code main} beginnt {@code east} links: Nichts steht
     * allein in der rechten Ecke.
     */
    private record Row(JComponent main, JComponent east, int height) implements LayoutManager {

        @Override
        public void addLayoutComponent(String name, Component component) {
        }

        @Override
        public void removeLayoutComponent(Component component) {
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            Insets insets = parent.getInsets();
            int width = insets.left + insets.right;
            if (main != null && main.isVisible()) {
                width += main.getPreferredSize().width;
            }
            if (east != null && east.isVisible()) {
                width += east.getPreferredSize().width + GAP;
            }
            return new Dimension(width, height);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            Insets insets = parent.getInsets();
            return new Dimension(insets.left + insets.right, height);
        }

        @Override
        public void layoutContainer(Container parent) {
            Insets insets = parent.getInsets();
            int top = insets.top;
            int room = Math.max(0, parent.getHeight() - insets.top - insets.bottom);
            int right = parent.getWidth() - insets.right;
            int left = insets.left;
            if (east != null && east.isVisible()) {
                Dimension wanted = east.getPreferredSize();
                int width = Math.min(wanted.width, Math.max(0, right - left));
                int own = Math.min(room, wanted.height);
                boolean alone = main == null || !main.isVisible();
                east.setBounds(alone ? left : right - width, top + (room - own) / 2, width, own);
                right -= width + GAP;
            }
            if (main != null && main.isVisible()) {
                int own = Math.min(room, main.getPreferredSize().height);
                main.setBounds(left, top + (room - own) / 2, Math.max(0, right - left), own);
            }
        }
    }
}
