package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.render.RenderedPageDocument;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The bounded, one-shot cache the model-free sidecar uses to hold a low-confidence
 * {@code RenderedPageDocument} between {@code web_search_prepare} and a later
 * {@code web_search_apply_layout}. Bounded by a maximum entry count and a TTL; oldest entries are
 * evicted first; an attempt may be consumed exactly once. Cleanup happens on discard, on consume, on
 * clear (session close / browser recovery) and lazily on access. This is a per-session instance, never
 * a global static map. Time is passed in so the sidecar controls the clock.
 */
public final class SearchLayoutRepairCache {

    /** One cached low-confidence snapshot with its engine metadata and lifetime bounds. */
    public static final class Entry {
        public final String attemptId;
        public final RenderedPageDocument document;
        public final String query;
        public final String engineHost;
        public final String analysisId;
        public final String layoutStructureFingerprint;
        public final String settingsDigest;
        public final long createdAtEpochMillis;
        public final long expiresAtEpochMillis;
        boolean consumed;

        Entry(String attemptId, RenderedPageDocument document, String query, String engineHost,
              String analysisId, String layoutStructureFingerprint, String settingsDigest,
              long createdAtEpochMillis, long expiresAtEpochMillis) {
            this.attemptId = attemptId;
            this.document = document;
            this.query = query;
            this.engineHost = engineHost;
            this.analysisId = analysisId == null ? "" : analysisId;
            this.layoutStructureFingerprint =
                    layoutStructureFingerprint == null ? "" : layoutStructureFingerprint;
            this.settingsDigest = settingsDigest == null ? "" : settingsDigest;
            this.createdAtEpochMillis = createdAtEpochMillis;
            this.expiresAtEpochMillis = expiresAtEpochMillis;
        }

        boolean expired(long nowEpochMillis) {
            return nowEpochMillis >= expiresAtEpochMillis;
        }
    }

    private final int maximumEntries;
    private final long ttlMillis;
    private final LinkedHashMap<String, Entry> byId = new LinkedHashMap<String, Entry>();

    public SearchLayoutRepairCache(int maximumEntries, long ttlMillis) {
        this.maximumEntries = Math.max(1, maximumEntries);
        this.ttlMillis = Math.max(1, ttlMillis);
    }

    public synchronized Entry put(String attemptId, RenderedPageDocument document, String query,
                                  String engineHost, String analysisId,
                                  String layoutStructureFingerprint, String settingsDigest,
                                  long nowEpochMillis) {
        removeExpired(nowEpochMillis);
        Entry entry = new Entry(attemptId, document, query, engineHost, analysisId,
                layoutStructureFingerprint, settingsDigest, nowEpochMillis,
                nowEpochMillis + ttlMillis);
        byId.remove(attemptId);
        byId.put(attemptId, entry);
        evictOldestBeyondCapacity();
        return entry;
    }

    /** The entry for the id, or null when absent. Does not mutate consumed/expiry state. */
    public synchronized Entry find(String attemptId) {
        return byId.get(attemptId);
    }

    public synchronized boolean isExpired(Entry entry, long nowEpochMillis) {
        return entry.expired(nowEpochMillis);
    }

    public synchronized boolean isConsumed(Entry entry) {
        return entry.consumed;
    }

    /** Mark the attempt terminally consumed and drop it — an attempt is applied at most once. */
    public synchronized void consume(String attemptId) {
        Entry entry = byId.remove(attemptId);
        if (entry != null) {
            entry.consumed = true;
        }
    }

    public synchronized void discard(String attemptId) {
        byId.remove(attemptId);
    }

    /** Cleanup for session close and browser recovery. */
    public synchronized void clear() {
        byId.clear();
    }

    public synchronized int size() {
        return byId.size();
    }

    public synchronized void removeExpired(long nowEpochMillis) {
        for (Iterator<Map.Entry<String, Entry>> it = byId.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue().expired(nowEpochMillis)) {
                it.remove();
            }
        }
    }

    private void evictOldestBeyondCapacity() {
        List<String> ids = new ArrayList<String>(byId.keySet());
        int overflow = ids.size() - maximumEntries;
        for (int i = 0; i < overflow; i++) {
            byId.remove(ids.get(i)); // LinkedHashMap keeps insertion order: oldest first
        }
    }
}
