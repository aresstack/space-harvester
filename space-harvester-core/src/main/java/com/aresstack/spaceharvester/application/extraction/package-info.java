/**
 * Experimentelle Dokumentanalyse (Extraktion): welche fachlichen Informationen ein Dokument enthält, als Felder mit
 * Wert, Aussagestand und optionaler Fundstelle, gruppiert in Abschnitte.
 *
 * <p>Nur Datentypen und Schnittstellen: Dieses Paket extrahiert nichts, ruft keine KI, berechnet keine Fristen und
 * wendet keine fachlichen Regeln an. Die Logik kommt später als {@link
 * com.aresstack.spaceharvester.application.extraction.DocumentAnalysisProvider}; die Oberfläche kennt nur diese
 * Schnittstelle. Jedes Ergebnis gehört zu genau einem Stand eines Dokuments ({@link
 * com.aresstack.spaceharvester.application.extraction.DocumentRef}, Kennung samt Version/ETag). Ergebnisse liegen nur
 * im Arbeitsspeicher ({@link com.aresstack.spaceharvester.application.extraction.InMemoryDocumentAnalyses}); die
 * eine Konfliktregel steht in {@link com.aresstack.spaceharvester.application.extraction.FieldMerge}. Dieses Paket hängt nie von der
 * Fristprüfung ab.
 */
package com.aresstack.spaceharvester.application.extraction;
