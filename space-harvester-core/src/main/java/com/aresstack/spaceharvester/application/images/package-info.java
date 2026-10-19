/**
 * Bildanalyse (ADR 0021, #362): Bilder der Lese-Ansicht einer Seite, inhaltlich beschrieben durch das eigene
 * Vision-Modell des KI-Dienstes, wie die Bildbeschreibung des Enterprise AI Client ({@code SourceImageAnalysisUseCase}).
 * Beschrieben wird nur auf Abruf ({@link com.aresstack.spaceharvester.application.images.PageImageAnalysis#describe}),
 * nie durch bloßes Anzeigen; jede Beschreibung liegt je Bildbytes (SHA-256), Beschreiber und Modell in der
 * Asset-Ablage, sodass dasselbe Bild nie zweimal an das Modell geht. Die Zuordnung zur Seite steht als Ergebnis des
 * Prozessors {@code images} in der Anreicherungsablage; die Leseansicht liest es mit
 * {@link com.aresstack.spaceharvester.application.images.PageImages#read}.
 */
package com.aresstack.spaceharvester.application.images;
