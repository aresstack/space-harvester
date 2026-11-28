package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.enterpriseai.ui.comic.control.ComicSplitPane;
import com.aresstack.spaceharvester.app.ui.Comic;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.HierarchyEvent;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/** Reuse the existing Harvester split layout in every workspace. */
public final class LeftSidebarPane extends ComicSplitPane {
    public static final String HOST = "leftSidebar.host";
    /** Ob die Leiste über ihren Knopf zugeklappt wurde (nicht von einer Perspektive ohne Leiste weggenommen). */
    private boolean hiddenByUser;

    public LeftSidebarPane(Component sidebar, Component content, int width) {
        this(sidebar, content, 200, 1200, width);
    }

    public LeftSidebarPane(Component sidebar, Component content, int min, int max, int width) {
        super(sidebar, content, min, max, Comic.PALETTE);
        setBackground(Comic.SURFACE);
        ComicDivider.install(this, Comic.PALETTE);
        setPreferredLeftWidth(width);
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                notifyHost();
            }
        });
    }

    /**
     * Klappt die Leiste über ihren Knopf zu. Der Trenner verschwindet dabei ganz (Angelo 28.11.2026): Ein sichtbarer
     * Trenner ließ sich weiter aufziehen, und die Leiste stand danach falsch. Wieder auf geht sie nur über den Knopf.
     */
    public void collapseByUser() {
        collapseLeft();
        hiddenByUser = true;
    }

    /** Zugeklappt, ohne dass der Mensch es wollte (eine Perspektive ohne Leiste, das Ende der Einstellungen). */
    @Override
    public void collapseLeft() {
        if (extras) {
            // Weitere Bereiche halten die Leiste offen: Zu ist dann nur ihr eigentlicher Inhalt.
            primaryHidden = true;
        } else {
            super.collapseLeft();
        }
        hiddenByUser = false;
        primaryChanged.run();
    }

    @Override
    public void openLeft() {
        primaryHidden = false;
        if (super.isLeftCollapsed() || !extras) {
            super.openLeft();
        }
        hiddenByUser = false;
        primaryChanged.run();
    }

    /** Ob der eigentliche Inhalt der Leiste zu ist; weitere Bereiche ({@link #extras}) können sie trotzdem offen halten. */
    @Override
    public boolean isLeftCollapsed() {
        return extras ? primaryHidden : super.isLeftCollapsed();
    }

    private boolean extras;
    private boolean primaryHidden;
    private Runnable primaryChanged = () -> { };

    /**
     * Ob neben dem eigentlichen Inhalt weitere Bereiche in der Leiste offen sind (Angelo 28.11.2026: Kugeln, die an
     * den linken Rand verschoben wurden, öffnen ihren Bereich in dieser Leiste). Solange es welche gibt, bleibt die
     * Leiste offen, auch wenn ihr eigentlicher Inhalt zugeklappt ist; ohne sie klappt sie dann ganz zu.
     */
    public void extras(boolean any) {
        if (extras == any) {
            return;
        }
        if (any) {
            primaryHidden = super.isLeftCollapsed();
            extras = true;
            if (primaryHidden) {
                super.openLeft();
            }
        } else {
            extras = false;
            if (primaryHidden) {
                super.collapseLeft();
            }
        }
    }

    /** Meldet, wenn der eigentliche Inhalt auf- oder zugeklappt wurde. */
    public void onPrimary(Runnable listener) {
        this.primaryChanged = listener;
    }

    public boolean hiddenByUser() {
        return hiddenByUser;
    }

    public void toggle() {
        if (isLeftCollapsed()) {
            openLeft();
        } else {
            collapseByUser();
        }
        notifyHost();
    }

    /** Keep the existing divider gesture as an alternative to the logo. */
    public void onDividerToggle(Runnable action) {
        if (getUI() instanceof javax.swing.plaf.basic.BasicSplitPaneUI basic) {
            basic.getDivider().addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent event) {
                    if (event.getClickCount() == 2) {
                        action.run();
                    }
                }
            });
        }
    }

    public LeftSidebarPane withDividerToggle() {
        onDividerToggle(this::toggle);
        return this;
    }

    private void notifyHost() {
        for (Container parent = getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof JComponent component
                    && component.getClientProperty(HOST) instanceof Runnable listener) {
                SwingUtilities.invokeLater(listener);
                return;
            }
        }
    }

    /** Find the active workspace sidebar without entering hidden cards. */
    public static LeftSidebarPane activeIn(Container root) {
        for (Component child : root.getComponents()) {
            if (!child.isVisible()) {
                continue;
            }
            if (child instanceof LeftSidebarPane pane) {
                return pane;
            }
            if (child instanceof Container inner) {
                LeftSidebarPane found = activeIn(inner);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
