package com.aresstack.askai.research.runtime.team;

import java.util.List;

/**
 * An honest {@link MainModelChat} for when no usable main-model descriptor is configured. Every call returns a
 * typed {@link MainModelChatResult.Status#PROVIDER_FAILURE} carrying the reason, so the {@link ResearchTeamAgent}
 * surfaces a visible {@code MODEL_UNAVAILABLE} turn (with retry) instead of a fabricated or static conversation.
 * When a valid descriptor later appears, a {@link ReloadableMainModelChat} swaps this out for a real client.
 */
public final class UnavailableMainModelChat implements MainModelChat {

    private final String reason;

    public UnavailableMainModelChat(String reason) {
        this.reason = reason == null || reason.trim().isEmpty()
                ? "no main-model descriptor is configured" : reason.trim();
    }

    @Override
    public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
        return MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE, reason);
    }

    @Override
    public String modelName() {
        return "(no main model)";
    }
}
