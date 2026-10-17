package com.aresstack.enterpriseai.ui.comic.bubble;

/**
 * A bubble that can report the width it would LIKE within a limit the caller supplies.
 * <p>
 * This exists so the transcript row — the only component that knows how much space the chat actually has —
 * decides the upper bound, instead of every bubble carrying a fixed pixel cap of its own. A fixed cap is
 * wrong in both directions: on a narrow window it is irrelevant, and on a wide one it turns the chat into a
 * narrow column with large empty margins.
 * <p>
 * A SHORT message still stays short: the bubble asks for its natural width and the limit only caps it.
 */
public interface WidthBoundedBubble {

    /** The preferred width of this bubble, never exceeding {@code limit}; measurement only, no side effects. */
    int preferredWidthWithin(int limit);
}
