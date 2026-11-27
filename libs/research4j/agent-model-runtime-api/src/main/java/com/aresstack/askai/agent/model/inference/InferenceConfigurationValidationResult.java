package com.aresstack.askai.agent.model.inference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The strict result of decoding an {@code inference-config.json}: either a valid {@link
 * InferenceConfigurationDocument}, or an invalid result carrying every concrete violation (never a
 * guessed configuration).
 */
public final class InferenceConfigurationValidationResult {

    public final boolean valid;
    public final InferenceConfigurationDocument document;
    public final List<String> violations;

    private InferenceConfigurationValidationResult(boolean valid, InferenceConfigurationDocument document,
                                                   List<String> violations) {
        this.valid = valid;
        this.document = document;
        this.violations = Collections.unmodifiableList(new ArrayList<String>(violations));
    }

    public static InferenceConfigurationValidationResult valid(InferenceConfigurationDocument document) {
        return new InferenceConfigurationValidationResult(true, document, Collections.<String>emptyList());
    }

    public static InferenceConfigurationValidationResult invalid(List<String> violations) {
        return new InferenceConfigurationValidationResult(false, null, violations);
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < violations.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append("  - ").append(violations.get(i));
        }
        return sb.toString();
    }
}
