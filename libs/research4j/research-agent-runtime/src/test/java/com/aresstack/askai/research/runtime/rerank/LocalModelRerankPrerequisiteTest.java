package com.aresstack.askai.research.runtime.rerank;

import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/**
 * Deterministic coverage of the live-test prerequisite resolver, driven purely by written manifests (no
 * runtime, no network): the RERANK decision comes from the published capability, and an explicitly
 * configured non-rerank model fails rather than silently skipping.
 */
public class LocalModelRerankPrerequisiteTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private static File modelRootWith(String virtualName, String state, String capabilitiesJson)
            throws Exception {
        File root = Files.createTempDirectory("askai-models").toFile();
        File dir = new File(root, "model-a");
        if (!dir.mkdirs()) {
            throw new IllegalStateException("cannot create " + dir);
        }
        String manifest = "{\"schemaVersion\":1,\"virtualName\":\"" + virtualName + "\","
                + "\"runtimeModelId\":\"X\",\"capabilities\":" + capabilitiesJson + ","
                + "\"backendSupport\":[\"cpu\"],\"state\":\"" + state + "\"}";
        Files.write(new File(dir, "askai-local-model.json").toPath(), manifest.getBytes(UTF_8));
        return root;
    }

    @Test
    public void noInstalledModelIsNotRunnable() throws Exception {
        File empty = Files.createTempDirectory("askai-empty").toFile();
        LocalModelRerankPrerequisite.Result result = LocalModelRerankPrerequisite.inspect(empty);
        assertEquals(LocalModelRerankPrerequisite.Status.NO_MODEL_INSTALLED, result.status);
        assertNull(LocalModelRerankPrerequisite.firstRerankCapableModelOrNull(empty));
    }

    @Test
    public void installedButNoRerankCapabilityIsNotRunnable() throws Exception {
        File root = modelRootWith("local/embed:latest", "RUNNABLE", "[\"embedding\"]");
        LocalModelRerankPrerequisite.Result result = LocalModelRerankPrerequisite.inspect(root);
        assertEquals(LocalModelRerankPrerequisite.Status.NO_RERANK_CAPABLE_MODEL, result.status);
        assertEquals(1, result.runnableModels.size());
        assertNull("no rerank-capable model is offered",
                LocalModelRerankPrerequisite.firstRerankCapableModelOrNull(root));
    }

    @Test
    public void rerankCapableModelIsRunnable() throws Exception {
        File root = modelRootWith("local/reranker:latest", "RUNNABLE", "[\"rerank\"]");
        LocalModelRerankPrerequisite.Result result = LocalModelRerankPrerequisite.inspect(root);
        assertEquals(LocalModelRerankPrerequisite.Status.RERANK_AVAILABLE, result.status);
        assertEquals("local/reranker:latest",
                LocalModelRerankPrerequisite.firstRerankCapableModelOrNull(root));
    }

    @Test
    public void nonRunnableModelDoesNotCount() throws Exception {
        File root = modelRootWith("local/reranker:latest", "DOWNLOADING", "[\"rerank\"]");
        assertEquals(LocalModelRerankPrerequisite.Status.NO_MODEL_INSTALLED,
                LocalModelRerankPrerequisite.inspect(root).status);
    }

    @Test
    public void explicitlyConfiguredNonRerankModelFailsRatherThanSkips() throws Exception {
        File root = modelRootWith("local/embed:latest", "RUNNABLE", "[\"embedding\"]");
        try {
            LocalModelRerankPrerequisite.requireRerankCapability(root, "local/embed:latest");
            fail("an explicitly configured non-rerank model must fail as invalid configuration");
        } catch (AssertionError expected) {
            // invalid configuration surfaced as a failure, never a skip
        }
    }

    @Test
    public void explicitlyConfiguredRerankModelPasses() throws Exception {
        File root = modelRootWith("local/reranker:latest", "RUNNABLE", "[\"rerank\"]");
        LocalModelRerankPrerequisite.requireRerankCapability(root, "local/reranker:latest");
    }
}
