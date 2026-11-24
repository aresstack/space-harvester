package com.aresstack.spaceharvester.app.ui.controls;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseEvent;
import javax.swing.JComponent;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;

/**
 * Die Maustasten Zurück (4) und Vor (5) im ganzen Fenster für eine Ansicht mit eigenem Verlauf, wie in MainframeMate
 * ({@code TabbedPaneManager.installGlobalMouseNavigation}) und in der Suche des Space Harvesters. Solange die Ansicht zu
 * sehen ist, gehören ihr die Tasten: Sie setzt dafür {@link #OWN} am Wurzelfenster, sodass die Leseansicht (die die
 * Tasten sonst im ganzen Fenster nimmt) still bleibt. Ausgelöst wird beim Loslassen, wie im EAC.
 */
public final class MouseNavigation {

    /** Maustaste Zurück (Daumentaste) wie im EAC ({@code KnowledgeReaderPanel.MOUSE_BACK_BUTTON}). */
    public static final int BACK_BUTTON = 4;
    /** Maustaste Vor (Daumentaste) wie im EAC ({@code KnowledgeReaderPanel.MOUSE_FORWARD_BUTTON}). */
    public static final int FORWARD_BUTTON = 5;
    /** Merkmal an einer Komponente oder am Wurzelfenster: Hier gelten eigene Zurück/Vor, nicht die der Leseansicht. */
    public static final String OWN = "spaceharvester.ownMouseNavigation";

    private MouseNavigation() {
    }

    /**
     * Gibt {@code owner} die Maustasten Zurück/Vor im ganzen Fenster, solange er zu sehen ist.
     *
     * @param owner   die Ansicht mit eigenem Verlauf
     * @param back    läuft bei Maustaste Zurück
     * @param forward läuft bei Maustaste Vor
     */
    public static void install(JComponent owner, Runnable back, Runnable forward) {
        AWTEventListener listener = event -> {
            if (event instanceof MouseEvent mouse && event.getID() == MouseEvent.MOUSE_RELEASED && owner.isShowing()
                    && (mouse.getButton() == BACK_BUTTON || mouse.getButton() == FORWARD_BUTTON)
                    && sameWindow(mouse.getComponent(), owner)) {
                (mouse.getButton() == BACK_BUTTON ? back : forward).run();
            }
        };
        owner.putClientProperty(OWN, Boolean.TRUE);
        owner.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                JRootPane root = SwingUtilities.getRootPane(owner);
                if (root != null) {
                    root.putClientProperty(OWN, owner.isShowing() ? Boolean.TRUE : null);
                }
            }
            if ((e.getChangeFlags() & HierarchyEvent.DISPLAYABILITY_CHANGED) != 0) {
                if (owner.isDisplayable()) {
                    Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK);
                } else {
                    Toolkit.getDefaultToolkit().removeAWTEventListener(listener);
                }
            }
        });
    }

    private static boolean sameWindow(Component source, Component owner) {
        return source != null && SwingUtilities.getWindowAncestor(source) == SwingUtilities.getWindowAncestor(owner);
    }
}
