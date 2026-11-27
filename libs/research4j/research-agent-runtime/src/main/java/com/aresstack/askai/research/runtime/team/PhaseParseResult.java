package com.aresstack.askai.research.runtime.team;

/** The outcome of a phase output contract parse: either a validated {@link PhaseAssistantOutput}, or a reason. */
public final class PhaseParseResult {

    private final PhaseAssistantOutput output;
    private final String error;

    private PhaseParseResult(PhaseAssistantOutput output, String error) {
        this.output = output;
        this.error = error;
    }

    public static PhaseParseResult ok(PhaseAssistantOutput output) {
        return new PhaseParseResult(output, null);
    }

    public static PhaseParseResult fail(String error) {
        return new PhaseParseResult(null, error);
    }

    public boolean isOk() {
        return output != null;
    }

    public PhaseAssistantOutput getOutput() {
        return output;
    }

    public String getError() {
        return error;
    }
}
