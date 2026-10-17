package com.aresstack.spaceharvester.application.jobs;

/**
 * Zähler eines Auftrags.
 *
 * @param discovered gefundene Seiten
 * @param processed  verarbeitete Seiten (erfolgreich oder nicht)
 * @param written    in Cache oder Artefakt geschriebene Seiten
 * @param failed     Seiten, die nicht verarbeitet werden konnten
 */
public record JobProgress(long discovered, long processed, long written, long failed) {

    public static final JobProgress NONE = new JobProgress(0, 0, 0, 0);
}
