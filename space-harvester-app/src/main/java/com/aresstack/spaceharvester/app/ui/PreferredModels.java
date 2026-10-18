package com.aresstack.spaceharvester.app.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Die Vorzugsmodelle aus {@code setup-models.properties} neben dieser Klasse, wie {@code PreferredModels} des
 * Enterprise AI Client: Modellnamen sind Konfiguration und stehen nicht im Code. Sie gelten nur, solange nichts
 * gewählt ist.
 */
final class PreferredModels {

    static final String RESOURCE = "setup-models.properties";

    private final Properties properties;

    PreferredModels(Properties properties) {
        this.properties = properties;
    }

    static PreferredModels bundled() {
        Properties properties = new Properties();
        InputStream in = PreferredModels.class.getResourceAsStream(RESOURCE);
        if (in != null) {
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException e) {
                properties = new Properties();
            }
        }
        return new PreferredModels(properties);
    }

    /** Das Vorzugsmodell einer Art ({@code chat}, {@code embedding}) oder leer. */
    String model(String kind) {
        return properties.getProperty(kind, "").trim();
    }

    /** Die Teilwörter für die Ersatzwahl ({@code <art>.match}); leer heißt: der Teil nach dem letzten „/“. */
    List<String> match(String kind) {
        List<String> words = new ArrayList<>();
        for (String word : properties.getProperty(kind + ".match", "").split(",")) {
            if (!word.isBlank()) {
                words.add(word.trim());
            }
        }
        return List.copyOf(words);
    }
}
