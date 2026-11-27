package com.aresstack.knowledge4j.basket;

import com.aresstack.knowledge4j.scope.Fence;
import com.aresstack.knowledge4j.source.SourceRef;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The basket (Korb): the collection of sources a work is written from and the only interface between the research
 * (Research4j fills it) and the writing (Writing4j writes from it). It holds references to sources that live in the
 * host's stock, never copies; a {@link BasketReader} gives their passages. How the sources were found (a search plan,
 * the user's favourites, a folder) is not part of it. It carries the {@link Fence} it was collected for, mission and
 * Weidezaun with inclusions and exclusions, so the writing gets the same measure as the research and no second copy
 * of it travels beside the basket. It may carry the reader's {@link OrderingHint}s (term belongs under term), a
 * non-binding hint for the writing, never a structure it must follow. Beside the fence it may carry the reader's own
 * questions to the work (Pflichtfragen): the writing answers each from the basket or names it as a gap, it never drops
 * one. Immutable; a source is in it once, whatever its location; a question is in it once.
 */
public final class SourceBasket {

    private static final SourceBasket EMPTY = new SourceBasket(Collections.emptyList());

    private final Fence fence;
    private final List<SourceRef> sources;
    private final List<OrderingHint> hints;
    private final List<String> questions;

    /** A basket without a fence yet (an empty mission); {@link #withFence} gives it one. */
    public SourceBasket(List<SourceRef> sources) {
        this(null, sources);
    }

    /** A basket collected for {@code fence}; {@code null} means no fence yet. */
    public SourceBasket(Fence fence, List<SourceRef> sources) {
        this(fence, sources, null);
    }

    /** As above, with the reader's ordering hints; {@code null} means none. A hint is in it once. */
    public SourceBasket(Fence fence, List<SourceRef> sources, List<OrderingHint> hints) {
        this(fence, sources, hints, null);
    }

    /**
     * As above, with the reader's own questions to the work (Pflichtfragen); {@code null} means none. Blank lines are
     * dropped, each question is trimmed and in it once.
     */
    public SourceBasket(Fence fence, List<SourceRef> sources, List<OrderingHint> hints, List<String> questions) {
        List<String> asked = new ArrayList<>();
        if (questions != null) {
            for (String question : questions) {
                String q = question == null ? "" : question.trim();
                if (!q.isEmpty() && !asked.contains(q)) {
                    asked.add(q);
                }
            }
        }
        this.questions = Collections.unmodifiableList(asked);
        List<OrderingHint> distinct = new ArrayList<>();
        if (hints != null) {
            for (OrderingHint hint : hints) {
                if (hint != null && !distinct.contains(hint)) {
                    distinct.add(hint);
                }
            }
        }
        this.hints = Collections.unmodifiableList(distinct);
        this.fence = fence == null ? Fence.of("") : fence;
        List<SourceRef> once = new ArrayList<>();
        if (sources != null) {
            for (SourceRef source : sources) {
                if (source != null && once.stream().noneMatch(source::sameSource)) {
                    once.add(source);
                }
            }
        }
        this.sources = Collections.unmodifiableList(once);
    }

    public static SourceBasket empty() {
        return EMPTY;
    }

    /** Mission and Weidezaun the basket was collected for; an empty mission when none was given. */
    public Fence fence() {
        return fence;
    }

    /** This basket with {@code fence} instead of its own, as the reader widens or narrows the Weidezaun. */
    public SourceBasket withFence(Fence fence) {
        return new SourceBasket(fence, sources, hints, questions);
    }

    /** The reader's ordering hints in the order they were given; empty when the reader sorted nothing. */
    public List<OrderingHint> hints() {
        return hints;
    }

    /** This basket with {@code hints} instead of its own, as the reader sorts found terms after a search. */
    public SourceBasket withHints(List<OrderingHint> hints) {
        return new SourceBasket(fence, sources, hints, questions);
    }

    /**
     * The reader's own questions to the work in the order they were given (Pflichtfragen beside the fence); empty when
     * the reader asked none.
     */
    public List<String> questions() {
        return questions;
    }

    /** This basket with {@code questions} instead of its own. */
    public SourceBasket withQuestions(List<String> questions) {
        return new SourceBasket(fence, sources, hints, questions);
    }

    /** The sources in the order they were added. */
    public List<SourceRef> sources() {
        return sources;
    }

    public boolean isEmpty() {
        return sources.isEmpty();
    }

    /**
     * This basket with more sources, as a further search adds them; sources already in it stay as they are, and so
     * do the fence, the hints and the questions.
     */
    public SourceBasket with(List<SourceRef> more) {
        List<SourceRef> all = new ArrayList<>(sources);
        if (more != null) {
            all.addAll(more);
        }
        return new SourceBasket(fence, all, hints, questions);
    }

    /** Sizes and the fingerprint of the fence only, never the text of a question. */
    @Override
    public String toString() {
        return "SourceBasket[" + sources.size() + " sources, fence " + fence.basis() + ", " + hints.size() + " hints, "
                + questions.size() + " questions]";
    }
}
