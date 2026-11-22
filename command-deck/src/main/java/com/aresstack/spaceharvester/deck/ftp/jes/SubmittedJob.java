package com.aresstack.spaceharvester.deck.ftp.jes;

import java.util.Optional;

/**
 * Ergebnis des Einreichens.
 *
 * @param jobId   die Job-ID aus der Antwort des Hosts, leer wenn sie nicht darin stand
 * @param jobName der Name aus der ersten JOB-Karte, leer wenn keine
 * @param reply   die Antwort des Hosts
 */
public record SubmittedJob(Optional<String> jobId, Optional<String> jobName, String reply) {

    public SubmittedJob {
        jobId = jobId == null ? Optional.empty() : jobId;
        jobName = jobName == null ? Optional.empty() : jobName;
        reply = reply == null ? "" : reply.trim();
    }
}
