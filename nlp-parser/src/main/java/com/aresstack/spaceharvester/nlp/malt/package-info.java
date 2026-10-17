/**
 * Adapter: MaltParser 1.9.2 (BSD-3) als {@link com.aresstack.spaceharvester.nlp.syntax.DependencyParser}. Lädt ein
 * fertig trainiertes Modell aus einem Verzeichnis (entpacktes Release aus #99 oder Testmodell), prüft Manifest,
 * SHA-256, Version und Eingabeschema und parst threadsicher über {@code ConcurrentMaltParserModel}. Kein Download,
 * kein Training.
 */
package com.aresstack.spaceharvester.nlp.malt;
