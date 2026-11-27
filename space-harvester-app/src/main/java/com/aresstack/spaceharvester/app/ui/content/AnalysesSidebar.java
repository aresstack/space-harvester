package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.IconButton;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;

/**
 * Die Analysen als Seitenleiste rechts neben der Arbeitsfläche, zwischen Chat und Aufträgen (Angelo 27.11.2026):
 * oben die Überschrift, rechts davon die Aktionen des gezeigten Reiters, „Groß anzeigen“ und das Dreieck, darunter
 * die Analysen der Auswahl links bzw. der offenen Seite, unten ihre Reiter als Symbole. Kein Zurück-Knopf: Der
 * gehört zur Ansicht in der Mitte. Inhalt und Reiter liefert die Seitenansicht ({@link PageDetailPanel}); das
 * Hauptfenster stellt die Leiste neben die Arbeitsfläche und klappt sie auf und zu.
 */
public final class AnalysesSidebar extends JPanel {

    public static final String TITLE = PageDetailPanel.ANALYSES;
    public static final String MORE = "Weitere Aktionen zu den Analysen";

    private final PageDetailPanel detail;
    private final IconButton more = new IconButton(
            new com.aresstack.spaceharvester.app.ui.controls.DownTriangle(), MORE).compact();
    private final IconButton enlarge = new IconButton(ChatSidebar.enlargeIcon(false), ChatSidebar.ENLARGE);
    private Runnable collapse = () -> { };

    AnalysesSidebar(PageDetailPanel detail) {
        super(new BorderLayout());
        this.detail = detail;
        setBackground(Comic.SURFACE);
        more.addActionListener(e -> {
            javax.swing.JPopupMenu menu = com.aresstack.spaceharvester.app.ui.controls.PopupHover.clearOnClose(
                    moreMenu(), more);
            menu.show(more, more.getWidth() - menu.getPreferredSize().width, more.getHeight());
        });
        JLabel title = new JLabel(TITLE);
        title.setFont(Comic.semiBold(14f));
        title.setForeground(Comic.TEXT);
        JPanel head = new JPanel(new BorderLayout(8, 0));
        head.setOpaque(false);
        head.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Comic.LINE),
                BorderFactory.createEmptyBorder(0, 12, 0, 8)));
        head.setPreferredSize(new Dimension(0, ChatSidebar.HEAD_HEIGHT));
        head.add(title, BorderLayout.CENTER);
        JPanel centered = new JPanel(new java.awt.GridBagLayout());
        centered.setOpaque(false);
        centered.add(Comic.row(detail.analysesActions(), enlarge, more));
        head.add(centered, BorderLayout.EAST);
        JPanel foot = new JPanel(new BorderLayout());
        foot.setOpaque(false);
        foot.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Comic.LINE),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        foot.add(detail.analysesFoot(), BorderLayout.CENTER);
        add(head, BorderLayout.NORTH);
        add(detail.analyses(), BorderLayout.CENTER);
        add(foot, BorderLayout.SOUTH);
        setMinimumSize(new Dimension(ChatSidebar.MIN_WIDTH, 0));
        setPreferredSize(new Dimension(ChatSidebar.DEFAULT_WIDTH, 0));
    }

    /**
     * Zugeklappt ist der Inhalt unsichtbar, damit die Tabulatortaste nicht in verdeckte Felder läuft, und die
     * Analysen lesen und zählen nicht.
     */
    public void shown(boolean shown) {
        for (java.awt.Component child : getComponents()) {
            child.setVisible(shown);
        }
        detail.analysesShown(shown);
    }

    /** Was „Seitenleiste einklappen“ im Menü tut; das bestimmt das Hauptfenster. */
    public void onCollapse(Runnable action) {
        collapse = java.util.Objects.requireNonNull(action, "action");
    }

    /** Was die Mitte wieder zeigt, wenn ein Sprung aus der groß gezeigten Leiste dorthin führt. */
    public void onRevealMiddle(Runnable action) {
        detail.onRevealMiddle(action);
    }

    /** Was die Leiste aufklappt, wenn ein Sprung in die Analysen führt; das bestimmt das Hauptfenster. */
    public void onOpen(Runnable action) {
        detail.onOpenAnalyses(action);
    }

    public IconButton moreButton() {
        return more;
    }

    /** Das Menü hinter dem Dreieck: „Seitenleiste einklappen“ und was zu den gezeigten Analysen gehört. */
    public javax.swing.JPopupMenu moreMenu() {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem item = new javax.swing.JMenuItem(ChatSidebar.COLLAPSE, ChatSidebar.collapseIcon());
        item.setFont(Comic.regular(13f));
        item.addActionListener(e -> collapse.run());
        menu.add(item);
        java.util.List<javax.swing.JMenuItem> own = detail.analysesMenu();
        if (!own.isEmpty()) {
            menu.addSeparator();
            own.forEach(menu::add);
        }
        return menu;
    }

    public IconButton enlargeButton() {
        return enlarge;
    }

    /** Zeigt am Knopf, ob die Leiste gerade groß ist: Symbol und Tooltip wechseln. */
    public void large(boolean large) {
        enlarge.setIcon(ChatSidebar.enlargeIcon(large));
        enlarge.setToolTipText(large ? ChatSidebar.SHRINK : ChatSidebar.ENLARGE);
        enlarge.getAccessibleContext().setAccessibleName(large ? ChatSidebar.SHRINK : ChatSidebar.ENLARGE);
    }

    /** Die drei Balken für den Umschalter des Hauptfensters. */
    public static Icon icon() {
        return PageTabIcons.analyses();
    }
}
