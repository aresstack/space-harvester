package com.aresstack.spaceharvester.application.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.check.CheckScope;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** Einstellungen des Prüfschritts (ADR 0032, FL4) beim Dienst; die App liest dieselben Schlüssel (ParityTest). */
class CheckSettingsTest {

    @Test
    void withoutKeysTheDefaultHolds() {
        assertThat(HarvesterConfiguration.fromProperties(new Properties()).configuration().check())
                .isEqualTo(CheckSettings.DEFAULT)
                .isEqualTo(new CheckSettings(CheckScope.UNCERTAIN_AND_STAGE, 2, null));
    }

    @Test
    void scopeParallelAndModelAreRead() {
        Properties properties = new Properties();
        properties.setProperty(HarvesterConfiguration.PREFIX + CheckSettings.SCOPE, "all");
        properties.setProperty(HarvesterConfiguration.PREFIX + CheckSettings.PARALLEL, "3");
        properties.setProperty(HarvesterConfiguration.PREFIX + CheckSettings.MODEL, " check-model ");

        assertThat(HarvesterConfiguration.fromProperties(properties).configuration().check())
                .isEqualTo(new CheckSettings(CheckScope.ALL, 3, "check-model"));
    }

    @Test
    void anUnknownScopeOrNoParallelismIsAProblem() {
        Properties scope = new Properties();
        scope.setProperty(HarvesterConfiguration.PREFIX + CheckSettings.SCOPE, "manchmal");
        Properties parallel = new Properties();
        parallel.setProperty(HarvesterConfiguration.PREFIX + CheckSettings.PARALLEL, "0");

        assertThat(HarvesterConfiguration.fromProperties(scope).problems())
                .anySatisfy(problem -> assertThat(problem.key()).isEqualTo(CheckSettings.SCOPE));
        assertThat(HarvesterConfiguration.fromProperties(parallel).problems())
                .anySatisfy(problem -> assertThat(problem.key()).isEqualTo(CheckSettings.PARALLEL));
    }
}
