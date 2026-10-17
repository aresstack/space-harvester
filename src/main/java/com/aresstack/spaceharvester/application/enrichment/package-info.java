/**
 * Deterministische Anreicherungen (#29): Prozessoren leiten aus einer sicheren Textprojektion der nativen Payload
 * Hinweise ab (Akronyme, Gliederung, später Entitäten und Relationen). Die Payload bleibt unverändert; jedes Ergebnis
 * ist ein eigener Eintrag je Seite und Prozessor mit Prozessorversion und Eingabe-Hash und wird unabhängig neu
 * berechnet. Ändert sich die Eingabe oder die Prozessorversion, gilt nur das betroffene Ergebnis als veraltet.
 *
 * <p>Ports: {@link com.aresstack.spaceharvester.application.enrichment.PageTextProjector} (quellenspezifische
 * Textprojektion, ohne Makroausführung), {@link com.aresstack.spaceharvester.application.enrichment.EnrichmentProcessor}
 * (SPI der Prozessoren) und {@link com.aresstack.spaceharvester.application.enrichment.EnrichmentStore} (Ablage).
 * Ergebnisse sind maschinell abgeleitete Hinweise, keine geprüften Fakten; generative KI ist kein Bestandteil.
 */
package com.aresstack.spaceharvester.application.enrichment;
