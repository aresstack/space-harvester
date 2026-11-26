package com.aresstack.spaceharvester.application.navigation;

import com.aresstack.spaceharvester.application.query.InMemoryPageCache;
import com.aresstack.spaceharvester.application.query.PageCacheReader;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.InvalidNavigationCursorException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NavigationOrderTest {
    private final InMemoryPageCache cache = new InMemoryPageCache();
    private final NavigationOrder order = new NavigationOrder(cache);

    @Test
    void moreThanAThousandOpenListingsCanContinueIndependently() {
        List<String> cursors = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            cursors.add(start("folder-" + i, "a"));
        }
        for (int i = 0; i < cursors.size(); i++) {
            String parent = "folder-" + i;
            NavigationOrder.Request request = order.prepare("source", "space", parent, cursors.get(i), false);
            assertThat(request.sourceCursor).isEqualTo("next");
            assertThat(order.complete(request, batch(null, "b"))).isNull();
            assertThat(cache.childOrder("source", "space", parent)).containsExactly("a", "b");
        }
    }

    @Test
    void competingAndReplayedContinuationsCannotOverwriteNewerOrder() {
        String a = start("parent", "a");
        String b = start("parent", "b");
        NavigationOrder.Request old = order.prepare("source", null, "parent", a, false);
        assertThat(old.sourceCursor).isEqualTo("next");
        String oldNext = order.complete(old, batch("later", "old"));
        NavigationOrder.Request oldAgain = order.prepare("source", null, "parent", oldNext, false);
        assertThat(oldAgain.sourceCursor).isEqualTo("later");
        assertThat(oldAgain.snapshot).isNull();
        order.complete(oldAgain, batch(null, "obsolete"));
        assertThat(cache.childOrder("source", null, "parent")).containsExactly("b", "a");

        NavigationOrder.Request active = order.prepare("source", null, "parent", b, false);
        order.complete(active, batch(null, "c"));
        int writes = cache.childOrderWrites;
        order.complete(order.prepare("source", null, "parent", b, false), batch(null, "replayed"));
        assertThat(cache.childOrderWrites).isEqualTo(writes);
        assertThat(cache.childOrder("source", null, "parent")).containsExactly("b", "c");
    }

    @Test
    void rejectsManipulatedOrForeignCursor() {
        String cursor = start("parent", "a");
        assertThatThrownBy(() -> order.prepare("source", null, "different", cursor, false))
                .isInstanceOf(InvalidNavigationCursorException.class);
        assertThatThrownBy(() -> order.prepare("foreign", null, "parent", cursor, false))
                .isInstanceOf(InvalidNavigationCursorException.class);
        assertThatThrownBy(() -> order.prepare("source", null, "parent", cursor + "x", false))
                .isInstanceOf(InvalidNavigationCursorException.class);
    }

    @Test
    void groupNavigationAndUnavailableCacheDoNotDependOnOrderState() {
        NavigationOrder.Request grouped = order.prepare("source", null, "parent", "raw", true);
        assertThat(grouped.sourceCursor).isEqualTo("raw");
        assertThat(order.complete(grouped, batch("next", "a"))).isEqualTo("next");
        assertThat(cache.childOrderWrites).isZero();

        NavigationOrder unavailable = new NavigationOrder(PageCacheReader.unavailable());
        String cursor = unavailable.complete(unavailable.prepare("source", null, "parent", null, false),
                batch("next", "a"));
        NavigationOrder.Request next = unavailable.prepare("source", null, "parent", cursor, false);
        assertThat(next.sourceCursor).isEqualTo("next");
        assertThat(unavailable.complete(next, batch(null, "b"))).isNull();
    }

    private String start(String parent, String id) {
        return order.complete(order.prepare("source", null, parent, null, false), batch("next", id));
    }

    private static HarvestSource.ChildPages batch(String next, String... ids) {
        return new HarvestSource.ChildPages("space", Arrays.stream(ids)
                .map(id -> new HarvestSource.PageNode(id, id, "space", false))
                .collect(Collectors.toList()), next);
    }
}