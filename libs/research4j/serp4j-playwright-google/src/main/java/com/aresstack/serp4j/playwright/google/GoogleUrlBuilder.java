package com.aresstack.serp4j.playwright.google;

import com.aresstack.serp4j.core.model.SearchLocale;
import com.aresstack.serp4j.core.model.SearchRequest;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.Objects;

/**
 * Builds the classic Google web search URL {@code https://www.google.com/search?q=…} (Serp4J #6).
 *
 * <p>Parameters: {@code q} (URL-encoded query), {@code hl} (interface language), {@code gl} (country),
 * {@code start} (offset, only when &gt; 0). {@code num} is not sent because Google no longer honours it; a page has
 * about ten results. {@code udm} is never set, so AI Mode ({@code udm=50}) cannot be activated. Immutable.</p>
 */
public final class GoogleUrlBuilder {

    /** Default search endpoint. */
    public static final String DEFAULT_BASE_URL = "https://www.google.com/search";

    private final String baseUrl;
    private final String defaultLanguage;
    private final String defaultCountry;

    /**
     * @param baseUrl         search endpoint, normally {@link #DEFAULT_BASE_URL}
     * @param defaultLanguage {@code hl} when the request names none; may be {@code null}
     * @param defaultCountry  {@code gl} when the request names none; may be {@code null}
     */
    public GoogleUrlBuilder(String baseUrl, String defaultLanguage, String defaultCountry) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.defaultLanguage = blankToNull(defaultLanguage);
        this.defaultCountry = blankToNull(defaultCountry);
    }

    /** Builder with {@link #DEFAULT_BASE_URL} and no defaults. */
    public static GoogleUrlBuilder defaults() {
        return new GoogleUrlBuilder(DEFAULT_BASE_URL, null, null);
    }

    /**
     * @param request search request; query must not be blank
     * @return the search URL, never containing {@code udm}
     */
    public String build(SearchRequest request) {
        Objects.requireNonNull(request, "request");
        String query = request.getQuery();
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        String language = defaultLanguage;
        String country = defaultCountry;
        if (request.getLocale().isPresent()) {
            SearchLocale locale = request.getLocale().get();
            if (locale.getUiLanguage().isPresent()) {
                language = locale.getUiLanguage().get();
            } else if (locale.getLanguageCode().isPresent()) {
                language = locale.getLanguageCode().get();
            }
            if (locale.getCountryCode().isPresent()) {
                country = locale.getCountryCode().get();
            }
        }
        StringBuilder url = new StringBuilder(baseUrl).append("?q=").append(encode(query.trim()));
        if (language != null) {
            url.append("&hl=").append(encode(language.toLowerCase(Locale.ROOT)));
        }
        if (country != null) {
            url.append("&gl=").append(encode(country.toLowerCase(Locale.ROOT)));
        }
        int offset = request.getPaging().getOffset();
        if (offset > 0) {
            url.append("&start=").append(offset);
        }
        return url.toString();
    }

    /** Whether {@code url} would activate Google AI Mode ({@code udm=50}); used as a guard in tests and providers. */
    public static boolean isAiModeUrl(String url) {
        return url != null && url.matches("(?i).*[?&]udm=50(&.*|#.*)?$");
    }

    static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 missing", e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    @Override
    public String toString() {
        return "GoogleUrlBuilder{" + baseUrl + ", hl=" + defaultLanguage + ", gl=" + defaultCountry + '}';
    }
}
