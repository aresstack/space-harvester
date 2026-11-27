package com.aresstack.askai.research.runtime.inference;

import com.aresstack.askai.agent.model.inference.InferenceConfigurationDocument;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/** The agent-side inference descriptor loader: a valid file decodes; a missing or invalid one is an IOException. */
public class InferenceConfigurationLoaderTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private File write(String json) throws IOException {
        File file = folder.newFile("inference-config.json");
        Files.write(file.toPath(), json.getBytes(Charset.forName("UTF-8")));
        return file;
    }

    @Test
    public void loadsAValidDescriptor() throws Exception {
        File file = write("{\"formatVersion\":1,\"configurationRevision\":2,\"model\":\"local/m:latest\","
                + "\"baseUrl\":\"http://127.0.0.1:51000\",\"chatPath\":\"/api/chat\",\"timeoutMillis\":120000}");
        InferenceConfigurationDocument document = InferenceConfigurationLoader.load(file.getAbsolutePath());
        assertEquals("local/m:latest", document.getModel());
        assertEquals("http://127.0.0.1:51000", document.descriptor.baseUrl);
        assertEquals(2L, document.configurationRevision);
    }

    @Test
    public void aMissingFileIsAnIoException() {
        try {
            InferenceConfigurationLoader.load(new File(folder.getRoot(), "nope.json").getAbsolutePath());
            fail("expected an IOException for a missing descriptor");
        } catch (IOException expected) {
            // expected — the caller keeps the honest unavailable-fallback
        }
    }

    @Test
    public void anInvalidDescriptorIsAnIoException() throws Exception {
        File file = write("{\"formatVersion\":1,\"configurationRevision\":1,\"model\":\"\","
                + "\"baseUrl\":\"nope\",\"chatPath\":\"x\",\"timeoutMillis\":0}");
        try {
            InferenceConfigurationLoader.load(file.getAbsolutePath());
            fail("expected an IOException for an invalid descriptor");
        } catch (IOException expected) {
            // expected
        }
    }
}
