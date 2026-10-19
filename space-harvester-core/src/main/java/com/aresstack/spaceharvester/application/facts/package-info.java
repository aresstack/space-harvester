/**
 * Abfragbare Metadaten-Fakten je Seite (#182), nach dem Vorbild des Metadatenkatalogs im Enterprise AI Client
 * ({@code fact-api}: {@code FactStorePort}, {@code MetadataFact}, #106/#108 dort). Die Fakten entstehen ausschließlich
 * aus den bestehenden Anreicherungen ({@link com.aresstack.spaceharvester.application.facts.EnrichmentFacts}: Akronyme,
 * Begriffe, Eigenschaften), liegen im Lucene-Index der Anreicherungen neben ihrem Ergebnis (ADR 0003, kein H2) und
 * werden mit ihm ersetzt und gelöscht, wie die automatischen Fakten eines Extraktors im Client
 * ({@code replaceAutomaticFacts}). Suchbar sind sie nach Typ und nach Argumenten (Rolle und normalisierter Wert).
 *
 * <p>Nicht übernommen: manuelle Fakten, Prüfstatus ({@code FactReview}), Typkatalog zur Laufzeit
 * ({@code registerType}, JSON-Schema), Verweise zwischen Fakten und {@code ExtractionRun}; das ist der Faktenspeicher
 * des Assistenten im Client (ADR 0012, Punkt 4). Gelesen wird nur über
 * {@link com.aresstack.spaceharvester.application.facts.PageFacts} mit den Rechten des Aufrufers: Fakten einer Seite, die
 * er nicht lesen darf, erscheinen nirgends, auch nicht als Zahl. Fakten sind maschinell abgeleitete Hinweise, keine
 * geprüften Aussagen.
 */
package com.aresstack.spaceharvester.application.facts;
