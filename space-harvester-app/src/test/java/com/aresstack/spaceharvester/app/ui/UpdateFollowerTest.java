package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.application.changelog.ChangeKind;
import com.aresstack.spaceharvester.application.changelog.ChangeLogListener;
import com.aresstack.spaceharvester.application.changelog.ChangeUpdate;
import com.aresstack.spaceharvester.application.changelog.UpdateMark;
import com.aresstack.spaceharvester.application.observe.Subscription;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateFollowerTest {

    private static final Instant ATTACHED = Instant.parse("2026-11-16T20:00:00Z");

    private final List<String> opened = new ArrayList<>();
    private boolean enabled = true;
    /** Was die App gerade zeigt: die Updates dieser Quelle, sonst {@code null}. */
    private String showing;
    private final FakeFeed feed = new FakeFeed();
    private final UpdateFollower follower = new UpdateFollower(() -> enabled,
            (sourceId, title) -> {
                opened.add(sourceId + "|" + title);
                showing = sourceId;
            }, () -> showing, new UpdateFollower.Runner() {
                @Override
                public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
                    try {
                        onSuccess.accept(work.call());
                    } catch (Exception e) {
                        onFailure.accept(e);
                    }
                }
            }, () -> ATTACHED);

    @Test
    void aNewMarkOpensTheUpdatesOfItsSourceOnce() throws Exception {
        follower.attach(feed);
        assertThat(follower.attached()).isTrue();

        feed.updates = List.of(mark(ATTACHED.plusSeconds(60), false));
        feed.listener.changed("wiki");
        feed.listener.changed("wiki");

        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        assertThat(opened).containsExactly("wiki|Confluence HaVdB");
    }

    @Test
    void backgroundRowsMarksFromBeforeAndTheInitialFillOpenNothing() throws Exception {
        follower.attach(feed);

        feed.updates = List.of(open());
        feed.listener.changed("wiki");
        feed.updates = List.of(open(), mark(ATTACHED.minusSeconds(60), false));
        feed.listener.changed("wiki");
        feed.updates = List.of(mark(ATTACHED.plusSeconds(60), true));
        feed.listener.changed("wiki");

        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        assertThat(opened).isEmpty();
    }

    @Test
    void withTheSettingOffNothingOpens() throws Exception {
        enabled = false;
        follower.attach(feed);

        feed.updates = List.of(mark(ATTACHED.plusSeconds(60), false));
        feed.listener.changed("wiki");

        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        assertThat(opened).isEmpty();
        assertThat(feed.reads).as("ausgeschaltet liest nichts").isZero();
    }

    @Test
    void severalSourcesAtOnceOpenOnlyTheFirstUntilTheUserChoosesSomethingElse() throws Exception {
        follower.attach(feed);
        feed.updates = List.of(mark(ATTACHED.plusSeconds(60), false));

        feed.listener.changed("wiki");
        feed.listener.changed("dev");
        feed.listener.changed("ops");
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        assertThat(opened).as("die übrigen drängen sich nicht auf").containsExactly("wiki|Confluence HaVdB");

        showing = null; // der Benutzer wählt etwas anderes
        feed.updates = List.of(mark(ATTACHED.plusSeconds(120), false));
        feed.listener.changed("dev");
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        assertThat(opened).containsExactly("wiki|Confluence HaVdB", "dev|Confluence HaVdB");
    }

    @Test
    void anotherRuntimeEndsTheOldSubscription() {
        follower.attach(feed);

        follower.attach(null);

        assertThat(feed.closed).isTrue();
        assertThat(follower.attached()).isFalse();
    }

    private static ChangeUpdate mark(Instant at, boolean initial) {
        return new ChangeUpdate(String.valueOf(at.toEpochMilli()), "wiki", null, at,
                new UpdateMark("wiki", at, "angelo", List.of("CLUK"), 0, initial), initial,
                Map.of(ChangeKind.NEW, 1), Map.of("CLUK", 1), 1, 0, 0, false);
    }

    private static ChangeUpdate open() {
        return new ChangeUpdate(ChangeUpdate.OPEN, "wiki", ATTACHED, null, null, false, Map.of(ChangeKind.NEW, 1),
                Map.of("CLUK", 1), 1, 0, 0, false);
    }

    private static final class FakeFeed implements UpdateFollower.Feed {
        ChangeLogListener listener;
        List<ChangeUpdate> updates = List.of();
        boolean closed;
        int reads;

        @Override
        public Subscription subscribe(ChangeLogListener listener) {
            this.listener = listener;
            return () -> closed = true;
        }

        @Override
        public List<ChangeUpdate> updates(String sourceId) {
            reads++;
            return updates;
        }

        @Override
        public String title(String sourceId) {
            return "Confluence HaVdB";
        }
    }
}
