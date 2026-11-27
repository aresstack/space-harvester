package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.speech.ReadAloudBinding;
import com.aresstack.spaceharvester.app.speech.ReadAloudRate;
import com.aresstack.spaceharvester.app.ui.controls.PodcastOrb;
import com.aresstack.spaceharvester.app.ui.controls.ReadAloudDial;

import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Die Skala um einen Vorlese-Knopf, gleich angeschlossen für Chat ({@link ChatReadAloud}) und Leseansicht
 * ({@link PageReadAloud}): Grenzen und gemerkte Geschwindigkeit aus {@link ReadAloudRate}, jede Änderung wird gemerkt
 * und geht an die gemeinsame Sprachausgabe.
 */
final class ReadAloudSpeed {

    private ReadAloudSpeed() {
    }

    /**
     * @param orb     der Vorlese-Knopf
     * @param binding die angeschlossene Sprachausgabe, {@code null} ohne
     * @param jump    springt um so viele Sätze (negativ = zurück)
     * @param marker  die Satzmarke des Besitzers; sie folgt einer Tempoänderung mitten im Stück
     */
    static ReadAloudDial dial(PodcastOrb orb, Supplier<ReadAloudBinding> binding, IntConsumer jump,
                              ReadAloudMarker marker) {
        ReadAloudRate rates = ReadAloudRate.shared();
        ReadAloudDial dial = new ReadAloudDial(orb, rate -> {
            double before = rates.rate();
            rates.remember(rate);
            ReadAloudBinding current = binding.get();
            if (current != null) {
                before = current.rate();
                current.setRate(rate);
            }
            // die Stimme spricht ab sofort schneller oder langsamer: die Satzmarke schaltet im selben Maß weiter
            marker.rescale(before / rate);
        }, jump);
        Runnable limits = () -> {
            dial.setLimits(rates.min(), rates.max(), rates.step(), rates.jumpSentences());
            dial.setRate(rates.rate());
        };
        limits.run();
        dial.beforeShow(limits); // in den Einstellungen geänderte Grenzen gelten beim nächsten Einblenden
        return dial;
    }

    /** Eine neu angeschlossene Sprachausgabe spricht mit der eingestellten Geschwindigkeit. */
    static void bind(ReadAloudDial dial, ReadAloudBinding binding) {
        // maßgeblich ist die gemerkte Geschwindigkeit (Chat und Leseansicht teilen sie), nicht der Stand dieser Skala
        dial.setRate(ReadAloudRate.shared().rate());
        if (binding != null) {
            binding.setRate(dial.rate());
        }
    }
}
