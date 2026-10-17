package com.aresstack.spaceharvester.nlp.malt;

import java.net.URISyntaxException;
import java.nio.file.Path;

/** Das kleine Testmodell aus selbst geschriebenen Sätzen (src/test/malt/train-mini.conllu), kein GSD-Modell. */
public final class TestModel {

    public static final ExpectedModel EXPECTED = new ExpectedModel("maltparser-de-test", "0.1.0", "maltparser-de-test",
            "43cc823cf1a31f376e116c83b7d7d355d6a09fb6b79373dac028411d920dd335");

    private TestModel() {
    }

    public static Path directory() {
        try {
            return Path.of(TestModel.class.getResource("test-model").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    public static MaltDependencyParser load() {
        return MaltModelLoader.load(directory(), EXPECTED);
    }
}
