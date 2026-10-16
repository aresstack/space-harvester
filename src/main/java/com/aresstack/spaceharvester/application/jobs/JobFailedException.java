package com.aresstack.spaceharvester.application.jobs;

import java.util.Objects;

/**
 * Ein Auftrag ist mit benanntem Grund gescheitert. Code und Nachricht erscheinen unverändert im {@link JobStatus};
 * die Nachricht darf deshalb keine Zugangsdaten, Tokens oder URLs mit Query enthalten.
 */
public class JobFailedException extends RuntimeException {

    private final String code;

    public JobFailedException(String code, String message) {
        this(code, message, null);
    }

    public JobFailedException(String code, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public String code() {
        return code;
    }
}
