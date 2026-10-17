/**
 * Parserneutraler Vertrag für deutsches Dependency Parsing nach Universal Dependencies (#98, ADR 0005): Sätze mit
 * Wörtern (syntaktischen Tokens) samt Fundstelle im Originaltext, Wortart, Grundform, Merkmalen, Kopf und Relation,
 * dazu Mehrworttokens ({@code am} = {@code an dem}) und die Herkunft jeder Schicht ({@link
 * com.aresstack.spaceharvester.nlp.syntax.Component}). Keine Typen von MaltParser, OpenNLP oder Spring; Ports
 * {@link com.aresstack.spaceharvester.nlp.syntax.GermanPreprocessor} und
 * {@link com.aresstack.spaceharvester.nlp.syntax.DependencyParser}, zusammengesetzt von
 * {@link com.aresstack.spaceharvester.nlp.syntax.DependencyAnalysis}.
 */
package com.aresstack.spaceharvester.nlp.syntax;
