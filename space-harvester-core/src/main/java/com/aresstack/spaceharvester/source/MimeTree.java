package com.aresstack.spaceharvester.source;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Baum der {@code multipart}-Grenzen einer Mail: zu jeder Grenze die umgebende Grenze und der Untertyp. Tika nennt an
 * einem Teil nur die Grenze und den Untertyp seines direkten Behälters; ob ein Teil in einer bestimmten
 * {@code multipart/alternative} liegt, zeigt erst dieser Baum (ADR 0026, AP7a). Gelesen wird zeilenweise ohne
 * Inhalte im Speicher: Lange Zeilen (Base64, 8bit) werden übersprungen, eine Grenze ist höchstens 70 Zeichen lang.
 */
final class MimeTree {

    static final MimeTree EMPTY = new MimeTree(Map.of(), Map.of());

    private static final int MAX_LINE = 1_000;
    private static final int MAX_HEADER = 64 * 1024;
    private static final Pattern BOUNDARY = Pattern.compile("(?i)boundary\\s*=\\s*(?:\"([^\"]*)\"|([^;\\s]+))");

    private final Map<String, String> parents;
    private final Map<String, String> subtypes;

    private MimeTree(Map<String, String> parents, Map<String, String> subtypes) {
        this.parents = parents;
        this.subtypes = subtypes;
    }

    /** Liest den Baum aus einer Mail-Datei. */
    static MimeTree scan(Path file) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 64 * 1024)) {
            return scan(in);
        }
    }

    static MimeTree scan(InputStream in) throws IOException {
        Map<String, String> parents = new HashMap<>();
        Map<String, String> subtypes = new HashMap<>();
        Deque<String> open = new ArrayDeque<>();
        StringBuilder header = new StringBuilder();
        boolean inHeaders = true;
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        while (true) {
            int length = readLine(in, line);
            if (length < 0) {
                break;
            }
            if (length > MAX_LINE) {
                // Zu lang für eine Grenze oder einen sinnvollen Kopf: Inhalt.
                if (inHeaders) {
                    header.setLength(0);
                    inHeaders = false;
                }
                continue;
            }
            String text = line.toString(StandardCharsets.ISO_8859_1);
            if (inHeaders) {
                if (text.isBlank()) {
                    register(header.toString(), open, parents, subtypes);
                    header.setLength(0);
                    inHeaders = false;
                } else if (header.length() < MAX_HEADER) {
                    header.append(text.startsWith(" ") || text.startsWith("\t") ? text : "\n" + text);
                }
                continue;
            }
            String trimmed = text.stripTrailing();
            if (!trimmed.startsWith("--") || open.isEmpty()) {
                continue;
            }
            for (String boundary : open) {
                if (trimmed.equals("--" + boundary + "--")) {
                    while (!open.isEmpty() && !open.pop().equals(boundary)) {
                        // innere, nicht geschlossene Grenzen enden mit
                    }
                    break;
                }
                if (trimmed.equals("--" + boundary)) {
                    while (!open.peek().equals(boundary)) {
                        open.pop();
                    }
                    inHeaders = true;
                    break;
                }
            }
        }
        return new MimeTree(Map.copyOf(parents), Map.copyOf(subtypes));
    }

    private static void register(String header, Deque<String> open, Map<String, String> parents,
                                 Map<String, String> subtypes) {
        for (String field : header.split("\n")) {
            int colon = field.indexOf(':');
            if (colon < 0 || !field.substring(0, colon).strip().equalsIgnoreCase("content-type")) {
                continue;
            }
            String value = field.substring(colon + 1).strip();
            String type = value.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
            if (!type.startsWith("multipart/")) {
                return;
            }
            Matcher matcher = BOUNDARY.matcher(value);
            if (!matcher.find()) {
                return;
            }
            String boundary = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (boundary.isEmpty() || parents.containsKey(boundary) || subtypes.containsKey(boundary)) {
                return;
            }
            if (!open.isEmpty()) {
                parents.put(boundary, open.peek());
            }
            subtypes.put(boundary, type.substring("multipart/".length()));
            open.push(boundary);
            return;
        }
    }

    /** Liest eine Zeile ohne Zeilenende; gibt ihre Länge zurück (auch über der Grenze), {@code -1} am Ende. */
    private static int readLine(InputStream in, ByteArrayOutputStream line) throws IOException {
        line.reset();
        int length = 0;
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') {
                return length;
            }
            if (b != '\r' && length < MAX_LINE + 1) {
                line.write(b);
            }
            length++;
        }
        return length == 0 ? -1 : length;
    }

    /**
     * Der direkte Teil von {@code group}, in dem die Grenze {@code boundary} liegt; {@code null}, wenn sie nicht darin
     * liegt oder unbekannt ist.
     */
    String childBelow(String group, String boundary) {
        String node = boundary;
        int guard = 0;
        while (node != null && guard++ < 100) {
            String parent = parents.get(node);
            if (group.equals(parent)) {
                return node;
            }
            node = parent;
        }
        return null;
    }

    /** Die nächste umgebende {@code multipart/alternative} einer Grenze; {@code null}, wenn keine. */
    String alternativeAbove(String boundary) {
        String node = parents.get(boundary);
        int guard = 0;
        while (node != null && guard++ < 100) {
            if ("alternative".equals(subtypes.get(node))) {
                return node;
            }
            node = parents.get(node);
        }
        return null;
    }
}
