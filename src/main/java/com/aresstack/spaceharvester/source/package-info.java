/**
 * Quellenadapter hinter einer quellenunabhängigen SPI (Space-/Partition-Discovery, Metadaten, Seiten-Snapshots).
 * Der Confluence-Adapter stammt unverändert aus {@code aresstack/enterprise-ai-client} und liegt als Kopie im
 * Subprojekt {@code enterprise-ai-sources}; {@code source.confluence} baut ihn für den headless Betrieb zusammen.
 * {@link com.aresstack.spaceharvester.source.ConfiguredSourceCatalog} setzt den Port
 * {@link com.aresstack.spaceharvester.application.sources.SourceCatalog} aus der Konfiguration um.
 */
package com.aresstack.spaceharvester.source;
