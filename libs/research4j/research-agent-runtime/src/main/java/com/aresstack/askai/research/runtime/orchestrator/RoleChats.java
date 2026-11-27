package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.agent.model.port.ModelPort;
import com.aresstack.askai.research.runtime.role.ResearchRole;
import com.aresstack.askai.research.runtime.team.LoggedMainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.PortMainModelChat;
import com.aresstack.askai.research.runtime.team.UnavailableMainModelChat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The orchestrated run's model access: a role's chat transport whose token usage is booked on one counter
 * (the task's budget, or the orchestrator's share of the run budget). It is the run's counterpart of the
 * session's {@code RoleChatFactory}: the same role, slot and context, plus the accounting. Tests and the
 * benchmark inject their own.
 */
public interface RoleChats {

    /** Never null; a host without a model port yields a chat that answers with an honest failure. */
    MainModelChat chatFor(ResearchRole role, AtomicLong tokens);

    /** The productive source over the host's model port; {@code port == null} yields unavailable chats. */
    static RoleChats overPort(final ModelPort port, final Duration timeout) {
        return new RoleChats() {
            @Override
            public MainModelChat chatFor(ResearchRole role, AtomicLong tokens) {
                if (port == null) {
                    return new UnavailableMainModelChat("the host publishes no model port");
                }
                return new LoggedMainModelChat(
                        PortMainModelChat.forRole(new MeteredModelPort(port, tokens), role, timeout), "run");
            }
        };
    }
}
