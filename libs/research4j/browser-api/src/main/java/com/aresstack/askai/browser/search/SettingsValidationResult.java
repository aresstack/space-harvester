package com.aresstack.askai.browser.search;

import java.util.Collections;
import java.util.List;

/**
 * Outcome of validating a {@link LegacyBrowserSearchSettings}. Invalid configurations are NEVER
 * silently corrected — every violation is reported with its setting key so the UI can show it.
 */
public final class SettingsValidationResult {

    /** One concrete violation: the flat setting key (codec key) and a human-readable message. */
    public static final class Violation {
        public final String settingKey;
        public final String message;

        public Violation(String settingKey, String message) {
            this.settingKey = settingKey;
            this.message = message;
        }

        @Override
        public String toString() {
            return settingKey + ": " + message;
        }
    }

    public final List<Violation> violations;

    public SettingsValidationResult(List<Violation> violations) {
        this.violations = Collections.unmodifiableList(violations);
    }

    public boolean isValid() {
        return violations.isEmpty();
    }

    /** All violations, one per line — suitable for logs and error dialogs. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (Violation violation : violations) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(violation);
        }
        return sb.toString();
    }
}
