package com.aresstack.askai.research.runtime.rerank;

import com.aresstack.askai.agent.model.reranker.RerankerCapability;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationDocument;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationException;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationSnapshot;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationSnapshotProvider;
import com.aresstack.askai.agent.model.reranker.RerankerEndpointDescriptor;
import com.aresstack.askai.agent.model.reranker.RerankerEndpointDescriptorCodec;
import com.aresstack.askai.agent.model.reranker.RerankerProvider;
import com.aresstack.askai.agent.model.reranker.RerankerScoreSemantics;
import com.aresstack.askai.agent.model.reranker.RerankerSelectionConfiguration;
import com.aresstack.askai.localruntime.LocalModelRuntime;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * A shared test harness that starts the REAL local-model runtime (backend=cpu) in-process — exactly as the
 * host does since the return to Java 21 — against the installed cross-encoder and exposes it as a
 * {@link RerankerConfigurationSnapshotProvider}. Returns {@code null} from {@link #startOrNull()} when no
 * installed reranker model exists, so callers can skip readably.
 */
public final class LiveLocalRerankerRuntime {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private final LocalModelRuntime runtime;
    public final String baseUrl;
    public final String modelName;

    private LiveLocalRerankerRuntime(LocalModelRuntime runtime, String modelName) {
        this.runtime = runtime;
        this.baseUrl = runtime.baseUrl();
        this.modelName = modelName;
    }

    /** Prerequisites present in this environment? (an installed local model under the app's model root) */
    public static boolean available() {
        File modelRoot = localModelRoot();
        return modelRoot.isDirectory() && hasInstalledModel(modelRoot);
    }

    /**
     * Start the runtime, or return {@code null} when prerequisites are missing — including when no installed
     * RUNNABLE model publishes the {@code rerank} capability. The capability is resolved up front from the
     * published manifests, BEFORE starting the runtime and BEFORE any rerank call, so a non-rerank model
     * (e.g. an embedding-only model) makes callers skip readably instead of failing later on an HTTP 400.
     */
    public static LiveLocalRerankerRuntime startOrNull() throws Exception {
        if (!available()) {
            return null;
        }
        File modelRoot = localModelRoot();
        // Decide from the PUBLISHED capability, not from a runtime error and not from the model name.
        String rerankModel = LocalModelRerankPrerequisite.firstRerankCapableModelOrNull(modelRoot);
        if (rerankModel == null) {
            return null;
        }
        LocalModelRuntime runtime = LocalModelRuntime.start(modelRoot.toPath(), "cpu");
        // The runtime serves exactly the installed RUNNABLE manifests, so the rerank-capable model chosen
        // from the published capability above is the served model.
        return new LiveLocalRerankerRuntime(runtime, rerankModel);
    }

    public RerankerEndpointDescriptor descriptor(int topN) {
        return new RerankerEndpointDescriptor(RerankerProvider.ASKAI_LOCAL, baseUrl, modelName,
                Arrays.asList(RerankerCapability.RERANK), RerankerScoreSemantics.RAW_LOGIT, 30_000L,
                RerankerSelectionConfiguration.topN(topN));
    }

    /** A provider that writes a real snapshot pointing at this running runtime. */
    public RerankerConfigurationSnapshotProvider asProvider(final int topN) {
        return new RerankerConfigurationSnapshotProvider() {
            public RerankerConfigurationSnapshot prepareForSession(String sessionId,
                                                                   File sessionDirectory,
                                                                   String selectedModel)
                    throws RerankerConfigurationException {
                // Same explicit-selection contract as the productive host provider: an empty or
                // foreign selection fails, it is never replaced by this runtime's model as a guess.
                if (selectedModel == null || selectedModel.trim().isEmpty()) {
                    throw new RerankerConfigurationException("no reranker model is selected");
                }
                if (!modelName.equals(selectedModel.trim())) {
                    throw new RerankerConfigurationException("selected reranker model \""
                            + selectedModel + "\" is not the installed model \"" + modelName + "\"");
                }
                RerankerConfigurationDocument document =
                        RerankerConfigurationDocument.current(0L, descriptor(topN));
                File target = new File(sessionDirectory, "reranker-config.json");
                try {
                    Files.write(target.toPath(),
                            RerankerEndpointDescriptorCodec.toJson(document).getBytes(UTF_8));
                } catch (Exception ex) {
                    throw new RerankerConfigurationException("cannot write snapshot: "
                            + ex.getMessage(), ex);
                }
                return new RerankerConfigurationSnapshot(target.getAbsoluteFile(), document);
            }
        };
    }

    public void close() {
        runtime.close();
    }

    // ------------------------------------------------------------------ helpers

    static File localModelRoot() {
        String appData = System.getenv("APPDATA");
        File base = appData != null && !appData.trim().isEmpty()
                ? new File(appData) : new File(System.getProperty("user.home"), "AppData/Roaming");
        return new File(new File(new File(base, ".askai"), "models"), "local");
    }

    private static boolean hasInstalledModel(File modelRoot) {
        File[] children = modelRoot.listFiles();
        if (children == null) {
            return false;
        }
        for (File child : children) {
            if (child.isDirectory() && new File(child, "askai-local-model.json").isFile()) {
                return true;
            }
        }
        return false;
    }
}
