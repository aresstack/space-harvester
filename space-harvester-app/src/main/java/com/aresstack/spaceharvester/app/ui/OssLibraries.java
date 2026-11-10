package com.aresstack.spaceharvester.app.ui;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Liest den Lizenzbericht des Builds ({@code /oss-licenses.json}, geschrieben von {@code gradle/oss-licenses.gradle}
 * vor {@code processResources}) aus dem Klassenpfad; wie {@code ClasspathOssLicenseRepository} in aresstack/askai,
 * nur mit Jackson statt eines eigenen Parsers.
 */
final class OssLibraries {

    static final String RESOURCE = "/oss-licenses.json";

    private OssLibraries() {
    }

    /** Alle Bibliotheken des Berichts in Berichtsreihenfolge (Gruppe, Artefakt, Version). */
    static List<OssLibrary> read() throws IOException {
        try (InputStream in = OssLibraries.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("Der Lizenzbericht oss-licenses.json fehlt im Build "
                        + "(Gradle-Aufgabe generateOssLicenseReport).");
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /** Liest einen Bericht; jede Zeile braucht Gruppe, Artefakt, Version und Lizenz, das Modul ist freiwillig. */
    static List<OssLibrary> parse(String json) throws IOException {
        JsonNode root;
        try {
            root = JsonMapper.shared().readTree(json);
        } catch (RuntimeException e) {
            throw new IOException("Der Lizenzbericht oss-licenses.json ist kein gültiges JSON: " + e.getMessage(), e);
        }
        if (!root.isArray()) {
            throw new IOException("Der Lizenzbericht oss-licenses.json enthält keine Liste.");
        }
        List<OssLibrary> libraries = new ArrayList<>();
        for (JsonNode entry : root) {
            libraries.add(new OssLibrary(required(entry, "group"), required(entry, "artifact"),
                    required(entry, "version"), required(entry, "license"),
                    entry.path("module").asString("")));
        }
        return List.copyOf(libraries);
    }

    private static String required(JsonNode entry, String field) throws IOException {
        JsonNode value = entry.get(field);
        if (value == null || !value.isString()) {
            throw new IOException("Im Lizenzbericht oss-licenses.json fehlt das Feld " + field + ".");
        }
        return value.asString();
    }
}
