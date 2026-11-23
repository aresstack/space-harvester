package com.aresstack.spaceharvester.deck.terminal;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * Automatische Anmeldung nach dem Ablauf aus MainframeMate: warten bis die Tastatur frei ist, CLEAR, Benutzer ins
 * erste Eingabefeld, Passwort ins nächste, ENTER, danach optional das Startkommando mit ENTER. Zwischen den Schritten
 * eine kurze Pause wie dort (Aktionsverzögerung), damit langsame Hosts mitkommen.
 */
public final class LoginScript {

    /** Vorgabe der Aktionsverzögerung in MainframeMate (tn3270ActionDelayMs). */
    public static final Duration DEFAULT_DELAY = Duration.ofMillis(1000);
    static final Duration UNLOCK_TIMEOUT = Duration.ofSeconds(10);

    private final java.util.function.Supplier<Duration> delay;

    public LoginScript(Duration delay) {
        this(() -> delay);
    }

    /** Mit einer Verzögerung, die sich zur Laufzeit ändern kann (Einstellungen › Terminal › Aktionsverzögerung). */
    public LoginScript(java.util.function.Supplier<Duration> delay) {
        this.delay = java.util.Objects.requireNonNull(delay, "delay");
    }

    /** Die aktuelle Aktionsverzögerung; auch das Abspielen von Makros wartet so lange. */
    public Duration delay() {
        Duration current = delay.get();
        return current == null || current.isNegative() ? Duration.ZERO : current;
    }

    /**
     * Führt die Anmeldung aus und löscht danach die Zugangsdaten.
     *
     * @return leer bei Erfolg, sonst der Grund, warum abgebrochen wurde
     */
    public Optional<String> run(TerminalKeyboard keyboard, TerminalLogin login) throws InterruptedException {
        return run(keyboard, login, Long.MIN_VALUE);
    }

    /**
     * Wie {@link #run(TerminalKeyboard, TerminalLogin)}; hat der Mensch nach {@code since} selbst getippt, bricht die
     * Anmeldung vor dem nächsten Schritt ab und tippt nichts mehr.
     */
    public Optional<String> run(TerminalKeyboard keyboard, TerminalLogin login, long since)
            throws InterruptedException {
        try (login) {
            if (!keyboard.awaitUnlocked(UNLOCK_TIMEOUT)) {
                return Optional.of("Tastatur wurde nicht frei");
            }
            pause();
            if (typedByHand(keyboard, since)) {
                return Optional.of(BY_HAND);
            }
            keyboard.clear();
            if (!keyboard.awaitUnlocked(UNLOCK_TIMEOUT)) {
                return Optional.of("Tastatur nach CLEAR nicht frei");
            }
            pause();
            if (typedByHand(keyboard, since)) {
                return Optional.of(BY_HAND);
            }
            if (!keyboard.firstInputField()) {
                return Optional.of("kein Eingabefeld für den Benutzer");
            }
            if (!typed(keyboard, login.user().toCharArray())) {
                return Optional.of("Benutzerfeld ist geschützt");
            }
            pause();
            if (typedByHand(keyboard, since)) {
                return Optional.of(BY_HAND);
            }
            if (!keyboard.nextInputField()) {
                return Optional.of("kein zweites Eingabefeld für das Passwort");
            }
            char[] password = login.copyPassword();
            try {
                if (!typed(keyboard, password)) {
                    return Optional.of("Passwortfeld ist geschützt");
                }
            } finally {
                Arrays.fill(password, '\0');
            }
            pause();
            keyboard.enter();
            if (!login.command().isEmpty()) {
                if (!keyboard.awaitUnlocked(UNLOCK_TIMEOUT)) {
                    return Optional.of("Tastatur nach der Anmeldung nicht frei, Startkommando nicht gesendet");
                }
                pause();
                if (typedByHand(keyboard, since)) {
                    return Optional.of(BY_HAND);
                }
                if (!typed(keyboard, login.command().toCharArray())) {
                    return Optional.of("Startkommando: Feld an der Schreibmarke ist geschützt");
                }
                pause();
                keyboard.enter();
            }
            return Optional.empty();
        }
    }

    /** Grund, wenn der Mensch selbst tippt. */
    public static final String BY_HAND = "du tippst selbst";

    private static boolean typedByHand(TerminalKeyboard keyboard, long since) {
        long last = keyboard.lastUserInput();
        return last != Long.MIN_VALUE && last - since > 0;
    }

    /** Tippt; {@code false}, wenn das Terminal die Eingabe an dieser Stelle ablehnt (geschütztes Feld). */
    private static boolean typed(TerminalKeyboard keyboard, char[] text) {
        try {
            keyboard.type(text);
            return true;
        } catch (IllegalStateException protectedField) {
            return false;
        }
    }

    private void pause() throws InterruptedException {
        Duration current = delay();
        if (!current.isZero()) {
            Thread.sleep(current);
        }
    }
}
