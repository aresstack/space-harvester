package com.aresstack.askai.research.runtime.team;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes ONE technical line per model call (plan PR 01) and the warnings that belong to it, under the log
 * category {@code [model-call]}. A warning fires when the answer stopped at its length limit, when the
 * pre-call estimate plus the answer budget exceeds {@code num_ctx} minus {@value #SAFETY_MARGIN_TOKENS}, and
 * when the measured {@code prompt_eval_count} plus the answer budget exceeds {@code num_ctx}. Nothing here
 * changes a request.
 */
public final class ModelCallLog {

    /** Where the lines go; production writes STDERR like every other runtime log. */
    public interface Sink {
        void line(String line);
    }

    /** Headroom the estimate must leave below {@code num_ctx}; the estimate is only a guess. */
    public static final int SAFETY_MARGIN_TOKENS = 1024;

    public static final String TAG = "[model-call]";

    private static final Sink STDERR = new Sink() {
        @Override
        public void line(String line) {
            System.err.println(line);
        }
    };

    private static volatile Sink sink = STDERR;

    private ModelCallLog() {
    }

    /** Tests redirect the lines; {@code null} restores STDERR. */
    public static void setSink(Sink replacement) {
        sink = replacement == null ? STDERR : replacement;
    }

    /**
     * Log one call.
     *
     * @param phase  the active phase id, "" outside a phase
     * @param meter  the blocks of the final request
     * @param result the call's outcome (its metrics; a failure logs its status)
     */
    public static void log(String phase, PromptBlockMeter meter, MainModelChatResult result) {
        for (String line : lines(phase, meter, result)) {
            sink.line(line);
        }
    }

    static List<String> lines(String phase, PromptBlockMeter meter, MainModelChatResult result) {
        ModelCallMetrics m = result.getMetrics();
        List<String> lines = new ArrayList<String>();
        StringBuilder line = new StringBuilder(TAG);
        line.append(" phase=").append(orDash(phase))
                .append(" role=").append(orDash(m.getRole()))
                .append(" slot=").append(orDash(m.getSlot()))
                .append(" model=").append(orDash(m.getModelLabel()))
                .append(" status=").append(result.getStatus().name())
                .append(" blocks=[").append(meter.render()).append(']')
                .append(" chars=").append(meter.totalChars())
                .append(" estimate=").append(meter.estimatedTokens())
                .append(" prompt_eval_count=").append(m.getPromptEvalCount())
                .append(" eval_count=").append(m.getEvalCount())
                .append(" done_reason=").append(orDash(m.getDoneReason()))
                .append(" thinking_chars=").append(m.getThinkingChars())
                .append(" duration_ms=").append(m.getDurationMillis())
                .append(" load_ms=").append(m.getLoadMillis())
                .append(" prompt_eval_ms=").append(m.getPromptEvalMillis())
                .append(" num_ctx=").append(m.getContextTokens())
                .append(" answer_budget=").append(m.getAnswerBudget());
        lines.add(line.toString());
        String who = "role " + orDash(m.getRole()) + " (slot " + orDash(m.getSlot()) + ")";
        if (m.isCutAtLength()) {
            lines.add(TAG + " WARNING: " + who + " stopped at its answer limit (done_reason=length, budget "
                    + m.getAnswerBudget() + ")");
        }
        int ctx = m.getContextTokens();
        if (ctx > 0) {
            int budget = m.getAnswerBudget();
            if (meter.estimatedTokens() + budget > ctx - SAFETY_MARGIN_TOKENS) {
                lines.add(TAG + " WARNING: " + who + " may reach num_ctx " + ctx + " while answering: estimate "
                        + meter.estimatedTokens() + " + answer budget " + budget + " > num_ctx - "
                        + SAFETY_MARGIN_TOKENS);
            }
            if (m.getPromptEvalCount() >= 0 && m.getPromptEvalCount() + budget > ctx) {
                lines.add(TAG + " WARNING: " + who + " exceeds num_ctx " + ctx + ": prompt_eval_count "
                        + m.getPromptEvalCount() + " + answer budget " + budget);
            }
        }
        return lines;
    }

    private static String orDash(String value) {
        return value == null || value.isEmpty() ? "-" : value.replace(' ', '_');
    }
}
