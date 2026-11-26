package com.aresstack.spaceharvester.application.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.sync.SyncMode;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SyncPresetTest {

    @Test
    void aPresetSetsModeBackgroundFillAndPrefetchAndKeepsEveryOtherRule() {
        SourceSyncOverride own = new SourceSyncOverride(Duration.ofHours(1), Duration.ofDays(1), true, false, true,
                SyncMode.CATALOG_ONLY, RefreshStrategy.TIME_BASED, Duration.ofHours(6), Duration.ofDays(3), true, null);
        SourceSyncOverride selective = SyncPreset.SELECTIVE.applyTo(own);
        assertThat(selective.mode()).isEqualTo(SyncMode.SYNC_CACHE);
        assertThat(selective.backgroundFill()).isFalse();
        assertThat(selective.prefetchHits()).isFalse();
        assertThat(selective.changeSearchInterval()).isEqualTo(own.changeSearchInterval());
        assertThat(selective.inventoryInterval()).isEqualTo(own.inventoryInterval());
        assertThat(selective.refreshStrategy()).isEqualTo(own.refreshStrategy());
        assertThat(selective.withheldRetention()).isEqualTo(own.withheldRetention());

        SourceSyncOverride full = SyncPreset.FULL.applyTo(SourceSyncOverride.NONE);
        assertThat(full.mode()).isEqualTo(SyncMode.FULL);
        assertThat(full.backgroundFill()).isTrue();
        assertThat(full.prefetchHits()).isTrue();
    }

    @Test
    void theEffectiveValuesNameThePresetOrNoneForCustomRules() {
        assertThat(SyncPreset.of(SyncMode.FULL, true, true)).contains(SyncPreset.FULL);
        assertThat(SyncPreset.of(SyncMode.SYNC_CACHE, false, false)).contains(SyncPreset.SELECTIVE);
        assertThat(SyncPreset.of(SyncMode.SYNC_CACHE, false, true)).as("Prefetch an: nicht selektiv").isEmpty();
        assertThat(SyncPreset.of(SyncMode.FULL, false, true)).isEmpty();
        assertThat(SyncPreset.of(SyncMode.CATALOG_ONLY, false, false)).isEmpty();
    }
}
