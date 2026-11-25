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
    public static final int COLLAPSED_DIVIDER = 6;

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

    public void collapseWithHandle() {
        collapseLeft();
        setDividerSize(COLLAPSED_DIVIDER);
    }

    public void toggle() {
        if (isLeftCollapsed()) {
            openLeft();
        } else {
            collapseWithHandle();
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
