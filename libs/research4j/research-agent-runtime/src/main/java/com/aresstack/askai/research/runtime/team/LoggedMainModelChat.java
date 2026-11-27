package com.aresstack.askai.research.runtime.team;

import java.util.List;

/**
 * Writes the {@link ModelCallLog} line for callers that assemble their own messages (the orchestrator's
 * planner, verifier and writer roles). Their requests have no named phase blocks, so the meter counts the
 * system, user and assistant messages. The messages pass through unchanged.
 */
public final class LoggedMainModelChat implements MainModelChat {

    private final MainModelChat delegate;
    private final String phase;

    public LoggedMainModelChat(MainModelChat delegate, String phase) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        this.delegate = delegate;
        this.phase = phase == null ? "" : phase;
    }

    @Override
    public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
        MainModelChatResult result = delegate.complete(messages, temperature, maxOutputTokens);
        ModelCallLog.log(phase, meterOf(messages), result);
        return result;
    }

    @Override
    public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature, int maxOutputTokens,
                                            String schemaJson) {
        MainModelChatResult result = delegate.completeJson(messages, temperature, maxOutputTokens, schemaJson);
        ModelCallLog.log(phase, meterOf(messages), result);
        return result;
    }

    static PromptBlockMeter meterOf(List<ChatMessage> messages) {
        PromptBlockMeter meter = new PromptBlockMeter();
        if (messages != null) {
            for (ChatMessage message : messages) {
                meter.add(message.getRole().name().toLowerCase(java.util.Locale.ROOT), message);
            }
        }
        return meter;
    }

    @Override
    public int contextTokens() {
        return delegate.contextTokens();
    }

    @Override
    public String modelName() {
        return delegate.modelName();
    }

    @Override
    public void cancelInFlight() {
        delegate.cancelInFlight();
    }
}
