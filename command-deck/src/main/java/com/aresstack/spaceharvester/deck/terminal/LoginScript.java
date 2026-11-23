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
        return attempt(keyboard, login, since).problem();
    }

    /**
     * Ergebnis einer Anmeldung.
     *
     * @param sent     ob Benutzer und Passwort an den Host gingen (nur dann zählt es als Versuch für die Kontosperre)
     * @param accepted ob der Host die Anmeldung angenommen hat (geprüft mit {@link LoginCheck})
     * @param problem  leer bei Erfolg, sonst der Grund (auch für ein gescheitertes Startkommando nach der Anmeldung)
     */
    public record Result(boolean sent, boolean accepted, Optional<String> problem) {

        static Result notSent(String reason) {
            return new Result(false, false, Optional.of(reason));
        }

        static Result rejected(String reason) {
            return new Result(true, false, Optional.of(reason));
        }

        static Result accepted(Optional<String> problem) {
            return new Result(true, true, problem);
        }
    }

    /**
     * Wie {@link #run(TerminalKeyboard, TerminalLogin, long)}, sagt aber auch, ob die Zugangsdaten abgeschickt wurden.
     * Nach ENTER wartet sie wie MainframeMate vor dem Startkommando auf die Antwort und prüft das Bild
     * ({@link LoginCheck}): Lehnt der Host ab oder bleibt es unklar, ist das ein Fehlschlag.
     */
    public Result attempt(TerminalKeyboard keyboard, TerminalLogin login, long since) throws InterruptedException {
        try (login) {
            if (!keyboard.awaitUnlocked(UNLOCK_TIMEOUT)) {
                return Result.notSent("Tastatur wurde nicht frei");
            }
            pause();
            if (typedByHand(keyboard, since)) {
                return Result.notSent(BY_HAND);
            }
            keyboard.clear();
            if (!keyboard.awaitUnlocked(UNLOCK_TIMEOUT)) {
                return Result.notSent("Tastatur nach CLEAR nicht frei");
            }
            pause();
            if (typedByHand(keyboard, since)) {
                return Result.notSent(BY_HAND);
            }
            if (!keyboard.firstInputField()) {
                return Result.notSent("kein Eingabefeld für den Benutzer");
            }
            if (!typed(keyboard, login.user().toCharArray())) {
                return Result.notSent("Benutzerfeld ist geschützt");
            }
            pause();
            if (typedByHand(keyboard, since)) {
                return Result.notSent(BY_HAND);
            }
            if (!keyboard.nextInputField()) {
                return Result.notSent("kein zweites Eingabefeld für das Passwort");
            }
            char[] password = login.copyPassword();
            try {
                if (!typed(keyboard, password)) {
                    return Result.notSent("Passwortfeld ist geschützt");
                }
            } finally {
                Arrays.fill(password, '\0');
            }
            pause();
            keyboard.enter();
            // Wie MainframeMate vor dem Startkommando: Antwort abwarten, dann das Bild prüfen (Sperrschutz).
            if (!keyboard.awaitUnlocked(UNLOCK_TIMEOUT)) {
                return Result.rejected("keine Antwort des Hosts auf die Anmeldung");
            }
            pause();
            Optional<String> rejected = LoginCheck.rejected(keyboard.screenText(), keyboard.loginMask());
            if (rejected.isPresent()) {
                return Result.rejected(rejected.get());
            }
            if (!login.command().isEmpty()) {
                if (typedByHand(keyboard, since)) {
                    return Result.accepted(Optional.of(BY_HAND));
                }
                if (!typed(keyboard, login.command().toCharArray())) {
                    return Result.accepted(Optional.of("Startkommando: Feld an der Schreibmarke ist geschützt"));
                }
                pause();
                keyboard.enter();
            }
            return Result.accepted(Optional.empty());
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
