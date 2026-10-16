/**
 * Space Harvester.
 *
 * <p>Schichten und Abhängigkeitsrichtung:
 * <ul>
 *   <li>{@code api} – REST-Controller, DTOs und Fehlerabbildung. Hängt nur von {@code application} ab.</li>
 *   <li>{@code application} – Anwendungsfälle (z. B. Spaces auflisten, Space exportieren).
 *       Kennt Quellen und Ausgabe nur über deren Verträge.</li>
 *   <li>{@code source} – Quellenadapter (Confluence, später weitere) hinter einer quellenunabhängigen SPI.</li>
 *   <li>{@code output} – Exportformate und Artefakte (JSON/NDJSON, Manifest).</li>
 * </ul>
 * {@code source} und {@code output} kennen weder {@code api} noch einander.
 */
package com.aresstack.spaceharvester;
