package com.aresstack.spaceharvester.testcorpus;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.time.Duration;

/**
 * Hilfen für die Grenzfälle (ADR 0026, Abnahme 4): je Grenze ein Fall knapp darunter, genau darauf und darüber, und
 * ein Strom, der nach einer Anzahl Bytes stockt (Laufzeit ohne Fortschritt).
 */
public final class Limits {

    private Limits() {
    }

    /** {@code limit - 1}, {@code limit}, {@code limit + 1}. */
    public static long[] around(long limit) {
        return new long[] {limit - 1, limit, limit + 1};
    }

    /** {@code limit - 1}, {@code limit}, {@code limit + 1} für Zähler. */
    public static int[] around(int limit) {
        return new int[] {limit - 1, limit, limit + 1};
    }

    /**
     * Liefert die ersten {@code before} Bytes von {@code in} sofort und stockt dann für {@code stall}, bevor der Rest
     * folgt. Ein Unterbrechen des lesenden Threads beendet das Warten mit {@link InterruptedIOException}.
     */
    public static InputStream stalling(InputStream in, long before, Duration stall) {
        return new FilterInputStream(in) {
            private long delivered;
            private boolean stalled;

            @Override
            public int read() throws IOException {
                pause();
                int b = super.read();
                if (b >= 0) {
                    delivered++;
                }
                return b;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                if (length == 0) {
                    return 0;
                }
                pause();
                int allowed = stalled ? length : (int) Math.max(1, Math.min(length, before - delivered));
                int n = super.read(buffer, offset, allowed);
                if (n > 0) {
                    delivered += n;
                }
                return n;
            }

            private void pause() throws IOException {
                if (stalled || delivered < before) {
                    return;
                }
                stalled = true;
                try {
                    Thread.sleep(stall.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Lesen unterbrochen");
                }
            }
        };
    }
}
