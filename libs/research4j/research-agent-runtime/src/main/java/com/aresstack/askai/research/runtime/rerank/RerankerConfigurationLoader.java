package com.aresstack.askai.research.runtime.rerank;

import com.aresstack.askai.agent.model.reranker.RerankerConfigurationDocument;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationValidationResult;
import com.aresstack.askai.agent.model.reranker.RerankerEndpointDescriptorCodec;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;

/**
 * A5c: reads the host-published reranker START snapshot (the file named by {@code ASKAI_RERANKER_CONFIG})
 * once at agent start-up and decodes it through the strict shared codec. There is no live
 * reconfiguration and no default fallback: a present-but-invalid snapshot is a hard configuration error,
 * because reranking is mandatory and a guessed endpoint would silently corrupt which pages get opened.
 */
public final class RerankerConfigurationLoader {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private RerankerConfigurationLoader() {
    }

    /**
     * Load and validate the snapshot at {@code path}.
     *
     * @throws IOException if the file cannot be read, or its content is not a contract-valid snapshot
     */
    public static RerankerConfigurationDocument load(String path) throws IOException {
        if (path == null || path.trim().isEmpty()) {
            throw new IOException("reranker configuration path is empty");
        }
        File file = new File(path);
        if (!file.isFile()) {
            throw new IOException("reranker configuration file does not exist: " + file);
        }
        String json = new String(Files.readAllBytes(file.toPath()), UTF_8);
        RerankerConfigurationValidationResult result = RerankerEndpointDescriptorCodec.parse(json);
        if (!result.valid) {
            throw new IOException("reranker configuration is invalid (" + file + "):\n"
                    + result.describe());
        }
        return result.document;
    }
}
