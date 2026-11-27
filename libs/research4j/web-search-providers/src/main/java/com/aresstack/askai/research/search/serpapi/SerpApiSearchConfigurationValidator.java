package com.aresstack.askai.research.search.serpapi;

import com.aresstack.askai.research.search.config.ConfigurationValidation;

public final class SerpApiSearchConfigurationValidator {

    public void validate(SerpApiSearchConfiguration configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException(
                    "configuration must not be null");
        }
        ConfigurationValidation.requireRange(
                configuration.getFormatVersion(),
                1,
                Integer.MAX_VALUE,
                "formatVersion");
        ConfigurationValidation.requireText(
                configuration.getBaseUrl(),
                "baseUrl");
        ConfigurationValidation.requireNonNull(
                configuration.getSearchEngine(),
                "searchEngine");
        ConfigurationValidation.requireRange(
                configuration.getUrlResolveTimeoutMillis(),
                0,
                Integer.MAX_VALUE,
                "urlResolveTimeoutMillis");
        ConfigurationValidation.validateTransport(
                configuration.getTransport());

        if (configuration.isEnabled()
                && (configuration.getApiKey() == null
                || !configuration.getApiKey().isPresent())) {

            throw new IllegalArgumentException(
                    "apiKey must be configured when SerpApi is enabled");
        }
    }
}
