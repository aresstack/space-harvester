/**
 * Exporte aus der Quelle neben dem Seitenexport (Angelo, 16.11.2026): „Confluence-Metadaten“ über die dokumentierten
 * REST-Schnittstellen und „Analytics“ über die internen Endpunkte des Analytics-Plugins, je als eigene JSON-Datei
 * im Ausgabeordner. Eigene Abrufe über {@code HarvestSource.restGet}, nie ein weiteres {@code expand} im
 * Seitenabruf: Snapshot, {@code nativeFormat} und {@code attributesHash} bleiben unverändert.
 */
package com.aresstack.spaceharvester.application.metadata;
