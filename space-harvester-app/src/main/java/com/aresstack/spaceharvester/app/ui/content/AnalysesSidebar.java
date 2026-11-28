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
    public static final String FILTER = "Filtern: Suchfeld des gezeigten Reiters ein- oder ausblenden";

    private final PageDetailPanel detail;
    private final IconButton more = new IconButton(
            new com.aresstack.spaceharvester.app.ui.controls.DownTriangle(), MORE).compact();
    /**
     * Das Filtersymbol links neben dem Dreieck (Angelo 27.11.2026): In der Leiste hat ein Suchfeld je Reiter keinen
     * Platz; es erscheint erst mit diesem Knopf und verschwindet mit ihm wieder, sein Text wird dabei geleert.
     */
    private final com.aresstack.enterpriseai.ui.comic.control.ComposerToggleButton filter =
            new com.aresstack.enterpriseai.ui.comic.control.ComposerToggleButton(
                    com.aresstack.spaceharvester.app.ui.Glyph.FILTER, null, FILTER);
    private final IconButton enlarge = new IconButton(ChatSidebar.enlargeIcon(false), ChatSidebar.ENLARGE);
    private Runnable collapse = () -> { };
    private final JPanel switchSlot = new JPanel(new java.awt.GridBagLayout());

    /** Platz für den Umschalter der rechten Leiste (Chat | Analysen | Aufträge); das Hauptfenster stellt ihn hinein. */
    public javax.swing.JComponent switchSlot() {
        return switchSlot;
    }

    AnalysesSidebar(PageDetailPanel detail) {
        super(new BorderLayout());
        this.detail = detail;
        setBackground(Comic.SURFACE);
        more.addActionListener(e -> {
            javax.swing.JPopupMenu menu = com.aresstack.spaceharvester.app.ui.controls.PopupHover.clearOnClose(
                    moreMenu(), more);
            menu.show(more, more.getWidth() - menu.getPreferredSize().width, more.getHeight());
        });
        filter.getAccessibleContext().setAccessibleName(FILTER);
        com.aresstack.spaceharvester.app.ui.controls.InputFrame.gate(detail.analyses(), false);
        filter.addActionListener(e -> filter(filter.isSelected()));
        // Links der Umschalter der Leiste; sein gewähltes Symbol sagt, dass hier die Analysen stehen.
        switchSlot.setOpaque(false);
        JPanel leading = new JPanel(new BorderLayout());
        leading.setOpaque(false);
        leading.add(switchSlot, BorderLayout.WEST);
        JPanel head = new com.aresstack.spaceharvester.app.ui.controls.HeadBar(new BorderLayout(8, 0), 12, 8);
        head.add(leading, BorderLayout.CENTER);
        JPanel centered = new JPanel(new java.awt.GridBagLayout());
        centered.setOpaque(false);
        centered.add(Comic.row(detail.analysesActions(), enlarge, filter, more));
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

    /** Blendet die Suchfelder der Analysen ein oder aus; eingeblendet steht die Schreibmarke im gezeigten Feld. */
    public void filter(boolean shown) {
        filter.setSelected(shown);
        com.aresstack.spaceharvester.app.ui.controls.InputFrame.gate(detail.analyses(), shown);
        detail.analyses().revalidate();
        detail.analyses().repaint();
        if (shown) {
            com.aresstack.spaceharvester.app.ui.controls.InputFrame field =
                    com.aresstack.spaceharvester.app.ui.controls.InputFrame.gated(detail.analyses());
            if (field != null) {
                field.field().requestFocusInWindow();
            }
        }
    }

    public javax.swing.AbstractButton filterButton() {
        return filter;
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
