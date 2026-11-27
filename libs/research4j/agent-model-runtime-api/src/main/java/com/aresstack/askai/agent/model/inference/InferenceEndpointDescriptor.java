package com.aresstack.askai.agent.model.inference;

/**
 * The neutral, callable structured-inference endpoint AskAI hands to an agent: which model to call, at
 * which Ollama-compatible base URL, on which chat path, with which request timeout. Sekret-free — no
 * tokens or credentials ever live here (a plain local/loopback or configured Ollama base URL only).
 *
 * <p>The model is the centrally selected AskAI main (chat) model; the host has already resolved it to its
 * actual serving endpoint (the local runtime sidecar for a {@code local/...} model, or the configured
 * remote Ollama base URL otherwise). The agent is a dumb consumer: it POSTs to {@code baseUrl + chatPath}
 * and never decides which model to use.</p>
 */
public final class InferenceEndpointDescriptor {

    public final String model;
    public final String baseUrl;
    public final String chatPath;
    public final long timeoutMillis;

    public InferenceEndpointDescriptor(String model, String baseUrl, String chatPath, long timeoutMillis) {
        this.model = model == null ? "" : model.trim();
        this.baseUrl = baseUrl == null ? "" : stripTrailingSlash(baseUrl.trim());
        this.chatPath = chatPath == null || chatPath.trim().isEmpty() ? "/api/chat" : chatPath.trim();
        this.timeoutMillis = timeoutMillis;
    }

    private static String stripTrailingSlash(String value) {
        String normalized = value;
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
