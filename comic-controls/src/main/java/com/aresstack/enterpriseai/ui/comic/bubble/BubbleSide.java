package com.aresstack.enterpriseai.ui.comic.bubble;

/**
 * Define the transcript side occupied by a bubble.
 *
 * <p>Always point the visual tail toward the transcript center: left-side bubbles point right,
 * while right-side bubbles point left.</p>
 */
public enum BubbleSide {
    LEFT,
    RIGHT;

    public boolean pointsRight() {
        return this == LEFT;
    }

    public boolean pointsLeft() {
        return this == RIGHT;
    }
}
