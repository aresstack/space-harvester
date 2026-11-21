/**
 * Verzeichnis der Verweise zwischen lokal gespeicherten Seiten (Paket V1 des Fahrplans Analyse-Reiter): je Seite die
 * ausgehenden Verweise, aus der nativen Payload gelesen, und daraus die Rückrichtung „wird verwiesen von“.
 *
 * <p>{@link com.aresstack.spaceharvester.application.links.PageLinks} ist der Anwendungsfall (Abfrage, Aktualisierung,
 * Beobachter), {@link com.aresstack.spaceharvester.application.links.PageLinkExtractor} der quellenspezifische Port, der
 * Verweisziele aus einer Payload liest (umgesetzt in {@code source..}), {@link
 * com.aresstack.spaceharvester.application.links.LinkIndex} die Ablage (Lucene in {@code persistence..}, im Speicher
 * für Tests). Alles ist abgeleitet und aus dem Cache neu aufbaubar.
 */
package com.aresstack.spaceharvester.application.links;
