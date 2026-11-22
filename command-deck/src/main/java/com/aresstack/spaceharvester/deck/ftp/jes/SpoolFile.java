package com.aresstack.spaceharvester.deck.ftp.jes;

/**
 * Eine Spool-Datei (DD) eines Jobs.
 *
 * @param id          Nummer für {@code RETR JOBnnnnn.id}
 * @param ddName      etwa {@code JESMSGLG}, {@code JESJCL}, {@code JESYSMSG}, {@code SYSPRINT}; {@code SPOOL#n} wenn
 *                    unbekannt
 * @param stepName    Schritt, leer wenn unbekannt
 * @param procStep    Prozedurschritt, leer wenn keiner
 * @param outputClass Ausgabeklasse, leer wenn unbekannt
 * @param bytes       Größe in Bytes, 0 wenn unbekannt
 * @param records     Zeilen, 0 wenn unbekannt
 */
public record SpoolFile(int id, String ddName, String stepName, String procStep, String outputClass, long bytes,
                        int records) {

    public SpoolFile {
        ddName = ddName == null || ddName.isBlank() ? "SPOOL#" + id : ddName;
        stepName = stepName == null ? "" : stepName;
        procStep = procStep == null ? "" : procStep;
        outputClass = outputClass == null ? "" : outputClass;
    }

    /** Kurzname für Listen: {@code STEP1 · SYSPRINT} oder nur der DD-Name. */
    public String label() {
        return stepName.isEmpty() || stepName.equalsIgnoreCase("JES2") ? ddName : stepName + " · " + ddName;
    }
}
