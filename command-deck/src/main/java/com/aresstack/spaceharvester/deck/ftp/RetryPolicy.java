package com.aresstack.spaceharvester.deck.ftp;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Wann ein lesender Auftrag wiederholt wird, übernommen aus MainframeMate ({@code FtpRetryPolicy}, Einstellungen
 * „Maximale Versuche“, „Wartezeit“, „Backoff-Strategie“, „Max Backoff“, „Retry bei Timeout-Fehlern“, „Retry bei
 * IO-Fehlern“, „Reply Codes“). Schreibende Aufträge werden nie wiederholt, damit nichts doppelt geschieht.
 *
 * @param maxAttempts       Versuche insgesamt, 1 = keine Wiederholung
 * @param backoffMillis     Wartezeit vor der Wiederholung, 0 = sofort
 * @param exponential       Wartezeit je Versuch verdoppeln statt gleich lassen
 * @param maxBackoffMillis  Obergrenze der Wartezeit, 0 = keine
 * @param onTimeout         bei Zeitüberschreitung wiederholen
 * @param onConnectionError bei abgebrochener Verbindung wiederholen (neu verbunden wird ohnehin)
 * @param replyCodes        Antwortcodes des Hosts, bei denen wiederholt wird (etwa 421, 425, 426)
 */
public record RetryPolicy(int maxAttempts, int backoffMillis, boolean exponential, int maxBackoffMillis,
                          boolean onTimeout, boolean onConnectionError, Set<Integer> replyCodes) {

    /** Wie bisher und wie die Vorgabe von MainframeMate: zwei Versuche, sofort, bei Zeitüberschreitung und Abbruch. */
    public static final RetryPolicy DEFAULT = new RetryPolicy(2, 0, false, 0, true, true, Set.of());

    public RetryPolicy {
        if (maxAttempts < 1 || maxAttempts > 10) {
            throw new IllegalArgumentException("Versuche müssen zwischen 1 und 10 liegen, nicht " + maxAttempts);
        }
        if (backoffMillis < 0 || maxBackoffMillis < 0) {
            throw new IllegalArgumentException("Wartezeiten dürfen nicht negativ sein");
        }
        replyCodes = replyCodes == null ? Set.of() : Set.copyOf(replyCodes);
    }

    /**
     * Ob nach dem Fehler des Versuchs {@code attempt} (ab 1) noch einmal versucht wird.
     *
     * @param error der Fehler; ein {@link FtpReplyException} zählt nur mit einem der Antwortcodes
     */
    public boolean shouldRetry(Throwable error, int attempt) {
        if (attempt >= maxAttempts) {
            return false;
        }
        if (error instanceof LoginRefusedException) {
            // Abgelehnte Anmeldung nie wiederholen, auch nicht mit 530 in der Liste: Schutz vor der Kontosperre.
            return false;
        }
        if (error instanceof FtpReplyException refused) {
            return refused.code() > 0 && replyCodes.contains(refused.code());
        }
        if (timeout(error)) {
            return onTimeout;
        }
        return onConnectionError && error instanceof IOException;
    }

    /** Wartezeit vor dem Versuch nach {@code attempt} (ab 1). */
    public long delayMillis(int attempt) {
        if (backoffMillis <= 0) {
            return 0;
        }
        long delay = exponential ? (long) backoffMillis << Math.min(20, Math.max(0, attempt - 1)) : backoffMillis;
        return maxBackoffMillis > 0 ? Math.min(delay, maxBackoffMillis) : delay;
    }

    /** Antwortcodes aus „421, 425,426“; Ungültiges fällt weg wie in MainframeMate. */
    public static Set<Integer> parseCodes(String text) {
        Set<Integer> codes = new TreeSet<>();
        if (text == null) {
            return codes;
        }
        for (String part : text.split("[,;\\s]+")) {
            try {
                int code = Integer.parseInt(part.trim());
                if (code > 0) {
                    codes.add(code);
                }
            } catch (NumberFormatException ignored) {
                // wie MainframeMate: überspringen
            }
        }
        return codes;
    }

    /** Die Codes als „421, 425“. */
    public String codesText() {
        return replyCodes.stream().sorted().map(String::valueOf).collect(Collectors.joining(", "));
    }

    static boolean timeout(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SocketTimeoutException) {
                return true;
            }
            if (t instanceof InterruptedIOException && t.getMessage() != null
                    && t.getMessage().toLowerCase(Locale.ROOT).contains("timed out")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "RetryPolicy" + Arrays.asList(maxAttempts, backoffMillis, exponential, maxBackoffMillis, onTimeout,
                onConnectionError, codesText());
    }
}
