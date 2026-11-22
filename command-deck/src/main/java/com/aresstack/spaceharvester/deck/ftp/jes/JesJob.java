package com.aresstack.spaceharvester.deck.ftp.jes;

import java.util.Objects;
import java.util.Optional;

/**
 * Ein Job aus der JES-Liste.
 *
 * @param jobId      etwa {@code JOB01234}, {@code STC00042}, {@code TSU00007} oder {@code J0012345}
 * @param jobName    Jobname
 * @param owner      Besitzer
 * @param status     {@code OUTPUT}, {@code ACTIVE}, {@code INPUT}, {@code HELD} …
 * @param jobClass   Jobklasse, leer wenn unbekannt
 * @param returnCode Rückgabe wie {@code 0000}, {@code ABEND S0C4}, {@code JCL ERROR}; leer solange er läuft
 * @param spoolFiles Anzahl der Spool-Dateien, 0 wenn unbekannt
 */
public record JesJob(String jobId, String jobName, String owner, String status, String jobClass,
                     Optional<String> returnCode, int spoolFiles) {

    public JesJob {
        Objects.requireNonNull(jobId, "jobId");
        jobName = jobName == null ? "" : jobName;
        owner = owner == null ? "" : owner;
        status = status == null ? "" : status;
        jobClass = jobClass == null ? "" : jobClass;
        returnCode = returnCode == null ? Optional.empty() : returnCode.filter(code -> !code.isBlank());
    }

    /** Ob der Job fertig ist und Ausgabe hat. */
    public boolean finished() {
        return "OUTPUT".equalsIgnoreCase(status);
    }

    /** Ob die Rückgabe auf einen Fehler deutet (alles außer 0000 und 0004). */
    public boolean failed() {
        return returnCode.map(code -> {
            String compact = code.replace("RC=", "").replace("CC", "").trim();
            return !(compact.equals("0000") || compact.equals("0004") || compact.equals("0") || compact.equals("4"));
        }).orElse(false);
    }
}
