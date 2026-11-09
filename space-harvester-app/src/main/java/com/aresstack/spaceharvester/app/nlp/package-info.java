/**
 * Lokale Aussagenanalyse der Desktop-App (#499, ADR 0024): belegt den Core-Port
 * {@link com.aresstack.spaceharvester.application.knowledge.StatementAnalyzer} mit MaltParser aus {@code :nlp-parser}.
 * Nur hier liegt die Abhängigkeit auf den Parser; Core und Dienst kennen ihn nicht.
 */
package com.aresstack.spaceharvester.app.nlp;
