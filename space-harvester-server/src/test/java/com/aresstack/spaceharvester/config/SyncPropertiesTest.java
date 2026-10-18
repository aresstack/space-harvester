package com.aresstack.spaceharvester.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class SyncPropertiesTest {

    @Test
    void withoutSettingsThereIsNoBackgroundSync() {
        SyncProperties properties = new SyncProperties(null, null, null, null);

        assertThat(properties.interval()).isNull();
        assertThat(properties.spaces()).isEmpty();
        assertThat(properties.withheldRetention()).isEqualTo(Duration.ofDays(30));
        assertThat(properties.loadUnknown()).isTrue();
    }

    @Test
    void withheldPagesKeepTheirRawDataForAConfigurablePeriod() {
        assertThat(new SyncProperties(null, null, Duration.ZERO, null).withheldRetention()).isZero();
        assertThatThrownBy(() -> new SyncProperties(null, null, Duration.ofDays(-1), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("withheld-retention");
    }

    @Test
    void spacesNeedSourceAndSpaceKey() {
        assertThat(new SyncProperties(Duration.ofMinutes(30), List.of("wiki/DEV"), null, null).spaces())
                .containsExactly("wiki/DEV");
        for (String invalid : new String[]{"DEV", "/DEV", "wiki/", "wiki/DEV/x"}) {
            assertThatThrownBy(() -> new SyncProperties(Duration.ofMinutes(30), List.of(invalid), null, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(invalid);
        }
    }

    @Test
    void theIntervalMustBePositive() {
        assertThatThrownBy(() -> new SyncProperties(Duration.ZERO, List.of(), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SyncProperties(Duration.ofSeconds(-1), List.of(), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SyncProperties(Duration.ofSeconds(30), List.of(), null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mindestens");
    }
}
