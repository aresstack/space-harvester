package com.aresstack.spaceharvester.application.assets;

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

    private final Object lock = new Object();
    private final Set<Lease> leases = new LinkedHashSet<>();
    private int total;
    private int inFlight;

    /** @param total Verbindungen aller Downloads zusammen, 0 bis {@value JobLimits#MAX_DOWNLOAD_CONNECTIONS} */
    public ConnectionBudget(int total) {
        this.total = JobLimits.requireConnections(total);
    }

    /** Verbindungen aller Downloads zusammen. */
    public int total() {
        synchronized (lock) {
            return total;
        }
    }

    /** Ändert das Budget sofort; laufende Dateien laufen zu Ende. */
    public void total(int value) {
        synchronized (lock) {
            total = JobLimits.requireConnections(value);
            lock.notifyAll();
        }
    }

    /** Wartet, bis das Budget wieder Verbindungen hat; {@code checkpoint} läuft zwischen den Wartezeiten. */
    public void awaitAvailable(Runnable checkpoint) {
        synchronized (lock) {
            while (total == 0) {
                checkpoint.run();
                try {
                    lock.wait(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Warten auf eine Verbindung unterbrochen", e);
                }
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
        synchronized (lock) {
            leases.add(lease);
            lock.notifyAll();
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
         * Verbindung. {@code checkpoint} läuft zwischen den Wartezeiten (Anhalten, Abbrechen).
         */
        public void acquire(Runnable checkpoint) {
            synchronized (lock) {
                while (inFlight >= total || running >= ConnectionBudget.this.share(this)) {
                    checkpoint.run();
                    try {
                        lock.wait(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Warten auf eine Verbindung unterbrochen", e);
                    }
                }
                running++;
                inFlight++;
            }
        }

        /** Gibt eine mit {@link #acquire} belegte Verbindung frei. */
        public void release() {
            synchronized (lock) {
                running--;
                inFlight--;
                lock.notifyAll();
            }
        }

        /** Das Budget, aus dem dieser Auftrag lädt. */
        public ConnectionBudget budget() {
            return ConnectionBudget.this;
        }

        /** Der aktuelle Anteil dieses Auftrags am Budget. */
        public int share() {
            synchronized (lock) {
                return ConnectionBudget.this.share(this);
            }
        }

        @Override
        public void close() {
            synchronized (lock) {
                leases.remove(this);
                lock.notifyAll();
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
