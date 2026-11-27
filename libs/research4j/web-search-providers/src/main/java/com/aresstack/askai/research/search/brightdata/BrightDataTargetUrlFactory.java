package com.aresstack.askai.research.search.brightdata;

import com.aresstack.askai.research.search.api.WebSearchRequest;
import com.aresstack.askai.research.search.http.UrlQueryBuilder;

import java.util.Map;

public final class BrightDataTargetUrlFactory {

    public String create(
            BrightDataSearchConfiguration configuration,
            WebSearchRequest request) {

        BrightDataSearchEngine engine =
                configuration.getSearchEngine();

        String endpoint = hasText(
                configuration.getSearchEngineEndpoint())
                ? configuration.getSearchEngineEndpoint()
                : engine.getDefaultEndpoint();

        String country = firstText(
                request.getCountryCode(),
                configuration.getCountry());
        String language = firstText(
                request.getLanguageCode(),
                configuration.getLanguage());
        int maximumResults = Math.min(
                request.getMaximumResults(),
                configuration.getResultsPerPage());
        int offset = request.getOffset() > 0
                ? request.getOffset()
                : configuration.getStartOffset();

        UrlQueryBuilder url = new UrlQueryBuilder(endpoint);
        switch (engine) {
            case GOOGLE:
                url.add("q", request.getQuery())
                        .add("hl", language)
                        .add("gl", lower(country))
                        .add("num", maximumResults)
                        .add("start", offset);
                addGoogleSafeSearch(url, configuration.getSafeSearch());
                break;
            case BING:
                url.add("q", request.getQuery())
                        .add("setlang", language)
                        .add("cc", lower(country))
                        .add("count", maximumResults)
                        .add("first", offset + 1);
                addBingSafeSearch(url, configuration.getSafeSearch());
                break;
            case DUCKDUCKGO:
                url.add("q", request.getQuery())
                        .add("kl", lower(country) + "-" + lower(language));
                addDuckDuckGoSafeSearch(
                        url,
                        configuration.getSafeSearch());
                break;
            case YANDEX:
                url.add("text", request.getQuery())
                        .add("lang", language)
                        .add("p", offset / Math.max(maximumResults, 1));
                break;
            default:
                throw new IllegalStateException(
                        "Unsupported search engine: " + engine);
        }

        Map<String, String> additional =
                configuration.getAdditionalSearchParameters();
        if (additional != null) {
            for (Map.Entry<String, String> entry
                    : additional.entrySet()) {
                url.add(entry.getKey(), entry.getValue());
            }
        }

        return url.build();
    }

    private void addGoogleSafeSearch(
            UrlQueryBuilder url,
            BrightDataSafeSearch safeSearch) {

        if (safeSearch == BrightDataSafeSearch.STRICT) {
            url.add("safe", "active");
        } else if (safeSearch == BrightDataSafeSearch.OFF) {
            url.add("safe", "off");
        }
    }

    private void addBingSafeSearch(
            UrlQueryBuilder url,
            BrightDataSafeSearch safeSearch) {

        if (safeSearch == BrightDataSafeSearch.STRICT) {
            url.add("safeSearch", "Strict");
        } else if (safeSearch == BrightDataSafeSearch.MODERATE) {
            url.add("safeSearch", "Moderate");
        } else if (safeSearch == BrightDataSafeSearch.OFF) {
            url.add("safeSearch", "Off");
        }
    }

    private void addDuckDuckGoSafeSearch(
            UrlQueryBuilder url,
            BrightDataSafeSearch safeSearch) {

        if (safeSearch == BrightDataSafeSearch.STRICT) {
            url.add("kp", "1");
        } else if (safeSearch == BrightDataSafeSearch.OFF) {
            url.add("kp", "-2");
        }
    }

    private String firstText(String first, String second) {
        return hasText(first) ? first : second;
    }

    private String lower(String value) {
        return value == null ? null : value.toLowerCase();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
