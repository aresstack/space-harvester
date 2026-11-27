package com.aresstack.askai.agent.model.reranker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The typed outcome of decoding/validating a reranker configuration: either a valid
 * {@link RerankerConfigurationDocument} or a non-empty list of concrete violations. Decoding is STRICT
 * — malformed JSON, missing required fields, wrong types, unknown enum values and out-of-range numbers
 * all produce violations and a {@code null} document; the caller must treat that as a configuration
 * error and never fall back to a guessed configuration.
 */
public final class RerankerConfigurationValidationResult {

    public final boolean valid;
    public final RerankerConfigurationDocument document;
    public final List<String> violations;

    private RerankerConfigurationValidationResult(boolean valid,
                                                  RerankerConfigurationDocument document,
                                                  List<String> violations) {
        this.valid = valid;
        this.document = document;
        this.violations = Collections.unmodifiableList(violations);
    }

    public static RerankerConfigurationValidationResult valid(RerankerConfigurationDocument document) {
        return new RerankerConfigurationValidationResult(true, document,
                new ArrayList<String>());
    }

    public static RerankerConfigurationValidationResult invalid(List<String> violations) {
        return new RerankerConfigurationValidationResult(false, null,
                new ArrayList<String>(violations));
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (String violation : violations) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(violation);
        }
        return sb.toString();
    }
}
