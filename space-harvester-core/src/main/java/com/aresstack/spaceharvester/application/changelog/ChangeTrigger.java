package com.aresstack.spaceharvester.application.changelog;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Was eine Änderung im Cache ausgelöst hat (ADR 0030, Abschnitt 1). Der Auslöser gilt je Thread für die Dauer von
 * {@link #run}/{@link #call}; der Schreiblauf des Cache übernimmt ihn bei seinem Beginn. Wechselt die Arbeit den
 * Thread, nimmt {@link #carry} ihn mit. Ohne gesetzten Auslöser gilt {@link #UNKNOWN}, nie still
 * {@link #BACKGROUND}: Ein vergessener Weg soll auffallen.
 */
public enum ChangeTrigger {
    /** Vom Benutzer gestartete Synchronisierung. */
    MANUAL,
    /** Hintergrundfüllung (ADR 0016). */
    BACKGROUND,
    /** Nachholen beim Start. */
    STARTUP,
    /** Zeitplan. */
    SCHEDULE,
    /** Suche, auch der Abgleich vor der Suche. */
    SEARCH,
    /** Lesen einer Seite. */
    READ,
    /** Änderungsereignis der Quelle (Webhook). */
    EVENT,
    /** „Nur Fehler neu versuchen“. */
    RETRY,
    /** Export samt Bildern und Anhängen. */
    EXPORT,
    /** Kein Auslöser gesetzt; in einem bekannten Weg ein Fehler. */
    UNKNOWN;

    /** Angabe am Auftrag ({@code JobStatus#parameters()}), übersteht Anhalten, Fortsetzen und Neustart. */
    public static final String PARAMETER = "trigger";

    private static final ThreadLocal<ChangeTrigger> CURRENT = new ThreadLocal<>();

    /** Der Auslöser des aufrufenden Threads, {@link #UNKNOWN} ohne Angabe. */
    public static ChangeTrigger current() {
        ChangeTrigger trigger = CURRENT.get();
        return trigger == null ? UNKNOWN : trigger;
    }

    /** Führt {@code work} mit diesem Auslöser aus und stellt danach den vorigen wieder her. */
    public void run(Runnable work) {
        Objects.requireNonNull(work, "work");
        call(() -> {
            work.run();
            return null;
        });
    }

    /** Wie {@link #run}, mit Ergebnis. */
    public <T> T call(Supplier<T> work) {
        Objects.requireNonNull(work, "work");
        ChangeTrigger previous = CURRENT.get();
        CURRENT.set(this);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** {@code work} mit dem jetzt gültigen Auslöser, für einen anderen Thread. */
    public static Runnable carry(Runnable work) {
        Objects.requireNonNull(work, "work");
        ChangeTrigger captured = current();
        return () -> captured.run(work);
    }

    /** Name in Auftragsangaben und REST, klein geschrieben. */
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Liest {@link #value()} oder den Namen; {@link #UNKNOWN} für fehlende oder unbekannte Werte. */
    public static ChangeTrigger parse(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        for (ChangeTrigger trigger : values()) {
            if (trigger.name().equals(normalized)) {
                return trigger;
            }
        }
        return UNKNOWN;
    }
}
