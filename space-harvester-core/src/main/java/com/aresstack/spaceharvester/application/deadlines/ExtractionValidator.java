package com.aresstack.spaceharvester.application.deadlines;

import com.aresstack.spaceharvester.application.extraction.AnalysisNote;
import com.aresstack.spaceharvester.application.extraction.AnalysisSection;
import com.aresstack.spaceharvester.application.extraction.DocumentAnalysis;
import com.aresstack.spaceharvester.application.extraction.ExtractedField;
import com.aresstack.spaceharvester.application.extraction.ExtractionOrigin;
import com.aresstack.spaceharvester.application.extraction.Processor;
import com.aresstack.spaceharvester.application.extraction.Qualifier;
import com.aresstack.spaceharvester.application.extraction.SourceReference;
import com.aresstack.spaceharvester.application.text.GroundedText;
import com.aresstack.spaceharvester.application.text.TextGrounding;
import com.aresstack.spaceharvester.application.text.TextSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.aresstack.spaceharvester.application.deadlines.DeadlineFieldKeys.*;

/**
 * Validierung der KI-Angaben (ADR 0035, Abschnitt 3, Stufe 3): Jedes Zitat wird mit {@link TextGrounding} im Text
 * verankert, zuerst im Bereich seines Sachverhalts, dann im ganzen Dokument. Was nirgends eindeutig steht, wird
 * verworfen; was nur außerhalb seines Sachverhalts steht, bleibt unbestätigt mit Hinweis. Bestätigt wird außerdem nur,
 * wenn der Wert zum Zitat passt (Ziffern und Daten) und eine „genehmigt“-Angabe ein Genehmigungswort zitiert: Ein
 * Antrag ist nie eine Genehmigung.
 *
 * <p>Felder anderer Stufen bleiben unverändert. Zusammengeführt wird danach über {@code FieldMerge}.
 */
public final class ExtractionValidator implements ExtractionStage {

    public static final Processor PROCESSOR = new Processor("deadline-validation", "1");

    private final Function<AnalysisInput, List<DocumentPart>> parts;

    /** @param parts dieselben Sachverhalte wie für die KI-Stufe */
    public ExtractionValidator(Function<AnalysisInput, List<DocumentPart>> parts) {
        this.parts = Objects.requireNonNull(parts, "parts");
    }

    @Override
    public Processor processor() {
        return PROCESSOR;
    }

    @Override
    public DocumentAnalysis extract(AnalysisInput input, DocumentAnalysis previous) {
        String text = input.text();
        Map<String, DocumentPart> byMatter = parts.apply(input).stream()
                .collect(Collectors.toMap(DocumentPart::matterId, part -> part, (a, b) -> a));
        TextSpan whole = new TextSpan(0, text.length());
        int dropped = 0;
        List<AnalysisSection> sections = new ArrayList<>();
        for (AnalysisSection section : previous.sections()) {
            DocumentPart part = byMatter.get(section.matterId());
            TextSpan window = part == null ? whole : new TextSpan(part.start(), Math.min(part.end(), text.length()));
            List<ExtractedField> fields = new ArrayList<>();
            for (ExtractedField field : section.fields()) {
                if (field.origin() != ExtractionOrigin.AI || field.verified()) {
                    fields.add(field);
                    continue;
                }
                ExtractedField checked = check(text, field, window, whole);
                if (checked == null) {
                    dropped++;
                } else {
                    fields.add(checked);
                }
            }
            if (!fields.isEmpty()) {
                sections.add(new AnalysisSection(section.matterId(), section.matter(), section.title(), fields));
            }
        }
        List<AnalysisNote> notes = new ArrayList<>(previous.notes());
        if (dropped > 0) {
            notes.add(new AnalysisNote(AnalysisNote.Code.AI_PARTIAL, dropped == 1
                    ? "Eine KI-Angabe ohne Beleg im Text wurde verworfen."
                    : dropped + " KI-Angaben ohne Beleg im Text wurden verworfen.", PROCESSOR));
        }
        List<Processor> processors = new ArrayList<>(previous.processors());
        if (!processors.contains(PROCESSOR)) {
            processors.add(PROCESSOR);
        }
        return new DocumentAnalysis(previous.document(), previous.state(), sections, previous.failure(),
                previous.sample(), notes, processors);
    }

    /** Das geprüfte Feld oder {@code null}, wenn das Zitat nirgends eindeutig steht. */
    static ExtractedField check(String text, ExtractedField field, TextSpan window, TextSpan whole) {
        String quote = field.source().excerpt();
        boolean inside = true;
        TextGrounding.Result result = TextGrounding.ground(text, null, quote, window);
        if (!(result instanceof TextGrounding.Grounded) && !window.equals(whole)) {
            result = TextGrounding.ground(text, null, quote, whole);
            inside = false;
        }
        if (!(result instanceof TextGrounding.Grounded grounded)) {
            return null;
        }
        GroundedText found = grounded.text();
        SourceReference source = new SourceReference(field.source().section(), field.source().page(),
                field.source().paragraph(), found.span().start(), found.span().end(), found.text());
        List<String> problems = new ArrayList<>();
        if (!inside) {
            problems.add("Zitat steht außerhalb des Sachverhalts");
        }
        if (!valueMatchesQuote(field, found.text())) {
            problems.add("Wert steht so nicht im Zitat");
        }
        if (field.qualifier() == Qualifier.GRANTED && !ApprovalWording.namesGrant(found.text())) {
            problems.add(ApprovalWording.namesRequest(found.text())
                    ? "Zitat nennt nur einen Antrag, keine Genehmigung" : "Zitat nennt keine Genehmigung");
        }
        String note = field.note();
        if (!problems.isEmpty()) {
            String joined = String.join("; ", problems);
            note = note.isEmpty() ? joined : note + "; " + joined;
        }
        return new ExtractedField(field.key(), field.label(), field.value(), field.rawValue(), field.status(),
                field.origin(), field.processor(), problems.isEmpty(), source, field.qualifier(), field.group(), note);
    }

    /** Kennzeichen und Daten müssen im Zitat stehen; Freitext wird nicht verglichen. */
    private static boolean valueMatchesQuote(ExtractedField field, String quote) {
        return switch (field.key()) {
            case MANAGER_NUMBER, OBJECT_ACCOUNT, BOOKING_POINT, LEDGER_ACCOUNT, BUDGET_ITEM ->
                    quote.replaceAll("[^0-9]", "").contains(field.value());
            case DOCUMENT_DATE, VALID_FROM, VALID_UNTIL, DEADLINE_END -> quoteHasDate(quote, field.value());
            default -> true;
        };
    }

    private static boolean quoteHasDate(String quote, String iso) {
        return IdentifierNormalizer.datesIn(quote).stream().anyMatch(date -> date.toString().equals(iso));
    }
}
