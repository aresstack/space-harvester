package com.aresstack.spaceharvester.persistence.lucene;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.query.PageTextIndex;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import com.aresstack.spaceharvester.application.sources.SearchSpaces;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LucenePageTextIndexTest {

    @TempDir
    Path dir;

    @Test
    void ranksByBm25WithTheTitleWeightedAndAWindowAroundTheFirstFinding() {
        try (LucenePageTextIndex index = LucenePageTextIndex.open(dir)) {
            index.put(entry("wiki", "1", "DEV", "Betrieb", "Einleitung. " + "Füllwort ".repeat(80)
                    + "Der Nachtlauf startet um zwei Uhr. " + "Füllwort ".repeat(80)));
            index.put(entry("wiki", "2", "DEV", "Nachtlauf", "Steht im Titel und im Text: Nachtlauf."));
            index.put(entry("wiki", "3", "OPS", "Urlaub", "Nichts dazu."));

            var matches = index.search("NACHTLAUF", Set.of("wiki"), SearchSpaces.all(), 10);

            assertThat(matches).extracting(PageTextIndex.Match::pageId).containsExactly("2", "1");
            String window = matches.get(1).snippet();
            assertThat(window).contains("Der Nachtlauf startet um zwei Uhr.").startsWith("… ").endsWith(" …");
            assertThat(window.length()).isLessThanOrEqualTo(LucenePageTextIndex.WINDOW + LucenePageTextIndex.WINDOW);
            assertThat(window).doesNotContain("  ");
        }
    }

    @Test
    void theWindowIsAroundAWholeWordNotAPartOfOne() {
        try (LucenePageTextIndex index = LucenePageTextIndex.open(dir)) {
            index.put(entry("wiki", "1", "DEV", "A", "Die party beginnt. " + "Füllwort ".repeat(80)
                    + "Moderne art im Foyer."));

            String window = index.search("art", Set.of("wiki"), SearchSpaces.all(), 10).getFirst().snippet();

            assertThat(window).contains("Moderne art im Foyer.").doesNotContain("party");
        }
    }

    @Test
    void filtersBySourceAndSpacesAndNeedsAtLeastOneWord() {
        try (LucenePageTextIndex index = LucenePageTextIndex.open(dir)) {
            index.put(entry("wiki", "1", "DEV", "A", "Nachtlauf Prüfung"));
            index.put(entry("wiki", "2", "OPS", "B", "Nachtlauf"));
            index.put(entry("docs", "3", "docs", "C", "Nachtlauf"));

            assertThat(index.search("nachtlauf", Set.of("wiki"), SearchSpaces.all(), 10))
                    .extracting(PageTextIndex.Match::pageId).containsExactlyInAnyOrder("1", "2");
            assertThat(index.search("nachtlauf", Set.of("wiki", "docs"), new SearchSpaces(Set.of("OPS"), Set.of()),
                    10)).extracting(PageTextIndex.Match::pageId).containsExactly("2");
            assertThat(index.search("nachtlauf", Set.of("wiki"), new SearchSpaces(Set.of(), Set.of("OPS")), 10))
                    .extracting(PageTextIndex.Match::pageId).containsExactly("1");
            assertThat(index.search("Prüfung oder Urlaub", Set.of("wiki"), SearchSpaces.all(), 10))
                    .extracting(PageTextIndex.Match::pageId).containsExactly("1");
            assertThat(index.search(" ,; ", Set.of("wiki"), SearchSpaces.all(), 10)).isEmpty();
            assertThat(index.search("nachtlauf", Set.of(), SearchSpaces.all(), 10)).isEmpty();
        }
    }

    @Test
    void aPageSetFiltersBeforeTheRankingAndKeepsTheAttachmentsOfItsPages() {
        try (LucenePageTextIndex index = LucenePageTextIndex.open(dir)) {
            index.put(entry("wiki", "1", "DEV", "Nachtlauf", "Nachtlauf Nachtlauf"));
            index.put(entry("wiki", "2", "DEV", "B", "Nachtlauf"));
            index.put(entry("wiki", "3", "DEV", "C", "nichts"));
            index.put(entry("docs", "2", "docs", "D", "Nachtlauf"));
            index.putAttachment(new PageTextIndex.AttachmentEntry(new PageKey("wiki", "3"), "DEV", "a1", "plan.txt",
                    "Nachtlauf im Anhang", "s1"));
            PageSet subtree = PageSet.of(Map.of("wiki", java.util.List.of("2", "3")));

            // Mit Grenze 1 fände die Suche ohne Filter nur die beste Seite 1; der Filter wirkt vorher.
            assertThat(index.search("nachtlauf", Set.of("wiki", "docs"), SearchSpaces.all(), subtree, 1))
                    .singleElement().satisfies(match -> {
                        assertThat(match.sourceId()).isEqualTo("wiki");
                        assertThat(match.pageId()).isIn("2", "3");
                    });
            assertThat(index.search("nachtlauf", Set.of("wiki", "docs"), SearchSpaces.all(), subtree, 10))
                    .extracting(match -> match.sourceId() + "/" + match.pageId() + "/" + match.inAttachment())
                    .containsExactlyInAnyOrder("wiki/2/false", "wiki/3/true");
            assertThat(index.search("nachtlauf", Set.of("wiki"), SearchSpaces.all(), PageSet.EMPTY, 10)).isEmpty();
            assertThat(index.search("nachtlauf", Set.of("wiki", "docs"), SearchSpaces.all(), null, 10))
                    .hasSize(4);
        }
    }

    @Test
    void replacesRemovesAndSurvivesAReopen() {
        try (LucenePageTextIndex index = LucenePageTextIndex.open(dir)) {
            index.put(entry("wiki", "1", "DEV", "A", "alt"));
            index.put(entry("wiki", "1", "DEV", "A", "neu"));
            index.put(entry("wiki", "2", "DEV", "B", "neu"));
            index.put(entry("docs", "3", "docs", "C", "neu"));
            index.remove(new PageKey("wiki", "2"));
            index.removeSource("docs");

            assertThat(index.search("alt", Set.of("wiki"), SearchSpaces.all(), 10)).isEmpty();
            assertThat(index.inputSha256(new PageKey("wiki", "1"))).contains("sha256:neu");
        }
        try (LucenePageTextIndex index = LucenePageTextIndex.open(dir)) {
            assertThat(index.search("neu", Set.of("wiki", "docs"), SearchSpaces.all(), 10))
                    .extracting(PageTextIndex.Match::pageId).containsExactly("1");
            assertThat(index.inputSha256(new PageKey("wiki", "2"))).isEmpty();
        }
    }

    @Test
    void anIndexInAnotherSchemaStartsEmpty() throws Exception {
        try (FSDirectory directory = FSDirectory.open(Files.createDirectories(dir));
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            writer.setLiveCommitData(Map.of(PageCacheSchema.SCHEMA_KEY, "page-text-index/v0").entrySet());
            writer.commit();
        }
        try (LucenePageTextIndex index = LucenePageTextIndex.open(dir)) {
            index.put(entry("wiki", "1", "DEV", "A", "neu"));

            assertThat(index.search("neu", Set.of("wiki"), SearchSpaces.all(), 10)).hasSize(1);
        }
    }

    private static PageTextIndex.Entry entry(String sourceId, String pageId, String spaceKey, String title,
                                             String text) {
        return new PageTextIndex.Entry(new PageKey(sourceId, pageId), spaceKey, title, text,
                "sha256:" + text.split(" ")[0]);
    }

    @Test
    void anAttachmentIsReplacedAsAWholeAcrossItsDocumentsAndSections() {
        PageKey page = new PageKey("wiki", "1");
        try (LucenePageTextIndex index = LucenePageTextIndex.open(dir)) {
            index.put(new PageTextIndex.Entry(page, "DEV", "Seite", "Seitentext", "sha256:p"));
            index.replaceAttachment(page, "a1", java.util.List.of(
                    part(page, "a1", "", 0, "Absatz Ankerkette", "s1"),
                    part(page, "a1", "", 1, "Absatz Bugstrahl", "s1"),
                    part(page, "a1", "sha256:x:1", 0, "Anhang Kompass", "s1"),
                    part(page, "a1", "sha256:y:1", 0, "Anhang Sextant", "s1")));
            index.putAttachment(new PageTextIndex.AttachmentEntry(page, "DEV", "a2", "andere.pdf", "Ruderhaus",
                    "s9"));

            assertThat(index.search("Kompass", Set.of("wiki"), SearchSpaces.all(), 10))
                    .singleElement().satisfies(match -> {
                        assertThat(match.attachmentId()).isEqualTo("a1");
                        assertThat(match.partId()).isEqualTo("sha256:x:1");
                        assertThat(match.partTitle()).isEqualTo("teil.txt");
                    });
            assertThat(index.search("Bugstrahl", Set.of("wiki"), SearchSpaces.all(), 10))
                    .singleElement().satisfies(match -> assertThat(match.partId()).isNull());
            assertThat(index.attachmentInputSha256(page, "a1")).contains("s1");
            // Namen für Treffer der semantischen Suche in einem Dokument (ADR 0026, AP9a).
            assertThat(index.documentTitles(page, "a1", "sha256:x:1"))
                    .contains(new PageTextIndex.DocumentTitles("akte.zip", "teil.txt"));
            assertThat(index.documentTitles(page, "a2", "")).contains(new PageTextIndex.DocumentTitles("andere.pdf",
                    ""));
            assertThat(index.documentTitles(page, "a3", "")).isEmpty();

            // Kürzer geworden, ein Teil weniger: überzählige Abschnitte und Teile verschwinden, der andere Anhang
            // und die Seite bleiben.
            index.replaceAttachment(page, "a1", java.util.List.of(
                    part(page, "a1", "", 0, "Absatz Ankerkette", "s2"),
                    part(page, "a1", "sha256:x:1", 0, "Anhang Kompass", "s2")));
            assertThat(index.search("Bugstrahl", Set.of("wiki"), SearchSpaces.all(), 10)).isEmpty();
            assertThat(index.search("Sextant", Set.of("wiki"), SearchSpaces.all(), 10)).isEmpty();
            assertThat(index.search("Kompass", Set.of("wiki"), SearchSpaces.all(), 10)).hasSize(1);
            assertThat(index.search("Ruderhaus", Set.of("wiki"), SearchSpaces.all(), 10)).hasSize(1);
            assertThat(index.search("Seitentext", Set.of("wiki"), SearchSpaces.all(), 10)).hasSize(1);

            // Ein einzelnes Dokument neu: die übrigen Dokumente des Anhangs bleiben.
            index.replaceDocument(page, "a1", "sha256:x:1", java.util.List.of(
                    part(page, "a1", "sha256:x:1", 0, "Anhang Lot", "s3")));
            assertThat(index.search("Kompass", Set.of("wiki"), SearchSpaces.all(), 10)).isEmpty();
            assertThat(index.search("Lot", Set.of("wiki"), SearchSpaces.all(), 10)).hasSize(1);
            assertThat(index.search("Ankerkette", Set.of("wiki"), SearchSpaces.all(), 10)).hasSize(1);

            // Scheitert das Lesen mitten im Ersetzen, gilt der alte Stand.
            Iterable<PageTextIndex.AttachmentEntry> failing = () -> new java.util.Iterator<>() {
                private int given;

                @Override
                public boolean hasNext() {
                    return true;
                }

                @Override
                public PageTextIndex.AttachmentEntry next() {
                    if (given++ > 0) {
                        throw new IllegalStateException("Ablage nicht lesbar");
                    }
                    return part(page, "a1", "", 0, "Absatz Neuland", "s4");
                }
            };
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> index.replaceAttachment(page, "a1", failing))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(index.search("Neuland", Set.of("wiki"), SearchSpaces.all(), 10)).isEmpty();
            assertThat(index.search("Ankerkette", Set.of("wiki"), SearchSpaces.all(), 10)).hasSize(1);

            // Die Teile folgen der Seite in einen anderen Space, mit Teilkennung.
            index.put(new PageTextIndex.Entry(page, "OPS", "Seite", "Seitentext", "sha256:p2"));
            assertThat(index.search("Lot", Set.of("wiki"), new SearchSpaces(Set.of("OPS"), Set.of()), 10))
                    .singleElement().satisfies(match -> assertThat(match.partId()).isEqualTo("sha256:x:1"));

            index.removeAttachment(page, "a1");
            assertThat(index.search("Ankerkette Lot", Set.of("wiki"), SearchSpaces.all(), 10)).isEmpty();
        }
    }

    private static PageTextIndex.AttachmentEntry part(PageKey page, String attachmentId, String partId, int section,
                                                      String text, String input) {
        return new PageTextIndex.AttachmentEntry(page, "DEV", attachmentId, "akte.zip", text, input, partId,
                partId.isEmpty() ? "" : "teil.txt", section);
    }
}
