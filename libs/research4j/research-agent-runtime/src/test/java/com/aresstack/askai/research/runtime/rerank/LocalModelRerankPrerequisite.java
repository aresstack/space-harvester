package com.aresstack.askai.research.runtime.rerank;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Test-only prerequisite resolver for the live/real-model reranker tests. It decides — BEFORE any reranker
 * call — whether the local-model runtime can serve a RERANK-capable model, using ONLY the published
 * per-model capability in each installed {@code askai-local-model.json} (and only models in state
 * {@code RUNNABLE}, exactly what the runtime serves). It never interprets model names heuristically and
 * never turns a runtime error (e.g. an HTTP 400 "does not support rerank") into a skip: that decision is
 * made here, up front, from the declared capability.
 *
 * <ul>
 *   <li>no RUNNABLE model installed → {@link Status#NO_MODEL_INSTALLED} (caller skips);</li>
 *   <li>RUNNABLE models installed but none advertises {@code rerank} →
 *       {@link Status#NO_RERANK_CAPABLE_MODEL} (caller skips);</li>
 *   <li>at least one RUNNABLE model advertises {@code rerank} → {@link Status#RERANK_AVAILABLE} (run);</li>
 *   <li>a model EXPLICITLY configured as the reranker that does not advertise {@code rerank} is an invalid
 *       configuration and must FAIL — see {@link #requireRerankCapability(File, String)}.</li>
 * </ul>
 */
final class LocalModelRerankPrerequisite {

    static final String RERANK = "rerank";
    static final String STATE_RUNNABLE = "RUNNABLE";
    private static final String MANIFEST_FILE = "askai-local-model.json";
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    enum Status {
        NO_MODEL_INSTALLED,
        NO_RERANK_CAPABLE_MODEL,
        RERANK_AVAILABLE
    }

    static final class Result {
        final Status status;
        /** Virtual names of installed RUNNABLE models (what the runtime would serve). */
        final List<String> runnableModels;
        /** The subset of {@link #runnableModels} that publish the {@code rerank} capability. */
        final List<String> rerankCapableModels;

        Result(Status status, List<String> runnableModels, List<String> rerankCapableModels) {
            this.status = status;
            this.runnableModels = Collections.unmodifiableList(runnableModels);
            this.rerankCapableModels = Collections.unmodifiableList(rerankCapableModels);
        }

        boolean isRerankAvailable() {
            return status == Status.RERANK_AVAILABLE;
        }

        String describe() {
            return "installed RUNNABLE models=" + runnableModels
                    + ", rerank-capable=" + rerankCapableModels;
        }
    }

    private LocalModelRerankPrerequisite() {
    }

    /** Inspect the model root's published capabilities (never starts the runtime, never calls it). */
    static Result inspect(File modelRoot) {
        List<String> runnable = new ArrayList<String>();
        List<String> rerank = new ArrayList<String>();
        File[] children = modelRoot == null ? null : modelRoot.listFiles();
        if (children != null) {
            Arrays.sort(children);
            for (File child : children) {
                if (!child.isDirectory()) {
                    continue;
                }
                File manifest = new File(child, MANIFEST_FILE);
                if (!manifest.isFile()) {
                    continue;
                }
                Map<String, Object> json = parse(manifest);
                if (json == null || !STATE_RUNNABLE.equals(string(json.get("state")))) {
                    continue;
                }
                String virtualName = string(json.get("virtualName"));
                if (virtualName == null || virtualName.trim().isEmpty()) {
                    continue;
                }
                runnable.add(virtualName);
                if (capabilities(json).contains(RERANK)) {
                    rerank.add(virtualName);
                }
            }
        }
        Status status = !rerank.isEmpty() ? Status.RERANK_AVAILABLE
                : runnable.isEmpty() ? Status.NO_MODEL_INSTALLED
                : Status.NO_RERANK_CAPABLE_MODEL;
        return new Result(status, runnable, rerank);
    }

    /** @return a virtual name of a RUNNABLE, rerank-capable model, or {@code null} when none exists. */
    static String firstRerankCapableModelOrNull(File modelRoot) {
        List<String> capable = inspect(modelRoot).rerankCapableModels;
        return capable.isEmpty() ? null : capable.get(0);
    }

    /**
     * Explicit-configuration contract: a model named as THE reranker must publish the {@code rerank}
     * capability. If it does not (or is not installed as RUNNABLE), that is an invalid configuration and
     * the caller must FAIL — this is never downgraded to a skip.
     */
    static void requireRerankCapability(File modelRoot, String virtualName) {
        Result result = inspect(modelRoot);
        if (!result.rerankCapableModels.contains(virtualName)) {
            throw new AssertionError("reranker model \"" + virtualName + "\" is explicitly configured but "
                    + "does not advertise the RERANK capability (invalid configuration). "
                    + result.describe());
        }
    }

    private static List<String> capabilities(Map<String, Object> json) {
        List<String> out = new ArrayList<String>();
        Object caps = json.get("capabilities");
        if (caps instanceof List) {
            for (Object element : (List<?>) caps) {
                if (element instanceof String) {
                    out.add((String) element);
                }
            }
        }
        return out;
    }

    private static String string(Object value) {
        return value instanceof String ? (String) value : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(File file) {
        try {
            Object root = RerankJson.parse(new String(Files.readAllBytes(file.toPath()), UTF_8));
            return root instanceof Map ? (Map<String, Object>) root : null;
        } catch (RuntimeException | java.io.IOException unreadable) {
            return null;
        }
    }
}
