package com.aresstack.spaceharvester.app.ui.content.viewer;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Wie das Anhang-Overlay einen Anhang zeigt, aus Medientyp und Dateiendung: Bild (auch SVG, über den Bildweg),
 * PDF (Seiten über PDFBox), Text (Textansicht), Office (Vorschau der Quelle, etwa Confluences Dokumentkonvertierung)
 * oder sonstige Datei (nur Name, Typ, Größe, Speichern und Öffnen). Die Endung zählt, wenn der Medientyp nichts sagt
 * ({@code application/octet-stream}). Die Listen sind eine Auswahl gängiger Typen (kannst du kippen); was nicht
 * erkannt wird, bleibt trotzdem in der Liste und öffnet das Overlay.
 *
 * <p>Kopie von {@code ReaderAttachmentKind} aus dem Enterprise AI Client (exedra ui.reader); unverändert bis auf das
 * Paket. Die Office-Vorschau bietet die App nur bei Quellen mit {@code SourceCapability.ATTACHMENT_PREVIEWS} an.</p>
 */
public enum ReaderAttachmentKind {
    IMAGE, PDF, TEXT, OFFICE, OTHER;

    private static final Set<String> IMAGE_EXTENSIONS = set("png", "jpg", "jpeg", "gif", "bmp", "svg", "webp");
    private static final Set<String> TEXT_EXTENSIONS = set("txt", "log", "md", "csv", "tsv", "json", "xml", "yml",
            "yaml", "properties", "ini", "cfg", "conf", "sql", "java", "js", "ts", "py", "sh", "bat", "cmd", "ps1",
            "html", "htm", "css", "jcl", "cbl", "cob", "cpy", "nsp", "nsn", "gradle", "kt", "groovy", "c", "h", "cpp",
            "cs", "go", "rs", "toml", "wsdl", "xsd");
    private static final Set<String> OFFICE_EXTENSIONS = set("doc", "docx", "docm", "dot", "dotx", "xls", "xlsx",
            "xlsm", "xlsb", "ppt", "pptx", "pptm", "pps", "ppsx", "odt", "ods", "odp", "rtf", "vsd", "vsdx", "msg");

    static ReaderAttachmentKind of(String mediaType, String fileName) {
        String type = baseType(mediaType);
        String extension = extension(fileName);
        if (type.startsWith("image/") || IMAGE_EXTENSIONS.contains(extension)) {
            return IMAGE;
        }
        if ("application/pdf".equals(type) || "pdf".equals(extension)) {
            return PDF;
        }
        if (isOffice(type) || OFFICE_EXTENSIONS.contains(extension)) {
            return OFFICE;
        }
        if (isText(type) || TEXT_EXTENSIONS.contains(extension)) {
            return TEXT;
        }
        return OTHER;
    }

    private static boolean isText(String type) {
        return type.startsWith("text/") || type.endsWith("/json") || type.endsWith("+json") || type.endsWith("/xml")
                || type.endsWith("+xml") || type.endsWith("/javascript") || type.endsWith("/x-yaml")
                || type.endsWith("/yaml") || type.endsWith("/x-sh") || type.endsWith("/sql");
    }

    private static boolean isOffice(String type) {
        return type.equals("application/msword") || type.equals("application/rtf")
                || type.startsWith("application/vnd.openxmlformats-officedocument.")
                || type.startsWith("application/vnd.ms-") || type.startsWith("application/vnd.oasis.opendocument.")
                || type.equals("application/vnd.visio");
    }

    /** Kurzer Typname für Liste und Overlay: „PDF“, „Word“, „PNG“ …; sonst die Endung oder der Medientyp. */
    static String label(String mediaType, String fileName) {
        String type = baseType(mediaType);
        String extension = extension(fileName);
        if ("application/pdf".equals(type) || "pdf".equals(extension)) {
            return "PDF";
        }
        if (type.contains("wordprocessing") || type.equals("application/msword") || extension.startsWith("doc")
                || "odt".equals(extension) || "rtf".equals(extension)) {
            return "Word";
        }
        if (type.contains("spreadsheet") || type.contains("ms-excel") || extension.startsWith("xls")
                || "ods".equals(extension)) {
            return "Excel";
        }
        if (type.contains("presentation") || type.contains("powerpoint") || extension.startsWith("pp")
                || "odp".equals(extension)) {
            return "PowerPoint";
        }
        if (type.contains("svg") || "svg".equals(extension)) {
            return "SVG";
        }
        if (type.equals("image/jpeg") || "jpg".equals(extension) || "jpeg".equals(extension)) {
            return "JPEG";
        }
        if (type.startsWith("image/")) {
            return type.substring(6).toUpperCase(Locale.ROOT);
        }
        if (!extension.isEmpty()) {
            return extension.toUpperCase(Locale.ROOT);
        }
        return type.isEmpty() ? "Datei" : type;
    }

    static String baseType(String mediaType) {
        String type = mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT);
        int semicolon = type.indexOf(';');
        return (semicolon < 0 ? type : type.substring(0, semicolon)).trim();
    }

    static String extension(String fileName) {
        String name = fileName == null ? "" : fileName.trim();
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static Set<String> set(String... values) {
        return new HashSet<String>(Arrays.asList(values));
    }
}
