package com.aresstack.spaceharvester.application.enrichment;

import com.aresstack.spaceharvester.application.enrichment.AcronymRules.Finding;
import com.aresstack.spaceharvester.application.enrichment.AcronymRules.Occurrence;
import com.aresstack.spaceharvester.application.passages.SentenceSegmenter;
import com.aresstack.spaceharvester.source.EacAcronymRules;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Findet Akronyme und technische Kennungen mit dem {@code AcronymExtractor} des Enterprise AI Client (unverändert
 * übernommen, {@code enterprise-ai-sources}): Schreibregeln von {@code AcronymForms} (mindestens zwei Großbuchstaben,
 * die mindestens die Hälfte der Buchstaben stellen, also auch {@code GmbH} und {@code IoT}; Punkt-Akronyme wie
 * {@code U.S.A.}; höchstens zwei Ziffern, sonst eine Kennung; ein Plural-{@code s} zählt zum selben Akronym), die
 * Wortzerlegung von {@code TermScanner} (Code in Backticks und Blöcken, Web- und Mailadressen, Linkziele, Pfade und
 * Dateinamen bleiben außen vor) und ohne Akronyme in Versalzeilen. Ausschreibungen stehen wörtlich im selben Satz in
 * Klammern direkt vor oder nach dem Akronym, ausgerichtet nach {@code LongFormMatcher} (Schwartz und Hearst 2003);
 * jede unterschiedliche Ausschreibung zählt. Seit Version 4 (davor eigene Regeln, ADR 0019).
 *
 * <p>Ergebnis {@code {"acronyms":[{"acronym","normalized","count","expansion","expansions":[…],"section",
 * "charStart","occurrences":[{"start","end"}]}],"identifiers":[{"identifier","count","section","charStart",
 * "occurrences":[…]}]}}, je Liste aufsteigend nach Schreibweise, höchstens {@value #MAX_ACRONYMS} Einträge.
 * {@code acronym} ist die häufigste Schreibweise (wie {@code AcronymFinding.shortForm}), {@code normalized} die
 * Suchform (groß, ohne Punkte und Plural-{@code s}); {@code expansion} ist die erste Ausschreibung, {@code expansions}
 * nennt alle unterschiedlichen in der Reihenfolge des Textes. {@code charStart} ist die erste Fundstelle,
 * {@code occurrences} nennt jede Fundstelle in Textreihenfolge; {@code end} schließt ein Plural-{@code s} ein. Alle
 * Positionen sind Zeichenpositionen (UTF-16 wie {@link String#substring}) in {@link PageText#text()} derselben Eingabe
 * ({@code inputSha256} des Ergebnisses). Über alle Einträge zusammen werden nur die ersten {@value #MAX_OCCURRENCES}
 * Fundstellen im Text behalten und geschrieben; {@code count} zählt weiter alle Vorkommen, eine Warnung nennt die
 * Kürzung. Gelesen wird das Ergebnis mit {@link PageAcronyms}.
 */
public class AcronymProcessor implements EnrichmentProcessor {

    static final int MAX_ACRONYMS = 500;
    static final int MAX_OCCURRENCES = 5000;

    private static final Comparator<Occurrence> TEXT_ORDER = Comparator.comparingInt(Occurrence::start)
            .thenComparingInt(Occurrence::end);

    private final AcronymRules rules;

    /** Mit dem regelbasierten Satz-Splitter des EAC ({@code SentenceSegmenter.splitter()}), ohne Satzmodell. */
    public AcronymProcessor() {
        this(EacAcronymRules.splitter());
    }

    /**
     * @param sentences Satzgrenzen für die Ausschreibungen, produktiv wie {@code passages} das OpenNLP-Satzmodell mit
     *                  dem Splitter als Rückfall, wie im EAC
     */
    public AcronymProcessor(SentenceSegmenter sentences) {
        this(EacAcronymRules.with(sentences));
    }

    public AcronymProcessor(AcronymRules rules) {
        this.rules = Objects.requireNonNull(rules, "rules");
    }

    @Override
    public String id() {
        return "acronyms";
    }

    @Override
    public String version() {
        return "4";
    }

    /** Akronyme und Kennungen eines Textes nach den Regeln des EAC, mit den Standardschwellen. */
    public List<Finding> analyze(String text) {
        return rules.analyze(text);
    }

    @Override
    public EnrichmentOutput process(PageText text) {
        List<Finding> analysis = analyze(text.text());
        List<String> warnings = new ArrayList<>();
        Set<Occurrence> kept = firstOccurrences(analysis);
        StringBuilder json = new StringBuilder("{\"acronyms\":[");
        List<Finding> acronyms = sorted(analysis.stream().filter(Finding::acronym).toList());
        int written = 0;
        for (Finding finding : acronyms) {
            if (written == MAX_ACRONYMS) {
                warnings.add("Nur die ersten " + MAX_ACRONYMS + " von " + acronyms.size() + " Akronymen");
                break;
            }
            List<String> longForms = finding.longForms();
            json.append(written++ == 0 ? "" : ",")
                    .append("{\"acronym\":").append(JsonText.quote(finding.shortForm()))
                    .append(",\"normalized\":").append(JsonText.quote(finding.normalized()))
                    .append(",\"count\":").append(finding.occurrences().size())
                    .append(",\"expansion\":")
                    .append(longForms.isEmpty() ? "null" : JsonText.quote(longForms.get(0)))
                    .append(",\"expansions\":[");
            for (int i = 0; i < longForms.size(); i++) {
                json.append(i == 0 ? "" : ",").append(JsonText.quote(longForms.get(i)));
            }
            json.append("],");
            location(json, text, finding, kept);
            json.append('}');
        }
        json.append("],\"identifiers\":[");
        List<Finding> identifiers = sorted(analysis.stream().filter(finding -> !finding.acronym()).toList());
        written = 0;
        for (Finding finding : identifiers) {
            if (written == MAX_ACRONYMS) {
                warnings.add("Nur die ersten " + MAX_ACRONYMS + " von " + identifiers.size() + " Kennungen");
                break;
            }
            json.append(written++ == 0 ? "" : ",")
                    .append("{\"identifier\":").append(JsonText.quote(finding.shortForm()))
                    .append(",\"count\":").append(finding.occurrences().size()).append(',');
            location(json, text, finding, kept);
            json.append('}');
        }
        if (kept.size() == MAX_OCCURRENCES && analysis.stream()
                .mapToInt(finding -> finding.occurrences().size()).sum() > MAX_OCCURRENCES) {
            warnings.add("Fundstellen nur für die ersten " + MAX_OCCURRENCES + " Vorkommen im Text");
        }
        return new EnrichmentOutput(json.append("]}").toString(), warnings);
    }

    /** Die ersten {@value #MAX_OCCURRENCES} Fundstellen im Text über alle Akronyme und Kennungen. */
    private static Set<Occurrence> firstOccurrences(List<Finding> analysis) {
        List<Occurrence> all = new ArrayList<>();
        analysis.forEach(finding -> all.addAll(finding.occurrences()));
        all.sort(TEXT_ORDER);
        return new HashSet<>(all.subList(0, Math.min(all.size(), MAX_OCCURRENCES)));
    }

    /** {@code "section","charStart","occurrences"}; Fundstellen nur, soweit sie zu {@code kept} gehören. */
    private static void location(StringBuilder json, PageText text, Finding finding, Set<Occurrence> kept) {
        List<Occurrence> occurrences = finding.occurrences();
        int first = occurrences.get(0).start();
        PageText.Section section = text.sectionAt(first);
        json.append("\"section\":").append(section == null ? "null" : JsonText.quote(section.heading()))
                .append(",\"charStart\":").append(first).append(",\"occurrences\":[");
        int written = 0;
        for (Occurrence occurrence : occurrences) {
            if (kept.contains(occurrence)) {
                json.append(written++ == 0 ? "" : ",").append("{\"start\":").append(occurrence.start())
                        .append(",\"end\":").append(occurrence.end()).append('}');
            }
        }
        json.append(']');
    }

    /** Nach Schreibweise, bei gleicher nach Suchform. */
    private static List<Finding> sorted(List<Finding> findings) {
        List<Finding> sorted = new ArrayList<>(findings);
        sorted.sort(Comparator.comparing(Finding::shortForm).thenComparing(Finding::normalized));
        return sorted;
    }

    /**
     * Jedes Vorkommen eines Akronyms oder einer Kennung in Textreihenfolge, nach denselben Regeln wie {@link #process};
     * für den Begriffsindex ({@code terms}, #119), der alle Fundstellen ohne Kürzung braucht.
     */
    public List<Mention> mentions(String text) {
        List<Mention> mentions = new ArrayList<>();
        for (Finding finding : analyze(text)) {
            String expansion = finding.longForms().isEmpty() ? null : finding.longForms().get(0);
            for (Occurrence occurrence : finding.occurrences()) {
                mentions.add(new Mention(occurrence.term(), finding.acronym(), expansion, occurrence.start(),
                        occurrence.end()));
            }
        }
        mentions.sort(Comparator.comparingInt(Mention::start).thenComparingInt(Mention::end));
        return mentions;
    }

    /**
     * Ein Vorkommen eines Akronyms oder einer Kennung.
     *
     * @param term      Schreibweise an dieser Stelle, bei Akronymen ohne Plural-{@code s}
     * @param acronym   {@code true} für ein Akronym, {@code false} für eine technische Kennung
     * @param expansion erste Ausschreibung des Akronyms auf der Seite oder {@code null}
     * @param start     erste Zeichenposition
     * @param end       Position nach dem Wort, einschließlich eines Plural-{@code s}
     */
    public record Mention(String term, boolean acronym, String expansion, int start, int end) {
    }
}
