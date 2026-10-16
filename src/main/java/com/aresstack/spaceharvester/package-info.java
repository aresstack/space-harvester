/**
 * Space Harvester.
 *
 * <p>Schichten und Abhängigkeitsrichtung:
 * <ul>
 *   <li>{@code api} – REST-Controller, DTOs und Fehlerabbildung. Hängt nur von {@code application} ab.</li>
 *   <li>{@code application} – Anwendungsfälle (z. B. Spaces auflisten, Space exportieren).
 *       Frameworkneutral (kein Spring); kennt Quellen und Ausgabe nur über deren Verträge.</li>
 *   <li>{@code config} – Spring-Konfiguration, die Anwendungsfälle, Quellen und Ausgaben verdrahtet.</li>
 *   <li>{@code source} – Quellenadapter (Confluence, später weitere) hinter einer quellenunabhängigen SPI.</li>
 *   <li>{@code output} – Exportformate und Artefakte (JSON/NDJSON, Manifest).</li>
 *   <li>{@code persistence} – Speicher hinter den Ports aus {@code application}; Lucene nur in
 *       {@code persistence.lucene}.</li>
 * </ul>
 * {@code source} und {@code output} kennen weder {@code api} noch einander; {@code persistence} kennt nur
 * {@code application}. {@code LayeringTest} prüft diese Regeln.
 */
package com.aresstack.spaceharvester;
