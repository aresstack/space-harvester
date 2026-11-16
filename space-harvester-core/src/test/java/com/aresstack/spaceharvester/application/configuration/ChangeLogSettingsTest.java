package com.aresstack.spaceharvester.application.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** Aufbewahrung des Änderungsprotokolls (ADR 0030, Abschnitt 2). */
class ChangeLogSettingsTest {

    @Test
    void missingIsOneYearEmptyOrZeroIsUnlimited() {
        assertThat(ChangeLogSettings.parse(null).retention()).isEqualTo(Duration.ofDays(365));
        assertThat(ChangeLogSettings.parse("").unlimited()).isTrue();
        assertThat(ChangeLogSettings.parse("  ").unlimited()).isTrue();
        assertThat(ChangeLogSettings.parse("0").unlimited()).isTrue();
        assertThat(ChangeLogSettings.parse("P0D").unlimited()).isTrue();
        assertThat(ChangeLogSettings.UNLIMITED.cutoff(Instant.EPOCH)).isEmpty();
    }

    @Test
    void readsIsoPeriodsAndTheShortForm() {
        assertThat(ChangeLogSettings.parse("P1Y").retention()).isEqualTo(Duration.ofDays(365));
        assertThat(ChangeLogSettings.parse("p6m").retention()).isEqualTo(Duration.ofDays(180));
        assertThat(ChangeLogSettings.parse("P1Y2M3DT4H").retention())
                .isEqualTo(Duration.ofDays(365 + 60 + 3).plusHours(4));
        assertThat(ChangeLogSettings.parse("PT12H").retention()).isEqualTo(Duration.ofHours(12));
        assertThat(ChangeLogSettings.parse("365d").retention()).isEqualTo(Duration.ofDays(365));
        assertThat(ChangeLogSettings.parse("2w").retention()).isEqualTo(Duration.ofDays(14));
        assertThat(ChangeLogSettings.parse("2y").retention()).isEqualTo(Duration.ofDays(730));
        assertThat(ChangeLogSettings.parse("30d").cutoff(Instant.parse("2026-11-16T00:00:00Z")))
                .contains(Instant.parse("2026-10-17T00:00:00Z"));
    }

    @Test
    void rejectsUnreadableAndNegativeValues() {
        assertThatThrownBy(() -> ChangeLogSettings.parse("ein Jahr")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChangeLogSettings.parse("-P1D")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theConfigurationReadsTheKeyAndReportsAnUnreadableValue() {
        Properties properties = new Properties();
        assertThat(HarvesterConfiguration.fromProperties(properties).configuration().changeLog())
                .isEqualTo(ChangeLogSettings.DEFAULT);

        properties.setProperty("space-harvester.cache.change-log.retention", "");
        assertThat(HarvesterConfiguration.fromProperties(properties).configuration().changeLog().unlimited())
                .isTrue();

        properties.setProperty("space-harvester.cache.change-log.retention", "P2Y");
        assertThat(HarvesterConfiguration.fromProperties(properties).configuration().changeLog().retention())
                .isEqualTo(Duration.ofDays(730));

        assertThat(HarvesterConfiguration.fromProperties(properties).configuration().changeLog().showAfterUpdate())
                .as("Vorgabe: Änderungen nach dem Update nicht von selbst zeigen").isFalse();
        properties.setProperty("space-harvester." + ChangeLogSettings.SHOW_AFTER_UPDATE, "true");
        assertThat(HarvesterConfiguration.fromProperties(properties).configuration().changeLog())
                .isEqualTo(new ChangeLogSettings(Duration.ofDays(730), true));

        properties.setProperty("space-harvester.cache.change-log.retention", "irgendwann");
        assertThat(HarvesterConfiguration.fromProperties(properties).problems())
                .anySatisfy(problem -> assertThat(problem.key()).isEqualTo(ChangeLogSettings.RETENTION));
    }
}
