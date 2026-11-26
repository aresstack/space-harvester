package com.aresstack.spaceharvester.app.ui;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackgroundActivityTest {

    @Test
    void queuedAndNestedCompletionsRemainPending() throws Exception {
        BackgroundActivity activity = new BackgroundActivity();
        AtomicInteger completed = new AtomicInteger();
        Runnable[] continuation = new Runnable[1];
        Runnable first = activity.track(() -> continuation[0] = activity.track(completed::incrementAndGet));
        assertThatThrownBy(() -> activity.awaitIdle(Duration.ZERO)).isInstanceOf(TimeoutException.class);
        first.run();
        assertThatThrownBy(() -> activity.awaitIdle(Duration.ZERO)).isInstanceOf(TimeoutException.class);
        continuation[0].run();
        activity.awaitIdle(Duration.ZERO);
        assertThat(completed.get()).isEqualTo(1);
    }

    @Test
    void completionCanStartMoreWorkAndAwaitIdleWaitsForItsUiCallback() throws Exception {
        AtomicInteger completed = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> Background.run(() -> 1,
                first -> Background.run(() -> first + 1, completed::set,
                        failure -> completed.set(-1)), failure -> completed.set(-1)));
        Background.awaitIdle(Duration.ofSeconds(10));
        assertThat(completed.get()).isEqualTo(2);
    }
}
