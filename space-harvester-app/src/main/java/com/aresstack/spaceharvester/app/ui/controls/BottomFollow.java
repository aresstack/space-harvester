package com.aresstack.spaceharvester.app.ui.controls;

import javax.swing.BoundedRangeModel;
import javax.swing.JScrollBar;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeListener;

/**
 * Mitrollen nur, solange der Nutzer ganz unten steht (Angelo 26.11.2026): Wächst der Inhalt (eine Antwort wird
 * geschrieben), rollt die Ansicht mit, wenn sie unten stand. Rollt der Nutzer hoch – Mausrad, Bildlaufleiste oder
 * Tastatur –, hört das Mitrollen auf, damit er in Ruhe weiter oben lesen kann; rollt er wieder ganz nach unten, setzt
 * es wieder ein.
 *
 * <p>Erkannt wird das am Modell der Bildlaufleiste, ohne Abfragen: Sinkt der Wert, ohne dass die Ansicht unten
 * steht, hat der Nutzer hochgerollt. Wächst nur der Inhalt, bleibt der Wert stehen; das ändert nichts. Wird der Inhalt
 * kürzer, setzt das Modell den Wert auf das Ende zurück – die Ansicht steht dann unten und folgt weiter. „Ganz unten“
 * ist das Ende des Modells ({@code value + extent == maximum}); eine Toleranz braucht es nicht, weil Mausrad, Leiste und
 * Tastatur am Ende genau dort anschlagen.</p>
 */
public final class BottomFollow {

    private final JScrollBar bar;
    private final ChangeListener observer = event -> observe();
    private boolean following = true;
    private int lastValue;

    private BottomFollow(JScrollBar bar) {
        this.bar = bar;
        this.lastValue = bar.getValue();
        bar.getModel().addChangeListener(observer);
        bar.addPropertyChangeListener("model", event -> {
            if (event.getOldValue() instanceof BoundedRangeModel old) {
                old.removeChangeListener(observer);
            }
            bar.getModel().addChangeListener(observer);
            lastValue = bar.getValue();
        });
    }

    /** Folgt dem Ende der senkrechten Bildlaufleiste {@code bar}; anfangs steht die Ansicht unten. */
    public static BottomFollow install(JScrollBar bar) {
        return new BottomFollow(bar);
    }

    /** Ob die Ansicht beim nächsten Wachsen mitrollt. */
    public boolean following() {
        return following;
    }

    /** Rollt ans Ende, wenn die Ansicht unten stand; hat der Nutzer hochgerollt, bleibt sie, wo sie ist. */
    public void follow() {
        SwingUtilities.invokeLater(() -> {
            if (following) {
                toEnd();
            }
        });
    }

    /**
     * Rollt ans Ende und folgt wieder, auch wenn der Nutzer hochgerollt hatte: für eigene Schritte des Nutzers, die er
     * unten sehen will (eine gesendete Frage, ein neu geöffneter Chat).
     */
    public void jump() {
        following = true;
        SwingUtilities.invokeLater(() -> {
            following = true;
            toEnd();
        });
    }

    private void toEnd() {
        bar.setValue(bar.getMaximum());
    }

    private void observe() {
        BoundedRangeModel model = bar.getModel();
        int value = model.getValue();
        if (atEnd(model)) {
            following = true;
        } else if (value < lastValue) {
            following = false;
        }
        lastValue = value;
    }

    static boolean atEnd(BoundedRangeModel model) {
        return model.getValue() + model.getExtent() >= model.getMaximum();
    }
}
