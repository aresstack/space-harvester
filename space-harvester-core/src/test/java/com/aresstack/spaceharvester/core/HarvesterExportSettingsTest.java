package com.aresstack.spaceharvester.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.configuration.ConfigurationManager;
import com.aresstack.spaceharvester.application.configuration.ExportSettings;
import com.aresstack.spaceharvester.application.configuration.HarvesterConfiguration;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Die Anhänge im Export folgen der wirksamen Konfiguration, ohne Neustart (ADR 0023, ADR 0015). */
class HarvesterExportSettingsTest {

    @Test
    void theExportSettingsFollowTheLiveConfiguration() {
        ConfigurationManager manager = new ConfigurationManager(HarvesterConfiguration.DEFAULT);
        Supplier<ExportSettings> settings = Harvester.exportSettings(manager);
        assertThat(settings.get()).isEqualTo(ExportSettings.DEFAULT);

        ExportSettings off = new ExportSettings(false, List.of("application/pdf"), 1024);
        assertThat(manager.apply(HarvesterConfiguration.DEFAULT.withExport(off)).activated()).isTrue();
        assertThat(settings.get()).isEqualTo(off);
    }

    @Test
    void withoutLiveConfigurationTheDefaultsApply() {
        assertThat(Harvester.exportSettings(null).get()).isEqualTo(ExportSettings.DEFAULT);
    }
}
