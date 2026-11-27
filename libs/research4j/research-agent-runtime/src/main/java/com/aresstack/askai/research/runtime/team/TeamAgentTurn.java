package com.aresstack.askai.research.runtime.team;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The STRUCTURED intent the main model returns for one TeamAgent turn. The model never mutates research state
 * directly: it proposes, and the host's two nested state patterns decide legality. This value type is exactly
 * that proposal —
 * <ul>
 *   <li>{@link #getAssistantMessage()} — what to say to the user (always present);</li>
 *   <li>{@link #getProposedCommand()} — an OPTIONAL research-control command NAME to attempt (validated
 *       against the live allowed set before it can run — an unknown/illegal name is dropped, never invented);</li>
 *   <li>{@link #getQuestion()} + {@link #getAspects()} — the understood research scope so far;</li>
 *   <li>{@link #isApprovalRequested()} + {@link #getApprovalSubject()} — an explicit ask for the user's
 *       approval (e.g. of the outline);</li>
 *   <li>{@link #getSearchQueries()} — varying search queries proposed AFTER approval.</li>
 * </ul>
 */
public final class TeamAgentTurn implements PhaseAssistantOutput {

    private final String assistantMessage;
    private final String proposedCommand;
    private final String question;
    private final List<String> aspects;
    private final boolean approvalRequested;
    private final String approvalSubject;
    private final List<String> searchQueries;
    private final List<String> understoodFacts;
    private final List<String> suggestedFacts;
    private final List<String> openQuestions;
    private final Action action;

    /**
     * Plan PR 15: one OPTIONAL action of the Sources assistant — {@code request_research} (adds a plan entry) or
     * {@code blacklist_add} (an add-only blacklist entry). The runtime runs it only when the host offers that
     * tool in the live phase; anything else is dropped.
     */
    public static final class Action {
        public static final String REQUEST_RESEARCH = "request_research";
        public static final String BLACKLIST_ADD = "blacklist_add";

        private final String type;
        private final String query;
        private final String node;
        private final String entry;

        public Action(String type, String query, String node, String entry) {
            this.type = trim(type);
            this.query = trim(query);
            this.node = trim(node);
            this.entry = trim(entry);
        }

        public static Action requestResearch(String query, String node) {
            return new Action(REQUEST_RESEARCH, query, node, "");
        }

        public static Action blacklistAdd(String entry) {
            return new Action(BLACKLIST_ADD, "", "", entry);
        }

        private static String trim(String value) {
            return value == null ? "" : value.trim();
        }

        public String getType() {
            return type;
        }

        public String getQuery() {
            return query;
        }

        public String getNode() {
            return node;
        }

        public String getEntry() {
            return entry;
        }

        /** Whether the action names a known type with its required argument. */
        public boolean isComplete() {
            return (REQUEST_RESEARCH.equals(type) && !query.isEmpty())
                    || (BLACKLIST_ADD.equals(type) && !entry.isEmpty());
        }
    }

    public TeamAgentTurn(String assistantMessage, String proposedCommand, String question,
                         List<String> aspects, boolean approvalRequested, String approvalSubject,
                         List<String> searchQueries) {
        this(assistantMessage, proposedCommand, question, aspects, approvalRequested, approvalSubject,
                searchQueries, null, null, null);
    }

    public TeamAgentTurn(String assistantMessage, String proposedCommand, String question,
                         List<String> aspects, boolean approvalRequested, String approvalSubject,
                         List<String> searchQueries, List<String> understoodFacts,
                         List<String> suggestedFacts, List<String> openQuestions) {
        this(assistantMessage, proposedCommand, question, aspects, approvalRequested, approvalSubject,
                searchQueries, understoodFacts, suggestedFacts, openQuestions, null);
    }

    public TeamAgentTurn(String assistantMessage, String proposedCommand, String question,
                         List<String> aspects, boolean approvalRequested, String approvalSubject,
                         List<String> searchQueries, List<String> understoodFacts,
                         List<String> suggestedFacts, List<String> openQuestions, Action action) {
        this.action = action != null && action.isComplete() ? action : null;
        this.assistantMessage = assistantMessage == null ? "" : assistantMessage;
        this.proposedCommand = emptyToNull(proposedCommand);
        this.question = emptyToNull(question);
        this.aspects = immutableCopy(aspects);
        this.approvalRequested = approvalRequested;
        this.approvalSubject = emptyToNull(approvalSubject);
        this.searchQueries = immutableCopy(searchQueries);
        this.understoodFacts = immutableCopy(understoodFacts);
        this.suggestedFacts = immutableCopy(suggestedFacts);
        this.openQuestions = immutableCopy(openQuestions);
    }

    /** A plain assistant message with no proposed command/scope/approval (e.g. an honest fallback line). */
    public static TeamAgentTurn message(String assistantMessage) {
        return new TeamAgentTurn(assistantMessage, null, null, null, false, null, null);
    }

    /** The facts the assistant now takes as settled (understood from the user). */
    public List<String> getUnderstoodFacts() {
        return understoodFacts;
    }

    /** Defaults/options the assistant proposed to fill a gap (not yet confirmed by the user). */
    public List<String> getSuggestedFacts() {
        return suggestedFacts;
    }

    public List<String> getOpenQuestions() {
        return openQuestions;
    }

    /** The Sources assistant's action, or {@code null} (plan PR 15). */
    public Action getAction() {
        return action;
    }

    public String getAssistantMessage() {
        return assistantMessage;
    }

    /** The canonical, round-trippable JSON of this generic turn (its slot in the model history). */
    public String canonicalJson() {
        return TeamAgentTurnCodec.toJson(this);
    }

    public String getProposedCommand() {
        return proposedCommand;
    }

    public boolean hasProposedCommand() {
        return proposedCommand != null;
    }

    public String getQuestion() {
        return question;
    }

    public List<String> getAspects() {
        return aspects;
    }

    public boolean isApprovalRequested() {
        return approvalRequested;
    }

    public String getApprovalSubject() {
        return approvalSubject;
    }

    public List<String> getSearchQueries() {
        return searchQueries;
    }

    private static String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static List<String> immutableCopy(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> copy = new ArrayList<String>();
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                copy.add(value.trim());
            }
        }
        return Collections.unmodifiableList(copy);
    }
}
