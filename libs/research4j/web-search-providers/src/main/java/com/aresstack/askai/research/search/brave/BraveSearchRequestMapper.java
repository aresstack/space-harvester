package com.aresstack.askai.research.search.brave;

import com.aresstack.askai.research.search.api.WebSearchRequest;
import com.aresstack.askai.research.search.http.UrlQueryBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BraveSearchRequestMapper {

    public String createUrl(
            BraveSearchConfiguration configuration,
            WebSearchRequest request) {

        int count = Math.min(
                Math.min(request.getMaximumResults(), 20),
                configuration.getCount());

        int offset = request.getOffset() > 0
                ? Math.min(request.getOffset() / Math.max(count, 1), 9)
                : configuration.getOffset();

        UrlQueryBuilder url = new UrlQueryBuilder(
                configuration.getEndpoint())
                .add("q", request.getQuery())
                .add("country", firstText(
                        request.getCountryCode(),
                        configuration.getCountry()))
                .add("search_lang", firstText(
                        request.getLanguageCode(),
                        configuration.getSearchLanguage()))
                .add("ui_lang", configuration.getUiLanguage())
                .add("count", count)
                .add("offset", offset)
                .add("safesearch", apiValue(
                        configuration.getSafeSearch()))
                .add("freshness", freshnessValue(
                        configuration.getFreshness()))
                .add("text_decorations",
                        configuration.isTextDecorations())
                .add("spellcheck",
                        configuration.isSpellcheck())
                .add("result_filter", joinResultTypes(
                        configuration.getResultFilter()))
                .add("goggles", joinStrings(
                        configuration.getGoggles()))
                .add("extra_snippets",
                        configuration.isExtraSnippets())
                .add("operators", configuration.isOperators())
                .add("units", unitsValue(
                        configuration.getUnits()))
                .add("enable_rich_callback",
                        configuration.isEnableRichCallback())
                .add("include_fetch_metadata",
                        configuration.isIncludeFetchMetadata());

        return url.build();
    }

    public Map<String, String> createHeaders(
            BraveSearchConfiguration configuration) {

        Map<String, String> headers =
                new LinkedHashMap<String, String>();

        add(headers, "Api-Version", configuration.getApiVersion());

        BraveLocationConfiguration location =
                configuration.getLocation();

        if (location == null) {
            return headers;
        }

        if (location.getLatitude() != null) {
            add(headers, "X-Loc-Lat",
                    location.getLatitude().toString());
            add(headers, "X-Loc-Long",
                    location.getLongitude().toString());
        }

        add(headers, "X-Loc-Timezone", location.getTimezone());
        add(headers, "X-Loc-City", location.getCity());
        add(headers, "X-Loc-State-Code", location.getStateCode());
        add(headers, "X-Loc-State-Name", location.getStateName());
        add(headers, "X-Loc-Country", location.getCountryCode());
        add(headers, "X-Loc-Postal-Code", location.getPostalCode());

        return headers;
    }

    private String firstText(String first, String second) {
        if (first != null && !first.trim().isEmpty()) {
            return first;
        }
        return second;
    }

    private String apiValue(BraveSafeSearch value) {
        return value == null ? null : value.getApiValue();
    }

    private String freshnessValue(
            BraveFreshnessConfiguration value) {

        return value == null ? null : value.toApiValue();
    }

    private String unitsValue(BraveUnits value) {
        return value == null ? null : value.getApiValue();
    }

    private String joinResultTypes(
            List<BraveResultType> values) {

        if (values == null || values.isEmpty()) {
            return null;
        }

        StringBuilder result = new StringBuilder();
        for (BraveResultType value : values) {
            if (value == null) {
                continue;
            }
            if (result.length() > 0) {
                result.append(',');
            }
            result.append(value.getApiValue());
        }
        return result.length() == 0 ? null : result.toString();
    }

    private String joinStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }

        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append(',');
            }
            result.append(value.trim());
        }
        return result.length() == 0 ? null : result.toString();
    }

    private void add(
            Map<String, String> headers,
            String name,
            String value) {

        if (value != null && !value.trim().isEmpty()) {
            headers.put(name, value.trim());
        }
    }
}
