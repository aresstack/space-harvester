package com.aresstack.askai.research.runtime.team;

/**
 * The chat transport of each research phase (plan 5.1): a turn in phase X runs on the model of slot
 * {@code phase.X}, independent of the chat model and of the profile the registry picked. The session provides
 * the productive implementation over its {@code RoleChatFactory}; tests pass one fixed chat.
 */
public interface PhaseChats {

    /** The chat for the live phase id ({@code ""} = Concept, before the host reports a phase). Never null. */
    MainModelChat chatFor(String phaseId);

    /** One chat for every phase: tests and harnesses that do not distinguish phases. */
    static PhaseChats fixed(final MainModelChat chat) {
        if (chat == null) {
            throw new IllegalArgumentException("chat must not be null");
        }
        return new PhaseChats() {
            public MainModelChat chatFor(String phaseId) {
                return chat;
            }
        };
    }
}
