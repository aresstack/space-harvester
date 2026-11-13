package com.aresstack.spaceharvester.application.assets;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Vergibt die Teilkennungen eines äußeren Behälters (ADR 0026, Punkt 5): inhaltsgleiche Teile bekommen in
 * Lesereihenfolge über den ganzen äußeren Behälter die laufenden Nummern 1, 2, …; Teile ohne vollständig gelesene Bytes
 * ihre Position, gültig für diesen Stand des Behälters. Eine Instanz je Extraktion eines äußeren Behälters, nicht
 * threadsicher.
 */
public final class PartIdNumbering {

    private final String containerSha256;
    private final Map<String, Integer> seen = new HashMap<>();

    /** @param containerSha256 SHA-256 des äußeren Behälters (dieser Stand) */
    public PartIdNumbering(String containerSha256) {
        // Prüft das Format wie die Kennung nach Position.
        this.containerSha256 = new PartId.Position(Objects.requireNonNull(containerSha256, "containerSha256"), "/1")
                .containerSha256();
    }

    /** Kennung eines vollständig gelesenen Teils. */
    public PartId.Content content(String sha256) {
        String key = new PartId.Content(sha256, 1).sha256();
        int ordinal = seen.merge(key, 1, Integer::sum);
        return new PartId.Content(key, ordinal);
    }

    /** Kennung eines nicht vollständig gelesenen Teils an seiner Position wie {@code /1/3}. */
    public PartId.Position position(String path) {
        return new PartId.Position(containerSha256, path);
    }
}
