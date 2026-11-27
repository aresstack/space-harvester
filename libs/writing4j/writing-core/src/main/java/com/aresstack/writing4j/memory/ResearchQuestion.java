package com.aresstack.writing4j.memory;

import java.util.ArrayList;
import java.util.List;

/**
 * Record "Frage": one question the book answers from the material. A question moves neither the Weidezaun nor
 * anything the research planned. There is no state "answered": a question
 * is open or set aside, and the memory never says it is done. Immutable; a change is a new record with the same id,
 * and the memory keeps the latest.
 * <p>
 * Field names of the data model: frageId, Text, themaId, Herkunft, zusammengeführtAus, Zustand.
 */
public final class ResearchQuestion {

    /** Who brought the question in (Herkunft). */
    public enum Origin {
        /** The topic finder, for a topic found in the material. */
        FINDER,
        /** The research lead, when the evidence so far calls for a new question. */
        LEITUNG,
        /** The user. */
        NUTZER,
        /** Phase 4: drafted from a group of the clustering of the basket and checked against mission and fence. */
        BESTAND
    }

    /** Zustand. There is deliberately no "answered". */
    public enum State { OFFEN, ZURUECKGESTELLT }

    private final String id;
    private final String text;
    private final String topicId;
    private final Origin origin;
    private final List<String> mergedFrom;
    private final State state;
    private final long timeMillis;

    /**
     * @param topicId    the topic the question belongs to, {@code ""} when it belongs to none yet
     * @param mergedFrom ids of the questions of the same meaning merged into this one; their verdicts count for it
     */
    public ResearchQuestion(String id, String text, String topicId, Origin origin, List<String> mergedFrom,
                            State state, long timeMillis) {
        this.id = Texts.clean(id);
        this.text = Texts.clean(text);
        this.topicId = Texts.clean(topicId);
        this.origin = origin == null ? Origin.NUTZER : origin;
        this.mergedFrom = Texts.ids(mergedFrom);
        this.state = state == null ? State.OFFEN : state;
        this.timeMillis = timeMillis;
        if (this.id.isEmpty() || this.text.isEmpty()) {
            throw new IllegalArgumentException("a question needs an id and a text");
        }
        if (this.mergedFrom.contains(this.id)) {
            throw new IllegalArgumentException("question " + this.id + " cannot be merged into itself");
        }
    }

    /** A new open question. */
    public static ResearchQuestion open(String id, String text, String topicId, Origin origin, long timeMillis) {
        return new ResearchQuestion(id, text, topicId, origin, null, State.OFFEN, timeMillis);
    }

    public ResearchQuestion withState(State newState, long time) {
        return new ResearchQuestion(id, text, topicId, origin, mergedFrom, newState, time);
    }

    public ResearchQuestion withTopic(String newTopicId, long time) {
        return new ResearchQuestion(id, text, newTopicId, origin, mergedFrom, state, time);
    }

    /** This question with others of the same meaning merged into it; its own id stays. */
    public ResearchQuestion withMerged(List<ResearchQuestion> others, long time) {
        List<String> ids = new ArrayList<String>(mergedFrom);
        for (ResearchQuestion other : others) {
            ids.add(other.getId());
            ids.addAll(other.getMergedFrom());
        }
        return new ResearchQuestion(id, text, topicId, origin, ids, state, time);
    }

    /** This question with a merge taken back. */
    public ResearchQuestion withoutMerged(List<String> ids, long time) {
        List<String> kept = new ArrayList<String>(mergedFrom);
        kept.removeAll(ids);
        return new ResearchQuestion(id, text, topicId, origin, kept, state, time);
    }

    public String getId() {
        return id;
    }

    public String getText() {
        return text;
    }

    public String getTopicId() {
        return topicId;
    }

    public Origin getOrigin() {
        return origin;
    }

    public List<String> getMergedFrom() {
        return mergedFrom;
    }

    public State getState() {
        return state;
    }

    public long getTimeMillis() {
        return timeMillis;
    }

    @Override
    public String toString() {
        return id + " [" + origin + ", " + state + "] " + text;
    }
}
