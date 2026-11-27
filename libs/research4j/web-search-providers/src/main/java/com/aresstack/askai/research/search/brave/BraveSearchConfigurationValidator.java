package com.aresstack.askai.research.search.brave;

import com.aresstack.askai.research.search.config.ConfigurationValidation;

public final class BraveSearchConfigurationValidator {

    public void validate(BraveSearchConfiguration configuration) {
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
                configuration.getEndpoint(),
                "endpoint");
        ConfigurationValidation.requireRange(
                configuration.getCount(),
                1,
                20,
                "count");
        ConfigurationValidation.requireRange(
                configuration.getOffset(),
                0,
                9,
                "offset");
        ConfigurationValidation.validateTransport(
                configuration.getTransport());

        if (configuration.isEnabled()
                && (configuration.getApiKey() == null
                || !configuration.getApiKey().isPresent())) {

            throw new IllegalArgumentException(
                    "apiKey must be configured when Brave is enabled");
        }

        validateLocation(configuration.getLocation());

        if (configuration.getFreshness() != null) {
            configuration.getFreshness().toApiValue();
        }
    }

    private void validateLocation(
            BraveLocationConfiguration location) {

        if (location == null) {
            return;
        }

        Double latitude = location.getLatitude();
        Double longitude = location.getLongitude();

        if ((latitude == null) != (longitude == null)) {
            throw new IllegalArgumentException(
                    "latitude and longitude must be configured together");
        }

        if (latitude != null
                && (latitude.doubleValue() < -90.0
                || latitude.doubleValue() > 90.0)) {

            throw new IllegalArgumentException(
                    "latitude must be between -90 and 90");
        }

        if (longitude != null
                && (longitude.doubleValue() < -180.0
                || longitude.doubleValue() > 180.0)) {

            throw new IllegalArgumentException(
                    "longitude must be between -180 and 180");
        }
    }
}
