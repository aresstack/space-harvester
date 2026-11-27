package com.aresstack.askai.research.search.dataforseo;

import com.aresstack.askai.research.search.config.ConfigurationValidation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class DataForSeoSearchConfigurationValidator {

    public void validate(
            DataForSeoSearchConfiguration configuration) {

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
                configuration.getEndpointBase(),
                "endpointBase");
        ConfigurationValidation.requireNonNull(
                configuration.getSearchEngine(),
                "searchEngine");
        ConfigurationValidation.requireNonNull(
                configuration.getResultFormat(),
                "resultFormat");
        ConfigurationValidation.requireRange(
                configuration.getDepth(),
                1,
                200,
                "depth");
        ConfigurationValidation.requireNonNull(
                configuration.getDevice(),
                "device");
        ConfigurationValidation.requireNonNull(
                configuration.getOperatingSystem(),
                "operatingSystem");
        ConfigurationValidation.validateTransport(
                configuration.getTransport());

        if (configuration.getOperatingSystem().getDevice()
                != configuration.getDevice()) {
            throw new IllegalArgumentException(
                    "operatingSystem does not match device");
        }

        if (configuration.isEnabled()) {
            if (configuration.getApiKey() == null
                    || !configuration.getApiKey().isPresent()) {
                throw new IllegalArgumentException(
                        "apiKey must be configured when DataForSEO is enabled");
            }
        }

        validateExclusiveLocation(configuration);
        validateExclusiveLanguage(configuration);
        validateOptionalRanges(configuration);
        validateStopTargets(configuration.getStopCrawlOnMatch());
        validateTargetElementLists(configuration);
    }

    private void validateExclusiveLocation(
            DataForSeoSearchConfiguration configuration) {

        int configured = countPresent(
                configuration.getLocationCode(),
                configuration.getLocationName(),
                configuration.getLocationCoordinate());

        if (!hasText(configuration.getDirectUrl())
                && configured != 1) {
            throw new IllegalArgumentException(
                    "Configure exactly one of locationCode, locationName or locationCoordinate");
        }

        if (configured > 1) {
            throw new IllegalArgumentException(
                    "Location parameters are mutually exclusive");
        }
    }

    private void validateExclusiveLanguage(
            DataForSeoSearchConfiguration configuration) {

        if (hasText(configuration.getLanguageCode())
                && hasText(configuration.getLanguageName())) {
            throw new IllegalArgumentException(
                    "languageCode and languageName are mutually exclusive");
        }
    }

    private void validateOptionalRanges(
            DataForSeoSearchConfiguration configuration) {

        ConfigurationValidation.requireOptionalRange(
                configuration.getMaxCrawlPages(),
                1,
                100,
                "maxCrawlPages");
        ConfigurationValidation.requireOptionalRange(
                configuration.getPeopleAlsoAskClickDepth(),
                1,
                4,
                "peopleAlsoAskClickDepth");
        ConfigurationValidation.requireOptionalRange(
                configuration.getBrowserScreenWidth(),
                240,
                9999,
                "browserScreenWidth");
        ConfigurationValidation.requireOptionalRange(
                configuration.getBrowserScreenHeight(),
                240,
                9999,
                "browserScreenHeight");

        Double ratio = configuration
                .getBrowserScreenResolutionRatio();
        if (ratio != null
                && (ratio.doubleValue() < 0.5
                || ratio.doubleValue() > 3.0)) {
            throw new IllegalArgumentException(
                    "browserScreenResolutionRatio must be between 0.5 and 3.0");
        }

        ConfigurationValidation.requireMaximumSize(
                configuration.getRemoveFromUrl(),
                10,
                "removeFromUrl");

        if (!configuration.isCalculateRectangles()
                && (configuration.getBrowserScreenWidth() != null
                || configuration.getBrowserScreenHeight() != null
                || ratio != null)) {
            throw new IllegalArgumentException(
                    "Browser screen parameters require calculateRectangles=true");
        }
    }

    private void validateStopTargets(
            List<DataForSeoStopCrawlTarget> targets) {

        ConfigurationValidation.requireMaximumSize(
                targets,
                10,
                "stopCrawlOnMatch");

        if (targets == null) {
            return;
        }

        for (DataForSeoStopCrawlTarget target : targets) {
            if (target == null) {
                throw new IllegalArgumentException(
                        "stopCrawlOnMatch must not contain null entries");
            }
            ConfigurationValidation.requireNonNull(
                    target.getMatchType(),
                    "stopCrawlOnMatch.matchType");
            ConfigurationValidation.requireText(
                    target.getMatchValue(),
                    "stopCrawlOnMatch.matchValue");
        }
    }

    private void validateTargetElementLists(
            DataForSeoSearchConfiguration configuration) {

        Set<DataForSeoSerpElementType> find =
                createSet(configuration.getFindTargetsIn());
        Set<DataForSeoSerpElementType> ignore =
                createSet(configuration.getIgnoreTargetsIn());

        for (DataForSeoSerpElementType value : find) {
            if (ignore.contains(value)) {
                throw new IllegalArgumentException(
                        "findTargetsIn and ignoreTargetsIn must not overlap: "
                                + value);
            }
        }

        boolean hasTargetFilters = !find.isEmpty()
                || !ignore.isEmpty()
                || configuration.getTargetSearchMode() != null;
        boolean hasStopTargets = configuration.getStopCrawlOnMatch() != null
                && !configuration.getStopCrawlOnMatch().isEmpty();

        if (hasTargetFilters && !hasStopTargets
                && (!find.isEmpty() || !ignore.isEmpty())) {
            throw new IllegalArgumentException(
                    "findTargetsIn and ignoreTargetsIn require stopCrawlOnMatch");
        }
    }

    private Set<DataForSeoSerpElementType> createSet(
            List<DataForSeoSerpElementType> values) {

        Set<DataForSeoSerpElementType> result =
                new HashSet<DataForSeoSerpElementType>();
        if (values == null) {
            return result;
        }
        for (DataForSeoSerpElementType value : values) {
            if (value == null) {
                throw new IllegalArgumentException(
                        "SERP element lists must not contain null entries");
            }
            result.add(value);
        }
        return result;
    }

    private int countPresent(
            Integer code,
            String name,
            String coordinate) {

        int count = code == null ? 0 : 1;
        count += hasText(name) ? 1 : 0;
        count += hasText(coordinate) ? 1 : 0;
        return count;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
