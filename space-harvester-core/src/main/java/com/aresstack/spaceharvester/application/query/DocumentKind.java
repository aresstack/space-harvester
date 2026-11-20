package com.aresstack.spaceharvester.application.query;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Die Art eines Dokuments für die Suche: Seite (HTML) oder Dateityp (PDF, Word, …). Die Art ergibt sich aus dem
 * Dateinamen (Endung), sonst aus dem Medientyp, den die Quelle gemeldet hat (wie bei Videos ohne Endung in BSCW,
 * #964); ohne beides ist es eine Seite.
 */
public enum DocumentKind {

    /** Eine Seite der Quelle (Confluence-Seite, HTML). */
    PAGE("Seiten (HTML)"),
    PDF("PDF"),
    WORD("Word"),
    PRESENTATION("PowerPoint"),
    SPREADSHEET("Excel"),
    TEXT("Text"),
    IMAGE("Bilder"),
    VIDEO("Videos"),
    AUDIO("Audio"),
    EMAIL("E-Mails"),
    ARCHIVE("Archive"),
    /** Eine Datei, deren Typ keiner der anderen Arten entspricht. */
    OTHER("Sonstige Dateien");

    private static final Map<String, DocumentKind> EXTENSIONS = Map.ofEntries(
            Map.entry("html", PAGE), Map.entry("htm", PAGE), Map.entry("xhtml", PAGE),
            Map.entry("pdf", PDF),
            Map.entry("doc", WORD), Map.entry("docx", WORD), Map.entry("docm", WORD), Map.entry("dot", WORD),
            Map.entry("dotx", WORD), Map.entry("odt", WORD), Map.entry("rtf", WORD),
            Map.entry("ppt", PRESENTATION), Map.entry("pptx", PRESENTATION), Map.entry("pptm", PRESENTATION),
            Map.entry("pps", PRESENTATION), Map.entry("ppsx", PRESENTATION), Map.entry("pot", PRESENTATION),
            Map.entry("potx", PRESENTATION), Map.entry("odp", PRESENTATION),
            Map.entry("xls", SPREADSHEET), Map.entry("xlsx", SPREADSHEET), Map.entry("xlsm", SPREADSHEET),
            Map.entry("xlsb", SPREADSHEET), Map.entry("ods", SPREADSHEET), Map.entry("csv", SPREADSHEET),
            Map.entry("txt", TEXT), Map.entry("md", TEXT), Map.entry("markdown", TEXT), Map.entry("json", TEXT),
            Map.entry("xml", TEXT), Map.entry("yaml", TEXT), Map.entry("yml", TEXT), Map.entry("log", TEXT),
            Map.entry("png", IMAGE), Map.entry("jpg", IMAGE), Map.entry("jpeg", IMAGE), Map.entry("gif", IMAGE),
            Map.entry("bmp", IMAGE), Map.entry("svg", IMAGE), Map.entry("webp", IMAGE), Map.entry("tif", IMAGE),
            Map.entry("tiff", IMAGE), Map.entry("heic", IMAGE),
            Map.entry("mp4", VIDEO), Map.entry("m4v", VIDEO), Map.entry("webm", VIDEO), Map.entry("mov", VIDEO),
            Map.entry("mkv", VIDEO), Map.entry("avi", VIDEO), Map.entry("wmv", VIDEO), Map.entry("mpg", VIDEO),
            Map.entry("mpeg", VIDEO),
            Map.entry("mp3", AUDIO), Map.entry("wav", AUDIO), Map.entry("m4a", AUDIO), Map.entry("ogg", AUDIO),
            Map.entry("flac", AUDIO), Map.entry("aac", AUDIO), Map.entry("wma", AUDIO),
            Map.entry("msg", EMAIL), Map.entry("eml", EMAIL), Map.entry("pst", EMAIL), Map.entry("mbox", EMAIL),
            Map.entry("zip", ARCHIVE), Map.entry("7z", ARCHIVE), Map.entry("rar", ARCHIVE), Map.entry("tar", ARCHIVE),
            Map.entry("gz", ARCHIVE), Map.entry("tgz", ARCHIVE), Map.entry("bz2", ARCHIVE), Map.entry("xz", ARCHIVE));

    private static final Map<String, DocumentKind> MEDIA_TYPES = Map.ofEntries(
            Map.entry("text/html", PAGE), Map.entry("application/xhtml+xml", PAGE),
            Map.entry("application/pdf", PDF),
            Map.entry("application/msword", WORD),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", WORD),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.template", WORD),
            Map.entry("application/vnd.ms-word.document.macroenabled.12", WORD),
            Map.entry("application/vnd.oasis.opendocument.text", WORD), Map.entry("application/rtf", WORD),
            Map.entry("text/rtf", WORD),
            Map.entry("application/vnd.ms-powerpoint", PRESENTATION),
            Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", PRESENTATION),
            Map.entry("application/vnd.openxmlformats-officedocument.presentationml.slideshow", PRESENTATION),
            Map.entry("application/vnd.openxmlformats-officedocument.presentationml.template", PRESENTATION),
            Map.entry("application/vnd.ms-powerpoint.presentation.macroenabled.12", PRESENTATION),
            Map.entry("application/vnd.oasis.opendocument.presentation", PRESENTATION),
            Map.entry("application/vnd.ms-excel", SPREADSHEET),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", SPREADSHEET),
            Map.entry("application/vnd.ms-excel.sheet.macroenabled.12", SPREADSHEET),
            Map.entry("application/vnd.ms-excel.sheet.binary.macroenabled.12", SPREADSHEET),
            Map.entry("application/vnd.oasis.opendocument.spreadsheet", SPREADSHEET), Map.entry("text/csv", SPREADSHEET),
            // application/json fehlt absichtlich: so legt der Cache Confluence-Seiten ab; eine JSON-Datei hat die Endung.
            Map.entry("application/xml", TEXT), Map.entry("text/xml", TEXT),
            Map.entry("application/x-yaml", TEXT), Map.entry("text/markdown", TEXT),
            Map.entry("application/vnd.ms-outlook", EMAIL), Map.entry("message/rfc822", EMAIL),
            Map.entry("application/vnd.ms-outlook-pst", EMAIL),
            Map.entry("application/zip", ARCHIVE), Map.entry("application/x-zip-compressed", ARCHIVE),
            Map.entry("application/x-7z-compressed", ARCHIVE), Map.entry("application/x-rar-compressed", ARCHIVE),
            Map.entry("application/vnd.rar", ARCHIVE), Map.entry("application/x-tar", ARCHIVE),
            Map.entry("application/gzip", ARCHIVE), Map.entry("application/x-gzip", ARCHIVE),
            Map.entry("application/x-bzip2", ARCHIVE), Map.entry("application/x-xz", ARCHIVE));

    /** Medientypen, die keine Datei meinen, sondern die Seite selbst, wie der Cache sie ablegt. */
    private static final Set<String> PAGE_MEDIA_TYPES = Set.of("text/html", "application/xhtml+xml",
            "application/json", "text/uri-list", "inode/directory");

    private final String label;

    DocumentKind(String label) {
        this.label = label;
    }

    /** Die Bezeichnung für die Oberfläche, z. B. „PDF“ oder „Seiten (HTML)“. */
    public String label() {
        return label;
    }

    /**
     * Die Art eines Dokuments aus Dateiname und gemeldetem Medientyp. Eine bekannte Endung entscheidet; ohne sie der
     * Medientyp; ohne beides (oder mit dem Medientyp einer Seite) eine Seite.
     *
     * @param fileName  Dateiname oder Titel; {@code null} oder leer ohne
     * @param mediaType Medientyp mit oder ohne Parameter, z. B. {@code application/pdf}; {@code null} ohne
     */
    public static DocumentKind of(String fileName, String mediaType) {
        String extension = extension(fileName);
        DocumentKind byExtension = extension == null ? null : EXTENSIONS.get(extension);
        if (byExtension != null) {
            return byExtension;
        }
        String type = mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT).split(";", 2)[0].strip();
        DocumentKind byType = MEDIA_TYPES.get(type);
        if (byType != null) {
            return byType;
        }
        if (type.startsWith("image/")) {
            return IMAGE;
        }
        if (type.startsWith("video/")) {
            return VIDEO;
        }
        if (type.startsWith("audio/")) {
            return AUDIO;
        }
        if (type.startsWith("text/") && !PAGE_MEDIA_TYPES.contains(type)) {
            return TEXT;
        }
        if (type.isEmpty() || PAGE_MEDIA_TYPES.contains(type) || type.startsWith("application/x-space-harvester")) {
            // Kein Medientyp oder der einer Seite: eine Seite; eine unbekannte Endung im Titel zählt nicht.
            return PAGE;
        }
        return OTHER;
    }

    /** Die Endung des Dateinamens in Kleinbuchstaben oder {@code null} ohne. */
    private static String extension(String fileName) {
        if (fileName == null) {
            return null;
        }
        String name = fileName.strip();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return null;
        }
        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        // Eine „Endung“ ohne Buchstaben, mit Leerzeichen oder länger als zehn Zeichen ist keine, etwa „V1.0“.
        if (extension.length() > 10 || !extension.chars().allMatch(Character::isLetterOrDigit)
                || extension.chars().noneMatch(Character::isLetter)) {
            return null;
        }
        return extension;
    }
}
