package com.aresstack.spaceharvester.application.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SwappableTest {

    private final List<String> released = new ArrayList<>();
    private final Swappable<String> client = new Swappable<>("alt", released::add);

    @Test
    void aRunningJobKeepsItsInstanceUntilItEnds() {
        Swappable.Lease<String> job = client.acquire();

        client.swap("neu");

        assertThat(job.get()).isEqualTo("alt");
        assertThat(client.get()).isEqualTo("neu");
        assertThat(released).isEmpty();
        job.close();
        job.close();
        assertThat(released).containsExactly("alt");
    }

    @Test
    void anUnusedInstanceIsReleasedAtOnce() {
        client.swap("neu");

        assertThat(released).containsExactly("alt");
    }

    @Test
    void closeReleasesAfterTheLastLease() {
        Swappable.Lease<String> job = client.acquire();
        client.close();

        assertThat(released).isEmpty();
        job.close();
        assertThat(released).containsExactly("alt");
        assertThatThrownBy(client::acquire).isInstanceOf(IllegalStateException.class);
    }
}
