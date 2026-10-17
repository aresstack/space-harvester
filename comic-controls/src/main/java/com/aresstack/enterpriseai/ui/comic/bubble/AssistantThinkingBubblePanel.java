package com.aresstack.enterpriseai.ui.comic.bubble;

/**
 * Visualize the model's reasoning ({@code message.thinking}) as a green animated thought bubble, in the
 * same colour family as the normal assistant answer. Distinct from the amber
 * {@link AgentActivityBubblePanel} so model thinking and tool activity are never confused. The reasoning
 * text is streamed into the bubble via {@link #appendBodyText(String)}. All drawing/animation lives in
 * {@link AnimatedThoughtBubblePanel}; this subclass only fixes the assistant (green) theme.
 */
public final class AssistantThinkingBubblePanel extends AnimatedThoughtBubblePanel {

    public AssistantThinkingBubblePanel(BubbleSide side, BubblePalette palette, String title, String thinking) {
        super(side, ThoughtBubbleTheme.assistant(palette), title, thinking);
    }
}
