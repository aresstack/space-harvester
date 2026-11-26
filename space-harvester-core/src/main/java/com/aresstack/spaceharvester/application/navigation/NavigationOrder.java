package com.aresstack.spaceharvester.application.navigation;

import com.aresstack.spaceharvester.application.query.PageCacheReader;
import com.aresstack.spaceharvester.application.query.PageCacheReader.ChildOrderState;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Maintain an optional display hint without retaining individual navigation sessions. */
final class NavigationOrder {
    private final PageCacheReader cache;
    private final NavigationContinuation continuation = new NavigationContinuation();

    NavigationOrder(PageCacheReader cache) {
        this.cache = cache;
    }

    Request prepare(String sourceId, String spaceKey, String parentId, String cursor, boolean groups) {
        if (cache == null || groups) {
            return new Request(sourceId, spaceKey, parentId, cursor, false, 0, null);
        }
        boolean first = cursor == null || cursor.isEmpty();
        NavigationContinuation.State state = first
                ? new NavigationContinuation.State(null, 0, null)
                : continuation.decode(sourceId, node(spaceKey, parentId), cursor);
        ChildOrderState snapshot = first || state.revision != null
                ? readOrder(sourceId, spaceKey, parentId) : null;
        if (!first && snapshot != null && (!Objects.equals(state.revision, snapshot.revision())
                || state.confirmedCount > snapshot.pageIds().size())) {
            snapshot = null;
        }
        return new Request(sourceId, spaceKey, parentId, state.sourceCursor, true, state.confirmedCount, snapshot);
    }

    String complete(Request request, HarvestSource.ChildPages batch) {
        if (!request.wrapped) {
            return batch.nextCursor();
        }
        NavigationContinuation.State state = updateOrder(request, batch);
        return batch.nextCursor() == null ? null
                : continuation.encode(request.sourceId, node(request.spaceKey, request.parentId), state);
    }

    private ChildOrderState readOrder(String sourceId, String spaceKey, String parentId) {
        try {
            return cache.childOrderState(sourceId, spaceKey, parentId).orElse(null);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private NavigationContinuation.State updateOrder(Request request, HarvestSource.ChildPages batch) {
        String next = batch.nextCursor();
        if (request.snapshot == null) {
            return new NavigationContinuation.State(next, 0, null);
        }
        List<String> known = request.snapshot.pageIds();
        LinkedHashSet<String> order = new LinkedHashSet<>(known.subList(0, request.confirmedCount));
        batch.pages().forEach(page -> order.add(page.pageId()));
        int confirmed = order.size();
        if (next != null) {
            order.addAll(known);
        }
        if (Objects.equals(batch.partitionKey(), request.spaceKey) && new ArrayList<>(order).equals(known)) {
            // Unchanged order: keep the stored revision instead of committing the same hint again.
            return new NavigationContinuation.State(next, confirmed, request.snapshot.revision());
        }
        try {
            String revision = cache.compareAndSetChildOrder(request.sourceId, batch.partitionKey(), request.parentId,
                    request.snapshot.revision(), new ArrayList<>(order)).orElse(null);
            if (revision != null) {
                return new NavigationContinuation.State(next, confirmed, revision);
            }
        } catch (RuntimeException unavailable) {
            // Keep live navigation independent of the optional display hint.
        }
        return new NavigationContinuation.State(next, 0, null);
    }

    private static String node(String spaceKey, String parentId) {
        return parentId == null ? "space:" + spaceKey : "page:" + parentId;
    }

    static final class Request {
        final String sourceId;
        final String spaceKey;
        final String parentId;
        final String sourceCursor;
        final boolean wrapped;
        final int confirmedCount;
        final ChildOrderState snapshot;

        Request(String sourceId, String spaceKey, String parentId, String sourceCursor,
                boolean wrapped, int confirmedCount, ChildOrderState snapshot) {
            this.sourceId = sourceId;
            this.spaceKey = spaceKey;
            this.parentId = parentId;
            this.sourceCursor = sourceCursor;
            this.wrapped = wrapped;
            this.confirmedCount = confirmedCount;
            this.snapshot = snapshot;
        }
    }
}