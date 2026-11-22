package com.aresstack.spaceharvester.deck.ftp.jes;

import java.util.Objects;

/**
 * Welche Jobs der Host liefert (Filter auf dem Host über {@code SITE JESOWNER/JESJOBNAME/JESSTATUS}).
 *
 * @param owner   Muster für den Besitzer, leer für den angemeldeten Benutzer, {@code *} für alle
 * @param jobName Muster für den Jobnamen, leer für {@code *}
 * @param status  Zustand
 */
public record JobFilter(String owner, String jobName, JobStatus status) {

    public JobFilter {
        owner = owner == null ? "" : owner.trim().toUpperCase(java.util.Locale.ROOT);
        jobName = jobName == null || jobName.isBlank() ? "*" : jobName.trim().toUpperCase(java.util.Locale.ROOT);
        status = Objects.requireNonNullElse(status, JobStatus.ALL);
    }

    /** Die eigenen Jobs, alle Namen, alle Zustände. */
    public static JobFilter mine() {
        return new JobFilter("", "*", JobStatus.ALL);
    }

    /** Der Besitzer für den Host: das Muster oder der angemeldete Benutzer. */
    public String ownerOr(String user) {
        return owner.isEmpty() ? (user == null ? "*" : user.toUpperCase(java.util.Locale.ROOT)) : owner;
    }
}
