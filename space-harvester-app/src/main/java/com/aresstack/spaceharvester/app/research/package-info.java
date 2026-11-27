/**
 * Host-Adapter der Buchfunktion (Arbeitszweig der Ask-AI-Aufteilung): Space Harvester bindet Research4j (Phase 3, füllt
 * den Korb) über die neutralen Verträge von Knowledge4j an sein eigenes Datenmodell an. Die Suche ist dieselbe wie die
 * der Suchleiste, gelesen wird wie im Chat, und jede Seite, deren Text an das Modell geht, kommt vorher auf die Liste
 * freigegebener Seiten (ADR 0028/0033). Der Korb ist ein Favoritenordner (ADR 0034). Nichts läuft ohne Klick.
 */
package com.aresstack.spaceharvester.app.research;
