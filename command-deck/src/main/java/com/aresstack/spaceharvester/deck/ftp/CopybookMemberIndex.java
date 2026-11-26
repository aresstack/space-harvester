package com.aresstack.spaceharvester.deck.ftp;

import com.aresstack.spaceharvester.deck.editor.DocumentException;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/** Cache successful PDS listings, including empty ones, for one FTP document store. */
final class CopybookMemberIndex {
    static final long TTL_NANOS = Duration.ofSeconds(60).toNanos();

    @FunctionalInterface
    interface Listing {
        List<RemoteEntry> list(String dataset) throws DocumentException;
    }

    private record Snapshot(Map<String, String> members, long loadedAt) { }

    private final LongSupplier clock;
    private final Listing listing;
    private final Map<String, Snapshot> snapshots = new LinkedHashMap<>(16, 0.75f, true);

    CopybookMemberIndex(LongSupplier clock, Listing listing) {
        this.clock = clock;
        this.listing = listing;
    }

    synchronized String find(String dataset, String member) throws DocumentException {
        String key = MvsPath.unquote(dataset).toUpperCase(Locale.ROOT);
        Snapshot snapshot = snapshots.get(key);
        if (snapshot == null || clock.getAsLong() - snapshot.loadedAt() >= TTL_NANOS) {
            snapshots.remove(key);
            Map<String, String> members = new HashMap<>();
            for (RemoteEntry entry : listing.list(key)) {
                if (!entry.directory()) {
                    members.putIfAbsent(entry.name().toUpperCase(Locale.ROOT), entry.path());
                }
            }
            snapshot = new Snapshot(Map.copyOf(members), clock.getAsLong());
            snapshots.put(key, snapshot);
            if (snapshots.size() > 128) {
                snapshots.remove(snapshots.keySet().iterator().next());
            }
        }
        return snapshot.members().get(member.toUpperCase(Locale.ROOT));
    }

    synchronized void invalidate(String location) {
        if (MvsPath.isMember(location)) {
            snapshots.remove(MvsPath.splitMember(location)[0].toUpperCase(Locale.ROOT));
        }
    }
}
