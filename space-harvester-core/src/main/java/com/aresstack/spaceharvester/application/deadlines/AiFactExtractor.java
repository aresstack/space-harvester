package com.aresstack.spaceharvester.application.deadlines;

import com.aresstack.spaceharvester.ai.ModelRetry;
import com.aresstack.spaceharvester.ai.StrictJsonAnswer;
import com.aresstack.spaceharvester.application.extraction.AnalysisNote;
import com.aresstack.spaceharvester.application.extraction.AnalysisSection;
import com.aresstack.spaceharvester.application.extraction.AnalysisState;
import com.aresstack.spaceharvester.application.extraction.DocumentAnalysis;
import com.aresstack.spaceharvester.application.extraction.ExtractedField;
import com.aresstack.spaceharvester.application.extraction.ExtractionOrigin;
import com.aresstack.spaceharvester.application.extraction.FieldMerge;
import com.aresstack.spaceharvester.application.extraction.FieldStatus;
import com.aresstack.spaceharvester.application.extraction.Processor;
import com.aresstack.spaceharvester.application.extraction.Qualifier;
import com.aresstack.spaceharvester.application.extraction.SourceReference;
import com.aresstack.spaceharvester.application.jobs.JobCancelledException;
import com.aresstack.spaceharvester.application.jobs.ParallelWork;
import com.aresstack.spaceharvester.application.stepmodel.StepModel;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import java.util.function.IntSupplier;

import static com.aresstack.spaceharvester.application.deadlines.DeadlineFieldKeys.*;

/**
 * KI-Stufe der Fristenextraktion (ADR 0035, Abschnitt 3, Stufe 2): fragt das Modell je Sachverhalt nach den Merkmalen,
 * an denen eine Ausnahme von der Standardfrist erkennbar ist, mit wörtlichem Zitat je Wert. Die Antwort ist strenges
 * JSON ({@link StrictJsonAnswer}, eine Reparaturanfrage); jeder Aufruf wiederholt vorübergehende Fehler
 * ({@link StepModel#completeRetrying}). Die Felder sind unbestätigt ({@code verified=false}), bis die
 * {@link ExtractionValidator Validierung} ihr Zitat im Text gefunden hat.
 *
 * <p>Ein Teil über dem Zeichenbudget wird an Absätzen zerlegt; jeder Abschnitt geht ans Modell, nie wird still etwas
 * ausgelassen. Scheitert ein Abschnitt, ist das Ergebnis teilweise ({@code AI_PARTIAL}) mit dem Grund.
 *
 * <p>Fristgruppen der KI heißen {@code frist-ki-n}, damit sie nicht mit den Gruppen der Regex-Stufe zusammenfallen:
 * Gleiche Nummern bedeuten dort nicht dieselbe Fristaussage.
 */
public final class AiFactExtractor implements ExtractionStage {

    /** Steigt, wenn sich Anweisung oder Antwortform ändern. */
    public static final String PROMPT_VERSION = "prompt-1";
    /** Zeichenbudget je Aufruf, wenn die Modellzeile keines vorgibt. */
    public static final int DEFAULT_MAX_CHARS = 12_000;
    /** Kennung der Stufe; die Version trägt Modell und Anweisung. */
    public static final String ID = "deadline-ai";

    /** Die Schlüssel, die das Modell liefern darf, mit ihrer Bedeutung für die Anweisung. */
    static final Map<String, String> KEYS = keys();

    static final String INSTRUCTION = """
            Du liest einen Ausschnitt aus einem Erlass oder einer Verfügung der Bundesverwaltung zu Vorschuss- und \
            Verwahrungskonten. Gib nur Angaben zurück, die wörtlich im Ausschnitt stehen. Berechne nichts, ergänze \
            nichts und rate nicht.

            Regeln:
            - Jede Angabe braucht "quote": den Wortlaut aus dem Ausschnitt, Zeichen für Zeichen, höchstens 60 Wörter.
            - Ein Antrag ist keine Genehmigung. "qualifier" ist "requested", wenn etwas nur beantragt ist, "granted" \
            nur, wenn die Genehmigung ausdrücklich erteilt ist, sonst "none".
            - Das Datum des Erlasses ist nicht das Entstehungsdatum eines Betrags.
            - "frist.ende" nur, wenn das Ende im Text steht; nie selbst ausrechnen.
            - Widersprechen sich zwei Stellen, gib beide Angaben zurück.
            - Gehören Angaben zur selben Fristaussage, gib ihnen dieselbe Zahl in "group" (1, 2, …), sonst null.
            - "note" nur für einen kurzen Hinweis, etwa eine Bedingung oder einen Verweis.

            Erlaubte Schlüssel ("key"):
            %s

            Antworte ausschließlich mit einem JSON-Objekt dieser Form:
            {"facts":[{"key":"...","value":"...","quote":"...","qualifier":"none","group":null,"note":""}]}
            Gibt es nichts, antworte {"facts":[]}.""".formatted(keyList());

    private final StepModel model;
    private final IntSupplier lanes;
    private final ModelRetry.Listener retry;
    private final int maxChars;
    private final Function<AnalysisInput, List<DocumentPart>> parts;

    /**
     * @param model    Modellzeile „Fristen“, produktiv {@code ChatStepModel("deadline-facts", …)}
     * @param retry    meldet Wiederholungen, produktiv {@code ModelRetry.logging(cancelled)}
     * @param maxChars Zeichenbudget je Aufruf (mindestens 1000)
     * @param parts    Sachverhalte im Text, produktiv aus der Regex-Stufe; ohne Trennung das ganze Dokument
     */
    public AiFactExtractor(StepModel model, ModelRetry.Listener retry, int maxChars,
                           Function<AnalysisInput, List<DocumentPart>> parts) {
        this(model, retry, maxChars, parts, () -> 1);
    }

    /**
     * Wie oben, die Sachverhalte parallel.
     *
     * @param lanes so viele Sachverhalte zugleich an das Modell, produktiv {@code ai.parallel}
     */
    public AiFactExtractor(StepModel model, ModelRetry.Listener retry, int maxChars,
                           Function<AnalysisInput, List<DocumentPart>> parts, IntSupplier lanes) {
        this.lanes = Objects.requireNonNull(lanes, "lanes");
        this.model = Objects.requireNonNull(model, "model");
        this.retry = retry == null ? ModelRetry.Listener.LOG_ONLY : retry;
        if (maxChars < 1000) {
            throw new IllegalArgumentException("Zeichenbudget mindestens 1000");
        }
        this.maxChars = maxChars;
        this.parts = Objects.requireNonNull(parts, "parts");
    }

    @Override
    public Processor processor() {
        return new Processor(ID, model.version() + "/" + PROMPT_VERSION);
    }

    @Override
    public boolean available(AnalysisInput input) {
        return model.available();
    }

    @Override
    public DocumentAnalysis extract(AnalysisInput input, DocumentAnalysis previous) {
        Processor processor = processor();
        Map<List<String>, AnalysisSection> sections = new LinkedHashMap<>();
        List<AnalysisNote> notes = new ArrayList<>();
        List<DocumentPart> all = parts.apply(input);
        int failed = 0;
        int total = 0;
        List<DocumentPart> work = all.isEmpty() ? List.of(DocumentPart.whole(input.text())) : all;
        List<PartResult> results = ParallelWork.map(lanes.getAsInt(), work, "deadline-ai-", () -> {
        }, CancellationException::new, part -> extractPart(input, part, processor));
        for (int i = 0; i < work.size(); i++) {
            DocumentPart part = work.get(i);
            PartResult result = results.get(i);
            total += result.chunks();
            failed += result.failures().size();
            result.failures().forEach(reason -> notes.add(new AnalysisNote(AnalysisNote.Code.AI_PARTIAL,
                    (part.matter().isEmpty() ? "Dokument" : part.matter()) + ": " + reason, processor)));
            for (AnalysisSection section : result.sections()) {
                sections.merge(List.of(section.matterId(), section.title()), section, AiFactExtractor::join);
            }
        }
        if (input.truncated()) {
            notes.add(new AnalysisNote(AnalysisNote.Code.TEXT_TRUNCATED,
                    "Der Text wurde beim Lesen gekürzt; der Rest ging nicht an das Modell.", processor));
        }
        if (total > 0 && failed == total) {
            return new DocumentAnalysis(input.document(), AnalysisState.FAILED, List.of(),
                    "Kein Abschnitt konnte vom Modell gelesen werden", false, notes, List.of(processor));
        }
        return new DocumentAnalysis(input.document(), AnalysisState.COMPLETED, List.copyOf(sections.values()), "",
                false, notes, List.of(processor));
    }

    /** Ergebnis eines Teils: Abschnitte, Zahl der Aufrufe und die Gründe gescheiterter Aufrufe. */
    record PartResult(List<AnalysisSection> sections, int chunks, List<String> failures) {
    }

    /** Ein Teil, ggf. in mehreren Aufrufen. Blockiert. */
    PartResult extractPart(AnalysisInput input, DocumentPart part, Processor processor) {
        Map<String, List<ExtractedField>> byTitle = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        List<String> chunks = chunks(part.of(input.text()), maxChars);
        int groupBase = 0;
        for (String chunk : chunks) {
            List<Fact> facts;
            try {
                facts = ask(chunk);
            } catch (CancellationException | JobCancelledException e) {
                throw e; // Abbruch ist kein Teilergebnis
            } catch (StrictJsonAnswer.Unreadable e) {
                failures.add("Antwort nicht lesbar (" + e.getMessage() + ")");
                continue;
            } catch (RuntimeException e) {
                failures.add("Aufruf gescheitert (" + reason(e) + ")");
                continue;
            }
            int highest = 0;
            for (Fact fact : facts) {
                String title = titleOf(fact.key());
                String group = "";
                if (fact.group() != null && DEADLINE_GROUP_KEYS.contains(fact.key())) {
                    group = "frist-ki-" + (groupBase + fact.group());
                    highest = Math.max(highest, fact.group());
                }
                String section = title.equals(SECTION_DOCUMENT) ? SECTION_DOCUMENT : part.matter();
                ExtractedField field = new ExtractedField(fact.key(), label(fact.key()), normalize(fact.key(),
                        fact.value()), fact.value(), FieldStatus.EXPLICIT, ExtractionOrigin.AI, processor, false,
                        new SourceReference(section, 0, 0, fact.quote()), fact.qualifier(), group, fact.note());
                FieldMerge.add(byTitle.computeIfAbsent(title, t -> new ArrayList<>()), field);
            }
            groupBase += highest;
        }
        List<AnalysisSection> sections = new ArrayList<>();
        byTitle.forEach((title, fields) -> sections.add(title.equals(SECTION_DOCUMENT)
                ? new AnalysisSection("", "", SECTION_DOCUMENT, fields)
                : new AnalysisSection(part.matterId(), part.matter(), title, fields)));
        return new PartResult(sections, chunks.size(), failures);
    }

    private List<Fact> ask(String chunk) {
        return StrictJsonAnswer.read(new StrictJsonAnswer.Asker() {
            @Override
            public String ask() {
                return model.completeRetrying(INSTRUCTION, chunk, retry);
            }

            @Override
            public String repair(String previous, String instruction) {
                return model.completeRetrying(INSTRUCTION, chunk + "\n\n---\nDeine vorige Antwort:\n" + previous
                        + "\n---\n" + instruction, retry);
            }
        }, AiFactExtractor::parse);
    }

    /** Eine Angabe des Modells. */
    record Fact(String key, String value, String quote, Qualifier qualifier, Integer group, String note) {
    }

    /** Liest die Antwort; unbekannte Schlüssel und Angaben ohne Wert oder Zitat fallen weg. */
    static List<Fact> parse(String answer) {
        ObjectNode root = StrictJsonAnswer.object(answer);
        JsonNode facts = root.get("facts");
        if (facts == null || !facts.isArray()) {
            throw new StrictJsonAnswer.Unreadable("no facts array");
        }
        List<Fact> out = new ArrayList<>();
        for (JsonNode node : facts) {
            String key = text(node, "key");
            String value = text(node, "value");
            String quote = text(node, "quote");
            if (!KEYS.containsKey(key) || value.isEmpty() || quote.isEmpty()) {
                continue;
            }
            JsonNode group = node.get("group");
            Integer number = group != null && group.isIntegralNumber() && group.asInt() > 0 ? group.asInt() : null;
            out.add(new Fact(key, value, quote, qualifier(text(node, "qualifier")), number, text(node, "note")));
        }
        return out;
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        return value == null || value.isNull() ? "" : value.asString().strip();
    }

    private static Qualifier qualifier(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "granted" -> Qualifier.GRANTED;
            case "requested" -> Qualifier.REQUESTED;
            default -> Qualifier.NONE;
        };
    }

    /** Dieselbe Normalisierung wie die Regex-Stufe, damit gleiche Angaben kein falscher CONFLICT werden. */
    static String normalize(String key, String raw) {
        Optional<String> value = switch (key) {
            case MANAGER_NUMBER, OBJECT_ACCOUNT, BOOKING_POINT, LEDGER_ACCOUNT, BUDGET_ITEM ->
                    IdentifierNormalizer.digits(raw);
            case FILE_REFERENCE, DOK_ID, DIVISION -> Optional.of(IdentifierNormalizer.fileReference(raw));
            case DOCUMENT_DATE, VALID_FROM, VALID_UNTIL, DEADLINE_END ->
                    IdentifierNormalizer.germanDate(raw).map(Object::toString);
            case BOOK, ACCOUNT_SCOPE, DEADLINE_REFERENCE, ACCOUNT_CATEGORY ->
                    Optional.of(raw.strip().toLowerCase(Locale.ROOT));
            default -> Optional.empty();
        };
        return value.filter(v -> !v.isEmpty()).orElse(raw.strip());
    }

    private static String titleOf(String key) {
        if (key.startsWith("dokument.")) {
            return SECTION_DOCUMENT;
        }
        return key.startsWith("konto.") ? SECTION_ACCOUNT : SECTION_DEADLINES;
    }

    private static String label(String key) {
        String description = KEYS.get(key);
        int cut = description.indexOf(';');
        return cut < 0 ? description : description.substring(0, cut);
    }

    /** Zerlegt an Leerzeilen, sonst an Zeilenumbrüchen, sonst an Leerraum; nichts geht verloren. */
    static List<String> chunks(String text, int maxChars) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (text.length() - start > maxChars) {
            int limit = start + maxChars;
            int cut = text.lastIndexOf("\n\n", limit);
            if (cut <= start) {
                cut = text.lastIndexOf('\n', limit);
            }
            if (cut <= start) {
                cut = text.lastIndexOf(' ', limit);
            }
            if (cut <= start) {
                cut = limit;
            }
            chunks.add(text.substring(start, cut));
            start = cut;
        }
        if (start < text.length() && !text.substring(start).isBlank()) {
            chunks.add(text.substring(start));
        }
        return chunks;
    }

    private static AnalysisSection join(AnalysisSection a, AnalysisSection b) {
        List<ExtractedField> fields = new ArrayList<>(a.fields());
        b.fields().forEach(field -> FieldMerge.add(fields, field));
        return new AnalysisSection(a.matterId(), a.matter(), a.title(), fields);
    }

    private static String reason(RuntimeException e) {
        return e instanceof com.aresstack.spaceharvester.ai.AiServiceException ai ? String.valueOf(ai.reason())
                : e.getClass().getSimpleName();
    }

    private static String keyList() {
        StringBuilder out = new StringBuilder();
        KEYS.forEach((key, description) -> out.append("- ").append(key).append(": ").append(description)
                .append('\n'));
        return out.toString().stripTrailing();
    }

    private static Map<String, String> keys() {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put(DOCUMENT_KIND, "Dokumentart; z. B. Erlass, Verfügung, Genehmigung");
        keys.put(DOCUMENT_DATE, "Datum des Dokuments");
        keys.put(ISSUER, "Aussteller; Behörde");
        keys.put(DIVISION, "Referat");
        keys.put(FILE_REFERENCE, "Geschäftszeichen");
        keys.put(DOK_ID, "DOK-Kennung");
        keys.put(SUBJECT, "Betreff");
        keys.put(REFERENCED_DOCUMENT, "Bezug; ein früheres Dokument, auf das sich dieses bezieht");
        keys.put(VALID_FROM, "Geltung ab; Datum, ab dem die Regelung gilt");
        keys.put(VALID_UNTIL, "Geltung bis");
        keys.put(MANAGER_NUMBER, "Bewirtschafternummer");
        keys.put(MANAGER, "Bewirtschafter; Name der Stelle");
        keys.put(FUNDS_DISTRIBUTOR, "Mittelverteiler");
        keys.put(TITLE_ADMINISTRATOR, "Titelverwalter");
        keys.put(BOOK, "Buch; nur \"vorschuss\" oder \"verwahrung\"");
        keys.put(BOOKING_POINT, "Buchungsstelle");
        keys.put(SECTION, "Abschnitt; nur die Zahl");
        keys.put(SUBSECTION, "Unterabschnitt; nur die Zahl");
        keys.put(OBJECT_ACCOUNT, "Objektkonto");
        keys.put(LEDGER_ACCOUNT, "Sachbuchkonto");
        keys.put(BUDGET_ITEM, "Haushaltsstelle");
        keys.put(ACCOUNT_SCOPE, "Kontoumfang; nur \"einzelkonto\" oder \"sammelkonto\"");
        keys.put(ACCOUNT_PURPOSE, "Zweck des Kontos");
        keys.put(ACCOUNT_CATEGORY, "Kategorie; z. B. \"durchlaufende-gelder\", Sockelbetrag einer Geldstelle, "
                + "Sicherheitsleistung, beschlagnahmte Gelder, unanbringliche Auszahlung");
        keys.put(DEADLINE_STATEMENT, "Fristaussage; der Satz, der eine Frist regelt");
        keys.put(DEADLINE_START, "Fristbeginn; wovon die Frist an läuft");
        keys.put(DEADLINE_DURATION, "Fristdauer");
        keys.put(DEADLINE_END, "Fristende; nur wenn es im Text steht");
        keys.put(DEADLINE_REFERENCE, "Fristbezug; nur \"einzelne-zahlung\" oder \"konto\"");
        keys.put(DEADLINE_CONDITION, "Bedingung der Frist");
        keys.put(DEADLINE_EXCEPTION, "Ausnahme von der Frist; mit qualifier, wenn beantragt oder genehmigt");
        keys.put(DEADLINE_EXTENSION, "Verlängerung der Frist; mit qualifier, wenn beantragt oder genehmigt");
        keys.put(BOOKING_RULE, "Buchungsregelung; etwa eine abweichende Buchungsreihenfolge");
        keys.put(SETTLEMENT_RULE, "Ausgleichsregelung");
        keys.put(LEGAL_REFERENCE, "Verweis auf eine Vorschrift; z. B. § 60 BHO, Nr. 6 VO/VW-RiB");
        return Collections.unmodifiableMap(keys);
    }
}
