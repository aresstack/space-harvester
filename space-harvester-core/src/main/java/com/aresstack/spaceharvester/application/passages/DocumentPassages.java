package com.aresstack.spaceharvester.application.passages;

import com.aresstack.spaceharvester.application.assets.DocumentKey;
import com.aresstack.spaceharvester.application.enrichment.DocumentTexts;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Passage-IDs der Absätze eines Anhangs oder einer enthaltenen Datei (ADR 0026, AP9a, Punkt 9; Form von AT
 * bestätigt): {@code <sourceId>:<pageId>@<capture>~<DocumentKey#fileName()>#p<position>@<zerlegung>-<modell>}.
 * <ul>
 *   <li>{@code capture} ist der Hash des Lesetextes des ganzen Dokuments ({@link Enrichment#revision()}, 64
 *       Hexziffern ohne Präfix); ändert sich der Text irgendeines Abschnitts, ändert sich jede ID des Dokuments;</li>
 *   <li>{@code position} zählt die Absätze über alle Abschnitte in Reihenfolge, ab 0;</li>
 *   <li>Zerlegung und Modell wie bei Seiten; tragen die Abschnitte verschiedene (vor der Grenze für das
 *       Embedding-Modell je Dokument semantisch, danach nach Struktur), steht dort die Kurzform der verschiedenen
 *       Kürzel in Abschnittsfolge, so ändert eine andere Grenze die ID.</li>
 * </ul>
 * Solange die Absatz-Stände des Dokuments nicht alle dieselbe {@code revision} tragen (ein Lauf ist noch nicht
 * durch), gibt es keine ID. Die Zahl der Absätze je Abschnitt steht in den gespeicherten Ergebnissen; eine Position
 * aufzulösen liest den Lesetext nur des einen Abschnitts, nie die davor.
 */
public final class DocumentPassages {

    private DocumentPassages() {
    }

    /**
     * Die Passage-ID des Absatzes, der den Bereich {@code [start, end)} im Lesetext des Abschnitts {@code section}
     * ganz enthält, allein aus den gespeicherten Ergebnissen; {@code null}, wenn es keine gibt (siehe oben) oder der
     * Absatz-Stand des Abschnitts nicht aus demselben Text ({@code inputSha256}) stammt.
     *
     * @param results Ergebnisse von Dokumenten der Seite, etwa {@code findDocuments}; andere Dokumente zählen nicht
     */
    public static String idAt(DocumentKey document, List<Enrichment> results, int section, String inputSha256,
                              int start, int end) {
        Layout layout = layout(document, results).orElse(null);
        if (layout == null || inputSha256 == null) {
            return null;
        }
        for (Section candidate : layout.sections()) {
            if (candidate.result().section() != section || !inputSha256.equals(candidate.result().inputSha256())) {
                continue;
            }
            List<PassageSegmentation.Passage> passages = candidate.parsed().passages();
            for (int index = 0; index < passages.size(); index++) {
                if (passages.get(index).start() <= start && end <= passages.get(index).end()) {
                    return layout.id(document, candidate.base() + index);
                }
            }
        }
        return null;
    }

    /**
     * Löst die ID eines Absatzes eines Dokuments auf. Den Absatz gibt es nicht mehr ({@link PassageLookup.Match#OLDER_VERSION},
     * ohne Absätze), wenn das Dokument nicht mehr vorliegt (etwa eine Positionskennung nach dem Neulesen des
     * Behälters), sein Text sich geändert hat (andere Capture) oder die Position fehlt; {@link PassageLookup.Match#CHANGED}
     * bei gleichem Text mit anderer Zerlegung oder anderem Modell; {@link PassageLookup.Match#UNAVAILABLE}, solange kein
     * vollständiger Stand vorliegt oder der Lesetext des Abschnitts nicht mehr genau der des Ergebnisses ist. Gefunden:
     * der Absatz mit bis zu {@code neighbours} Nachbarn je Seite aus demselben Abschnitt.
     *
     * @param results Ergebnisse von Dokumenten der Seite ({@code findDocuments}); die Rechte der Seite prüft der Aufrufer
     */
    public static PassageLookup resolve(PassageId requested, List<Enrichment> results, DocumentTexts texts,
                                        int neighbours) {
        String fileName = requested.document().orElseThrow();
        Map<DocumentKey, List<Enrichment>> byDocument = new LinkedHashMap<>();
        for (Enrichment result : results) {
            if (PassageProcessor.ID.equals(result.processorId()) && result.ofDocument()
                    && result.document().page().sourceId().equals(requested.sourceId())
                    && result.document().page().pageId().equals(requested.pageId())
                    && fileName.equals(result.document().fileName())) {
                byDocument.computeIfAbsent(result.document(), key -> new ArrayList<>()).add(result);
            }
        }
        if (byDocument.isEmpty()) {
            return gone(requested);
        }
        DocumentKey document = byDocument.keySet().iterator().next();
        List<Enrichment> own = byDocument.get(document);
        Layout layout = layout(document, own).orElse(null);
        if (layout == null) {
            boolean failed = own.stream().anyMatch(result -> result.status() == Enrichment.Status.FAILED);
            return new PassageLookup(requested, PassageLookup.Match.UNAVAILABLE, empty(failed
                    ? PagePassages.State.FAILED : PagePassages.State.MISSING), List.of());
        }
        if (!layout.capture().equals(requested.capture())) {
            return gone(requested);
        }
        if (!layout.segmentation().equals(requested.segmentation())
                || !layout.model().equals(requested.embedding())) {
            return new PassageLookup(requested, PassageLookup.Match.CHANGED, empty(PagePassages.State.CURRENT),
                    List.of());
        }
        for (Section section : layout.sections()) {
            int index = requested.position() - section.base();
            if (index < 0 || index >= section.parsed().passages().size()) {
                continue;
            }
            Optional<PageText> text;
            try {
                // Mit der Extraktion des Standes: Die Capture gilt nur, solange der Stand zur jetzigen Extraktion gehört.
                String extraction = section.result().extraction();
                text = extraction == null ? Optional.empty()
                        : texts.text(document, section.result().section(), section.result().inputSha256(), extraction);
            } catch (RuntimeException e) {
                text = Optional.empty();
            }
            if (text.isEmpty()) {
                return new PassageLookup(requested, PassageLookup.Match.UNAVAILABLE,
                        empty(PagePassages.State.STALE), List.of());
            }
            String content = text.get().text();
            List<PassageSegmentation.Passage> passages = section.parsed().passages();
            List<PagePassages.Passage> found = new ArrayList<>();
            for (int i = Math.max(0, index - neighbours); i <= Math.min(passages.size() - 1, index + neighbours);
                 i++) {
                PassageSegmentation.Passage passage = passages.get(i);
                if (passage.end() > content.length()) {
                    if (i == index) {
                        return new PassageLookup(requested, PassageLookup.Match.UNAVAILABLE,
                                empty(PagePassages.State.FAILED), List.of());
                    }
                    continue;
                }
                String wording = content.substring(passage.start(), passage.end());
                found.add(new PagePassages.Passage(layout.id(document, section.base() + i), section.base() + i,
                        passage.start(), passage.end(), PagePassages.title(wording), wording, passage.section(),
                        passage.firstSentence(), passage.sentences(), passage.kind(), passage.boundary(),
                        passage.similarity()));
            }
            PassagePayload.Parsed parsed = section.parsed();
            PagePassages page = new PagePassages(PagePassages.State.CURRENT, section.result(), parsed.mode(),
                    parsed.reason(), parsed.model(), parsed.segmentation(), found, section.result().warnings());
            return new PassageLookup(requested, PassageLookup.Match.FOUND, page, found);
        }
        return gone(requested);
    }

    private static PassageLookup gone(PassageId requested) {
        return new PassageLookup(requested, PassageLookup.Match.OLDER_VERSION, empty(PagePassages.State.CURRENT),
                List.of());
    }

    private static PagePassages empty(PagePassages.State state) {
        return new PagePassages(state, null, null, null, null, null, List.of(), List.of());
    }

    /** Die Absatz-Stände eines Dokuments in Abschnittsfolge, wenn sie zusammen einen vollständigen Stand bilden. */
    private static Optional<Layout> layout(DocumentKey document, List<Enrichment> results) {
        Objects.requireNonNull(document, "document");
        List<Enrichment> own = results.stream()
                .filter(result -> PassageProcessor.ID.equals(result.processorId())
                        && document.equals(result.document()))
                .sorted(Comparator.comparingInt(Enrichment::section)).toList();
        if (own.isEmpty()) {
            return Optional.empty();
        }
        String revision = own.getFirst().revision();
        Set<String> segmentations = new LinkedHashSet<>();
        Set<String> models = new LinkedHashSet<>();
        List<Section> sections = new ArrayList<>(own.size());
        int base = 0;
        for (Enrichment result : own) {
            if (result.status() != Enrichment.Status.OK || revision == null || !revision.equals(result.revision())) {
                return Optional.empty();
            }
            PassagePayload.Parsed parsed;
            String segmentationTag;
            String modelTag;
            try {
                parsed = PassagePayload.parse(result.payloadJson());
                segmentationTag = PassageId.versionTag(parsed.segmentation());
                modelTag = PassageId.processorTag(result.processorVersion(), parsed.model());
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
            if (PagePassages.overlapping(parsed.passages())) {
                return Optional.empty();
            }
            segmentations.add(segmentationTag);
            models.add(modelTag);
            sections.add(new Section(result, parsed, base));
            base += parsed.passages().size();
        }
        try {
            return Optional.of(new Layout(PassageId.captureOf(revision), tag(segmentations), tag(models), sections));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Ein Kürzel, wenn alle Abschnitte es tragen, sonst die Kurzform der verschiedenen in Abschnittsfolge. */
    private static String tag(Set<String> tags) {
        return tags.size() == 1 ? tags.iterator().next() : PassageId.versionTag(String.join("\n", tags));
    }

    private record Layout(String capture, String segmentation, String model, List<Section> sections) {

        String id(DocumentKey document, int position) {
            try {
                return PassageId.of(document.page().sourceId(), document.page().pageId(), capture,
                        document.fileName(), position, segmentation, model).value();
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** Ein Abschnitt mit der Zahl der Absätze aller Abschnitte davor ({@code base}). */
    private record Section(Enrichment result, PassagePayload.Parsed parsed, int base) {
    }
}
