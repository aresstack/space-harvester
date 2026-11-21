package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.enterpriseai.ui.comic.control.ComicComboBoxUI;
import com.aresstack.spaceharvester.app.ui.Comic;

import javax.swing.JComboBox;
import java.util.List;

/**
 * Tokenlänge (Ausgabelimit) eines Modells als editierbare Auswahlliste (Angelo 21.11.2026): die Werte wie die Knöpfe
 * „Antwortlänge in Tokens“ in 3D4J (2048, 8192, 16384, 32768, 65536), dazu jede eingetippte Zahl. Steht in der Datei
 * nichts, zeigt die Liste den Standard des Core; bleibt er unverändert, liefert {@link #text()} leer, damit beim
 * Speichern nichts in die Datei kommt, was dort nicht stand.
 */
public final class TokenLengthBox extends JComboBox<String> {

    private final int standard;
    /** Sonderwert vor den Zahlen, etwa „wie Text-Modell“; {@code null} für keinen. */
    private final String inherit;
    /** Ob die Anzeige den Standard zeigt, weil in der Datei nichts steht. */
    private boolean implicit;

    /**
     * @param choices  die Auswahl, etwa {@code AiServiceSettings.TOKEN_CHOICES}
     * @param standard der Standard des Core, wenn in der Datei nichts steht
     */
    public TokenLengthBox(List<Integer> choices, int standard) {
        this(choices, standard, null);
    }

    /**
     * Mit einem Sonderwert vor den Zahlen, etwa „wie Text-Modell“: Ist er gewählt (oder steht nichts in der Datei),
     * liefert {@link #text()} leer, und es gilt die Tokenlänge, auf die er verweist.
     */
    public TokenLengthBox(List<Integer> choices, int standard, String inherit) {
        this.standard = standard;
        this.inherit = inherit;
        setFont(Comic.regular(13f));
        ComicComboBoxUI.install(this);
        setEditable(true);
        if (inherit != null) {
            addItem(inherit);
        }
        choices.forEach(value -> addItem(String.valueOf(value)));
        setToolTipText("Tokenlänge: so viele Tokens darf eine Antwort höchstens lang werden; Auswahl oder eigene "
                + "Zahl, Standard " + standard);
        showStored(null);
    }

    /** Zeigt den Wert aus der Datei; {@code null} oder leer zeigt den Standard. */
    public void showStored(String stored) {
        implicit = stored == null || stored.isBlank();
        setSelectedItem(implicit ? (inherit != null ? inherit : String.valueOf(standard)) : stored.strip());
    }

    /** Der eingetragene Wert als Text; leer, solange der Standard ungeändert angezeigt wird. */
    public String text() {
        Object editing = getEditor() == null ? null : getEditor().getItem();
        String value = editing == null ? "" : editing.toString().strip();
        if (inherit != null && value.equals(inherit) || implicit && value.equals(String.valueOf(standard))) {
            return "";
        }
        return value;
    }

    /** Der Standard des Core. */
    public int standard() {
        return standard;
    }
}
