package com.aresstack.research4j.judge;

import com.aresstack.knowledge4j.source.SourceRef;

/**
 * The profile (Steckbrief) of one source against the fence, as the model gave it, or the reason there is none.
 *
 * @param source  the source
 * @param fit     where it lies against the fence; {@code null} when the judgement failed
 * @param subject what the source is about, in one line
 * @param reason  the model's reason, or why there is no judgement
 */
public record SourceProfile(SourceRef source, FenceFit fit, String subject, String reason) {

    public boolean judged() {
        return fit != null;
    }
}
