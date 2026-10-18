/**
 * Lese-Ansicht einer Seite (#176): die Darstellung, die die Quelle selbst rendert (bei Confluence
 * {@code body.export_view} mit ausgeführten Makros), samt Lesetext und Bildern der Ansicht. Sie liegt als abgeleitete
 * Darstellung neben dem Raw-Snapshot, der unverändert die Quelle der Wahrheit bleibt.
 *
 * <p>{@link com.aresstack.spaceharvester.application.reading.ForeignContent} erkennt am Storage-Format, ob die Quelle
 * beim Rendern Inhalte anderer Seiten oder Systeme einbindet. Solche Inhalte rendert die Quelle mit ihren eigenen
 * Zugangsdaten; sie erhält nur, wer alles lesen darf, was diese Zugangsdaten sehen
 * ({@link com.aresstack.spaceharvester.application.access.AccessGuard#permitsSourceWide}).
 */
package com.aresstack.spaceharvester.application.reading;
