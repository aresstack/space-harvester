package com.aresstack.spaceharvester.app.ui.extraction;

import com.aresstack.spaceharvester.application.extraction.AnalysisSection;
import com.aresstack.spaceharvester.application.extraction.AnalysisState;
import com.aresstack.spaceharvester.application.extraction.DocumentAnalysis;
import com.aresstack.spaceharvester.application.extraction.DocumentAnalysisProvider;
import com.aresstack.spaceharvester.application.extraction.DocumentRef;
import com.aresstack.spaceharvester.application.extraction.ExtractedField;
import com.aresstack.spaceharvester.application.extraction.SourceReference;

import java.util.List;

import static com.aresstack.spaceharvester.application.extraction.ExtractionOrigin.AI;
import static com.aresstack.spaceharvester.application.extraction.ExtractionOrigin.DERIVED;
import static com.aresstack.spaceharvester.application.extraction.ExtractionOrigin.REGEX;
import static com.aresstack.spaceharvester.application.extraction.FieldStatus.CONFLICT;
import static com.aresstack.spaceharvester.application.extraction.FieldStatus.EXPLICIT;
import static com.aresstack.spaceharvester.application.extraction.FieldStatus.NOT_FOUND;

/**
 * Erfundene Beispieldaten für Oberflächenentwicklung und Tests, NUR im Testcode. Jedes Ergebnis trägt
 * {@code sample = true}; die Ansicht kennzeichnet es als „Beispieldaten, keine echten Analyseergebnisse“.
 */
final class DemoDocumentAnalysisProvider implements DocumentAnalysisProvider {

    @Override
    public DocumentAnalysis result(DocumentRef document) {
        return new DocumentAnalysis(document, AnalysisState.COMPLETED, List.of(
                new AnalysisSection("Dokumentinformationen", List.of(
                        field("Dokumentart", "Erlass", REGEX, new SourceReference("Kopf", 1, 1, "Erlass")),
                        field("Dokumentdatum", "01.02.2026", REGEX, SourceReference.NONE),
                        field("Geschäftszeichen", "GZD-H 0000-BEISPIEL", REGEX,
                                new SourceReference("Kopf", 1, 2, 120, 139, "GZD-H 0000-BEISPIEL")),
                        ExtractedField.of("Aussteller", "", NOT_FOUND, AI))),
                new AnalysisSection("Konto 1", "Haushalts- und Kontoinformationen", List.of(
                        field("Bewirtschafternummer", "0000", REGEX, SourceReference.NONE),
                        ExtractedField.of("Kontotyp", "Einzelvorschuss", com.aresstack.spaceharvester.application
                                .extraction.FieldStatus.DERIVED, DERIVED))),
                new AnalysisSection("Fristeninformationen", List.of(
                        ExtractedField.of("Fristende", "31.12.2026", CONFLICT, AI)
                                .withSource(new SourceReference("Abschnitt 3", 2, 4, "bis zum 31.12.2026")),
                        ExtractedField.of("Fristende", "30.06.2027", CONFLICT, AI)
                                .withSource(new SourceReference("Abschnitt 5", 3, 1, "spätestens 30.06.2027")),
                        field("Fristverlängerung", "bis 31.03.2027", REGEX, SourceReference.NONE).withNote("beantragt"),
                        field("Fristverlängerung", "bis 31.01.2027", REGEX, SourceReference.NONE).withNote("genehmigt")))),
                "", true);
    }

    private static ExtractedField field(String label, String value,
                                        com.aresstack.spaceharvester.application.extraction.ExtractionOrigin origin,
                                        SourceReference source) {
        return ExtractedField.of(label, value, EXPLICIT, origin).withSource(source);
    }
}
