package com.aresstack.enterpriseai.ui.comic.bubble;

/**
 * What a transcript needs from any message bubble, whatever renders its body: the plain-text
 * {@link SpeechBubblePanel} or a bubble whose body is rendered Markdown. The transcript keeps the header and the
 * timestamp in sync and reads the message source back for tests and context actions.
 */
public interface TranscriptBubble {

    BubbleSide getSide();

    /** The message source this bubble shows (plain text or Markdown), never {@code null}. */
    String getText();

    void setHeader(String header);

    void setHeaderTimestamp(long epochMillis);
}
