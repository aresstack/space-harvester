package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.speech.ReadAloudBinding;
import com.aresstack.spaceharvester.app.ui.controls.PodcastOrb;

import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;
import javax.swing.text.Utilities;
import java.awt.Point;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Objects;

/**
 * Vorlesen in der Leseansicht einer Seite (Angelo 23.11.2026: „die Vorlesefunktion eben auf die normale Seite ohne
 * Text“), wie im Lesebereich des Enterprise AI Client ({@code KnowledgeReaderPanel}):
 *
 * <ul>
 *   <li>Der runde Knopf oben rechts liest die Seite vor; beim Vorlesen hält er an und setzt fort.</li>
 *   <li>Ein Doppelklick auf ein Wort, während vorgelesen wird, liest sofort ab diesem Wort weiter („sonst liest er
 *   nachher das ganze Inhaltsverzeichnis“); daneben (Bild, Leerraum) ändert sich nichts ({@link ReadAloudStart}).</li>
 *   <li>Vorgelesen wird die kanonische Textfassung der Seite, nie die Darstellung, und nur mit KI-Freigabe.</li>
 *   <li>Der gerade gelesene Satz ist gelb hinterlegt wie im Podcast und bleibt im Blick, ungefähr und nie Wort für
 *   Wort; dieselbe Hervorhebung wie im Chat ({@link ReadAloudMarker}, Angelo 27.11.2026).</li>
 *   <li>Eine andere Seite, eine leere Ansicht oder ein anderer Reiter beenden das Vorlesen.</li>
 * </ul>
 *
 * Ohne Sprachausgabe bleibt der Knopf unsichtbar. Alles auf dem EDT.
 */
final class PageReadAloud {

    static final String PLAY_TIP = "Seite vorlesen · beim Vorlesen liest ein Doppelklick auf ein Wort ab dort weiter";
    static final String PAUSE_TIP = "Vorlesen anhalten";
    static final String RESUME_TIP = "Vorlesen fortsetzen";
    static final String NO_TEXT_TIP = "Nichts zum Vorlesen: ohne KI-Freigabe oder ohne lokalen Stand der Seite";

    private final JTextComponent text;
    private final PodcastOrb orb = new PodcastOrb(state -> switch (state) {
        case PLAY -> "Seite vorlesen";
        case PAUSE -> PAUSE_TIP;
        case WAITING -> "Vorlesen wird vorbereitet";
        case FAILED -> "Vorlesen erneut versuchen";
    });
    /** Hebt den gerade gelesenen Satz hervor; die Ansicht ist hell, daher die gelbe Fläche des Podcasts. */
    private final ReadAloudMarker marker;
    private ReadAloudBinding binding;
    /** Der vorlesbare Text der gezeigten Seite; leer ohne. */
    private String speechText = "";
    /** Ob die gezeigte Seite noch vorgelesen werden darf; gefragt auf dem Lese-Thread. */
    private java.util.function.BooleanSupplier allowed = () -> true;
    /** Ob gerade vorgelesen wird (auch angehalten). */
    private boolean speaking;
    /** Ob das Vorlesen angehalten ist (Stelle bleibt, der Knopf setzt dort fort). */
    private boolean paused;
    /** Kennung des laufenden Vorlesens; das Ende eines abgelösten Laufs setzt den Knopf nicht zurück. */
    private Object run;

    PageReadAloud(JTextComponent text) {
        this.text = Objects.requireNonNull(text, "text");
        this.marker = new ReadAloudMarker(() -> List.of(text), ReadAloudMarker.fill());
        orb.setName("reading.readAloudOrb");
        orb.setVisible(false);
        orb.addActionListener(event -> toggle());
        text.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event) && event.getClickCount() == 2) {
                    readFrom(event.getPoint());
                }
            }
        });
        // Anderer Reiter oder Fenster zu: die Seite liest nicht weiter.
        text.addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && !text.isShowing()) {
                stop();
            }
        });
        style();
    }

    /** Der Knopf für oben rechts über dem Text. */
    PodcastOrb orb() {
        return orb;
    }

    /** Schließt die Sprachausgabe an; {@code null} = keine, dann ist der Knopf weg. */
    void bind(ReadAloudBinding binding) {
        if (this.binding != null && this.binding != binding) {
            stop();
        }
        this.binding = binding;
        orb.setVisible(binding != null);
        style();
    }

    /** Der vorlesbare Text der gezeigten Seite; ein anderer Text (andere Seite) beendet das Vorlesen. */
    void speechText(String spoken) {
        speechText(spoken, () -> true);
    }

    /**
     * Wie {@link #speechText(String)}; {@code allowed} fragt vor jedem Vorlesen (nicht auf dem EDT), ob die Seite noch
     * an einen KI-Dienst darf (ADR 0028: eine zurückgenommene Freigabe gilt sofort).
     */
    void speechText(String spoken, java.util.function.BooleanSupplier allowed) {
        String next = spoken == null ? "" : spoken;
        if (!next.equals(speechText)) {
            stop();
        }
        speechText = next;
        this.allowed = Objects.requireNonNull(allowed, "allowed");
        style();
    }

    /** Wie der Knopf im Enterprise AI Client: vorlesen, anhalten, fortsetzen. */
    void toggle() {
        if (binding == null) {
            return;
        }
        if (speaking) {
            paused = !paused;
            if (paused) {
                binding.pause();
                marker.pause(); // die Marke wartet mit
            } else {
                binding.resume();
                marker.resume();
            }
            style();
            return;
        }
        if (speechText.isBlank()) {
            return;
        }
        speak(0);
    }

    /**
     * Doppelklick während des Vorlesens: trifft er eine vorlesbare Textstelle, beginnt das Vorlesen sofort ab dem
     * angeklickten Wort neu; daneben (Bild, Leerraum) ändert sich nichts.
     *
     * @return ob neu begonnen wurde
     */
    boolean readFrom(Point point) {
        if (binding == null || !speaking) {
            return false;
        }
        int position = text.viewToModel2D(point);
        if (position < 0) {
            return false;
        }
        Element element = Utilities.getParagraphElement(text, position);
        String paragraph;
        try {
            paragraph = text.getDocument().getText(element.getStartOffset(),
                    element.getEndOffset() - element.getStartOffset());
        } catch (BadLocationException e) {
            return false;
        }
        int start = ReadAloudStart.find(speechText, paragraph, position - element.getStartOffset(),
                place(element.getStartOffset(), paragraph));
        if (start < 0) {
            return false;
        }
        speak(start);
        return true;
    }

    /**
     * Wo der angeklickte Absatz in der Anzeige steht: eine Überschrift, die auch im Inhaltsverzeichnis steht, liest dann
     * ab der Überschrift im Text weiter, nicht ab dem Verzeichnis.
     */
    private ReadAloudStart.Place place(int paragraphStart, String paragraph) {
        try {
            return ReadAloudStart.place(text.getDocument().getText(0, text.getDocument().getLength()), paragraphStart,
                    paragraph);
        } catch (BadLocationException e) {
            return null;
        }
    }

    /** Liest den Text ab {@code start} vor; ein laufendes Vorlesen endet vorher (dieselbe Sprachausgabe). */
    private void speak(int start) {
        String spoken = speechText.substring(start);
        Object mine = new Object();
        run = mine;
        speaking = true;
        paused = false;
        marker.start(speechText, start, spoken.length());
        style();
        binding.speak(spoken, allowed, () -> {
            if (run == mine) {
                run = null;
                speaking = false;
                paused = false;
                marker.reset();
                style();
            }
        }, (from, to, millis) -> {
            if (run == mine) {
                playing(from, to, millis);
            }
        });
    }

    /** Ein Stück erklingt: ungefähr die Zeichen {@code [from, to)} des Ausschnitts, etwa {@code millis} lang. */
    void playing(int from, int to, long millis) {
        if (speaking) {
            marker.playing(from, to, millis);
        }
    }

    /** Beendet das Vorlesen; idempotent. */
    void stop() {
        if (!speaking) {
            marker.reset();
            return;
        }
        run = null;
        speaking = false;
        paused = false;
        marker.reset();
        if (binding != null) {
            binding.stop();
        }
        style();
    }

    /** Ob gerade vorgelesen wird (für Tests). */
    boolean speaking() {
        return speaking;
    }

    /** Ob angehalten ist (für Tests). */
    boolean paused() {
        return paused;
    }

    /** Der hervorgehobene Text; leer ohne (für Tests). */
    String marked() {
        return marker.marked();
    }

    private void style() {
        boolean hasText = !speechText.isBlank();
        boolean playing = speaking && !paused;
        orb.setState(playing ? PodcastOrb.State.PAUSE : PodcastOrb.State.PLAY);
        orb.setEnabled(speaking || hasText);
        orb.setToolTipText(paused && speaking ? RESUME_TIP : playing ? PAUSE_TIP : hasText ? PLAY_TIP : NO_TEXT_TIP);
    }
}
