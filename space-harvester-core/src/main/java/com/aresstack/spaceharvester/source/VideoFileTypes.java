package com.aresstack.spaceharvester.source;

/** Classify video documents independently of the transport supplying their bytes. */
final class VideoFileTypes {

    private VideoFileTypes() {
    }

    static String streamableType(String fileName, String mediaType) {
        String name = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".mp4") || name.endsWith(".m4v")) {
            return "video/mp4";
        }
        if (name.endsWith(".webm")) {
            return "video/webm";
        }
        if (name.endsWith(".mov")) {
            return "video/quicktime";
        }
        if (name.endsWith(".mkv")) {
            return "video/x-matroska";
        }
        String type = mediaType == null ? "" : mediaType.toLowerCase(java.util.Locale.ROOT).split(";", 2)[0].strip();
        return switch (type) {
            case "video/mp4", "video/x-m4v" -> "video/mp4";
            case "video/webm", "video/quicktime", "video/x-matroska" -> type;
            default -> null;
        };
    }
}
