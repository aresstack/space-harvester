package com.aresstack.spaceharvester.deck.terminal;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Spielt ein aufgezeichnetes Makro ab (TerminalMacroPlayer aus MainframeMate): vor Text bis zu 5 s, vor einer Taste bis
 * zu 10 s auf die freie Tastatur warten, danach jeweils die Aktionsverzögerung. Läuft nie auf dem EDT.
 */
public final class MacroPlayer {

    static final Duration TEXT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration AID_TIMEOUT = Duration.ofSeconds(10);

    private final Supplier<Duration> delay;

    public MacroPlayer(Supplier<Duration> delay) {
        this.delay = delay;
    }

    /**
     * @return leer, wenn alle Schritte gesendet wurden, sonst der Grund des Abbruchs
     */
    public Optional<String> play(TerminalKeyboard keyboard, List<TerminalMacro.Step> steps)
            throws InterruptedException {
        int number = 0;
        for (TerminalMacro.Step step : steps) {
            number++;
            if (step.kind() == TerminalMacro.Step.Kind.TAB) {
                if (!keyboard.awaitUnlocked(TEXT_TIMEOUT)) {
                    return Optional.of("Schritt " + number + ": Tastatur nicht frei");
                }
                if (!keyboard.nextInputField()) {
                    return Optional.of("Schritt " + number + ": kein weiteres Eingabefeld");
                }
            } else if (step.kind() == TerminalMacro.Step.Kind.TEXT) {
                if (!keyboard.awaitUnlocked(TEXT_TIMEOUT)) {
                    return Optional.of("Schritt " + number + ": Tastatur nicht frei");
                }
                try {
                    keyboard.type(step.value().toCharArray());
                } catch (IllegalStateException protectedField) {
                    return Optional.of("Schritt " + number + ": Feld an der Schreibmarke ist geschützt");
                }
            } else {
                AidKey key = step.key().orElse(null);
                if (key == null) {
                    continue; // unbekannte Taste wie in MainframeMate überspringen
                }
                if (!keyboard.awaitUnlocked(AID_TIMEOUT)) {
                    return Optional.of("Schritt " + number + " (" + step.display() + "): Tastatur nicht frei");
                }
                keyboard.press(key);
            }
            pause();
        }
        return Optional.empty();
    }

    private void pause() throws InterruptedException {
        Duration current = delay.get();
        if (current != null && !current.isZero() && !current.isNegative()) {
            Thread.sleep(current);
        }
    }
}
