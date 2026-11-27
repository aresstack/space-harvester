package com.aresstack.askai.agent.model.session;

import java.io.File;

/**
 * The host registry of RUNNING research sessions, so AskAI can re-publish a session's model descriptors when
 * the central model selection changes mid-session (the descriptor rewrite is what the agent's descriptor
 * watcher reacts to). Deliberately neutral: the plugin registers/unregisters through this SPI and never calls
 * any AskAI class directly, and AskAI never references a plugin/Swing type.
 *
 * <p>Register on a SUCCESSFUL session start; unregister on close, cancel, failed startup, process exit or
 * plugin disposal — the registry must contain only actually-running sessions and must never retain a
 * reference to a closed one.</p>
 */
public interface ActiveResearchSessionRegistry {

    /** Record a running session and where its per-session descriptors live. No-op on null arguments. */
    void register(String sessionId, File sessionDirectory);

    /** Forget a session that is no longer running. No-op when it was never registered. */
    void unregister(String sessionId);
}
