package com.aresstack.spaceharvester.nlp.malt;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Das kleine Testmodell aus selbst geschriebenen Sätzen (src/test/malt/train-mini.conllu), kein GSD-Modell. Als
 * Testfixture auch für die Tests der Desktop-App (#499); liegt es in einem JAR, wird es einmal in ein temporäres
 * Verzeichnis entpackt.
 */
public final class TestModel {

    public static final ExpectedModel EXPECTED = new ExpectedModel("maltparser-de-test", "0.1.0", "maltparser-de-test",
            "43cc823cf1a31f376e116c83b7d7d355d6a09fb6b79373dac028411d920dd335");
    private static final String[] FILES = {MaltModelLoader.MANIFEST, "maltparser-de-test.mco"};
    private static Path extracted;

    private TestModel() {
    }

    public static synchronized Path directory() {
        URL url = TestModel.class.getResource("test-model/" + MaltModelLoader.MANIFEST);
        if (url == null) {
            throw new IllegalStateException("Testmodell fehlt im Klassenpfad");
        }
        try {
            if ("file".equals(url.getProtocol())) {
                return Path.of(url.toURI()).getParent();
            }
            if (extracted == null) {
                Path dir = Files.createTempDirectory("maltparser-de-test");
                for (String file : FILES) {
                    try (InputStream input = TestModel.class.getResourceAsStream("test-model/" + file)) {
                        Files.copy(input, dir.resolve(file), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                extracted = dir;
            }
            return extracted;
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static MaltDependencyParser load() {
        return MaltModelLoader.load(directory(), EXPECTED);
    }
}
