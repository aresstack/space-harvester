package com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp;

/**
 * Normalizes MVS dataset names with exactly one pair of outer single quotes.
 *
 * <p>Übernommen aus corenth ({@code proasteion.emporion.holkas.mvs}).
 */
final class MvsQuoteNormalizer {

    private MvsQuoteNormalizer() {
    }

    public static String normalize(String path) {
        if (path == null) {
            return "''";
        }
        String stripped = stripOuterQuotes(path.trim());
        if (stripped.isEmpty()) {
            return "''";
        }
        if (!stripped.endsWith(".*") && !stripped.endsWith("*")) {
            stripped = stripTrailingDots(stripped);
        }
        if (stripped.isEmpty()) {
            return "''";
        }
        return "'" + stripped + "'";
    }

    public static String unquote(String path) {
        if (path == null) {
            return "";
        }
        return stripOuterQuotes(path.trim());
    }

    public static boolean hasWildcard(String path) {
        String unquoted = unquote(path);
        return unquoted.contains("*") || unquoted.contains("%");
    }

    public static String wildcardBase(String path) {
        String unquoted = unquote(path);
        if (!hasWildcard(unquoted)) {
            return unquoted;
        }
        int lastDot = unquoted.lastIndexOf('.');
        if (lastDot <= 0) {
            return "";
        }
        return unquoted.substring(0, lastDot);
    }

    public static String wildcardQuery(String logicalPath) {
        String unquoted = unquote(logicalPath);
        if (unquoted.isEmpty()) {
            return "''";
        }
        if (unquoted.endsWith(".*") || unquoted.endsWith("*")) {
            return normalize(unquoted);
        }
        return normalize(unquoted + ".*");
    }

    public static String extractHlq(String path) {
        String unquoted = unquote(path);
        if (unquoted.isEmpty()) {
            return "";
        }
        int memberOpen = unquoted.indexOf('(');
        String dataset = memberOpen > 0 ? unquoted.substring(0, memberOpen) : unquoted;
        int firstDot = dataset.indexOf('.');
        return firstDot > 0 ? dataset.substring(0, firstDot) : dataset;
    }

    private static String stripOuterQuotes(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '\'') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '\'') {
            end--;
        }
        return start < end ? value.substring(start, end) : "";
    }

    private static String stripTrailingDots(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '.') {
            end--;
        }
        return end > 0 ? value.substring(0, end) : "";
    }
}
