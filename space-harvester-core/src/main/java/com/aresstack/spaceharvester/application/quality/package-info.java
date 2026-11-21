/**
 * Qualitätsverfahren der Suche (Q1, Ziel 1 Auffindbarkeit): Ein lokaler Testbestand ({@code fragen.tsv}) nennt je
 * Recherchefrage Bereich und erwartete Fundstellen; {@link com.aresstack.spaceharvester.application.quality.QualityRun}
 * stellt jede Frage über eine {@link com.aresstack.spaceharvester.application.quality.QualitySearch} (in der App genau
 * der Suchweg des Chats) und zählt Rang, Treffer unter den ersten 5, Chat-Kontext und 10, Laufzeit und ob die erwartete
 * Seite vorher lokal lag. Bericht und Vergleich enthalten nur Fragen-IDs und Zahlen; Wortlaut, Titel und Adressen
 * stehen nur in der lokalen Ergebnisdatei neben dem Testbestand, nie in Logs. An der Suche ändert dieses Paket nichts.
 * Format und Zählweise: {@code docs/suchqualitaet/README.md}.
 */
package com.aresstack.spaceharvester.application.quality;
