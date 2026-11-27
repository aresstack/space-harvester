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
     */
    static ReadAloudDial dial(PodcastOrb orb, Supplier<ReadAloudBinding> binding, IntConsumer jump) {
        ReadAloudRate rates = ReadAloudRate.shared();
        ReadAloudDial dial = new ReadAloudDial(orb, rate -> {
            rates.remember(rate);
            ReadAloudBinding current = binding.get();
            if (current != null) {
                current.setRate(rate);
            }
        }, jump);
        dial.setLimits(rates.min(), rates.max(), rates.step(), rates.jumpSentences());
        dial.setRate(rates.rate());
        return dial;
    }

    /** Eine neu angeschlossene Sprachausgabe spricht mit der eingestellten Geschwindigkeit. */
    static void bind(ReadAloudDial dial, ReadAloudBinding binding) {
        if (binding != null) {
            binding.setRate(dial.rate());
        }
    }
}
