package com.aresstack.spaceharvester.application.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.standing.StandingWords;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** Wörter des Stands (ADR 0032, FL6) als Einstellung. */
class StandingSettingsTest {

    @Test
    void wordsAreLowerCaseUniqueAndWhole() {
        StandingSettings settings = new StandingSettings(List.of(" Archiv", "archiv", "", "Altbestand"),
                List.of("Idee"));

        assertThat(settings.outdatedWords()).containsExactly("archiv", "altbestand");
        assertThat(StandingWords.of(settings).outdated("ALTBESTAND")).isTrue();
        assertThat(StandingWords.of(settings).draft("entwurf")).isFalse();
        assertThatThrownBy(() -> new StandingSettings(List.of("alte seite"), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theServiceReadsListsAndFallsBackToTheDefaults() {
        Properties properties = new Properties();
        properties.setProperty(StandingSettings.OUTDATED_WORDS, "Archiv, abgelöst");

        StandingSettings read = HarvesterConfiguration.fromProperties(properties).orThrow().standing();

        assertThat(read.outdatedWords()).containsExactly("archiv", "abgelöst");
        assertThat(read.draftWords()).isEqualTo(StandingSettings.DEFAULT_DRAFT_WORDS);
        assertThat(HarvesterConfiguration.fromProperties(new Properties()).orThrow().standing())
                .isEqualTo(StandingSettings.DEFAULT);
    }

    @Test
    void anUnreadableWordIsAProblem() {
        Properties properties = new Properties();
        properties.setProperty(StandingSettings.DRAFT_WORDS, "alte seite");

        assertThat(HarvesterConfiguration.fromProperties(properties).problems())
                .anySatisfy(problem -> assertThat(problem.toString()).contains(StandingSettings.DRAFT_WORDS));
    }
}
