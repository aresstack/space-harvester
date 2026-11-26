package com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/** Retain only recent, unused metadata from the current session and inventory. */
final class NdvInventoryMetadata {

    private final int capacity;
    private final long maxAgeNanos;
    private final LongSupplier nanoTime;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    NdvInventoryMetadata(int capacity, long maxAgeNanos, LongSupplier nanoTime) {
        this.capacity = capacity;
        this.maxAgeNanos = maxAgeNanos;
        this.nanoTime = nanoTime;
    }

    void remember(String path, NdvObjectInfo object) {
        entries.put(key(path), new Entry(object, nanoTime.getAsLong()));
        if (entries.size() > capacity) {
            entries.remove(entries.keySet().iterator().next());
        }
    }

    NdvObjectInfo take(String path) {
        Entry entry = entries.remove(key(path));
        if (entry == null || nanoTime.getAsLong() - entry.observedAt >= maxAgeNanos) {
            return null;
        }
        return entry.object;
    }

    void clear() {
        entries.clear();
    }

    private static String key(String path) {
        return path.toUpperCase(Locale.ROOT);
    }

    private static final class Entry {
        private final NdvObjectInfo object;
        private final long observedAt;

        private Entry(NdvObjectInfo object, long observedAt) {
            this.object = object;
            this.observedAt = observedAt;
        }
    }
}
