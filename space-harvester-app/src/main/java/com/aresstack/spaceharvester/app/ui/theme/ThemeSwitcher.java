package com.aresstack.spaceharvester.app.ui.theme;

import com.aresstack.enterpriseai.ui.comic.theme.AskAiTheme;
import com.aresstack.enterpriseai.ui.comic.theme.ComicTheme;
import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.app.ui.Comic;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.JFrame;
import javax.swing.JMenuBar;
import javax.swing.SwingUtilities;

/**
 * Das eine Tor zum Wechseln des Farbschemas zur Laufzeit (Muster aus askai, {@code ThemeSwitcher}): Theme setzen,
 * die Menüvorgaben neu eintragen, jedes offene Fenster auffrischen, danach die Zuhörer benachrichtigen. Lebende
 * Farben ({@code ThemeColor}) folgen beim nächsten Zeichnen. Die Wahl liegt in den Benutzer-Einstellungen
 * ({@link #KEY}, Application State) und gilt beim nächsten Start wieder. Aufruf im Event-Thread.
 */
public final class ThemeSwitcher {

    /** Schlüssel der Wahl im Application State (wie {@code ui.theme} in askai). */
    public static final String KEY = "ui.theme";

    /** Bezeichnung des Vorgabe-Themes in der Auswahl. */
    static final String DEFAULT_LABEL = "Standard";

    private ThemeSwitcher() {
    }

    /** Das gemerkte Theme; ohne gültige Wahl das Vorgabe-Theme. Setzt es nur aktiv, ohne Fenster zu berühren. */
    public static AskAiTheme activateStored() {
        AskAiTheme theme = AskAiTheme.resolve(AppState.shared().get(KEY));
        AskAiTheme.setActive(theme);
        return theme;
    }

    /** Setzt das Theme für die ganze App und merkt die Wahl. */
    public static void apply(AskAiTheme theme) {
        if (theme == null) {
            throw new IllegalArgumentException("theme must not be null");
        }
        AppState.shared().put(KEY, theme.name());
        AskAiTheme.setActive(theme);
        ComicTheme.installMenuDefaults(Comic.PALETTE);
        for (Window window : Window.getWindows()) {
            refresh(window);
        }
        AskAiTheme.fireChanged();
    }

    private static void refresh(Window window) {
        SwingUtilities.updateComponentTreeUI(window);
        if (window instanceof JFrame) {
            JMenuBar menuBar = ((JFrame) window).getJMenuBar();
            if (menuBar != null) {
                SwingUtilities.updateComponentTreeUI(menuBar);
            }
        }
        window.invalidate();
        window.validate();
        window.repaint();
    }

    /** Die Namen der wählbaren Themes in Reihenfolge der Auswahl (Vorgabe zuerst). */
    public static List<String> names() {
        return new ArrayList<String>(AskAiTheme.themeNames());
    }

    /** Die Bezeichnung eines Themes in der Auswahl. */
    public static String label(String name) {
        if (AskAiTheme.CLASSIC.equals(name)) {
            return DEFAULT_LABEL;
        }
        return name.isEmpty() ? name : name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }
}
