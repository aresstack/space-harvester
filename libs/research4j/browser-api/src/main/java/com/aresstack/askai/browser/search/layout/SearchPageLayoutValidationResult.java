package com.aresstack.askai.browser.search.layout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The outcome of validating a raw model layout decision against its artifact: valid, or a list of
 * concrete {@link SearchPageLayoutValidationViolation}s. Validation is purely structural — the
 * model's free-text explanation never influences it.
 */
public final class SearchPageLayoutValidationResult {

    public final boolean valid;
    public final List<SearchPageLayoutValidationViolation> violations;

    public SearchPageLayoutValidationResult(List<SearchPageLayoutValidationViolation> violations) {
        List<SearchPageLayoutValidationViolation> copy = violations == null
                ? new ArrayList<SearchPageLayoutValidationViolation>()
                : new ArrayList<SearchPageLayoutValidationViolation>(violations);
        this.violations = Collections.unmodifiableList(copy);
        this.valid = copy.isEmpty();
    }

    public boolean hasKind(SearchPageLayoutValidationViolation.Kind kind) {
        for (SearchPageLayoutValidationViolation violation : violations) {
            if (violation.kind == kind) {
                return true;
            }
        }
        return false;
    }

    public boolean hasUnknownContainerId() {
        return hasKind(SearchPageLayoutValidationViolation.Kind.UNKNOWN_CONTAINER_ID);
    }

    public boolean hasSchemaViolation() {
        for (SearchPageLayoutValidationViolation violation : violations) {
            if (violation.isSchemaViolation()) {
                return true;
            }
        }
        return false;
    }

    public boolean hasSemanticViolation() {
        for (SearchPageLayoutValidationViolation violation : violations) {
            if (violation.isSemanticViolation()) {
                return true;
            }
        }
        return false;
    }

    public List<String> messages() {
        List<String> messages = new ArrayList<String>();
        for (SearchPageLayoutValidationViolation violation : violations) {
            messages.add(violation.toString());
        }
        return messages;
    }
}
