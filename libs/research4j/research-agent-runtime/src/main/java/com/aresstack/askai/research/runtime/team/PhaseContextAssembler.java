package com.aresstack.askai.research.runtime.team;

import java.util.ArrayList;
import java.util.List;

/**
 * Assembles the model context for ONE turn from the active phase's {@link PhaseAssistantProfile}: the phase's
 * own system prompt, the per-turn research-context system message, the phase-gated blocks and the running
 * history. This is the single place where "active phase → its assistant context" is realized.
 *
 * <p>Plan PR 16: the Phase-1 machinery (the scope fence with the conversation policy it carries, and the current
 * concept with its edit rules) is sent only in Concept. Every later phase gets the read-only concept summary of
 * the effective frozen concept instead, plus ONE artifact block of its own phase: the seam that PR 15 (Sources),
 * PR 21 (Outline) and PR 29 (Document) fill, carrying the phase's latest change request until the next Finish.
 * The live phase is the host's ({@code state.getPhaseId()}), never the model's. The history is never sent whole:
 * the assembler takes the own-phase window of {@link HistoryWindow} (plan PR 17, D7) within the role's budget.</p>
 */
public final class PhaseContextAssembler {

    /** Supplies the current date ({@code YYYY-MM-DD}) as runtime context — never inferred from model memory. */
    public interface CurrentDate {
        String today();
    }

    /**
     * Supplies the session's CURRENT working-language display name ("English"/"German") per turn — read at
     * assemble time, so a live {@code set_language} applies to the NEXT turn without touching history.
     */
    public interface CurrentLanguage {
        String displayName();
    }

    /** Supplies the rendered scope fence for the NEXT turn — read at assemble time, like the language. */
    public interface CurrentScope {
        String rendered();
    }

    private static final CurrentScope EMPTY_SCOPE = new CurrentScope() {
        public String rendered() {
            return "";
        }
    };

    /**
     * Supplies the AUTHORITATIVE persisted concept block for the NEXT turn (K2e): with the
     * concept tools active, EVERY inference must see the real workpiece BEFORE it answers —
     * a type=none turn once claimed an "Arduino focus" that existed nowhere but in the chat.
     * Empty = no block (no tools / old host / fetch failed).
     */
    public interface CurrentConcept {
        String rendered();
    }

    private static final CurrentConcept EMPTY_CONCEPT = new CurrentConcept() {
        public String rendered() {
            return "";
        }
    };

    /**
     * Supplies the read-only summary of the effective frozen concept (approved cards, mission, deliverable,
     * recorded blacklist) for a turn of a later phase; "" when there is none yet.
     */
    public interface CurrentConceptSummary {
        String rendered();
    }

    /** Supplies the user's latest change request of a phase until its next Finish; "" when none. */
    public interface CurrentChangeRequest {
        String forPhase(String phaseId);
    }

    /**
     * Supplies the artifact content of a phase's block (PR 15 Sources: plan and coverage; PR 21 Outline; PR 29
     * Document); "" until that PR fills it.
     */
    public interface PhaseArtifact {
        String rendered(String phaseId);
    }

    private static final CurrentConceptSummary EMPTY_SUMMARY = new CurrentConceptSummary() {
        public String rendered() {
            return "";
        }
    };

    private static final CurrentChangeRequest NO_CHANGE_REQUEST = new CurrentChangeRequest() {
        public String forPhase(String phaseId) {
            return "";
        }
    };

    private static final PhaseArtifact NO_ARTIFACT = new PhaseArtifact() {
        public String rendered(String phaseId) {
            return "";
        }
    };

    private static final CurrentLanguage ENGLISH_DEFAULT = new CurrentLanguage() {
        public String displayName() {
            return "English";
        }
    };

    private final CurrentDate currentDate;
    private final CurrentLanguage currentLanguage;
    /** Supplies the host's AUTHORITATIVE scope projection per turn; "" when the host sent none. */
    private volatile CurrentScope currentScope = EMPTY_SCOPE;
    private volatile CurrentConcept currentConcept = EMPTY_CONCEPT;
    private volatile CurrentConceptSummary currentConceptSummary = EMPTY_SUMMARY;
    private volatile CurrentChangeRequest currentChangeRequest = NO_CHANGE_REQUEST;
    private volatile PhaseArtifact phaseArtifact = NO_ARTIFACT;

    public PhaseContextAssembler() {
        this(systemDate(), ENGLISH_DEFAULT);
    }

    /** Inject a fixed date (tests / deterministic runs). */
    public PhaseContextAssembler(CurrentDate currentDate) {
        this(currentDate, ENGLISH_DEFAULT);
    }

    /** System clock + the session's live working language (the production wiring). */
    public PhaseContextAssembler(CurrentLanguage currentLanguage) {
        this(systemDate(), currentLanguage);
    }

    /** The scope projection source; without it a turn simply carries no scope context. */
    public PhaseContextAssembler withCurrentScope(CurrentScope scope) {
        this.currentScope = scope == null ? EMPTY_SCOPE : scope;
        return this;
    }

    /** The persisted-concept source; without it a turn simply carries no concept context. */
    public PhaseContextAssembler withCurrentConcept(CurrentConcept concept) {
        this.currentConcept = concept == null ? EMPTY_CONCEPT : concept;
        return this;
    }

    /** The frozen-concept summary source for later phases; without it they carry no concept summary. */
    public PhaseContextAssembler withCurrentConceptSummary(CurrentConceptSummary summary) {
        this.currentConceptSummary = summary == null ? EMPTY_SUMMARY : summary;
        return this;
    }

    /** The change-request source of the phase artifact blocks. */
    public PhaseContextAssembler withCurrentChangeRequest(CurrentChangeRequest changeRequest) {
        this.currentChangeRequest = changeRequest == null ? NO_CHANGE_REQUEST : changeRequest;
        return this;
    }

    /** The artifact source of the phase artifact blocks (filled by PR 15, PR 21 and PR 29). */
    public PhaseContextAssembler withPhaseArtifact(PhaseArtifact artifact) {
        this.phaseArtifact = artifact == null ? NO_ARTIFACT : artifact;
        return this;
    }

    public PhaseContextAssembler(CurrentDate currentDate, CurrentLanguage currentLanguage) {
        this.currentDate = currentDate;
        this.currentLanguage = currentLanguage == null ? ENGLISH_DEFAULT : currentLanguage;
    }

    /** The live working language's display name — the greeting bootstrap names it explicitly. */
    public String workingLanguageDisplayName() {
        return currentLanguage.displayName();
    }

    private static CurrentDate systemDate() {
        return new CurrentDate() {
            public String today() {
                return java.time.LocalDate.now().toString();
            }
        };
    }

    /**
     * system(phase prompt) + system(current date) + system(current working language) + system(live research
     * context) + the running history. The
     * profile decides the FIRST system message, so a different active phase yields a different assistant
     * context. The current date is supplied from the host clock so the model never invents a year in, e.g.,
     * search queries.
     */
    public List<ChatMessage> assemble(PhaseAssistantProfile profile, TeamAgentStateView state,
                                      List<ChatMessage> history) {
        return assemble(profile, state, history, new PromptBlockMeter());
    }

    /** As above, and counts every block it adds into {@code meter} (plan PR 01); the messages are the same. */
    public List<ChatMessage> assemble(PhaseAssistantProfile profile, TeamAgentStateView state,
                                      List<ChatMessage> history, PromptBlockMeter meter) {
        return assemble(profile, state, history, HistoryWindow.chatBudget(state.getPhaseId()), meter);
    }

    /**
     * As above with an explicit window budget in tokens: the newest own-phase {@code USER}/{@code FINAL}
     * entries of {@code history} that fit (0 = no history at all). The overflow guard of the TeamAgent lowers
     * it when the whole prompt would not fit into the model's context.
     */
    public List<ChatMessage> assemble(PhaseAssistantProfile profile, TeamAgentStateView state,
                                      List<ChatMessage> history, int windowTokens, PromptBlockMeter meter) {
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        add(messages, meter, PromptBlockMeter.PROFILE, ChatMessage.system(profile.getSystemPrompt()));
        add(messages, meter, PromptBlockMeter.DATE, ChatMessage.system("Current date: " + currentDate.today()));
        add(messages, meter, PromptBlockMeter.LANGUAGE, ChatMessage.system(
                TeamAgentPlaybook.workingLanguageContext(currentLanguage.displayName())));
        add(messages, meter, PromptBlockMeter.STATE, ChatMessage.system(TeamAgentPlaybook.stateContext(state)));
        String phaseId = state == null ? "" : state.getPhaseId();
        if (PhaseActionUnion.isConcept(phaseId)) {
            // The scope the HOST holds — authoritative for this turn. Without it the model would rebuild the
            // scope from the conversation, which is how earlier decisions quietly disappear. The conversation
            // policy travels inside this envelope, so it is Concept-only as well.
            String scope = currentScope.rendered();
            if (!scope.isEmpty()) {
                add(messages, meter, PromptBlockMeter.FENCE,
                        ChatMessage.system(TeamAgentPlaybook.scopeFenceContext(scope)));
            }
            // The persisted CONCEPT with its edit rules — authoritative for this turn, exactly like the scope
            // fence: without it a type=none inference can only reconstruct the concept from the chat, which is
            // how unpersisted "focus" claims are born.
            String concept = currentConcept.rendered();
            if (!concept.isEmpty()) {
                add(messages, meter, PromptBlockMeter.CONCEPT, ChatMessage.system(concept));
            }
        } else {
            // Later phases: the frozen concept, read-only, and the block of their own phase.
            String summary = currentConceptSummary.rendered();
            if (summary != null && !summary.trim().isEmpty()) {
                add(messages, meter, PromptBlockMeter.CONCEPT_SUMMARY,
                        ChatMessage.system(TeamAgentPlaybook.conceptSummaryContext(summary)));
            }
            String artifactBlock = TeamAgentPlaybook.phaseArtifactContext(phaseId,
                    phaseArtifact.rendered(phaseId), currentChangeRequest.forPhase(phaseId));
            if (!artifactBlock.isEmpty()) {
                add(messages, meter, PromptBlockMeter.PHASE_ARTIFACT, ChatMessage.system(artifactBlock));
            }
        }
        List<ChatMessage> window = HistoryWindow.select(history, state.getPhaseId(), windowTokens);
        if (!window.isEmpty()) {
            messages.addAll(window);
            meter.add(PromptBlockMeter.HISTORY, window);
        }
        return messages;
    }

    private static void add(List<ChatMessage> messages, PromptBlockMeter meter, String block, ChatMessage message) {
        messages.add(message);
        meter.add(block, message);
    }
}
