package com.aresstack.askai.research.search.config;

import java.util.Collection;

public final class ConfigurationValidation {

    private ConfigurationValidation() {
    }

    public static String requireText(
            String value,
            String propertyName) {

        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    propertyName + " must not be empty");
        }
        return value.trim();
    }

    public static int requireRange(
            int value,
            int minimum,
            int maximum,
            String propertyName) {

        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    propertyName + " must be between "
                            + minimum + " and " + maximum);
        }
        return value;
    }

    public static Integer requireOptionalRange(
            Integer value,
            int minimum,
            int maximum,
            String propertyName) {

        if (value == null) {
            return null;
        }
        requireRange(value.intValue(), minimum, maximum, propertyName);
        return value;
    }

    public static <T> T requireNonNull(
            T value,
            String propertyName) {

        if (value == null) {
            throw new IllegalArgumentException(
                    propertyName + " must not be null");
        }
        return value;
    }

    public static void requireMaximumSize(
            Collection<?> values,
            int maximum,
            String propertyName) {

        if (values != null && values.size() > maximum) {
            throw new IllegalArgumentException(
                    propertyName + " may contain at most "
                            + maximum + " entries");
        }
    }

    public static void validateTransport(
            HttpTransportConfiguration transport) {

        requireNonNull(transport, "transport");
        requireRange(
                transport.getConnectTimeoutMillis(),
                1,
                Integer.MAX_VALUE,
                "transport.connectTimeoutMillis");
        requireRange(
                transport.getReadTimeoutMillis(),
                1,
                Integer.MAX_VALUE,
                "transport.readTimeoutMillis");
        requireRange(
                transport.getRequestTimeoutMillis(),
                1,
                Integer.MAX_VALUE,
                "transport.requestTimeoutMillis");
        requireRange(
                transport.getMaxConnections(),
                1,
                Integer.MAX_VALUE,
                "transport.maxConnections");
        requireRange(
                transport.getMaxConnectionsPerHost(),
                1,
                Integer.MAX_VALUE,
                "transport.maxConnectionsPerHost");
        requireText(
                transport.getUserAgent(),
                "transport.userAgent");
    }
}
