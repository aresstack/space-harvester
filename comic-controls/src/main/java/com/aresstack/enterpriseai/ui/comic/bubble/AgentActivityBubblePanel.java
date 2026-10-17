package com.aresstack.enterpriseai.ui.comic.bubble;

/**
 * Visualize one explainable agent/tool action as an amber animated thought bubble.
 *
 * <p>Use this component for user-facing action rationale, for example why an agent opens a web page. Do
 * <em>not</em> use it for model reasoning — that has its own green {@link AssistantThinkingBubblePanel}.
 * Complete the activity with a compact summary; the bubble then bursts and lets the summary rise and
 * fade like a strategy-game reward. All drawing/animation lives in {@link AnimatedThoughtBubblePanel};
 * this subclass only fixes the amber theme.</p>
 */
public final class AgentActivityBubblePanel extends AnimatedThoughtBubblePanel {

    public AgentActivityBubblePanel(BubbleSide side, BubblePalette palette, String title, String explanation) {
        super(side, ThoughtBubbleTheme.activity(palette), title, explanation);
    }
}
