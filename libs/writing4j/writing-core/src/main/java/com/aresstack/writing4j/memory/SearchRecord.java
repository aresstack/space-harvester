package com.aresstack.writing4j.memory;

import java.util.List;

/**
 * Component 7, record "Suche" of the shared data model: one search the research ran or is running, with the
 * questions or topics it serves and, once it is done, its yield. Every search names at least one question or topic,
 * so the chain question, search, source, passage stays traceable. Immutable; the yield is a new record with the
 * same id.
 * <p>
 * Field names of the data model: sucheId, Anfrage, frageIds, Strategie, Herkunft, Ertrag (Quellen, Passagen, neue
 * Belege, neue Themen). Added: themaIds (the rule allows a topic instead of a question) and the round.
 */
public final class SearchRecord {

    /** Ertrag. */
    public static final class Yield {
        private final int sources;
        private final int passages;
        private final int newEvidence;
        private final int newTopics;

        public Yield(int sources, int passages, int newEvidence, int newTopics) {
            this.sources = Math.max(0, sources);
            this.passages = Math.max(0, passages);
            this.newEvidence = Math.max(0, newEvidence);
            this.newTopics = Math.max(0, newTopics);
        }

        public int getSources() {
            return sources;
        }

        public int getPassages() {
            return passages;
        }

        /** Direct evidence with a text the questions of this search did not have before. */
        public int getNewEvidence() {
            return newEvidence;
        }

        public int getNewTopics() {
            return newTopics;
        }
    }

    private final String id;
    private final String query;
    private final List<String> questionIds;
    private final List<String> topicIds;
    private final String strategy;
    private final String origin;
    private final int round;
    private final Yield yield;
    private final long timeMillis;

    /**
     * @param strategy what kind of search it is (other wording, other kind of source, other language, named
     *                 publishers, ...), as the search planner names it
     * @param origin   who asked for it, for example {@code PLANER}, {@code LEITUNG}, {@code WRITER} or
     *                 {@code NUTZER}
     * @param yield    {@code null} while the search has not finished
     */
    public SearchRecord(String id, String query, List<String> questionIds, List<String> topicIds, String strategy,
                        String origin, int round, Yield yield, long timeMillis) {
        this.id = Texts.clean(id);
        this.query = Texts.clean(query);
        this.questionIds = Texts.ids(questionIds);
        this.topicIds = Texts.ids(topicIds);
        this.strategy = Texts.clean(strategy);
        this.origin = Texts.clean(origin);
        this.round = Math.max(1, round);
        this.yield = yield;
        this.timeMillis = timeMillis;
        if (this.id.isEmpty() || this.query.isEmpty()) {
            throw new IllegalArgumentException("a search needs an id and a query");
        }
        if (this.questionIds.isEmpty() && this.topicIds.isEmpty()) {
            throw new IllegalArgumentException("search " + this.id + " names no question and no topic");
        }
    }

    public SearchRecord withYield(Yield result, long time) {
        return new SearchRecord(id, query, questionIds, topicIds, strategy, origin, round, result, time);
    }

    public String getId() {
        return id;
    }

    public String getQuery() {
        return query;
    }

    public List<String> getQuestionIds() {
        return questionIds;
    }

    public List<String> getTopicIds() {
        return topicIds;
    }

    public String getStrategy() {
        return strategy;
    }

    public String getOrigin() {
        return origin;
    }

    public int getRound() {
        return round;
    }

    /** {@code null} while the search has not finished. */
    public Yield getYield() {
        return yield;
    }

    public long getTimeMillis() {
        return timeMillis;
    }
}
