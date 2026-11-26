package com.aresstack.spaceharvester.app.ui.deck;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NdvCacheNamespaceTest {
    @Test
    void separatesAccountsAndPrincipalsAndReusesTheSameVerifiedIdentity() {
        String first = NdvAnalysisSettings.cacheNamespace("HOST:8011", "Account-A", "PILOT", "first");
        assertThat(NdvAnalysisSettings.cacheNamespace("host:8011", "Account-A", "pilot", "second"))
                .isEqualTo(first);
        assertThat(NdvAnalysisSettings.cacheNamespace("host:8011", "Account-B", "pilot", "second"))
                .isNotEqualTo(first);
        assertThat(NdvAnalysisSettings.cacheNamespace("host:8011", "Account-A", "other", "second"))
                .isNotEqualTo(first);
        assertThat(NdvAnalysisSettings.cacheNamespace("host:8011", "", "pilot", "first"))
                .isNotEqualTo(NdvAnalysisSettings.cacheNamespace("host:8011", "", "pilot", "second"));
        assertThat(NdvAnalysisSettings.cacheNamespace("host:8011", "Account-A", "", "first"))
                .isNotEqualTo(first);
    }
}
