package com.aresstack.askai.research.runtime.role;

import com.aresstack.askai.research.runtime.team.MainModelChat;

/**
 * Turns a {@link ResearchRole} into the chat transport that runs it: a {@link MainModelChat} bound to the
 * role's model slot and context size over the host's model port. The session provides the productive
 * factory; tests and the benchmark harness inject their own (a port fake, a token meter), so every role
 * caller — today's five roles, the orchestrator and the executor of lane F — reaches a model only through
 * this seam and never names a model.
 */
public interface RoleChatFactory {

    /** Never null: a host without a usable model yields a chat that answers with an honest failure. */
    MainModelChat chatFor(ResearchRole role);
}
