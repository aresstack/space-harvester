package com.aresstack.spaceharvester.nlp.malt;

import com.aresstack.spaceharvester.nlp.model.ModelUnavailableException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.Properties;

/**
 * Das fest hinterlegte deutsche Parsermodell aus #99 ({@code german-parser-model.properties}): Release-Tag, Asset
 * und SHA-256 der ZIP für den Download-Task, Modell-ID, Version, Konfigurationsname und SHA-256 der {@code .mco}
 * für die Prüfung beim Laden. Solange #99 nichts veröffentlicht hat, ist die Version leer.
 */
public final class GermanParserModelPin {

    static final String RESOURCE = "german-parser-model.properties";

    private GermanParserModelPin() {
    }

    /** Das erwartete Modell oder leer, wenn noch keines hinterlegt ist. */
    public static Optional<ExpectedModel> expected() {
        Properties properties = new Properties();
        try (InputStream input = GermanParserModelPin.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new ModelUnavailableException(RESOURCE + " fehlt im Klassenpfad");
            }
            properties.load(input);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String version = properties.getProperty("model.version", "").trim();
        String sha256 = properties.getProperty("model.mcoSha256", "").trim();
        if (version.isEmpty() || sha256.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ExpectedModel(properties.getProperty("model.id").trim(), version,
                properties.getProperty("model.configName").trim(), sha256));
    }
}
