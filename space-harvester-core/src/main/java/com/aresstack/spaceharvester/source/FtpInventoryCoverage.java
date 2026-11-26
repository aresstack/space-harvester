package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.source.api.SourceScope;

import java.util.Locale;

/** Decide whether an unlisted FTP resource belongs to a fully enumerated scope. */
final class FtpInventoryCoverage {

    private FtpInventoryCoverage() {
    }

    static boolean covers(SourceScope scope, String path) {
        // Treat explicitly truncated inventories conservatively.
        if (scope.maxDepth() != Integer.MAX_VALUE || scope.maxResources() != Integer.MAX_VALUE) {
            return false;
        }
        return scope.startPoints().stream().anyMatch(point -> contains(point, path));
    }

    private static boolean contains(String point, String path) {
        String start = point.trim();
        if (start.isEmpty() || "-".equals(start)) {
            return false;
        }
        if (start.startsWith("/") || start.contains("/") || path.contains("/")) {
            String root = start.replaceFirst("^/+", "").replaceFirst("/+$", "");
            return root.isEmpty() || path.equals(root) || path.startsWith(root + "/");
        }
        String root = start.replaceFirst("^'+", "").replaceFirst("'+$", "")
                .replaceFirst("\\.\\*$", "").toUpperCase(Locale.ROOT);
        String member = path.toUpperCase(Locale.ROOT);
        return !root.isEmpty() && (member.equals(root) || member.startsWith(root + ".")
                || member.startsWith(root + "("));
    }
}