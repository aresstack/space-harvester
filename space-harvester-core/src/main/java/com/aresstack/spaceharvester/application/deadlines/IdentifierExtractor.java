package com.aresstack.spaceharvester.application.deadlines;

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
import com.aresstack.spaceharvester.application.passages.SentenceSegmenter;
import com.aresstack.spaceharvester.nlp.lexicon.GermanLexicon;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.aresstack.spaceharvester.application.deadlines.DeadlineFieldKeys.*;

/**
 * Regex-Stufe der Fristenextraktion (ADR 0035, Abschnitt 3, Stufe 1): Kennzeichen hinter ihrer Beschriftung, Buch,
 * Abschnitt und Fristsätze, je mit Rohschreibweise, normalisiertem Wert und genauer Fundstelle. Es wird nichts ergänzt
 * und nichts berechnet: Ein Wert, der nicht hinter seiner Beschriftung steht, fehlt; ein Fristende steht nur da, wenn
 * das Dokument es nennt.
 *
 * <p>Sachverhalte trennt die Stufe an Überschriftzeilen mit Buch oder Abschnitt und an Kontogrenzen (eine zweite
 * Bewirtschafternummer oder ein zweites Objektkonto). Ein Wert gehört immer nur zu dem Sachverhalt, in dessen Text er
 * steht. Angaben zum Dokument liest sie nur vor dem ersten Sachverhalt.
 *
 * <p>Ein Fristsatz braucht ein Fristsignal (Frist, spätestens, bis zum …) und eine Regelung: ein Modalverb oder ein
 * „zu“-Infinitiv wie „auszugleichen“. Modalverb und Verneinung kommen aus {@link GermanLexicon} und stehen in der
 * Bemerkung, damit „darf nicht“ nicht als Erlaubnis gelesen wird.
 */
public final class IdentifierExtractor implements ExtractionStage {

    /** Steigt, wenn sich die Muster ändern; Ergebnisse der alten Version gelten dann als veraltet. */
    public static final Processor PROCESSOR = new Processor("deadline-regex", "1");

    private static final String DIGITS_VALUE = "([0-9][0-9 .\\-/\\u00a0]*[0-9]|[0-9])";
    private static final String LABEL_END = "\\s*(?:[:.]|-?Nr\\.?)?\\s*";
    private static final String LINE_VALUE = "(\\S[^\\n]*?)\\s*$";

    /** Kennzeichen eines Kontos; sie gelten überall im Sachverhalt, weil sie hinter ihrer Beschriftung stehen. */
    private static final List<Label> ACCOUNT_LABELS = List.of(
            digits(MANAGER_NUMBER, "Bewirtschafternummer", "Bewirtschafter(?:nummer|-?Nr\\.?)"),
            digits(OBJECT_ACCOUNT, "Objektkonto", "Objektkonto(?:nummer)?"),
            digits(BOOKING_POINT, "Buchungsstelle", "Buchungsstelle"),
            digits(LEDGER_ACCOUNT, "Sachbuchkonto", "Sachbuchkonto"),
            digits(BUDGET_ITEM, "Haushaltsstelle", "Haushaltsstelle"));

    /** Angaben zum Dokument, nur vor dem ersten Sachverhalt. */
    private static final List<Label> DOCUMENT_LABELS = List.of(
            line(FILE_REFERENCE, "Geschäftszeichen", "(?:Geschäftszeichen|Aktenzeichen|GZ|Az)",
                    IdentifierNormalizer::fileReference),
            new Label(DOK_ID, "DOK-Kennung", Pattern.compile("(?im)^\\s*DOK" + LABEL_END + "([0-9][0-9 ./\\-]*[0-9])"),
                    raw -> Optional.of(IdentifierNormalizer.fileReference(raw))),
            line(DIVISION, "Referat", "Referat", IdentifierNormalizer::fileReference),
            line(SUBJECT, "Betreff", "Betreff", String::strip),
            line(REFERENCED_DOCUMENT, "Bezug", "Bezug", String::strip),
            new Label(DOCUMENT_DATE, "Datum", Pattern.compile(
                    "(?im)^\\s*(?:Datum\\s*:?|[\\p{L}][\\p{L} .\\-]*,)\\s*(" + IdentifierNormalizer.DATE_TEXT + ")\\s*$"),
                    raw -> IdentifierNormalizer.germanDate(raw).map(Object::toString)));

    private static final Pattern BOOK_ADVANCE_WORD = Pattern.compile("(?i)\\bVorschu(?:ss|ß)buch(?:es|s)?\\b");
    private static final Pattern BOOK_CUSTODY_WORD = Pattern.compile("(?i)\\bVerwahr(?:ungs)?buch(?:es|s)?\\b");
    private static final Pattern SECTION_WORD = Pattern.compile("(?i)\\bAbschnitt\\s+(\\d+)");
    private static final Pattern SUBSECTION_WORD = Pattern.compile("(?i)\\bUnterabschnitt\\s+(\\d+)");
    private static final Pattern SECTION_HEADING = Pattern.compile("(?i)^(?:\\d+\\.?\\s*)?(?:Unter)?abschnitt\\s+\\d+");
    /** „Beschriftung: Wert“; solche Zeilen gehören zu keinem Satz. */
    private static final Pattern LABEL_LINE = Pattern.compile("^\\s*[\\p{L}][\\p{L} .\\-/]{1,40}:\\s*\\S");
    private static final Pattern PASS_THROUGH = Pattern.compile("(?i)\\bdurchlaufende[n]?\\s+Geld(?:er|ern)\\b");
    /** Zeilen mit Dokumentbeschriftung beginnen nie einen Sachverhalt, auch wenn ein Buch darin steht. */
    private static final Pattern DOCUMENT_LINE = Pattern.compile(
            "(?i)^\\s*(?:Betreff|Bezug|Geschäftszeichen|Aktenzeichen|GZ|Az)\\b");

    private static final Pattern DEADLINE_SIGNAL = Pattern.compile("(?i)\\b(?:frist\\p{L}*|\\p{L}+frist\\p{L}*"
            + "|spätestens|bis\\s+zum|bis\\s+spätestens|binnen|innerhalb|unverzüglich"
            + "|ende\\s+des\\s+(?:\\p{L}+\\s+)*(?:haushaltsjahr|jahr|monat)\\p{L}*)\\b");
    private static final Pattern ZU_INFINITIVE = Pattern.compile(
            "(?i)\\b(?:ab|an|auf|aus|ein|um|zurück|vor|nach|weiter)zu\\p{L}+en\\b|\\bzu\\s+\\p{L}+en\\b");
    private static final Pattern WORD = Pattern.compile("\\p{L}+");
    private static final Pattern DEADLINE_END_DATE = Pattern.compile(
            "(?i)\\b(?:bis\\s+(?:spätestens\\s+)?(?:zum|am)?|spätestens\\s+(?:zum|am|bis\\s+zum)?)\\s*"
                    + "(" + IdentifierNormalizer.DATE_TEXT + ")");
    private static final Pattern EXTENSION = Pattern.compile("(?i)\\b(?:verlänger\\p{L}*|fristverlänger\\p{L}*)");
    private static final Pattern PAYMENT_REFERENCE = Pattern.compile(
            "(?i)\\b(?:je(?:de[rsn]?)?\\s+(?:einzelne[nr]?\\s+)?(?:zahlung|buchung|betrag)|einzelne[nr]?\\s+(?:zahlung|buchung|betr\\p{L}+))\\b");
    private static final Pattern ACCOUNT_REFERENCE = Pattern.compile("(?i)\\b(?:das|des|dem)\\s+(?:gesamte[nm]?\\s+)?konto\\b");

    private final SentenceSegmenter sentences;

    /** @param sentences Satzgrenzen, produktiv {@code OpenNlpSentenceSegmenter.german(…)} (ADR 0020) */
    public IdentifierExtractor(SentenceSegmenter sentences) {
        this.sentences = Objects.requireNonNull(sentences, "sentences");
    }

    @Override
    public Processor processor() {
        return PROCESSOR;
    }

    @Override
    public DocumentAnalysis extract(AnalysisInput input, DocumentAnalysis previous) {
        String text = input.text();
        List<Matter> matters = matters(text);
        int headerEnd = matters.isEmpty() ? text.length() : matters.get(0).start();

        List<AnalysisSection> sections = new ArrayList<>();
        List<ExtractedField> document = new ArrayList<>();
        for (Label label : DOCUMENT_LABELS) {
            label.find(text, 0, headerEnd, SECTION_DOCUMENT, "", document);
        }
        if (!document.isEmpty()) {
            sections.add(new AnalysisSection("", "", SECTION_DOCUMENT, document));
        }
        for (int i = 0; i < matters.size(); i++) {
            Matter matter = matters.get(i);
            String matterId = matterId(i);
            List<ExtractedField> account = new ArrayList<>();
            for (Label label : ACCOUNT_LABELS) {
                label.find(text, matter.start(), matter.end(), matter.heading(), "", account);
            }
            headingFields(text, matter, account);
            List<ExtractedField> deadlines = deadlineStatements(text, matter);
            if (!account.isEmpty()) {
                sections.add(new AnalysisSection(matterId, matter.heading(), SECTION_ACCOUNT, account));
            }
            if (!deadlines.isEmpty()) {
                sections.add(new AnalysisSection(matterId, matter.heading(), SECTION_DEADLINES, deadlines));
            }
        }
        List<AnalysisNote> notes = input.truncated()
                ? List.of(new AnalysisNote(AnalysisNote.Code.TEXT_TRUNCATED,
                "Der Text wurde beim Lesen gekürzt; der Rest ist nicht durchsucht.", PROCESSOR))
                : List.of();
        return new DocumentAnalysis(input.document(), AnalysisState.COMPLETED, sections, "", false, notes,
                List.of(PROCESSOR));
    }

    // --- Sachverhalte ------------------------------------------------------------------------------------------

    /**
     * Die Teile für KI-Stufe und Validierung (ADR 0035, Abschnitt 4): der Kopf vor dem ersten Sachverhalt (ohne
     * Kennung) und jeder Sachverhalt mit derselben Kennung wie seine Abschnitte hier. Ohne Sachverhalt leer; dann gilt
     * das ganze Dokument als ein Teil.
     */
    public static List<DocumentPart> parts(AnalysisInput input) {
        String text = input.text();
        List<Matter> matters = matters(text);
        List<DocumentPart> parts = new ArrayList<>();
        if (!matters.isEmpty() && !text.substring(0, matters.get(0).start()).isBlank()) {
            parts.add(new DocumentPart("", "", 0, matters.get(0).start()));
        }
        for (int i = 0; i < matters.size(); i++) {
            Matter matter = matters.get(i);
            parts.add(new DocumentPart(matterId(i), matter.heading(), matter.start(), matter.end()));
        }
        return List.copyOf(parts);
    }

    private static String matterId(int index) {
        return "sachverhalt-" + (index + 1);
    }

    /** Ein Sachverhalt: Bereich im Text und seine Überschrift (oder die erste Zeile). */
    record Matter(int start, int end, String heading, int headingEnd) {
    }

    static List<Matter> matters(String text) {
        List<int[]> starts = new ArrayList<>(); // {start, headingEnd}
        boolean managerSeen = false;
        boolean objectSeen = false;
        int offset = 0;
        for (String line : text.split("\n", -1)) {
            int end = offset + line.length();
            boolean heading = isHeading(line);
            boolean manager = ACCOUNT_LABELS.get(0).pattern().matcher(line).find();
            boolean object = ACCOUNT_LABELS.get(1).pattern().matcher(line).find();
            boolean accountBoundary = !starts.isEmpty() && ((manager && managerSeen) || (object && objectSeen));
            if (heading || accountBoundary || (starts.isEmpty() && (manager || object))) {
                starts.add(new int[]{offset, heading ? end : offset});
                managerSeen = false;
                objectSeen = false;
            }
            managerSeen |= manager;
            objectSeen |= object;
            offset = end + 1;
        }
        List<Matter> matters = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            int start = starts.get(i)[0];
            int end = i + 1 < starts.size() ? starts.get(i + 1)[0] : text.length();
            int headingEnd = starts.get(i)[1];
            String heading = headingEnd > start ? text.substring(start, headingEnd).strip()
                    : "Sachverhalt " + (i + 1);
            matters.add(new Matter(start, end, heading, headingEnd));
        }
        return matters;
    }

    /** Kurze Zeile ohne Satzende mit Buch oder Abschnitt am Anfang; Dokumentbeschriftungen zählen nicht. */
    private static boolean isHeading(String line) {
        String stripped = line.strip();
        if (stripped.isEmpty() || stripped.length() > 100 || stripped.endsWith(".")
                || DOCUMENT_LINE.matcher(stripped).find()) {
            return false;
        }
        return BOOK_ADVANCE_WORD.matcher(stripped).find() || BOOK_CUSTODY_WORD.matcher(stripped).find()
                || SECTION_HEADING.matcher(stripped).find();
    }

    /** Buch, Abschnitt und Kategorie: Buch und Abschnitt aus der Überschrift, sonst Buch aus dem Text. */
    private static void headingFields(String text, Matter matter, List<ExtractedField> fields) {
        int headingStart = matter.start();
        int headingEnd = matter.headingEnd();
        boolean book = addBooks(text, headingStart, headingEnd, matter.heading(), fields);
        if (!book) {
            addBooks(text, headingEnd, matter.end(), matter.heading(), fields);
        }
        addMatches(SUBSECTION_WORD, text, headingStart, headingEnd, SUBSECTION, "Unterabschnitt", matter.heading(),
                fields, m -> m.group(1));
        // „\\bAbschnitt“ trifft „Unterabschnitt“ nicht: zwischen r und a liegt keine Wortgrenze
        addMatches(SECTION_WORD, text, headingStart, headingEnd, SECTION, "Abschnitt", matter.heading(), fields,
                m -> m.group(1));
        Matcher category = PASS_THROUGH.matcher(text).region(matter.start(), matter.end());
        while (category.find()) {
            if (!negated(lineAround(text, category.start()))) { // „keine durchlaufenden Gelder“ zählt nicht
                FieldMerge.add(fields, field(ACCOUNT_CATEGORY, "Kategorie", CATEGORY_PASS_THROUGH, category.group(),
                        source(matter.heading(), category.start(), category.end(), category.group())));
                break;
            }
        }
    }

    private static String lineAround(String text, int at) {
        int start = text.lastIndexOf('\n', at - 1) + 1;
        int end = text.indexOf('\n', at);
        return text.substring(start, end < 0 ? text.length() : end);
    }

    private static boolean addBooks(String text, int from, int to, String section, List<ExtractedField> fields) {
        int before = fields.size();
        addMatches(BOOK_ADVANCE_WORD, text, from, to, BOOK, "Buch", section, fields, m -> BOOK_ADVANCE);
        addMatches(BOOK_CUSTODY_WORD, text, from, to, BOOK, "Buch", section, fields, m -> BOOK_CUSTODY);
        return fields.size() > before;
    }

    private static void addMatches(Pattern pattern, String text, int from, int to, String key, String label,
                                   String section, List<ExtractedField> fields, Function<Matcher, String> value) {
        if (to <= from) {
            return;
        }
        Matcher m = pattern.matcher(text).region(from, to);
        while (m.find()) {
            FieldMerge.add(fields, field(key, label, value.apply(m), m.group(),
                    source(section, m.start(), m.end(), m.group())));
        }
    }

    // --- Fristsätze --------------------------------------------------------------------------------------------

    private List<ExtractedField> deadlineStatements(String text, Matter matter) {
        List<ExtractedField> fields = new ArrayList<>();
        int number = 0;
        for (int[] paragraph : paragraphs(text, matter.headingEnd(), matter.end())) {
            number = deadlineStatements(text, matter, paragraph[0], paragraph[1], number, fields);
        }
        return fields;
    }

    /**
     * Absätze im Bereich: getrennt an Leerzeilen und an Zeilen „Beschriftung: Wert“, die selbst übersprungen werden.
     * Ein Satz darf über Zeilenumbrüche laufen (PDF), nie aber über eine Kontoangabe hinweg.
     */
    private static List<int[]> paragraphs(String text, int from, int to) {
        List<int[]> paragraphs = new ArrayList<>();
        int start = -1;
        int last = -1;
        int offset = from;
        while (offset <= to) {
            int newline = text.indexOf('\n', offset);
            int end = newline < 0 || newline > to ? to : newline;
            String line = text.substring(offset, end);
            // „Hinweis: Die Frist …“ ist keine Kontoangabe, sondern ein Satz
            boolean separator = line.isBlank() || (LABEL_LINE.matcher(line).find() && !line.strip().endsWith(".")
                    && !isDeadlineSentence(line) && !EXTENSION.matcher(line).find());
            if (separator) {
                if (start >= 0) {
                    paragraphs.add(new int[]{start, last});
                    start = -1;
                }
            } else {
                if (start < 0) {
                    start = offset;
                }
                last = end;
            }
            offset = end + 1;
        }
        if (start >= 0) {
            paragraphs.add(new int[]{start, last});
        }
        return paragraphs;
    }

    private int deadlineStatements(String text, Matter matter, int bodyStart, int bodyEnd, int number,
                                   List<ExtractedField> fields) {
        String body = text.substring(bodyStart, bodyEnd);
        int cursor = 0;
        for (String sentence : sentences.sentences(body)) {
            int at = body.indexOf(sentence, cursor);
            if (at < 0) {
                continue;
            }
            cursor = at + sentence.length();
            int start = bodyStart + at;
            int end = start + sentence.length();
            boolean extension = EXTENSION.matcher(sentence).find();
            if (!extension && !isDeadlineSentence(sentence)) {
                continue;
            }
            String group = groupOf(++number);
            String flat = sentence.replaceAll("\\s+", " ");
            SourceReference source = source(matter.heading(), start, end, flat);
            List<String> notes = new ArrayList<>();
            modal(sentence).ifPresent(modal -> notes.add("Modalverb " + modal.name().toLowerCase(Locale.ROOT)));
            if (negated(sentence)) {
                notes.add("verneint");
            }
            String note = String.join(", ", notes);
            if (isDeadlineSentence(sentence)) {
                fields.add(field(DEADLINE_STATEMENT, "Fristaussage", flat, sentence, source).inGroup(group)
                        .withNote(note));
                Matcher endDate = DEADLINE_END_DATE.matcher(sentence);
                if (endDate.find()) {
                    String raw = endDate.group(1);
                    IdentifierNormalizer.germanDate(raw).ifPresent(date -> fields.add(field(DEADLINE_END,
                            "Fristende (im Text)", date.toString(), raw,
                            source(matter.heading(), start + endDate.start(1), start + endDate.end(1), raw))
                            .inGroup(group)));
                }
                reference(sentence).ifPresent(value -> fields.add(field(DEADLINE_REFERENCE, "Fristbezug", value,
                        sentence, source).inGroup(group)));
            }
            if (extension) {
                fields.add(extension(sentence, flat, source, note).inGroup(group));
            }
        }
        return number;
    }

    /** Fristsignal und Regelung (Modalverb oder „zu“-Infinitiv). */
    static boolean isDeadlineSentence(String sentence) {
        return DEADLINE_SIGNAL.matcher(sentence).find()
                && (modal(sentence).isPresent() || ZU_INFINITIVE.matcher(sentence).find());
    }

    private static Optional<GermanLexicon.Modal> modal(String sentence) {
        Matcher word = WORD.matcher(sentence);
        while (word.find()) {
            Optional<GermanLexicon.Modal> modal = GermanLexicon.modalOfForm(word.group());
            if (modal.isPresent()) {
                return modal;
            }
        }
        return Optional.empty();
    }

    private static boolean negated(String sentence) {
        Matcher word = WORD.matcher(sentence);
        while (word.find()) {
            if (GermanLexicon.isNegation(word.group()) || GermanLexicon.isNegatingDeterminer(word.group())) {
                return true;
            }
        }
        return false;
    }

    private static Optional<String> reference(String sentence) {
        boolean payment = PAYMENT_REFERENCE.matcher(sentence).find();
        boolean account = ACCOUNT_REFERENCE.matcher(sentence).find();
        if (payment == account) {
            return Optional.empty(); // keins oder beides: nicht eindeutig, die KI-Stufe soll es lesen
        }
        return Optional.of(payment ? REFERS_TO_PAYMENT : REFERS_TO_ACCOUNT);
    }

    /**
     * Verlängerung: genehmigt nur bei einem unverneinten Genehmigungswort, beantragt bei einem Antragswort. Ein
     * Antrag ist nie eine Genehmigung; ist beides oder nichts eindeutig, bleibt der Qualifier offen.
     */
    private static ExtractedField extension(String sentence, String flat, SourceReference source, String note) {
        boolean granted = ApprovalWording.namesGrant(sentence);
        boolean requested = ApprovalWording.namesRequest(sentence);
        boolean negated = negated(sentence);
        Qualifier qualifier = Qualifier.NONE;
        String extra = "";
        if (granted && negated) {
            extra = "Genehmigungswort verneint";
        } else if (granted && requested) {
            extra = "Antrag und Genehmigung im selben Satz, bitte prüfen";
        } else if (granted) {
            qualifier = Qualifier.GRANTED;
        } else if (requested) {
            qualifier = Qualifier.REQUESTED;
        }
        String combined = note.isEmpty() ? extra : extra.isEmpty() ? note : note + ", " + extra;
        return field(DEADLINE_EXTENSION, "Verlängerung", flat, sentence, source).qualified(qualifier)
                .withNote(combined);
    }

    // --- Felder ------------------------------------------------------------------------------------------------

    private static ExtractedField field(String key, String label, String value, String raw, SourceReference source) {
        return new ExtractedField(key, label, value, raw.strip(), FieldStatus.EXPLICIT, ExtractionOrigin.REGEX,
                PROCESSOR, true, source, Qualifier.NONE, "", "");
    }

    private static SourceReference source(String section, int start, int end, String excerpt) {
        return new SourceReference(section, 0, 0, start, end, excerpt);
    }

    private static Label digits(String key, String label, String regex) {
        return new Label(key, label, Pattern.compile("(?i)\\b" + regex + LABEL_END + DIGITS_VALUE),
                IdentifierNormalizer::digits);
    }

    private static Label line(String key, String label, String regex, Function<String, String> normalize) {
        return new Label(key, label, Pattern.compile("(?im)^\\s*" + regex + "\\b\\s*[:.]?\\s*" + LINE_VALUE),
                raw -> {
                    String value = normalize.apply(raw);
                    return value.isEmpty() ? Optional.empty() : Optional.of(value);
                });
    }

    /** Eine Beschriftung mit Wertgruppe 1 und Normalisierung; ohne normalisierten Wert kein Feld. */
    private record Label(String key, String label, Pattern pattern, Function<String, Optional<String>> normalize) {

        void find(String text, int from, int to, String section, String group, List<ExtractedField> fields) {
            if (to <= from) {
                return;
            }
            Matcher m = pattern.matcher(text).region(from, to);
            while (m.find()) {
                String raw = m.group(1);
                Optional<String> value = normalize.apply(raw);
                if (value.isPresent()) {
                    // Mehrere Bezüge sind kein Widerspruch: je Bezug eine eigene Gruppe.
                    String own = key.equals(REFERENCED_DOCUMENT)
                            ? "bezug-" + (fields.stream().filter(f -> f.key().equals(key)).count() + 1) : group;
                    FieldMerge.add(fields, field(key, label, value.get(), raw,
                            source(section, m.start(1), m.end(1), raw)).inGroup(own));
                }
            }
        }
    }
}
