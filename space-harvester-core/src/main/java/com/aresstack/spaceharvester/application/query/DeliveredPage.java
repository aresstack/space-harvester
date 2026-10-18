package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sync.PageFreshness;
import java.time.Instant;
import java.util.Objects;

/**
 * Eine Einzelseite, wie die API sie ausliefert: nach der Prüfung gegen die Quelle (ADR 0010, Punkt 8) aus dem Cache oder
 * neu geholt; nur wenn die Quelle für die Prüfung nicht erreichbar ist, ungeprüft aus dem Cache.
 *
 * @param header     gültige Kopfdaten
 * @param snapshot   auszuliefernde Payload
 * @param generation Cache-Generation, aus der die Seite stammt, oder {@code null}, wenn sie nicht im Cache liegt
 * @param live       {@code true}, wenn Kopfdaten oder Payload live aus der Quelle stammen und vom Cache abweichen
 * @param refresh    Ergebnis der Prüfung gegen die Quelle oder {@code null}, wenn ungeprüft aus dem Cache ausgeliefert
 * @param checkedAt  Zeitpunkt der Prüfung oder {@code null}, wenn ungeprüft aus dem Cache ausgeliefert
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

    /** Ungeprüft aus dem Cache, wenn die Quelle für die Prüfung nicht erreichbar ist. */
    public static DeliveredPage cached(CachedPage page) {
        return new DeliveredPage(page.header(), page.snapshot(), page.generation(), false, null, null);
    }
}
