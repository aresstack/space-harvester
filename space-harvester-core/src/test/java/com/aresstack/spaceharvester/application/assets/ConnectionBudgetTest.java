package com.aresstack.spaceharvester.application.assets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Gesamtbudget der Verbindungen für Bilder und Anhänge, nach Restzeit verteilt (Angelo 11.11.2026). */
class ConnectionBudgetTest {

    private static final Runnable NOTHING = () -> { };

    @Test
    void aSingleDownloadGetsTheWholeBudget() {
        ConnectionBudget budget = new ConnectionBudget(16);

        try (ConnectionBudget.Lease lease = budget.register(() -> 1_000)) {
            assertThat(lease.share()).isEqualTo(16);
        }
    }

    @Test
    void theBudgetFollowsTheRemainingTimeAndChangesWithIt() {
        ConnectionBudget budget = new ConnectionBudget(8);
        AtomicLong slow = new AtomicLong(30_000);
        try (ConnectionBudget.Lease a = budget.register(slow::get);
             ConnectionBudget.Lease b = budget.register(() -> 10_000)) {
            assertThat(a.share()).isEqualTo(6);
            assertThat(b.share()).isEqualTo(2);

            // Der erste holt auf: die Verbindungen wandern zum anderen, damit beide zugleich fertig werden.
            slow.set(10_000);
            assertThat(a.share()).isEqualTo(4);
            assertThat(b.share()).isEqualTo(4);
        }
    }

    @Test
    void everyDownloadKeepsAtLeastOneConnectionAndTheTotalIsNeverExceeded() {
        ConnectionBudget budget = new ConnectionBudget(3);
        try (ConnectionBudget.Lease big = budget.register(() -> 1_000_000);
             ConnectionBudget.Lease small = budget.register(() -> 1);
             ConnectionBudget.Lease done = budget.register(() -> 0)) {
            assertThat(small.share()).isEqualTo(1);
            assertThat(done.share()).isEqualTo(1);
            assertThat(big.share()).isEqualTo(1);
        }
    }

    @Test
    void aDownloadWaitsForAFreeConnectionOfItsShare() throws Exception {
        ConnectionBudget budget = new ConnectionBudget(1);
        ConnectionBudget.Lease lease = budget.register(() -> 1);
        lease.acquire(NOTHING);
        CountDownLatch second = new CountDownLatch(1);
        Thread waiting = new Thread(() -> {
            lease.acquire(NOTHING);
            second.countDown();
        });
        waiting.start();

        assertThat(second.await(300, TimeUnit.MILLISECONDS)).isFalse();
        lease.release();
        assertThat(second.await(5, TimeUnit.SECONDS)).isTrue();
        lease.release();
        lease.close();
    }

    @Test
    void zeroLoadsNothingUntilConnectionsAreBack() throws Exception {
        ConnectionBudget budget = new ConnectionBudget(0);
        ConnectionBudget.Lease lease = budget.register(() -> 1);
        assertThat(lease.share()).isZero();
        CountDownLatch loaded = new CountDownLatch(1);
        Thread waiting = new Thread(() -> {
            budget.awaitAvailable(NOTHING);
            lease.acquire(NOTHING);
            loaded.countDown();
        });
        waiting.start();

        assertThat(loaded.await(300, TimeUnit.MILLISECONDS)).isFalse();
        budget.total(2);
        assertThat(loaded.await(5, TimeUnit.SECONDS)).isTrue();
        lease.release();
        lease.close();
    }

    @Test
    void theCheckpointEndsAWait() {
        ConnectionBudget budget = new ConnectionBudget(0);

        assertThatThrownBy(() -> budget.awaitAvailable(() -> {
            throw new IllegalStateException("angehalten");
        })).hasMessage("angehalten");
        assertThatThrownBy(() -> new ConnectionBudget(65)).isInstanceOf(IllegalArgumentException.class);
    }
}
