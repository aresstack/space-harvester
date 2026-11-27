package com.aresstack.askai.research.runtime.orchestrator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The orchestrator's schema-bound judgement after one round (ADR 0001, 4.2: evaluate results, detect gaps and
 * contradictions, re-plan, quality check, close). Code acts on the typed fields only; the free-text notes are
 * shown to the user, never parsed.
 *
 * <p>The decision is advice inside the envelope: {@link Action#CLOSE} closes the run only when the
 * deterministic sufficiency of the {@link EvidenceMeter} holds as well, and follow-ups beyond the approved
 * plan and budget go back to the user (see {@link OrchestratedResearchRun}).</p>
 */
public final class RoundDecision {

    /** What the orchestrator wants next. */
    public enum Action {
        /** Run the follow-up packages. */
        CONTINUE("continue"),
        /** The research is sufficient; stop the run (checked against the measured evidence). */
        CLOSE("close"),
        /** A question only the user can answer (the run waits). */
        ASK_USER("ask-user");

        private final String wireName;

        Action(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static Action fromWireName(String value) {
            for (Action action : values()) {
                if (action.wireName.equalsIgnoreCase(value) || action.name().equalsIgnoreCase(value)) {
                    return action;
                }
            }
            throw new IllegalArgumentException("unknown action: " + value);
        }
    }

    /** The verdict on one finished work package. */
    public enum Verdict {
        USEFUL("useful"),
        PARTIAL("partial"),
        USELESS("useless"),
        OFF_SCOPE("off-scope");

        private final String wireName;

        Verdict(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static Verdict fromWireName(String value) {
            for (Verdict verdict : values()) {
                if (verdict.wireName.equalsIgnoreCase(value) || verdict.name().equalsIgnoreCase(value)) {
                    return verdict;
                }
            }
            throw new IllegalArgumentException("unknown verdict: " + value);
        }
    }

    private final List<Evaluation> evaluations;
    private final List<Finding> gaps;
    private final List<Finding> contradictions;
    private final boolean qualitySufficient;
    private final String qualityNote;
    private final Action action;
    private final String question;
    private final List<PlannedTask> followUps;

    public RoundDecision(List<Evaluation> evaluations, List<Finding> gaps, List<Finding> contradictions,
                         boolean qualitySufficient, String qualityNote, Action action, String question,
                         List<PlannedTask> followUps) {
        if (action == null) {
            throw new IllegalArgumentException("a decision needs an action");
        }
        this.evaluations = copy(evaluations);
        this.gaps = copy(gaps);
        this.contradictions = copy(contradictions);
        this.qualitySufficient = qualitySufficient;
        this.qualityNote = qualityNote == null ? "" : qualityNote.trim();
        this.action = action;
        this.question = question == null ? "" : question.trim();
        this.followUps = copy(followUps);
    }

    public List<Evaluation> getEvaluations() {
        return evaluations;
    }

    /** Sections the orchestrator judges insufficiently covered, each with what is missing. */
    public List<Finding> getGaps() {
        return gaps;
    }

    /** Contradictions between findings, each anchored on a section. */
    public List<Finding> getContradictions() {
        return contradictions;
    }

    /** The orchestrator's quality check of the material so far. */
    public boolean isQualitySufficient() {
        return qualitySufficient;
    }

    public String getQualityNote() {
        return qualityNote;
    }

    public Action getAction() {
        return action;
    }

    /** The question for the user when the action is {@link Action#ASK_USER}; empty otherwise. */
    public String getQuestion() {
        return question;
    }

    public List<PlannedTask> getFollowUps() {
        return followUps;
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? Collections.<T>emptyList()
                : Collections.unmodifiableList(new ArrayList<T>(values));
    }

    /** The verdict on one work package of the round. */
    public static final class Evaluation {
        private final String taskId;
        private final Verdict verdict;
        private final String note;

        public Evaluation(String taskId, Verdict verdict, String note) {
            this.taskId = taskId;
            this.verdict = verdict;
            this.note = note == null ? "" : note.trim();
        }

        /** The planned id of the package (without the run prefix). */
        public String getTaskId() {
            return taskId;
        }

        public Verdict getVerdict() {
            return verdict;
        }

        public String getNote() {
            return note;
        }
    }

    /** A gap or contradiction on one outline section. */
    public static final class Finding {
        private final String section;
        private final String description;

        public Finding(String section, String description) {
            this.section = section;
            this.description = description == null ? "" : description.trim();
        }

        public String getSection() {
            return section;
        }

        public String getDescription() {
            return description;
        }
    }
}
