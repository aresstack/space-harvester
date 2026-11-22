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

    private final Duration delay;

    public LoginScript(Duration delay) {
        this.delay = delay;
    }

    /**
     * Führt die Anmeldung aus und löscht danach die Zugangsdaten.
     *
     * @return leer bei Erfolg, sonst der Grund, warum abgebrochen wurde
     */
    public Optional<String> run(TerminalKeyboard keyboard, TerminalLogin login) throws InterruptedException {
        try (login) {
            if (!keyboard.awaitUnlocked(UNLOCK_TIMEOUT)) {
                return Optional.of("Tastatur wurde nicht frei");
            }
            pause();
            keyboard.clear();
            if (!keyboard.awaitUnlocked(UNLOCK_TIMEOUT)) {
                return Optional.of("Tastatur nach CLEAR nicht frei");
            }
            pause();
            if (!keyboard.firstInputField()) {
                return Optional.of("kein Eingabefeld für den Benutzer");
            }
            keyboard.type(login.user().toCharArray());
            pause();
            if (!keyboard.nextInputField()) {
                return Optional.of("kein zweites Eingabefeld für das Passwort");
            }
            char[] password = login.copyPassword();
            try {
                keyboard.type(password);
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
                keyboard.type(login.command().toCharArray());
                pause();
                keyboard.enter();
            }
            return Optional.empty();
        }
    }

    private void pause() throws InterruptedException {
        if (!delay.isZero()) {
            Thread.sleep(delay);
        }
    }
}
