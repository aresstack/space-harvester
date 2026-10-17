package com.aresstack.enterpriseai.ui.comic.bubble;

/**
 * A component whose height depends on the width it is laid out at (wrapping text). Transcript rows ask it
 * for the exact height at the final bubble width instead of trusting an unwrapped preferred size.
 */
public interface WidthAwareHeight {

    /** The height this component needs when laid out at exactly {@code width} pixels. */
    int preferredHeightForWidth(int width);
}
