package com.aresstack.spaceharvester.application.assets;

import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Gesamtbudget gleichzeitiger Verbindungen aller ladenden Bilder- und Anhängeaufträge ({@code jobs.download-connections},
 * Angelo 11.11.2026): Lädt nur einer, bekommt er alle Verbindungen; laden mehrere, teilt sich das Budget nach ihrer
 * geschätzten Restzeit, damit möglichst alle zugleich fertig werden. Die Anteile werden bei jeder Datei neu berechnet:
 * ein Auftrag, dessen Restzeit wächst oder langsamer sinkt, bekommt laufend mehr Verbindungen.
 *
 * <p>Jeder Auftrag hat mindestens einen Anteil; insgesamt laufen nie mehr Dateien als das Budget. Bei 0 lädt kein
 * Auftrag; sie warten, ohne Fehler zu zählen, bis wieder Verbindungen da sind (Angelo 11.11.2026). Textdownloads
 * gehören nicht dazu und laden weiter mit einer Verbindung.
 */
public final class ConnectionBudget {

    /**
     * {@link java.util.concurrent.locks.ReentrantLock} statt {@code synchronized} (Architekt 22.11.2026): Wartende
     * Aufträge laufen auf virtuellen Threads und geben beim Warten ihren Träger frei.
     */
    private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
    /** Budget, Anmeldung oder Freigabe geändert. */
    private final java.util.concurrent.locks.Condition changed = lock.newCondition();
    private final Set<Lease> leases = new LinkedHashSet<>();
    private int total;
    private int inFlight;
    /** Frist des Rückfallnetzes beim Warten auf eine Verbindung, siehe {@link Lease#acquire}. */
    private final long fallbackNanos;

    /** @param total Verbindungen aller Downloads zusammen, 0 bis {@value JobLimits#MAX_DOWNLOAD_CONNECTIONS} */
    public ConnectionBudget(int total) {
        this(total, java.time.Duration.ofMillis(200));
    }

    /** Mit eigener Frist des Rückfallnetzes; Tests setzen sie lang, damit nur ein Signal weckt. */
    ConnectionBudget(int total, java.time.Duration fallback) {
        this.total = JobLimits.requireConnections(total);
        this.fallbackNanos = fallback.toNanos();
    }

    /** Verbindungen aller Downloads zusammen. */
    public int total() {
        lock.lock();
        try {
            return total;
        } finally {
            lock.unlock();
        }
    }

    /** Ändert das Budget sofort; laufende Dateien laufen zu Ende. */
    public void total(int value) {
        lock.lock();
        try {
            total = JobLimits.requireConnections(value);
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Wartet ohne Takt (ADR 0027), bis das Budget wieder Verbindungen hat: geweckt wird nur durch eine Änderung des
     * Budgets und über {@link JobContext#whenStopRequested} (Anhalten, Abbrechen und der Ablauf der Laufzeitgrenze,
     * den {@code Jobs} mit einem einmaligen Zeitgeber meldet); danach prüft {@link JobContext#checkpoint()}.
     */
    public void awaitAvailable(JobContext context) {
        try (Wake wake = new Wake(context)) {
            lock.lock();
            try {
                while (true) {
                    wake.checkpoint();
                    if (total != 0) {
                        return;
                    }
                    try {
                        changed.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Warten auf eine Verbindung unterbrochen", e);
                    }
                }
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Weckt die Wartenden dieses Budgets, wenn der Auftrag anhalten oder abbrechen soll. Der Prüfpunkt läuft unter
     * {@link #lock}; er blockiert nicht.
     * <p>
     * Sperrreihenfolge (Abnahme #1133), bitte so lassen: Der Stopp-Rückruf läuft unter der Sperre der Aufträge und
     * nimmt danach {@link #lock}; {@link #checkpoint()} läuft unter {@link #lock} und darf darum nie eine Sperre der
     * Aufträge nehmen (nur Flag und Frist lesen), sonst drohte eine Verklemmung.
     */
    private final class Wake implements AutoCloseable {

        private final JobContext context;
        private final AutoCloseable registration;

        Wake(JobContext context) {
            this.context = java.util.Objects.requireNonNull(context, "context");
            this.registration = context.whenStopRequested(() -> {
                lock.lock();
                try {
                    changed.signalAll();
                } finally {
                    lock.unlock();
                }
            });
        }

        void checkpoint() {
            context.checkpoint();
        }

        @Override
        public void close() {
            try {
                registration.close();
            } catch (Exception e) {
                throw new IllegalStateException("Abmelden vom Auftrag gescheitert", e);
            }
        }
    }

    /**
     * Meldet einen ladenden Auftrag an.
     *
     * @param remaining geschätzte Restzeit in Millisekunden (etwa offene Dateien mal Durchschnittsdauer); wird bei jeder
     *                  Verteilung neu gefragt und darf nicht blockieren
     */
    public Lease register(LongSupplier remaining) {
        Lease lease = new Lease(remaining);
        lock.lock();
        try {
            leases.add(lease);
            changed.signalAll();
        } finally {
            lock.unlock();
        }
        return lease;
    }

    /** Ein ladender Auftrag; {@link #close()} meldet ihn ab und gibt seinen Anteil frei. */
    public final class Lease implements AutoCloseable {

        private final LongSupplier remaining;
        private int running;

        private Lease(LongSupplier remaining) {
            this.remaining = remaining;
        }

        /**
         * Wartet, bis dieser Auftrag unter seinem Anteil und das Budget nicht ausgeschöpft ist, und belegt dann eine
         * Verbindung. Geweckt wird durch Freigeben, An- und Abmelden, eine Änderung des Budgets und eine Anforderung
         * zum Anhalten oder Abbrechen; danach prüft {@link JobContext#checkpoint()}.
         */
        public void acquire(JobContext context) {
            try (Wake wake = new Wake(context)) {
                lock.lock();
                try {
                    while (true) {
                        wake.checkpoint();
                        if (inFlight < total && running < ConnectionBudget.this.share(this)) {
                            break;
                        }
                        try {
                            // Rückfallnetz, kein Takt (ADR 0027): Freigeben, Zuteilen (An- und Abmelden, Budget),
                            // Anhalten, Abbrechen und die Laufzeitgrenze wecken sofort. Die Frist greift nur für die
                            // Verschiebung der Anteile nach Restzeit: die Schätzungen der Aufträge ändern sich ohne
                            // Meldung.
                            changed.awaitNanos(fallbackNanos);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Warten auf eine Verbindung unterbrochen", e);
                        }
                    }
                    running++;
                    inFlight++;
                } finally {
                    lock.unlock();
                }
            }
        }

        /** Gibt eine mit {@link #acquire} belegte Verbindung frei. */
        public void release() {
            lock.lock();
            try {
                running--;
                inFlight--;
                changed.signalAll();
            } finally {
                lock.unlock();
            }
        }

        /** Das Budget, aus dem dieser Auftrag lädt. */
        public ConnectionBudget budget() {
            return ConnectionBudget.this;
        }

        /** Der aktuelle Anteil dieses Auftrags am Budget. */
        public int share() {
            lock.lock();
            try {
                return ConnectionBudget.this.share(this);
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void close() {
            lock.lock();
            try {
                leases.remove(this);
                changed.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    /** Anteil von {@code lease}: nach Restzeit verteilt, größte Reste zuerst, jeder mindestens 1. Unter {@link #lock}. */
    private int share(Lease lease) {
        if (!leases.contains(lease)) {
            return 0;
        }
        List<Lease> all = new ArrayList<>(leases);
        if (all.size() == 1 || total == 0) {
            return total;
        }
        long[] weights = new long[all.size()];
        long sum = 0;
        for (int i = 0; i < weights.length; i++) {
            // Ohne Schätzung (oder fertig bis auf Laufendes) zählt ein Auftrag wie der kleinste denkbare.
            weights[i] = Math.max(1, safe(all.get(i).remaining));
            sum += weights[i];
        }
        int[] shares = new int[weights.length];
        double[] rests = new double[weights.length];
        int given = 0;
        for (int i = 0; i < weights.length; i++) {
            double exact = (double) total * weights[i] / sum;
            shares[i] = Math.max(1, (int) Math.floor(exact));
            rests[i] = exact - Math.floor(exact);
            given += shares[i];
        }
        // Die Mindestanteile können das Budget überziehen: dann geben die größten Anteile ab, solange sie mehr als 1 haben.
        while (given > total) {
            int largest = -1;
            for (int i = 0; i < shares.length; i++) {
                if (shares[i] > 1 && (largest < 0 || shares[i] > shares[largest])) {
                    largest = i;
                }
            }
            if (largest < 0) {
                break;
            }
            shares[largest]--;
            given--;
        }
        while (given < total) {
            int best = 0;
            for (int i = 1; i < rests.length; i++) {
                if (rests[i] > rests[best]) {
                    best = i;
                }
            }
            shares[best]++;
            rests[best] = -1;
            given++;
            if (Arrays.stream(rests).allMatch(rest -> rest < 0)) {
                break;
            }
        }
        return shares[all.indexOf(lease)];
    }

    private static long safe(LongSupplier remaining) {
        try {
            return remaining.getAsLong();
        } catch (RuntimeException e) {
            return 1;
        }
    }
}
