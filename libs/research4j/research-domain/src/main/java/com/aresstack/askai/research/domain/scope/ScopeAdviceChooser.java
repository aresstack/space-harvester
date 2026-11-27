package com.aresstack.askai.research.domain.scope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Z4b: the NARROWLY scoped chooser port — the one place a model re-enters the advice chain, with
 * a deliberately tiny contract: it receives the finished reason-aware offers (never the raw
 * sweep), may pick AT MOST ONE offered candidate or NONE, and phrases one natural question. It
 * may NOT invent candidates, may NOT turn a drift guard into a positive question, produces no
 * scope patch, no workflow action and no submit — the observer must not move the fence before the
 * negotiation happened. The user's ANSWER later changes the draft through the normal scoping
 * turn, never this decision.
 * <p>
 * {@code NONE} means exactly "this sweep offers no sensible next question" — it NEVER means "the
 * scope is complete". Saturation evidence grows over several sweeps; one quiet run proves nothing.
 */
public interface ScopeAdviceChooser {

    /** One offered question candidate, rendered for the model: id + reason + topic + context. */
    final class CandidateOffer {
        private final String candidateId;
        private final ScopeAdviceCandidate.Reason reason;
        private final String topicText;
        /** Host-rendered context (anchor semantic texts etc.) in the conversation's language. */
        private final String contextNote;

        public CandidateOffer(String candidateId, ScopeAdviceCandidate.Reason reason,
                              String topicText, String contextNote) {
            if (candidateId == null || candidateId.trim().isEmpty()) {
                throw new IllegalArgumentException("candidateId must not be empty");
            }
            if (reason == null) {
                throw new IllegalArgumentException("reason must not be null");
            }
            if (topicText == null || topicText.trim().isEmpty()) {
                throw new IllegalArgumentException("topicText must not be empty");
            }
            this.candidateId = candidateId.trim();
            this.reason = reason;
            this.topicText = topicText.trim();
            this.contextNote = contextNote == null ? "" : contextNote.trim();
        }

        public String getCandidateId() {
            return candidateId;
        }

        public ScopeAdviceCandidate.Reason getReason() {
            return reason;
        }

        public String getTopicText() {
            return topicText;
        }

        public String getContextNote() {
            return contextNote;
        }
    }

    /** What the chooser sees — offers and guards only, never the raw sweep or the fence math. */
    final class ChoiceRequest {
        private final String mission;
        private final List<CandidateOffer> candidates;
        /** Rendered drift reminders — context only, NEVER selectable. */
        private final List<String> driftGuardNotes;

        public ChoiceRequest(String mission, List<CandidateOffer> candidates,
                             List<String> driftGuardNotes) {
            this.mission = mission == null ? "" : mission.trim();
            this.candidates = Collections.unmodifiableList(new ArrayList<CandidateOffer>(
                    candidates == null ? Collections.<CandidateOffer>emptyList() : candidates));
            this.driftGuardNotes = Collections.unmodifiableList(new ArrayList<String>(
                    driftGuardNotes == null ? Collections.<String>emptyList() : driftGuardNotes));
        }

        public String getMission() {
            return mission;
        }

        public List<CandidateOffer> getCandidates() {
            return candidates;
        }

        public List<String> getDriftGuardNotes() {
            return driftGuardNotes;
        }

        public boolean offersCandidate(String candidateId) {
            for (CandidateOffer offer : candidates) {
                if (offer.getCandidateId().equals(candidateId)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** The chosen next step: ask ONE offered question, or honestly nothing this round. */
    final class AdviceDecision {
        public enum Decision {
            ASK,
            /** No sensible next question THIS sweep — never "the scope is complete". */
            NONE
        }

        private final Decision decision;
        private final String candidateId;
        private final String assistantMessage;

        private AdviceDecision(Decision decision, String candidateId, String assistantMessage) {
            this.decision = decision;
            this.candidateId = candidateId == null ? "" : candidateId;
            this.assistantMessage = assistantMessage == null ? "" : assistantMessage;
        }

        public static AdviceDecision ask(String candidateId, String assistantMessage) {
            if (candidateId == null || candidateId.trim().isEmpty()) {
                throw new IllegalArgumentException("ASK carries the chosen candidate");
            }
            if (assistantMessage == null || assistantMessage.trim().isEmpty()) {
                throw new IllegalArgumentException("ASK carries the phrased question");
            }
            return new AdviceDecision(Decision.ASK, candidateId.trim(), assistantMessage.trim());
        }

        public static AdviceDecision none(String assistantMessage) {
            return new AdviceDecision(Decision.NONE, "",
                    assistantMessage == null ? "" : assistantMessage.trim());
        }

        public Decision getDecision() {
            return decision;
        }

        public String getCandidateId() {
            return candidateId;
        }

        public String getAssistantMessage() {
            return assistantMessage;
        }
    }

    /**
     * The typed outcome of one chooser call — the model seam's error semantics survive exactly
     * like the generator's; a broken chooser is never a silent NONE ("model broke" must stay
     * distinguishable from "nothing worth asking").
     */
    final class ChoiceResult {
        public enum Status {
            OK,
            TIMEOUT,
            PROVIDER_FAILURE,
            INVALID_RESPONSE
        }

        private final Status status;
        private final AdviceDecision decision;
        private final String message;

        private ChoiceResult(Status status, AdviceDecision decision, String message) {
            this.status = status;
            this.decision = decision;
            this.message = message == null ? "" : message;
        }

        public static ChoiceResult ok(AdviceDecision decision) {
            if (decision == null) {
                throw new IllegalArgumentException("an OK result carries a decision");
            }
            return new ChoiceResult(Status.OK, decision, "");
        }

        public static ChoiceResult failure(Status status, String message) {
            if (status == Status.OK) {
                throw new IllegalArgumentException("a failure result must carry a failure status");
            }
            return new ChoiceResult(status, null, message);
        }

        public Status getStatus() {
            return status;
        }

        public boolean isOk() {
            return status == Status.OK;
        }

        /** The decision — null on any non-OK status. */
        public AdviceDecision getDecision() {
            return decision;
        }

        public String getMessage() {
            return message;
        }
    }

    /** ONE model call over the offers; every failure comes back typed, never as an exception. */
    ChoiceResult choose(ChoiceRequest request);
}
