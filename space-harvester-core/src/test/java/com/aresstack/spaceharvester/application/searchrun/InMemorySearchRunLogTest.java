package com.aresstack.spaceharvester.application.searchrun;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;

/** Der Protokollvertrag gegen die flüchtige Ablage, dazu ihre Obergrenze. */
class InMemorySearchRunLogTest extends SearchRunLogContract {

    @Override
    protected SearchRunLog newLog(Duration maxAge, Clock clock) {
        return new InMemorySearchRunLog(maxAge, clock);
    }

    @org.junit.jupiter.api.Test
    void theOldestRunsGiveWayAtCapacity() {
        InMemorySearchRunLog log = new InMemorySearchRunLog(null, Clock.fixed(T0, ZoneOffset.UTC), 2);
        log.record(run("r1", null, "a", 1));
        log.record(run("r2", null, "a", 2));
        log.record(run("r3", null, "a", 3));

        assertThat(log.recent(null, 10)).extracting(run -> run.id().value()).containsExactly("r3", "r2");
    }
}
