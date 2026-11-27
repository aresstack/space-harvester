package com.aresstack.writing4j.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Record "Thema": a subject the book can cover, found in the material (in Phase 4 a group of the clustering of the
 * basket, checked against mission and Weidezaun). The id stays when other topics are merged into it. Immutable; a change is a
 * new record with the same id ({@code with...}), and the memory keeps the latest.
 * <p>
 * Field names of the data model: themaId, Name, Beschreibung, zusammengeführtAus, Fundstellen, Zaun mit Grund; the
 * merge adds Unterthema von ({@link #getParentId()}).
 * <p>
 * A record holds its own findings only. The findings of topics merged into it stay on their records, and the memory
 * counts a topic's findings over everything merged into it ({@link ResearchMemory#findingsOfTopic}); so a merge taken
 * back ({@link #withoutMerged}) takes its findings back too.
 */
public final class ResearchTopic {

    /** The topic check's verdict against the Weidezaun (Zaun). */
    public enum Fence { INNEN, AUSSEN, UNKLAR, UNGEPRUEFT }

    /** A place the topic was found (Fundstelle): source, passage and the quote that shows it. */
    public static final class Finding {
        private final String sourceId;
        private final String passageId;
        private final String quote;

        public Finding(String sourceId, String passageId, String quote) {
            this.sourceId = Texts.clean(sourceId);
            this.passageId = Texts.clean(passageId);
            this.quote = Texts.clean(quote);
        }

        public String getSourceId() {
            return sourceId;
        }

        public String getPassageId() {
            return passageId;
        }

        public String getQuote() {
            return quote;
        }
    }

    private final String id;
    private final String name;
    private final String description;
    private final List<String> mergedFrom;
    private final List<Finding> findings;
    private final Fence fence;
    private final String fenceReason;
    private final String model;
    private final long timeMillis;
    private final String parentId;

    /** A topic that is no subtopic of another; see the full constructor. */
    public ResearchTopic(String id, String name, String description,
                         List<String> mergedFrom, List<Finding> findings, Fence fence, String fenceReason,
                         String model, long timeMillis) {
        this(id, name, description, mergedFrom, findings, fence, fenceReason, model, timeMillis, "");
    }

    /**
     * @param mergedFrom   ids of the topics merged into this one
     * @param model        the model of the last step that changed the record, {@code ""} for a program step
     * @param parentId     the topic this one is a subtopic of (Unterthema von), {@code ""} for none; the topic stands
     *                     on its own all the same, with its own id, findings and fence verdict
     */
    public ResearchTopic(String id, String name, String description,
                         List<String> mergedFrom, List<Finding> findings, Fence fence, String fenceReason,
                         String model, long timeMillis, String parentId) {
        this.id = Texts.clean(id);
        this.name = Texts.clean(name);
        this.description = Texts.clean(description);
        this.mergedFrom = Texts.ids(mergedFrom);
        this.findings = Collections.unmodifiableList(new ArrayList<Finding>(
                findings == null ? Collections.<Finding>emptyList() : findings));
        this.fence = fence == null ? Fence.UNGEPRUEFT : fence;
        this.fenceReason = Texts.clean(fenceReason);
        this.model = Texts.clean(model);
        this.timeMillis = timeMillis;
        if (this.id.isEmpty() || this.name.isEmpty()) {
            throw new IllegalArgumentException("a topic needs an id and a name");
        }
        if (this.mergedFrom.contains(this.id)) {
            throw new IllegalArgumentException("topic " + this.id + " cannot be merged into itself");
        }
        this.parentId = Texts.clean(parentId);
        if (this.parentId.equals(this.id)) {
            throw new IllegalArgumentException("topic " + this.id + " cannot be a subtopic of itself");
        }
    }

    /** A topic found during the research, not yet checked against the Weidezaun. */
    public static ResearchTopic found(String id, String name, String description, List<Finding> findings,
                                      String model, long timeMillis) {
        return new ResearchTopic(id, name, description, null, findings, Fence.UNGEPRUEFT, "", model,
                timeMillis);
    }

    public ResearchTopic withFence(Fence newFence, String reason, String byModel, long time) {
        return new ResearchTopic(id, name, description, mergedFrom, findings, newFence, reason,
                byModel, time, parentId);
    }

    /**
     * This topic with others merged into it: their ids join it, its own id stays. Their findings stay on their own
     * records and count for this topic through the memory ({@link ResearchMemory#findingsOfTopic}).
     */
    public ResearchTopic withMerged(List<ResearchTopic> others, String byModel, long time) {
        List<String> ids = new ArrayList<String>(mergedFrom);
        for (ResearchTopic other : others) {
            ids.add(other.getId());
            ids.addAll(other.getMergedFrom());
        }
        return new ResearchTopic(id, name, description, ids, findings, fence, fenceReason,
                byModel, time, parentId);
    }

    /**
     * This topic with a merge taken back: the given ids leave {@code mergedFrom}, so they stand alone again, with
     * their findings.
     */
    public ResearchTopic withoutMerged(List<String> ids, long time) {
        List<String> kept = new ArrayList<String>(mergedFrom);
        kept.removeAll(ids);
        return new ResearchTopic(id, name, description, kept, findings, fence, fenceReason,
                "", time, parentId);
    }

    /** This topic as a subtopic of another ({@code ""}: of none). */
    public ResearchTopic withParent(String newParentId, String byModel, long time) {
        return new ResearchTopic(id, name, description, mergedFrom, findings, fence,
                fenceReason, byModel, time, newParentId);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getMergedFrom() {
        return mergedFrom;
    }

    public List<Finding> getFindings() {
        return findings;
    }

    public Fence getFence() {
        return fence;
    }

    public String getFenceReason() {
        return fenceReason;
    }

    public String getModel() {
        return model;
    }

    public long getTimeMillis() {
        return timeMillis;
    }

    /**
     * The topic this one is a subtopic of (Unterthema von), {@code ""} for none. It may name a topic merged into
     * another since; {@link ResearchMemory#parentOf} gives the parent as it stands now. A subtopic keeps its own fence
     * verdict and does not take its parent's.
     */
    public String getParentId() {
        return parentId;
    }

    @Override
    public String toString() {
        return id + " [" + fence + "] " + name;
    }
}
