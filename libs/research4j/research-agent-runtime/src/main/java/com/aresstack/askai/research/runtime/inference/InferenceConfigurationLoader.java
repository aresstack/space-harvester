package com.aresstack.askai.research.runtime.inference;

import com.aresstack.askai.agent.model.inference.InferenceConfigurationCodec;
import com.aresstack.askai.agent.model.inference.InferenceConfigurationDocument;
import com.aresstack.askai.agent.model.inference.InferenceConfigurationValidationResult;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;

/**
 * Reads and strictly validates the {@code inference-config.json} named by {@code ASKAI_INFERENCE_CONFIG}.
 * An invalid or missing file is an {@link IOException}; the caller decides whether that is fatal (it is not —
 * the agent then keeps the honest unavailable-fallback for SERP layout repair). Mirrors the reranker loader.
 */
public final class InferenceConfigurationLoader {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private InferenceConfigurationLoader() {
    }

    public static InferenceConfigurationDocument load(String path) throws IOException {
        if (path == null || path.trim().isEmpty()) {
            throw new IOException("inference configuration path is empty");
        }
        File file = new File(path);
        if (!file.isFile()) {
            throw new IOException("inference configuration file does not exist: " + file);
        }
        String json = new String(Files.readAllBytes(file.toPath()), UTF_8);
        InferenceConfigurationValidationResult result = InferenceConfigurationCodec.parse(json);
        if (!result.valid) {
            throw new IOException("inference configuration is invalid (" + file + "):\n"
                    + result.describe());
        }
        return result.document;
    }
}
