package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sync.PageFreshness;
import java.time.Instant;
import java.util.Objects;

/**
 * Eine Einzelseite, wie die API sie ausliefert: aus dem Cache oder nach {@code refresh=true} aus der Prüfung gegen die
 * Quelle.
 *
 * @param header     gültige Kopfdaten
 * @param snapshot   auszuliefernde Payload
 * @param generation Cache-Generation, aus der die Seite stammt, oder {@code null}, wenn sie nicht im Cache liegt
 * @param live       {@code true}, wenn Kopfdaten oder Payload live aus der Quelle stammen und vom Cache abweichen
 * @param refresh    Ergebnis der Prüfung gegen die Quelle oder {@code null} ohne {@code refresh=true}
 * @param checkedAt  Zeitpunkt der Prüfung oder {@code null} ohne {@code refresh=true}
 */
public record DeliveredPage(PageHeader header, PageSnapshot snapshot, CacheGeneration generation, boolean live,
                            PageFreshness.Outcome refresh, Instant checkedAt) {

    public DeliveredPage {
        Objects.requireNonNull(header, "header");
        Objects.requireNonNull(snapshot, "snapshot");
        if ((refresh == null) != (checkedAt == null)) {
            throw new IllegalArgumentException("refresh und checkedAt nur gemeinsam");
        }
    }

    /** Aus dem Cache, ohne Prüfung gegen die Quelle. */
    public static DeliveredPage cached(CachedPage page) {
        return new DeliveredPage(page.header(), page.snapshot(), page.generation(), false, null, null);
    }
}
