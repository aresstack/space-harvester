/**
 * Adapter: deutsche Vorverarbeitung mit Apache OpenNLP (Apache 2.0) und dessen auf UD German GSD trainierten Modellen
 * aus Maven Central. Liefert Sätze, syntaktische Wörter (Verschmelzungen wie {@code am} aufgelöst), UPOS und
 * Grundformen, also das Eingabeschema FORM + UPOS des Parsermodells aus #99. Keine Kasusmerkmale (FEATS) und kein
 * XPOS: die Modelle liefern sie nicht.
 */
package com.aresstack.spaceharvester.nlp.opennlp;
