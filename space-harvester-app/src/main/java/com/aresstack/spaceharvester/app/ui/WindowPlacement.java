package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComicWindowDragger;

import javax.swing.JFrame;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lage und Größe des Hauptfensters über den Neustart hinweg (Angelo, 22.11.2026): Position, Größe und ob es maximiert
 * war, gemerkt im {@link AppState}. Beim Start wird geprüft, ob das Fenster auf den jetzt angeschlossenen Bildschirmen
 * darstellbar ist; sonst wird es auf den Bildschirm verkleinert und in den sichtbaren Bereich geschoben, etwa nach
 * einem Monitorwechsel. Minimiert startet es nie: dann gilt der Zustand davor.
 */
final class WindowPlacement {

    static final String X = "window.x";
    static final String Y = "window.y";
    static final String WIDTH = "window.width";
    static final String HEIGHT = "window.height";
    static final String MAXIMIZED = "window.maximized";

    /** Mindestens so viel vom Fenster muss auf einem Bildschirm liegen, damit es dort bleibt (sonst neu zentriert). */
    static final int MIN_VISIBLE = 100;

    private WindowPlacement() {
    }

    /**
     * Setzt die gemerkten Grenzen (passend zu den Bildschirmen) oder, ohne gemerkten Stand, {@code fallback} mittig.
     * Vor {@code setVisible(true)} aufrufen; {@link #maximizeIfRemembered} danach.
     */
    static void restore(JFrame frame, Dimension fallback) {
        AppState state = AppState.shared();
        int width = state.getInt(WIDTH, -1);
        int height = state.getInt(HEIGHT, -1);
        List<Rectangle> screens = usableScreens();
        if (width <= 0 || height <= 0 || screens.isEmpty()) {
            frame.setSize(fallback);
            frame.setLocationRelativeTo(null);
            return;
        }
        Rectangle saved = new Rectangle(state.getInt(X, 0), state.getInt(Y, 0), width, height);
        Rectangle fitted = fit(saved, screens, frame.getMinimumSize());
        // Ein Bildschirm kleiner als die Mindestgröße: AWT hielte sonst in setBounds() die Mindestgröße fest und das
        // Fenster ragte über den Rand.
        Dimension minimum = frame.getMinimumSize();
        if (minimum.width > fitted.width || minimum.height > fitted.height) {
            frame.setMinimumSize(new Dimension(Math.min(minimum.width, fitted.width),
                    Math.min(minimum.height, fitted.height)));
        }
        frame.setBounds(fitted);
    }

    /** War das Fenster beim letzten Beenden maximiert, wird es wieder maximiert (nach dem Sichtbarmachen). */
    static void maximizeIfRemembered(JFrame frame) {
        if (AppState.shared().getBoolean(MAXIMIZED, false)) {
            ComicWindowDragger.maximize(frame);
        }
    }

    /** Merkt den Zustand bei jeder Verschiebung und Größenänderung, damit er auch nach einem Absturz gilt. */
    static void track(JFrame frame) {
        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                remember(frame);
            }

            @Override
            public void componentMoved(ComponentEvent event) {
                remember(frame);
            }
        });
    }

    /**
     * Schreibt den aktuellen Zustand: maximiert ja/nein und die Grenzen des normalen Fensters (bei einem maximierten
     * Fenster die Grenzen davor). Minimiert oder vom System maximiert ohne bekannte Grenzen bleiben die alten.
     */
    static void remember(JFrame frame) {
        if (!frame.isDisplayable() || (frame.getExtendedState() & Frame.ICONIFIED) != 0) {
            return;
        }
        boolean maximized = ComicWindowDragger.isMaximized(frame);
        Rectangle normal = maximized ? ComicWindowDragger.restoredBounds(frame) : frame.getBounds();
        Map<String, String> changes = new LinkedHashMap<>();
        changes.put(MAXIMIZED, String.valueOf(maximized));
        if (normal != null && normal.width > 0 && normal.height > 0) {
            changes.put(X, String.valueOf(normal.x));
            changes.put(Y, String.valueOf(normal.y));
            changes.put(WIDTH, String.valueOf(normal.width));
            changes.put(HEIGHT, String.valueOf(normal.height));
        }
        AppState.shared().putAll(changes);
    }

    /**
     * Die Grenzen, mit denen {@code saved} auf den Bildschirmen {@code screens} (nutzbare Flächen, der erste ist der
     * Hauptbildschirm) ganz sichtbar ist: auf dem Bildschirm, auf dem das meiste davon liegt, höchstens so groß wie
     * dessen Fläche und ganz hineingeschoben. Liegt kaum etwas davon auf einem Bildschirm, steht es mittig auf dem
     * Hauptbildschirm. Die Mindestgröße gilt, solange der Bildschirm groß genug ist.
     */
    static Rectangle fit(Rectangle saved, List<Rectangle> screens, Dimension minimum) {
        Rectangle screen = null;
        long best = 0;
        for (Rectangle candidate : screens) {
            Rectangle overlap = candidate.intersection(saved);
            long area = overlap.isEmpty() ? 0 : (long) overlap.width * overlap.height;
            if (area > best && overlap.width >= Math.min(MIN_VISIBLE, saved.width)
                    && overlap.height >= Math.min(MIN_VISIBLE, saved.height)) {
                best = area;
                screen = candidate;
            }
        }
        boolean center = screen == null;
        if (center) {
            screen = screens.get(0);
        }
        int minWidth = minimum == null ? 0 : minimum.width;
        int minHeight = minimum == null ? 0 : minimum.height;
        int width = Math.min(Math.max(saved.width, Math.min(minWidth, screen.width)), screen.width);
        int height = Math.min(Math.max(saved.height, Math.min(minHeight, screen.height)), screen.height);
        int x;
        int y;
        if (center) {
            x = screen.x + (screen.width - width) / 2;
            y = screen.y + (screen.height - height) / 2;
        } else {
            x = Math.max(screen.x, Math.min(saved.x, screen.x + screen.width - width));
            y = Math.max(screen.y, Math.min(saved.y, screen.y + screen.height - height));
        }
        return new Rectangle(x, y, width, height);
    }

    /** Die nutzbaren Flächen aller Bildschirme (ohne Taskleiste), der Hauptbildschirm zuerst. */
    static List<Rectangle> usableScreens() {
        List<Rectangle> screens = new ArrayList<>();
        if (GraphicsEnvironment.isHeadless()) {
            return screens;
        }
        GraphicsEnvironment environment = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice primary = environment.getDefaultScreenDevice();
        List<GraphicsDevice> devices = new ArrayList<>();
        devices.add(primary);
        for (GraphicsDevice device : environment.getScreenDevices()) {
            if (device != primary) {
                devices.add(device);
            }
        }
        for (GraphicsDevice device : devices) {
            GraphicsConfiguration configuration = device.getDefaultConfiguration();
            Rectangle bounds = configuration.getBounds();
            Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
            screens.add(new Rectangle(bounds.x + insets.left, bounds.y + insets.top,
                    bounds.width - insets.left - insets.right, bounds.height - insets.top - insets.bottom));
        }
        return screens;
    }
}
