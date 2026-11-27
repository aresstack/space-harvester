package com.aresstack.askai.research.search.http;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

public final class UrlQueryBuilder {

    private final String baseUrl;
    private final List<String> parameters;

    public UrlQueryBuilder(String baseUrl) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "baseUrl must not be empty");
        }

        this.baseUrl = baseUrl;
        this.parameters = new ArrayList<String>();
    }

    public UrlQueryBuilder add(
            String name,
            String value) {

        if (value == null || value.trim().isEmpty()) {
            return this;
        }

        parameters.add(
                encode(name) + "=" + encode(value));
        return this;
    }

    public UrlQueryBuilder add(
            String name,
            int value) {

        return add(name, Integer.toString(value));
    }

    public UrlQueryBuilder add(
            String name,
            boolean value) {

        return add(name, Boolean.toString(value));
    }

    public String build() {
        if (parameters.isEmpty()) {
            return baseUrl;
        }

        StringBuilder result =
                new StringBuilder(baseUrl);

        result.append(
                baseUrl.contains("?") ? "&" : "?");

        for (int index = 0;
                index < parameters.size();
                index++) {

            if (index > 0) {
                result.append('&');
            }
            result.append(parameters.get(index));
        }

        return result.toString();
    }

    private String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException exception) {
            throw new IllegalStateException(
                    "UTF-8 is not available",
                    exception);
        }
    }
}
