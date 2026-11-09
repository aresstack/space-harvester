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

    /**
     * Wo das freigegebene Modell liegt: GitHub-Repository, Release-Tag, Asset und SHA-256 der ZIP.
     *
     * @param repository etwa {@code aresstack/space-harvester}
     * @param tag        Release-Tag, zugleich Verzeichnisname des entpackten Modells
     * @param asset      Dateiname der ZIP im Release
     * @param sha256     SHA-256 der ZIP in Kleinbuchstaben-Hex
     */
    public record Release(String repository, String tag, String asset, String sha256) {

        /** Die Release-Seite im Browser. */
        public String page() {
            return "https://github.com/" + repository + "/releases/tag/" + tag;
        }

        /** Der direkte Download der ZIP. */
        public String download() {
            return "https://github.com/" + repository + "/releases/download/" + tag + "/" + asset;
        }
    }

    /** Das freigegebene Release oder leer, wenn noch keines hinterlegt ist. */
    public static Optional<Release> release() {
        Properties properties = load();
        String repository = properties.getProperty("release.repository", "").trim();
        String tag = properties.getProperty("release.tag", "").trim();
        String asset = properties.getProperty("release.asset", "").trim();
        String sha256 = properties.getProperty("release.sha256", "").trim().toLowerCase(java.util.Locale.ROOT);
        if (repository.isEmpty() || tag.isEmpty() || asset.isEmpty() || sha256.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Release(repository, tag, asset, sha256));
    }

    private static Properties load() {
        Properties properties = new Properties();
        try (InputStream input = GermanParserModelPin.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new ModelUnavailableException(RESOURCE + " fehlt im Klassenpfad");
            }
            properties.load(input);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return properties;
    }

    /** Das erwartete Modell oder leer, wenn noch keines hinterlegt ist. */
    public static Optional<ExpectedModel> expected() {
        Properties properties = load();
        String version = properties.getProperty("model.version", "").trim();
        String sha256 = properties.getProperty("model.mcoSha256", "").trim();
        if (version.isEmpty() || sha256.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ExpectedModel(properties.getProperty("model.id").trim(), version,
                properties.getProperty("model.configName").trim(), sha256));
    }
}
