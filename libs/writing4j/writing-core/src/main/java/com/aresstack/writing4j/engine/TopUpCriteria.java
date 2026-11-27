package com.aresstack.writing4j.engine;

/**
 * When a key-point part gets a top-up call (live run 5, book 2: the writer delivered 256 of 367 words per call on
 * average, 70 %, even with a full evidence pack, and nothing asked for the rest because a continuation followed only a
 * cut-off answer). A part whose checked answer keeps fewer than {@link #getMinShare() min share} of its word target
 * while passages of its pack are still uncited gets up to {@link #getMaxCalls() max calls} further calls, each sent
 * with the text written so far as "already written" and the uncited passages named. Immutable; every bound is a
 * setting.
 */
public final class TopUpCriteria {

    /**
     * What a top-up asks for. {@link #UNCITED_PASSAGES}: more words drawn from the passages the part has not cited
     * yet, all of them named (live run 5). That turns every remaining passage into a paragraph of its own and makes
     * the text retell source after source (writer proposal, stage 1: 78 to 96 % of the sentences cite exactly one
     * passage). {@link #KEY_POINTS}: more words on the part's key points; an uncited passage may serve where it adds
     * something new, and a passage that only repeats what is written stays out. Both end when every passage is cited:
     * without new material a top-up could only rephrase.
     */
    public enum Focus { UNCITED_PASSAGES, KEY_POINTS }

    /**
     * Default share of the part's word target below which a top-up follows. 0.85 lies above the measured mean of
     * live run 5 (0.70) and below a full answer, so a part that almost reached its target is not asked again.
     */
    public static final double DEFAULT_MIN_SHARE = 0.85;
    /**
     * Default: at most two top-ups per part. Retest of #246 (fixture 03b): with one round 33 of 129 parts stayed
     * below the share although a round was taken.
     */
    public static final int DEFAULT_MAX_CALLS = 2;

    private final double minShare;
    private final int maxCalls;
    private final Focus focus;

    /**
     * @param minShare share (0..1) of the word target a part must reach; 0 = never top up
     * @param maxCalls top-up calls per part (0 = off)
     */
    public TopUpCriteria(double minShare, int maxCalls) {
        this(minShare, maxCalls, Focus.UNCITED_PASSAGES);
    }

    /** As above, with what a top-up asks for ({@link Focus#UNCITED_PASSAGES} when {@code null}). */
    public TopUpCriteria(double minShare, int maxCalls, Focus focus) {
        if (minShare < 0 || minShare > 1 || maxCalls < 0) {
            throw new IllegalArgumentException("the share within 0..1 and max calls >= 0");
        }
        this.minShare = minShare;
        this.maxCalls = maxCalls;
        this.focus = focus == null ? Focus.UNCITED_PASSAGES : focus;
    }

    public static TopUpCriteria defaults() {
        return new TopUpCriteria(DEFAULT_MIN_SHARE, DEFAULT_MAX_CALLS);
    }

    /** No top-up calls at all. */
    public static TopUpCriteria off() {
        return new TopUpCriteria(0, 0);
    }

    public double getMinShare() {
        return minShare;
    }

    public int getMaxCalls() {
        return maxCalls;
    }

    public Focus getFocus() {
        return focus;
    }

    /** The words a part of {@code target} words must reach before no top-up follows. */
    public int minWords(int target) {
        return (int) Math.ceil(Math.max(0, target) * minShare);
    }
}
