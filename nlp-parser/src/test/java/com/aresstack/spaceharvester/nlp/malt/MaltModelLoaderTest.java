package com.aresstack.spaceharvester.nlp.malt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aresstack.spaceharvester.nlp.model.ModelUnavailableException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Prüfungen vor dem Laden: Manifest, Version, Eingabeschema, Dateiname, SHA-256. */
class MaltModelLoaderTest {

    @TempDir
    Path dir;

    @Test
    void loadsTheVerifiedTestModel() {
        MaltDependencyParser parser = TestModel.load();

        assertEquals("maltparser 1.9.2", parser.component().tool());
        assertEquals("maltparser-de-test 0.1.0", parser.component().model());
        assertEquals(TestModel.EXPECTED.mcoSha256(), parser.component().modelSha256());
        assertTrue(parser.manifest().labels().contains("obl:arg"));
    }

    @Test
    void rejectsWrongChecksum() throws IOException {
        copyTestModel();
        ExpectedModel other = new ExpectedModel("maltparser-de-test", "0.1.0", "maltparser-de-test", "0".repeat(64));

        ModelUnavailableException error = assertThrows(ModelUnavailableException.class,
                () -> MaltModelLoader.load(dir, other));
        assertTrue(error.getMessage().contains("SHA-256"), error.getMessage());
    }

    @Test
    void rejectsChangedModelFile() throws IOException {
        copyTestModel();
        Files.write(dir.resolve("maltparser-de-test.mco"), new byte[]{1}, java.nio.file.StandardOpenOption.APPEND);

        assertThrows(ModelUnavailableException.class, () -> MaltModelLoader.load(dir, TestModel.EXPECTED));
    }

    @Test
    void rejectsOtherVersion() throws IOException {
        copyTestModel();
        ExpectedModel newer = new ExpectedModel("maltparser-de-test", "0.2.0", "maltparser-de-test",
                TestModel.EXPECTED.mcoSha256());

        ModelUnavailableException error = assertThrows(ModelUnavailableException.class,
                () -> MaltModelLoader.load(dir, newer));
        assertTrue(error.getMessage().contains("0.2.0"), error.getMessage());
    }

    @Test
    void rejectsInputColumnsThePreprocessingDoesNotDeliver() throws IOException {
        copyTestModel();
        Path manifest = dir.resolve(MaltModelLoader.MANIFEST);
        Files.writeString(manifest, Files.readString(manifest).replace("\"UPOS\"", "\"UPOS\", \"FEATS\""));

        ModelUnavailableException error = assertThrows(ModelUnavailableException.class,
                () -> MaltModelLoader.load(dir, TestModel.EXPECTED));
        assertTrue(error.getMessage().contains("FEATS"), error.getMessage());
    }

    @Test
    void rejectsMissingManifest() throws IOException {
        copyTestModel();
        Files.delete(dir.resolve(MaltModelLoader.MANIFEST));

        assertThrows(ModelUnavailableException.class, () -> MaltModelLoader.load(dir, TestModel.EXPECTED));
    }

    @Test
    void pinnedModelIsNotYetPublished() {
        // Bis #99 ein lizenzgeprüftes Modell veröffentlicht, ist nichts hinterlegt und nichts wird geladen.
        assertTrue(GermanParserModelPin.expected().isEmpty());
        ModelUnavailableException error = assertThrows(ModelUnavailableException.class,
                () -> MaltModelLoader.loadPinned(dir));
        assertTrue(error.getMessage().contains("#99"), error.getMessage());
    }

    private void copyTestModel() throws IOException {
        for (String name : new String[]{"maltparser-de-test.mco", MaltModelLoader.MANIFEST}) {
            Files.copy(TestModel.directory().resolve(name), dir.resolve(name));
        }
    }
}
