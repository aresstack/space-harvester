package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.enterpriseai.ui.comic.control.ComicComboBoxUI;
import com.aresstack.spaceharvester.app.ui.Comic;

import javax.swing.JComboBox;
import java.util.List;

/**
 * Nachdenken eines Modells als Auswahl (Angelo 22.11.2026: „Neben der Tokenlänge muss außerdem überall das Nachdenken
 * einstellbar sein, sonst nimmt er irgendwas Standardmäßiges“): „denkt nicht“, „denkt wenig“, „denkt mittel“,
 * „denkt viel“, in der Datei {@code none}, {@code low}, {@code medium}, {@code high}; bei Modellen, die dem
 * Text-Modell folgen können, davor ein Sonderwert wie „Denken wie Text-Modell“. Die gewählte Stufe geht bei jeder
 * Anfrage mit, außer ein Aufruf legt das Nachdenken aus gutem Grund selbst fest.
 */
public final class ThinkingBox extends JComboBox<String> {

    /** Die Stufen in der Datei, in derselben Reihenfolge wie {@link #NAMES}. */
    public static final List<String> LEVELS = List.of("none", "low", "medium", "high");
    /** Die Anzeige der Stufen. */
    public static final List<String> NAMES = List.of("denkt nicht", "denkt wenig", "denkt mittel", "denkt viel");

    /** Sonderwert vor den Stufen, etwa „Denken wie Text-Modell“; {@code null} für keinen. */
    private final String inherit;

    /** Ohne Sonderwert: Steht nichts in der Datei, zeigt die Auswahl „denkt nicht“ (Standard des Core). */
    public ThinkingBox() {
        this(null);
    }

    /**
     * @param inherit Sonderwert vor den Stufen; ist er gewählt (oder steht nichts in der Datei), liefert
     *                {@link #level()} {@code null}, und es gilt das Nachdenken, auf das er verweist
     */
    public ThinkingBox(String inherit) {
        this.inherit = inherit;
        setFont(Comic.regular(13f));
        ComicComboBoxUI.install(this);
        if (inherit != null) {
            addItem(inherit);
        }
        NAMES.forEach(this::addItem);
        setToolTipText("Nachdenken: „denkt nicht“ antwortet direkt; sonst denkt ein Modell mit Nachdenk-Modus erst "
                + "nach, was dauert und Tokens kostet");
        showStored(null);
    }

    /** Zeigt die Stufe aus der Datei; {@code null}, leer oder unbekannt zeigt den Sonderwert bzw. „denkt nicht“. */
    public void showStored(String stored) {
        int index = stored == null ? -1 : LEVELS.indexOf(stored.strip().toLowerCase(java.util.Locale.ROOT));
        if (index < 0) {
            setSelectedIndex(0);
        } else {
            setSelectedIndex(index + (inherit == null ? 0 : 1));
        }
    }

    /**
     * Die gewählte Stufe für die Datei; {@code null} beim Sonderwert und ohne Sonderwert bei „denkt nicht“ (dem
     * Standard des Core), damit beim Speichern nichts in die Datei kommt, was dort nicht stand.
     */
    public String level() {
        int index = getSelectedIndex() - (inherit == null ? 0 : 1);
        if (index < 0 || inherit == null && index == 0) {
            return null;
        }
        return LEVELS.get(index);
    }
}
