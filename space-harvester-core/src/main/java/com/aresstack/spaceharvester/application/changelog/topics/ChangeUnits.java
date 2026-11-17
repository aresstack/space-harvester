package com.aresstack.spaceharvester.application.changelog.topics;

import com.aresstack.spaceharvester.application.changelog.ChangeKind;
import com.aresstack.spaceharvester.application.changelog.ChangeReport;
import com.aresstack.spaceharvester.application.changelog.ContentChange;
import com.aresstack.spaceharvester.application.changelog.ContentDiff;
import com.aresstack.spaceharvester.application.changelog.ParagraphChange;
import com.aresstack.spaceharvester.application.changelog.ReportedPage;
import com.aresstack.spaceharvester.application.changelog.topics.ChangeUnit.Kind;
import com.aresstack.spaceharvester.application.changelog.topics.ChangeUnit.Nature;
import com.aresstack.spaceharvester.application.changelog.topics.ChangeUnit.Occurrence;
import com.aresstack.spaceharvester.application.changelog.topics.ChangeUnit.Role;
import com.aresstack.spaceharvester.application.passages.SentenceSegmenter;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Bildet aus dem Bericht eines Updates die Änderungseinheiten (ADR 0030, Nachtrag Abschnitte 3 und 4, Satzebene). Eine
 * reine Funktion: kein Aufrufer, keine Ablage, kein KI-Dienst; der Bericht ist schon nach Leserechten gefiltert und
 * gegebenenfalls auf einen Space beschränkt.
 *
 * <ul>
 *   <li>Je geändertem Absatz eine Einheit mit den tatsächlich geänderten Sätzen (Satzabgleich nach Wortlaut).</li>
 *   <li>Text, der an einer Stelle entfällt und an einer anderen nahezu gleich hinzukommt, ist eine Einheit
 *       {@link Nature#RELOCATION} mit Herkunft und Ziel, keine inhaltliche Änderung.</li>
 *   <li>Dieselbe Änderung auf mehreren Seiten ist eine Einheit mit mehreren Fundstellen.</li>
 *   <li>Titel, Ort, Labels, neue und entfernte Seiten sind Einheiten ohne Absatztext.</li>
 * </ul>
 * Zeit und Bearbeiter gruppieren nie.
 */
public final class ChangeUnits {

    /** Verfahren und Version; ändern sich die Regeln, steigt sie. */
    public static final String METHOD = "change-units/v1";
    /** Ab dieser Wortlaut-Ähnlichkeit gilt entfallener und hinzugekommener Text als gewandert. */
    static final double RELOCATION_SIMILARITY = 0.9;
    /** Höchstens so viele abweichende Wörter, jedes nur anders geschrieben: eine Korrektur. */
    static final int CORRECTION_WORDS = 4;
    /** Unter dieser Ähnlichkeit ist eine Änderung eine große Überarbeitung. */
    static final double MAJOR_SIMILARITY = 0.5;
    /** Höchstens so viele Kandidaten je entfallenem Absatz, damit große Updates linear bleiben. */
    static final int RELOCATION_CANDIDATES = 64;

    private final SentenceSegmenter sentences;

    public ChangeUnits(SentenceSegmenter sentences) {
        this.sentences = Objects.requireNonNull(sentences, "sentences");
    }

    /**
     * Das Ergebnis.
     *
     * @param units        die Einheiten nach Space, Pfad, Seite und Position der ersten Fundstelle
     * @param notEvaluated geänderte Seiten ohne auswertbaren Unterschied (Inhalt fehlt, vorheriger Stand fehlt)
     */
    public record Result(List<ChangeUnit> units, int notEvaluated) {

        public Result {
            units = List.copyOf(units);
        }
    }

    /**
     * @param report   Bericht eines Updates mit Unterschieden
     * @param released ob eine Seite an einen KI-Dienst darf ({@code AiRelease.mayProcess})
     */
    public Result build(ChangeReport report, Predicate<PageKey> released) {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(released, "released");
        List<Draft> drafts = new ArrayList<>();
        int notEvaluated = 0;
        for (ReportedPage page : report.pages()) {
            for (ChangeKind kind : page.kinds()) {
                switch (kind) {
                    case NEW -> drafts.add(event(page, Kind.PAGE_NEW, Nature.NEW_PAGE));
                    case REMOVED, WITHHELD, MOVED_OUT -> drafts.add(event(page, Kind.PAGE_REMOVED,
                            Nature.REMOVED_PAGE));
                    case RENAMED -> drafts.add(event(page, Kind.RENAMED, Nature.STRUCTURE));
                    case MOVED -> drafts.add(event(page, Kind.MOVED, Nature.STRUCTURE));
                    case LABELS_CHANGED -> drafts.add(event(page, Kind.LABELS, Nature.STRUCTURE));
                    case CONTENT_CHANGED -> {
                        if (!paragraphs(page, drafts)) {
                            notEvaluated++;
                        }
                    }
                    case LOADED, RELOADED -> {
                        // Nur der Bestand hat sich geändert, nicht die Quelle.
                    }
                }
            }
        }
        List<Draft> merged = duplicates(relocations(drafts));
        merged.sort(ORDER);
        List<ChangeUnit> units = new ArrayList<>();
        for (Draft draft : merged) {
            units.add(draft.unit(released));
        }
        return new Result(units, notEvaluated);
    }

    /** Je geändertem Absatz ein Entwurf; {@code false}, wenn der Unterschied nicht auswertbar ist. */
    private boolean paragraphs(ReportedPage page, List<Draft> drafts) {
        ContentDiff diff = page.diff();
        if (diff == null || diff.status() != ContentDiff.Status.AVAILABLE
                && diff.status() != ContentDiff.Status.CONTENT_PENDING || diff.steps().isEmpty()) {
            return false;
        }
        for (int step = 0; step < diff.steps().size(); step++) {
            ContentChange change = diff.steps().get(step);
            for (int position = 0; position < change.paragraphs().size(); position++) {
                ParagraphChange paragraph = change.paragraphs().get(position);
                Occurrence occurrence = occurrence(page, change, step, position, Role.CHANGED);
                drafts.add(paragraph(paragraph, occurrence));
            }
        }
        return true;
    }

    private Draft paragraph(ParagraphChange paragraph, Occurrence occurrence) {
        return switch (paragraph.kind()) {
            case ADDED -> new Draft(Kind.PARAGRAPH_ADDED, Nature.NEW_SECTION, null, paragraph.after(), List.of(),
                    sentences(paragraph.after()), occurrence);
            case REMOVED -> new Draft(Kind.PARAGRAPH_REMOVED, Nature.REMOVED_SECTION, paragraph.before(), null,
                    sentences(paragraph.before()), List.of(), occurrence);
            case CHANGED -> changed(paragraph.before(), paragraph.after(), occurrence);
        };
    }

    /** Satzabgleich: gleiche Sätze (nach Wortlaut) fallen heraus, übrig bleiben die geänderten. */
    private Draft changed(String before, String after, Occurrence occurrence) {
        List<String> old = sentences(before);
        List<String> now = sentences(after);
        Map<String, Integer> remaining = new HashMap<>();
        for (String sentence : now) {
            remaining.merge(key(sentence), 1, Integer::sum);
        }
        List<String> changedBefore = new ArrayList<>();
        for (String sentence : old) {
            String key = key(sentence);
            Integer count = remaining.get(key);
            if (count != null && count > 0) {
                remaining.put(key, count - 1);
            } else {
                changedBefore.add(sentence);
            }
        }
        Map<String, Integer> kept = new HashMap<>();
        for (String sentence : old) {
            kept.merge(key(sentence), 1, Integer::sum);
        }
        List<String> changedAfter = new ArrayList<>();
        for (String sentence : now) {
            String key = key(sentence);
            Integer count = kept.get(key);
            if (count != null && count > 0) {
                kept.put(key, count - 1);
            } else {
                changedAfter.add(sentence);
            }
        }
        return new Draft(Kind.PARAGRAPH_CHANGED, nature(changedBefore, changedAfter), before, after, changedBefore,
                changedAfter, occurrence);
    }

    /** Art einer geänderten Stelle aus den geänderten Sätzen. */
    static Nature nature(List<String> changedBefore, List<String> changedAfter) {
        List<String> old = TextComparison.words(String.join(" ", changedBefore));
        List<String> now = TextComparison.words(String.join(" ", changedAfter));
        List<String> differing = TextComparison.differing(old, now);
        if (differing.isEmpty()) {
            return Nature.CORRECTION; // nur Schreibweise, Leerraum oder Satzzeichen
        }
        if (differing.stream().allMatch(TextComparison::number)) {
            return Nature.NUMBER_OR_DATE;
        }
        double similarity = TextComparison.similarity(old, now);
        if (differing.size() <= CORRECTION_WORDS && TextComparison.spellingOnly(old, now)) {
            return Nature.CORRECTION; // jedes abweichende Wort hat ein nahezu gleich geschriebenes Gegenstück
        }
        return similarity < MAJOR_SIMILARITY ? Nature.MAJOR_REVISION : Nature.EDIT;
    }

    /**
     * Entfallener und nahezu gleich hinzugekommener Text wird eine Einheit {@link Nature#RELOCATION}. Kandidaten nur
     * mit gleichem Anfang oder Ende (drei Wörter), damit große Updates nicht jedes Paar vergleichen.
     */
    private static List<Draft> relocations(List<Draft> drafts) {
        Map<String, List<Draft>> addedByEdge = new HashMap<>();
        for (Draft draft : drafts) {
            if (draft.kind == Kind.PARAGRAPH_ADDED) {
                for (String edge : edges(draft.words())) {
                    addedByEdge.computeIfAbsent(edge, ignored -> new ArrayList<>()).add(draft);
                }
            }
        }
        Set<Draft> used = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        Map<Draft, Draft> pairs = new java.util.IdentityHashMap<>();
        for (Draft removed : drafts) {
            if (removed.kind != Kind.PARAGRAPH_REMOVED) {
                continue;
            }
            Draft best = null;
            double bestSimilarity = RELOCATION_SIMILARITY;
            Set<Draft> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (String edge : edges(removed.words())) {
                for (Draft added : addedByEdge.getOrDefault(edge, List.of())) {
                    if (seen.size() >= RELOCATION_CANDIDATES) {
                        break;
                    }
                    if (used.contains(added) || !seen.add(added)) {
                        continue;
                    }
                    double similarity = TextComparison.similarity(removed.words(), added.words());
                    if (similarity > bestSimilarity || best == null && similarity >= bestSimilarity) {
                        best = added;
                        bestSimilarity = similarity;
                    }
                }
            }
            if (best != null) {
                used.add(best);
                pairs.put(removed, best);
            }
        }
        List<Draft> result = new ArrayList<>();
        for (Draft draft : drafts) {
            if (used.contains(draft)) {
                continue;
            }
            Draft target = pairs.get(draft);
            result.add(target == null ? draft : new Draft(Kind.PARAGRAPH_MOVED, Nature.RELOCATION, draft.before,
                    target.after, List.of(), List.of(), List.of(draft.occurrences.getFirst().withRole(Role.FROM),
                    target.occurrences.getFirst().withRole(Role.TO))));
        }
        return result;
    }

    private static List<String> edges(List<String> words) {
        if (words.size() < 3) {
            return words.isEmpty() ? List.of() : List.of("=" + String.join(" ", words));
        }
        return List.of("<" + String.join(" ", words.subList(0, 3)),
                ">" + String.join(" ", words.subList(words.size() - 3, words.size())));
    }

    /** Dieselbe Änderung (Art und geänderte Sätze nach Wortlaut) auf mehreren Seiten wird eine Einheit. */
    private static List<Draft> duplicates(List<Draft> drafts) {
        Map<String, Draft> byKey = new LinkedHashMap<>();
        List<Draft> result = new ArrayList<>();
        for (Draft draft : drafts) {
            String key = draft.duplicateKey();
            if (key == null) {
                result.add(draft);
                continue;
            }
            Draft known = byKey.get(key);
            if (known == null) {
                byKey.put(key, draft);
                result.add(draft);
            } else {
                known.occurrences.add(draft.occurrences.getFirst());
            }
        }
        return result;
    }

    private List<String> sentences(String paragraph) {
        if (paragraph == null || paragraph.isBlank()) {
            return List.of();
        }
        List<String> found = sentences.sentences(paragraph).stream().map(String::strip)
                .filter(sentence -> !sentence.isEmpty()).toList();
        return found.isEmpty() ? List.of(paragraph.strip()) : found;
    }

    private static String key(String sentence) {
        return String.join(" ", TextComparison.words(sentence));
    }

    private static Draft event(ReportedPage page, Kind kind, Nature nature) {
        return new Draft(kind, nature, null, null, List.of(), List.of(),
                new Occurrence(new PageKey(page.sourceId(), page.pageId()), page.spaceKey(), page.title(), page.path(),
                        page.url(), page.newParentId(), List.of(), page.at(), Role.CHANGED, -1, -1));
    }

    private static Occurrence occurrence(ReportedPage page, ContentChange change, int step, int position, Role role) {
        return new Occurrence(new PageKey(page.sourceId(), page.pageId()), page.spaceKey(), page.title(), page.path(),
                page.url(), page.newParentId(), List.of(), change.at(), role, step, position);
    }

    private static final Comparator<Draft> ORDER = Comparator
            .comparing((Draft draft) -> nullToEmpty(draft.occurrences.getFirst().spaceKey()))
            .thenComparing(draft -> String.join("\u0000", draft.occurrences.getFirst().path()))
            .thenComparing(draft -> draft.occurrences.getFirst().page().pageId())
            .thenComparingInt(draft -> draft.occurrences.getFirst().step())
            .thenComparingInt(draft -> draft.occurrences.getFirst().position())
            .thenComparing(draft -> draft.kind);

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Eine Einheit im Entstehen; Fundstellen kommen beim Zusammenlegen dazu. */
    private static final class Draft {

        private final Kind kind;
        private final Nature nature;
        private final String before;
        private final String after;
        private final List<String> changedBefore;
        private final List<String> changedAfter;
        private final List<Occurrence> occurrences;
        private List<String> words;

        private Draft(Kind kind, Nature nature, String before, String after, List<String> changedBefore,
                      List<String> changedAfter, Occurrence occurrence) {
            this(kind, nature, before, after, changedBefore, changedAfter, List.of(occurrence));
        }

        private Draft(Kind kind, Nature nature, String before, String after, List<String> changedBefore,
                      List<String> changedAfter, List<Occurrence> occurrences) {
            this.kind = kind;
            this.nature = nature;
            this.before = before;
            this.after = after;
            this.changedBefore = changedBefore;
            this.changedAfter = changedAfter;
            this.occurrences = new ArrayList<>(occurrences);
        }

        /** Wörter des ganzen Absatzes (für das Wandern). */
        private List<String> words() {
            if (words == null) {
                words = TextComparison.words(after != null ? after : before);
            }
            return words;
        }

        /** Schlüssel für gleiche Änderungen auf mehreren Seiten; {@code null} für Seitenereignisse. */
        private String duplicateKey() {
            if (before == null && after == null || kind == Kind.PARAGRAPH_MOVED) {
                return null;
            }
            List<String> old = changedBefore.isEmpty() && kind != Kind.PARAGRAPH_CHANGED ? sentencesOf(before)
                    : changedBefore;
            List<String> now = changedAfter.isEmpty() && kind != Kind.PARAGRAPH_CHANGED ? sentencesOf(after)
                    : changedAfter;
            String key = kind + "\u0000" + joined(old) + "\u0000" + joined(now);
            return old.isEmpty() && now.isEmpty() ? null : key;
        }

        private static List<String> sentencesOf(String text) {
            return text == null ? List.of() : List.of(text);
        }

        private static String joined(List<String> sentences) {
            List<String> keys = new ArrayList<>();
            for (String sentence : sentences) {
                keys.add(key(sentence));
            }
            return String.join("\u0001", keys);
        }

        private ChangeUnit unit(Predicate<PageKey> released) {
            occurrences.sort(Comparator.comparing((Occurrence occurrence) -> occurrence.role() == Role.TO)
                    .thenComparing(occurrence -> nullToEmpty(occurrence.spaceKey()))
                    .thenComparing(occurrence -> String.join("\u0000", occurrence.path()))
                    .thenComparing(occurrence -> occurrence.page().pageId())
                    .thenComparingInt(Occurrence::step)
                    .thenComparingInt(Occurrence::position));
            Set<String> terms = new TreeSet<>();
            int size = 0;
            if (before == null && after == null) {
                terms.addAll(TextComparison.terms(occurrences.getFirst().title()));
            } else {
                List<String> old = changedBefore.isEmpty() && changedAfter.isEmpty() ? sentencesOf(before)
                        : changedBefore;
                List<String> now = changedBefore.isEmpty() && changedAfter.isEmpty() ? sentencesOf(after)
                        : changedAfter;
                for (String sentence : old) {
                    terms.addAll(TextComparison.terms(sentence));
                    size += sentence.length();
                }
                for (String sentence : now) {
                    terms.addAll(TextComparison.terms(sentence));
                    size += sentence.length();
                }
            }
            boolean aiReleased = occurrences.stream().allMatch(occurrence -> released.test(occurrence.page()));
            return new ChangeUnit(id(occurrences.getFirst(), kind), kind, nature, before, after, changedBefore,
                    changedAfter, occurrences, terms, size, aiReleased);
        }
    }

    /** Stabile Kennung aus der ersten Fundstelle, nie Inhalt im Klartext. */
    static String id(Occurrence occurrence, Kind kind) {
        String input = occurrence.page().sourceId() + "\n" + occurrence.page().pageId() + "\n" + occurrence.step()
                + "\n" + occurrence.position() + "\n" + kind.name();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return "u-" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
