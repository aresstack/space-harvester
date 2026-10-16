package com.aresstack.enterpriseai.source.api;

import com.aresstack.enterpriseai.astu.security.SecretRef;
import com.aresstack.enterpriseai.astu.source.KnowledgeSourceType;
import com.aresstack.enterpriseai.astu.source.SourceSettingField;
import com.aresstack.enterpriseai.astu.source.SourceSettings;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Typisiertes Lesen der {@link SourceSettings} eines Adapters, wie es die {@link KnowledgeSourceProvider} beim
 * Prüfen und Öffnen brauchen. Sammelt Probleme statt beim ersten abzubrechen; jede Meldung nennt die Beschriftung
 * des Felds (sonst den Schlüssel) und die Erwartung, nie den Wert.
 */
public final class SourceSettingsReader {

    private final KnowledgeSourceType type;
    private final SourceSettings settings;
    private final List<String> problems = new ArrayList<String>();

    public SourceSettingsReader(KnowledgeSourceType type, SourceSettings settings) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        this.type = type;
        this.settings = settings == null ? SourceSettings.empty() : settings;
    }

    public List<String> problems() {
        return Collections.unmodifiableList(new ArrayList<String>(problems));
    }

    public boolean hasProblems() {
        return !problems.isEmpty();
    }

    /** Vermerkt ein Problem zum Feld {@code key}. */
    public void problem(String key, String expectation) {
        SourceSettingField field = type.field(key);
        problems.add((field == null ? key : field.label()) + ": " + expectation);
    }

    /** Getrimmter Text oder {@code def}. */
    public String text(String key, String def) {
        String value = settings.get(key);
        return value.isEmpty() ? def : value;
    }

    /** Pflichttext; fehlt er, wird ein Problem vermerkt und {@code null} geliefert. */
    public String required(String key) {
        String value = text(key, null);
        if (value == null) {
            problem(key, "fehlt (Pflichtangabe)");
        }
        return value;
    }

    public int integer(String key, int def, int min, int max) {
        String value = text(key, null);
        if (value == null) {
            return def;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) {
                problem(key, "muss zwischen " + min + " und " + max + " liegen");
                return def;
            }
            return parsed;
        } catch (NumberFormatException e) {
            problem(key, "keine ganze Zahl");
            return def;
        }
    }

    public boolean bool(String key, boolean def) {
        String value = text(key, null);
        if (value == null) {
            return def;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if ("true".equals(lower) || "yes".equals(lower) || "ja".equals(lower) || "on".equals(lower)
                || "1".equals(lower)) {
            return true;
        }
        if ("false".equals(lower) || "no".equals(lower) || "nein".equals(lower) || "off".equals(lower)
                || "0".equals(lower)) {
            return false;
        }
        problem(key, "muss true oder false sein");
        return def;
    }

    /** Kommagetrennte Liste; leere Einträge werden übersprungen. */
    public List<String> list(String key) {
        List<String> items = new ArrayList<String>();
        for (String item : settings.get(key).split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                items.add(trimmed);
            }
        }
        return items;
    }

    /** Absolute http(s)-URI ohne Benutzerinfo, Query und Fragment; {@code null} bei Fehler oder fehlendem Wert. */
    public URI httpUri(String key, boolean required) {
        String value = required ? required(key) : text(key, null);
        if (value == null) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            problem(key, "keine gültige URL");
            return null;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme) || uri.getHost() == null) {
            problem(key, "muss eine absolute http(s)-URL mit Host sein");
            return null;
        }
        if (uri.getRawUserInfo() != null) {
            problem(key, "darf keine Zugangsdaten enthalten (KeePass-Eintrag verwenden)");
            return null;
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            problem(key, "darf weder Query noch Fragment enthalten");
            return null;
        }
        return uri;
    }

    public Path path(String key, Path def) {
        String value = text(key, null);
        if (value == null) {
            return def;
        }
        try {
            return Paths.get(value);
        } catch (InvalidPathException e) {
            problem(key, "kein gültiger Pfad");
            return def;
        }
    }

    /** Optionaler Verweis auf einen Tresoreintrag; die Referenz ist kein Secret. */
    public SecretRef secretRef(String key) {
        String value = text(key, null);
        if (value == null) {
            return null;
        }
        try {
            return SecretRef.of(value);
        } catch (IllegalArgumentException e) {
            problem(key, "keine gültige Secret-Referenz");
            return null;
        }
    }

    /**
     * Startpunkte, Tiefe und Obergrenze ({@code startPoints}, {@code maxDepth}, {@code maxResources}); ohne
     * Startpunkte gilt {@code defaultStartPoint}, oder es ist ein Problem, wenn er {@code null} ist.
     */
    public SourceScope scope(String defaultStartPoint, int defaultDepth, int defaultMaxResources) {
        List<String> startPoints = list("startPoints");
        if (startPoints.isEmpty()) {
            if (defaultStartPoint == null) {
                problem("startPoints", "fehlt (mindestens ein Startpunkt)");
                startPoints = Collections.singletonList("-");
            } else {
                startPoints = Collections.singletonList(defaultStartPoint);
            }
        }
        int maxDepth = integer("maxDepth", defaultDepth, 0, 100);
        int maxResources = integer("maxResources", defaultMaxResources, 1, 1000000);
        try {
            return SourceScope.builder().startPoints(startPoints).maxDepth(maxDepth).maxResources(maxResources).build();
        } catch (IllegalArgumentException e) {
            problem("startPoints", "Crawl-Umfang ungültig");
            return SourceScope.of("-");
        }
    }
}
