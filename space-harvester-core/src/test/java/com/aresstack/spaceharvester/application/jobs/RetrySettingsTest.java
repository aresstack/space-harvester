package com.aresstack.spaceharvester.application.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetrySettingsTest {

    @Test
    void theDefaultRetriesTwiceLikeTheServiceDidAndPausesAfterFiveNetworkFailures() {
        assertThat(RetrySettings.DEFAULT).isEqualTo(new RetrySettings(2, Duration.ofSeconds(1),
                Duration.ofSeconds(30), 5));
        assertThat(new JobLimits(2, 20, Duration.ofHours(6)).retry()).isEqualTo(RetrySettings.DEFAULT);
    }

    @Test
    void valuesOutsideTheLimitsOfTheRestClientAreRejected() {
        assertThatThrownBy(() -> new RetrySettings(11, Duration.ZERO, Duration.ZERO, 5))
                .hasMessageContaining("max-attempts");
        assertThatThrownBy(() -> new RetrySettings(3, Duration.ofSeconds(61), Duration.ofSeconds(90), 5))
                .hasMessageContaining("initial-delay");
        assertThatThrownBy(() -> new RetrySettings(3, Duration.ofSeconds(5), Duration.ofSeconds(1), 5))
                .hasMessageContaining("max-delay");
        assertThatThrownBy(() -> new RetrySettings(3, Duration.ofSeconds(1), Duration.ofSeconds(301), 5))
                .hasMessageContaining("max-delay");
        assertThatThrownBy(() -> new RetrySettings(3, Duration.ofSeconds(1), Duration.ofSeconds(2), 0))
                .hasMessageContaining("pause-after-failures");
        assertThat(new RetrySettings(0, Duration.ZERO, Duration.ZERO, 1).maxAttempts()).isZero();
    }

    @Test
    void delayDoublesUpToTheLongestWait() {
        RetrySettings settings = new RetrySettings(3, java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(3),
                5);
        assertThat(settings.delay(0)).isEqualTo(java.time.Duration.ofSeconds(1));
        assertThat(settings.delay(1)).isEqualTo(java.time.Duration.ofSeconds(2));
        assertThat(settings.delay(2)).isEqualTo(java.time.Duration.ofSeconds(3));
        assertThat(new RetrySettings(1, java.time.Duration.ZERO, java.time.Duration.ZERO, 1).delay(4)).isZero();
    }
}
