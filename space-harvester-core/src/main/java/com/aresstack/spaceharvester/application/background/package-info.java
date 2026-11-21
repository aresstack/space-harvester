/**
 * Hintergrundaufträge (Angelo 21.11.2026, ADR 0025 Nachtrag „Hintergrundaufträge“): alles, was die App von sich aus
 * anstößt, läuft als eigene Auftragsart {@link com.aresstack.spaceharvester.application.jobs.JobType#BACKGROUND},
 * nachrangig, je Space ein Unterauftrag unter einer gemeinsamen Gruppe, nach einem Neustart von selbst fortgesetzt.
 */
package com.aresstack.spaceharvester.application.background;
