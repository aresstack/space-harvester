package com.aresstack.spaceharvester.application.passages;

import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.query.PageTextIndex;
import com.aresstack.spaceharvester.application.sources.SearchSpaces;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Suchschnipsel aus semantischen Absätzen (#181, ADR 0020), wie im Enterprise AI Client, wo ein Schlagworttreffer
 * seine Passage als Auszug mitbringt ({@code RetrieveKnowledgeUseCase}): Der Volltextindex sucht unverändert; nur der
 * Auszug jedes Treffers ist statt des festen Fensters um die erste Fundstelle der Absatz, der die meisten Suchwörter
 * enthält (bei Gleichstand der frühere), höchstens {@value #MAX_CHARS} Zeichen um seine erste Fundstelle.
 *
 * <p>Ein zweiter Suchmechanismus entsteht nicht: Rangfolge, Treffer und Rechte bleiben die des Index und der
 * {@link com.aresstack.spaceharvester.application.query.KeywordSearch}, die Auszüge ohnehin nur bei {@code content}
 * ausliefert. Kein Netz, kein Modell: gelesen werden nur die gespeicherten Stellen des Prozessors
 * {@value PassageProcessor#ID}. Den alten Auszug behält ein Treffer, wenn es keine Absätze gibt, sie zu einem anderen
 * Text gehören ({@code textSha256}), kein Absatz ein Suchwort enthält oder das Lesen scheitert.
 */
public final class PassageSnippets implements PageTextIndex {

    /** Höchstlänge eines Auszugs aus einem Absatz: zwei Fenster des Volltextindex. */
    static final int MAX_CHARS = 480;

    private static final System.Logger LOG = System.getLogger(PassageSnippets.class.getName());
    /** Wörter wie der {@code StandardAnalyzer} des Index sie ungefähr zerlegt: Buchstaben und Ziffern, klein. */
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private final PageTextIndex index;
    private final Function<PageKey, List<Enrichment>> stored;

    /**
     * @param index  der Volltextindex
     * @param stored die gespeicherten Anreicherungen einer Seite, z. B. {@code EnrichmentStore::find}
     */
    public PassageSnippets(PageTextIndex index, Function<PageKey, List<Enrichment>> stored) {
        this.index = Objects.requireNonNull(index, "index");
        this.stored = Objects.requireNonNull(stored, "stored");
    }

    @Override
    public List<Match> search(String text, Set<String> sourceIds, SearchSpaces spaces, int limit) {
        List<Match> matches = index.search(text, sourceIds, spaces, limit);
        Set<String> terms = words(text);
        if (terms.isEmpty() || matches.isEmpty()) {
            return matches;
        }
        List<Match> refined = new ArrayList<>(matches.size());
        for (Match match : matches) {
            // Ein Anhangstreffer (#183) behält seinen Auszug aus dem Anhang; Absätze gibt es nur für Seiten.
            String snippet = match.inAttachment() || match.snippet() == null || match.snippet().isEmpty() ? null
                    : passageSnippet(new PageKey(match.sourceId(), match.pageId()), terms).orElse(null);
            refined.add(snippet == null ? match : match.withSnippet(snippet));
        }
        return refined;
    }

    /** Der Auszug aus dem passendsten Absatz der Seite, wenn es einen gibt. */
    Optional<String> passageSnippet(PageKey key, Set<String> terms) {
        try {
            Enrichment result = stored.apply(key).stream()
                    .filter(e -> PassageProcessor.ID.equals(e.processorId()) && e.status() == Enrichment.Status.OK)
                    .findFirst().orElse(null);
            if (result == null) {
                return Optional.empty();
            }
            PassagePayload.Parsed parsed = PassagePayload.parse(result.payloadJson());
            if (parsed.textSha256() == null || parsed.passages().isEmpty()) {
                return Optional.empty();
            }
            Optional<String> text = index.text(key);
            if (text.isEmpty() || !parsed.textSha256().equals(PassagePayload.textSha256(text.get()))) {
                return Optional.empty();
            }
            return best(text.get(), parsed.passages(), terms);
        } catch (RuntimeException e) {
            // Ohne Meldung: sie könnte Text der Seite enthalten. Der Treffer behält seinen Auszug.
            LOG.log(System.Logger.Level.DEBUG, "Absätze einer Seite in {0} nicht lesbar ({1})", key.sourceId(),
                    e.getClass().getName());
            return Optional.empty();
        }
    }

    static Optional<String> best(String text, List<PassageSegmentation.Passage> passages, Set<String> terms) {
        PassageSegmentation.Passage best = null;
        int bestScore = 0;
        int bestFirst = -1;
        for (PassageSegmentation.Passage passage : passages) {
            if (passage.end() > text.length()) {
                continue;
            }
            Set<String> found = new LinkedHashSet<>();
            int first = -1;
            Matcher word = WORD.matcher(text).region(passage.start(), passage.end());
            while (word.find()) {
                if (terms.contains(word.group().toLowerCase(Locale.ROOT))) {
                    found.add(word.group().toLowerCase(Locale.ROOT));
                    if (first < 0) {
                        first = word.start();
                    }
                }
            }
            if (found.size() > bestScore) {
                best = passage;
                bestScore = found.size();
                bestFirst = first;
            }
        }
        return best == null ? Optional.empty() : Optional.of(excerpt(text, best, bestFirst));
    }

    /** Der Absatz, oder ein Ausschnitt um die erste Fundstelle an Wortgrenzen, Leerraum zusammengezogen. */
    static String excerpt(String text, PassageSegmentation.Passage passage, int at) {
        int start = passage.start();
        int end = passage.end();
        if (end - start > MAX_CHARS) {
            start = Math.max(passage.start(), at - MAX_CHARS / 3);
            end = Math.min(passage.end(), start + MAX_CHARS);
            start = Math.max(passage.start(), Math.min(start, end - MAX_CHARS));
            while (start > passage.start() && !Character.isWhitespace(text.charAt(start - 1))) {
                start--;
            }
            while (end < passage.end() && !Character.isWhitespace(text.charAt(end))) {
                end++;
            }
        }
        String excerpt = SPACES.matcher(text.substring(start, end)).replaceAll(" ").strip();
        return (start > passage.start() ? "… " : "") + excerpt + (end < passage.end() ? " …" : "");
    }

    static Set<String> words(String text) {
        Set<String> words = new LinkedHashSet<>();
        Matcher word = WORD.matcher(text == null ? "" : text);
        while (word.find()) {
            words.add(word.group().toLowerCase(Locale.ROOT));
        }
        return words;
    }

    @Override
    public void put(Entry entry) {
        index.put(entry);
    }

    @Override
    public Optional<String> inputSha256(PageKey key) {
        return index.inputSha256(key);
    }

    @Override
    public Optional<String> text(PageKey key) {
        return index.text(key);
    }

    @Override
    public void putAttachment(AttachmentEntry entry) {
        index.putAttachment(entry);
    }

    @Override
    public Optional<String> attachmentInputSha256(PageKey page, String attachmentId) {
        return index.attachmentInputSha256(page, attachmentId);
    }

    @Override
    public void retainAttachments(PageKey page, Set<String> attachmentIds) {
        index.retainAttachments(page, attachmentIds);
    }

    @Override
    public void remove(PageKey key) {
        index.remove(key);
    }

    @Override
    public void removeSource(String sourceId) {
        index.removeSource(sourceId);
    }

    @Override
    public void flush() {
        index.flush();
    }
}
