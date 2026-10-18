package com.aresstack.spaceharvester.application.configuration;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Konfiguration zur Laufzeit (ADR 0015): liest die wirksame Konfiguration, prüft eine neue, lässt sie von den
 * {@link ConfigurationListener Beobachtern} vorbereiten und aktiviert sie atomar. Neue Aufgaben nehmen danach die
 * neue Konfiguration; laufende behalten die, mit der sie begonnen haben. Lehnt ein Beobachter ab, wird alles
 * zurückgerollt und die bisherige bleibt wirksam.
 *
 * <p>Frameworkneutral: Server und App übergeben eine {@link HarvesterConfiguration}; eine geänderte Datei allein löst
 * nichts aus. Nicht zur Laufzeit änderbar ist nur der Speicherort ({@link StorageSettings}); eine Änderung daran
 * meldet {@link Activation#restartRequired()} und wirkt nach dem nächsten Start.
 *
 * <p>Aktivierungen laufen nacheinander; {@link #current()} ist jederzeit ohne Sperre lesbar.
 */
public final class ConfigurationManager {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigurationManager.class);

    private final Clock clock;
    private final List<ConfigurationListener> listeners = new CopyOnWriteArrayList<>();
    private final Object activation = new Object();
    private volatile ConfigurationSnapshot current;

    /**
     * @param initial Startkonfiguration (Version 1)
     * @throws ConfigurationRejectedException wenn sie ungültig ist
     */
    public ConfigurationManager(HarvesterConfiguration initial) {
        this(initial, Clock.systemUTC());
    }

    public ConfigurationManager(HarvesterConfiguration initial, Clock clock) {
        Objects.requireNonNull(initial, "initial");
        this.clock = Objects.requireNonNull(clock, "clock");
        List<ConfigurationProblem> problems = initial.validate();
        if (!problems.isEmpty()) {
            throw new ConfigurationRejectedException(problems);
        }
        this.current = new ConfigurationSnapshot(1, initial, clock.instant());
    }

    /** Die wirksame Konfiguration; wer eine Aufgabe beginnt, behält diesen Schnappschuss bis zu ihrem Ende. */
    public ConfigurationSnapshot current() {
        return current;
    }

    /**
     * Prüft eine Konfiguration, ohne etwas zu ändern oder Beobachter zu fragen, etwa für die Oberfläche beim Tippen.
     *
     * @return leer, wenn sie aktiviert werden kann
     */
    public List<ConfigurationProblem> validate(HarvesterConfiguration next) {
        return Objects.requireNonNull(next, "next").validate();
    }

    /** Welche geänderten Werte erst nach einem Neustart wirken, gemessen an der wirksamen Konfiguration. */
    public List<String> restartRequired(HarvesterConfiguration next) {
        return Objects.equals(next.storage(), current.configuration().storage()) ? List.of()
                : List.of(StorageSettings.DATA_DIRECTORY);
    }

    /**
     * Prüft, bereitet vor und aktiviert. Bei Ablehnung bleibt die bisherige Konfiguration ohne Nebenwirkung wirksam.
     *
     * @return Ergebnis mit dem danach wirksamen Schnappschuss
     */
    public Activation apply(HarvesterConfiguration next) {
        Objects.requireNonNull(next, "next");
        synchronized (activation) {
            ConfigurationSnapshot before = current;
            List<ConfigurationProblem> problems = next.validate();
            if (!problems.isEmpty()) {
                return Activation.rejected(before, problems);
            }
            List<String> restart = restartRequired(next);
            HarvesterConfiguration effective = next.withStorage(before.configuration().storage());
            if (effective.equals(before.configuration())) {
                return new Activation(Outcome.UNCHANGED, before, List.of(), restart);
            }
            ConfigurationChange change = new ConfigurationChange(before.configuration(), effective,
                    before.version() + 1);
            List<ConfigurationListener.Preparation> prepared = new ArrayList<>();
            for (ConfigurationListener listener : listeners) {
                try {
                    prepared.add(Objects.requireNonNull(listener.prepare(change), "preparation"));
                } catch (ConfigurationRejectedException e) {
                    problems = e.problems();
                } catch (RuntimeException e) {
                    // Die Meldung kann Adressen oder Werte enthalten; nach außen nur die Art des Fehlers.
                    LOG.warn("Vorbereitung der Konfiguration {} gescheitert: {}", change.version(),
                            e.getClass().getName());
                    problems = List.of(new ConfigurationProblem("configuration",
                            "Eine Komponente konnte die neue Konfiguration nicht vorbereiten ("
                                    + e.getClass().getSimpleName() + ")"));
                }
                if (!problems.isEmpty()) {
                    rollback(prepared);
                    LOG.info("Konfiguration {} abgelehnt, Version {} bleibt wirksam", change.version(),
                            before.version());
                    return Activation.rejected(before, problems);
                }
            }
            ConfigurationSnapshot after = new ConfigurationSnapshot(change.version(), effective, clock.instant());
            current = after;
            for (ConfigurationListener.Preparation preparation : prepared) {
                try {
                    preparation.commit();
                } catch (RuntimeException e) {
                    LOG.error("Austausch zur Konfiguration {} gescheitert: {}", after.version(),
                            e.getClass().getName());
                }
            }
            LOG.info("Konfiguration {} aktiv", after.version());
            return new Activation(Outcome.ACTIVATED, after, List.of(), restart);
        }
    }

    /**
     * Meldet einen Beobachter an, der bei jedem künftigen Wechsel gefragt wird; er baut seine ersten Instanzen selbst
     * aus {@link #current()}.
     *
     * @return schließen, um ihn abzumelden
     */
    public Registration register(ConfigurationListener listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private static void rollback(List<ConfigurationListener.Preparation> prepared) {
        for (int i = prepared.size() - 1; i >= 0; i--) {
            try {
                prepared.get(i).rollback();
            } catch (RuntimeException e) {
                LOG.warn("Zurückrollen gescheitert: {}", e.getClass().getName());
            }
        }
    }

    /** Abmeldung eines Beobachters. */
    @FunctionalInterface
    public interface Registration extends AutoCloseable {

        @Override
        void close();
    }

    public enum Outcome {
        /** Neue Version wirksam. */
        ACTIVATED,
        /** Nichts geändert, die bisherige Version bleibt (gegebenenfalls bis auf Werte, die einen Neustart brauchen). */
        UNCHANGED,
        /** Ungültig oder von einem Beobachter abgelehnt; die bisherige Version bleibt. */
        REJECTED
    }

    /**
     * Ergebnis von {@link #apply}.
     *
     * @param outcome         was geschah
     * @param snapshot        der danach wirksame Schnappschuss
     * @param problems        Gründe der Ablehnung, sonst leer
     * @param restartRequired Schlüssel geänderter Werte, die erst nach einem Neustart wirken
     */
    public record Activation(Outcome outcome, ConfigurationSnapshot snapshot, List<ConfigurationProblem> problems,
                             List<String> restartRequired) {

        public Activation {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(snapshot, "snapshot");
            problems = List.copyOf(problems);
            restartRequired = List.copyOf(restartRequired);
        }

        static Activation rejected(ConfigurationSnapshot snapshot, List<ConfigurationProblem> problems) {
            return new Activation(Outcome.REJECTED, snapshot, problems, List.of());
        }

        public boolean activated() {
            return outcome == Outcome.ACTIVATED;
        }
    }
}
