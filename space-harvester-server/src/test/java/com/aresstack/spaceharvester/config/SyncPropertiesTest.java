package com.aresstack.spaceharvester.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class SyncPropertiesTest {

    @Test
    void withoutSettingsThereIsNoBackgroundSync() {
        SyncProperties properties = new SyncProperties(null, null, null, null, null, null);

        assertThat(properties.interval()).isNull();
        assertThat(properties.spaces()).isEmpty();
        assertThat(properties.withheldRetention()).isEqualTo(Duration.ofDays(30));
        // Änderungsquellen sind optional, ein Zeitplan lädt nur Kopfdaten (ADR 0014, Fortschreibung).
        assertThat(properties.loadUnknown()).isFalse();
        assertThat(properties.catchUpOnStart()).isFalse();
    }

    @Test
    void theModeReplacesLoadUnknown() {
        assertThat(new SyncProperties(null, null, null, null, null, null).mode())
                .isEqualTo(com.aresstack.spaceharvester.application.sync.SyncMode.CATALOG_ONLY);
        assertThat(new SyncProperties(null, null, null, true, null, null).mode())
                .isEqualTo(com.aresstack.spaceharvester.application.sync.SyncMode.FULL);
        assertThat(new SyncProperties(null, null, null, false, null, null).mode())
                .isEqualTo(com.aresstack.spaceharvester.application.sync.SyncMode.SYNC_CACHE);
        assertThatThrownBy(() -> new SyncProperties(null, null, null, true, null,
                com.aresstack.spaceharvester.application.sync.SyncMode.CATALOG_ONLY))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("widersprechen");
    }

    @Test
    void withheldPagesKeepTheirRawDataForAConfigurablePeriod() {
        assertThat(new SyncProperties(null, null, Duration.ZERO, null, null, null).withheldRetention()).isZero();
        assertThatThrownBy(() -> new SyncProperties(null, null, Duration.ofDays(-1), null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("withheld-retention");
    }

    @Test
    void spacesNeedSourceAndSpaceKey() {
        assertThat(new SyncProperties(Duration.ofMinutes(30), List.of("wiki/DEV"), null, null, null, null).spaces())
                .containsExactly("wiki/DEV");
        for (String invalid : new String[]{"DEV", "/DEV", "wiki/", "wiki/DEV/x"}) {
            assertThatThrownBy(() -> new SyncProperties(Duration.ofMinutes(30), List.of(invalid), null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(invalid);
        }
    }

    @Test
    void theIntervalMustBePositive() {
        assertThatThrownBy(() -> new SyncProperties(Duration.ZERO, List.of(), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SyncProperties(Duration.ofSeconds(-1), List.of(), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SyncProperties(Duration.ofSeconds(30), List.of(), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mindestens");
    }
}
