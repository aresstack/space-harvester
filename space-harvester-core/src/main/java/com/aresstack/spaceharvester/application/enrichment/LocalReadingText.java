package com.aresstack.spaceharvester.application.enrichment;

import com.aresstack.spaceharvester.application.assets.DocumentExtractions;
import com.aresstack.spaceharvester.application.assets.DocumentKey;
import com.aresstack.spaceharvester.application.assets.StoredExtraction;
import com.aresstack.spaceharvester.application.snapshot.LocalOriginalReference;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Map section-local character positions to the bounded reader text without changing the original analysis input. */
public final class LocalReadingText {

    private final DocumentExtractions documents;

    public LocalReadingText(DocumentExtractions documents) {
        this.documents = Objects.requireNonNull(documents, "documents");
    }

    public Optional<Reading> read(PageSnapshot snapshot) {
        return LocalOriginalReference.read(snapshot.payload())
                .flatMap(reference -> Optional.ofNullable(reference.extractionKey()))
                .flatMap(documents::stored)
                .map(extraction -> project(snapshot, extraction))
                .filter(reading -> !reading.text().text().isBlank());
    }

    private Reading project(PageSnapshot snapshot, StoredExtraction extraction) {
        StringBuilder content = new StringBuilder();
        List<PageText.Section> headings = new ArrayList<>();
        List<Slice> slices = new ArrayList<>();
        int maximum = documents.maxTextChars();
        DocumentKey root = DocumentKey.localOriginal(snapshot.key());
        try (var sections = documents.documentSections(extraction)) {
            var iterator = sections.iterator();
            while (content.length() < maximum && iterator.hasNext()) {
                var section = iterator.next();
                PageText text = DocumentSectionText.project(section.section());
                if (text.text().isBlank()) {
                    continue;
                }
                int offset = content.length() + (content.isEmpty() ? 0 : 1);
                int length = Math.min(text.text().length(), maximum - offset);
                if (length <= 0) {
                    break;
                }
                if (length < text.text().length() && Character.isHighSurrogate(text.text().charAt(length - 1))) {
                    length--;
                }
                if (length == 0) {
                    break;
                }
                if (!content.isEmpty()) {
                    content.append('\n');
                }
                content.append(text.text(), 0, length);
                for (PageText.Section heading : text.sections()) {
                    if (heading.start() < length) {
                        headings.add(new PageText.Section(heading.heading(), heading.level(),
                                offset + heading.start(), offset + Math.min(length, heading.end())));
                    }
                }
                DocumentKey key = section.part().map(part -> root.child(part.id())).orElse(root);
                slices.add(new Slice(key, section.section().index(), text, offset, length));
            }
        }
        return new Reading(new PageText("local-original-text/v2", content.toString(), headings),
                List.copyOf(slices), extraction);
    }

    public record Reading(PageText text, List<Slice> slices, StoredExtraction extraction) {
    }

    public record Slice(DocumentKey document, int section, PageText text, int offset, int length) {
        public boolean contains(int start, int end) {
            return start >= 0 && end > start && end <= length;
        }
    }
}
