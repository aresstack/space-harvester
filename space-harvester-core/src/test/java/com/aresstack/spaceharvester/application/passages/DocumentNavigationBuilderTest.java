package com.aresstack.spaceharvester.application.passages;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.enrichment.PageText;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Dokumentnavigation wie {@code DocumentNavigationBuilderTest} im Enterprise AI Client (#181, ADR 0020):
 * Kapitelhierarchie aus Überschriften, Themenabschnitte für Text ohne Zwischenüberschriften, Stellen im angezeigten
 * Text, Benenner austauschbar, Rückfall bei Fehlern.
 */
class DocumentNavigationBuilderTest {

    static final String[] PASSAGES = {
            "Die Anwendung übernimmt Belege über die Import-Schnittstelle. Der Import prüft jeden Beleg.",
            "Belege werden validiert. Danach wird jeder Beleg vom Import verarbeitet.",
            "Die Ergebnisse landen in der Datenbank. Die Datenbank speichert alles.",
            "Der Speicher der Datenbank wird gesichert. Die Datenbank läuft nachts.",
            "Ein Urlaubsantrag ist etwas anderes. Der Antrag auf Urlaub geht an die Personalstelle.",
            "Urlaub muss genehmigt werden. Der Antrag wird geprüft."};

    static final TopicClustererTest.Vocabulary VOCABULARY = new TopicClustererTest.Vocabulary("beleg", "import",
            "datenbank", "speicher", "urlaub", "antrag");

    private final DocumentNavigationBuilder builder = new DocumentNavigationBuilder();

    /** Baut einen Seitentext aus Überschriften und Absätzen wie die Textprojektion, mit den Absätzen als Stellen. */
    static final class Page {

        private final StringBuilder text = new StringBuilder();
        private final List<int[]> headings = new ArrayList<>();
        private final List<String> headingTexts = new ArrayList<>();
        final List<DocumentNavigationBuilder.Passage> passages = new ArrayList<>();

        Page heading(int level, String heading) {
            separate();
            headings.add(new int[] {level, text.length()});
            headingTexts.add(heading);
            text.append(heading);
            return this;
        }

        Page paragraphs(String... paragraphs) {
            for (String paragraph : paragraphs) {
                separate();
                int start = text.length();
                text.append(paragraph);
                passages.add(new DocumentNavigationBuilder.Passage(start, text.length(),
                        VOCABULARY.vector(paragraph)));
            }
            return this;
        }

        private void separate() {
            if (!text.isEmpty()) {
                text.append('\n');
            }
        }

        PageText text() {
            List<PageText.Section> sections = new ArrayList<>();
            for (int i = 0; i < headings.size(); i++) {
                int end = i + 1 < headings.size() ? headings.get(i + 1)[1] - 1 : text.length();
                sections.add(new PageText.Section(headingTexts.get(i), headings.get(i)[0], headings.get(i)[1], end));
            }
            return new PageText("confluence-storage-text/v1", text.toString(), sections);
        }
    }

    @Test
    void headingsGiveTheChapterHierarchyAndASingleTitleIsResolved() {
        PageText text = new Page().heading(1, "Betriebshandbuch").paragraphs("Einleitung.")
                .heading(2, "Installation").paragraphs("Schritte.")
                .heading(3, "Voraussetzungen").paragraphs("Java 21.")
                .heading(3, "Ablauf").paragraphs("Starten.")
                .heading(2, "Betrieb").paragraphs("Täglich.").text();

        DocumentNavigationBuilder.Navigation navigation = builder.headingsOnly(text, NavigationPayload.REASON_NO_MODEL);

        assertThat(navigation.analysis()).isEqualTo(DocumentNavigationBuilder.Analysis.HEADINGS);
        assertThat(navigation.reason()).isEqualTo("no-model");
        assertThat(navigation.chapters()).extracting(NavigationSection::label).containsExactly("Installation",
                "Betrieb");
        NavigationSection installation = navigation.chapters().getFirst();
        assertThat(installation.parentId()).isNull();
        assertThat(installation.level()).isEqualTo(1);
        assertThat(installation.children()).extracting(NavigationSection::label)
                .containsExactly("Voraussetzungen", "Ablauf");
        assertThat(installation.children()).allSatisfy(child -> {
            assertThat(child.parentId()).isEqualTo(installation.id());
            assertThat(child.level()).isEqualTo(2);
            assertThat(child.generated()).isFalse();
            assertThat(text.text().startsWith(child.label(), child.start())).isTrue();
        });
        assertThat(installation.end()).isEqualTo(text.text().indexOf("Betrieb\n"));
    }

    @Test
    void textWithoutHeadingsGetsJumpableTopicSectionsInReadingOrder() {
        Page page = new Page().paragraphs(PASSAGES);
        PageText text = page.text();

        DocumentNavigationBuilder.Navigation navigation = builder.build(null, text, page.passages, null);

        assertThat(navigation.analysis()).isEqualTo(DocumentNavigationBuilder.Analysis.SEMANTIC);
        assertThat(navigation.reason()).isNull();
        assertThat(navigation.chapters()).hasSizeGreaterThanOrEqualTo(2);
        int last = -1;
        for (NavigationSection chapter : navigation.chapters()) {
            assertThat(chapter.origin()).isEqualTo(NavigationSection.Origin.TOPIC);
            assertThat(chapter.start()).isGreaterThan(last);
            last = chapter.start();
            assertThat(text.text().startsWith(chapter.anchor(), chapter.start())).isTrue();
        }
        assertThat(navigation.chapters().getFirst().id()).isEqualTo("p0");
        assertThat(navigation.chapters().getFirst().label())
                .isIn("Die Anwendung übernimmt Belege über die Import-Schnittstelle",
                        "Belege werden validiert");
        assertThat(navigation.chapters().getLast().start()).isEqualTo(text.text().indexOf(PASSAGES[4]));
        assertThat(navigation.chapters().getLast().end()).isEqualTo(text.text().length());
    }

    @Test
    void topicsSubdivideOnlyTheChapterTheyBelongTo() {
        Page page = new Page().heading(1, "Seite").paragraphs("Kurze Einleitung.")
                .heading(2, "Verarbeitung").paragraphs(PASSAGES[0], PASSAGES[1], PASSAGES[2], PASSAGES[3])
                .heading(2, "Personal").paragraphs(PASSAGES[4], PASSAGES[5]);
        PageText text = page.text();

        DocumentNavigationBuilder.Navigation navigation = builder.build(null, text, page.passages, null);

        assertThat(navigation.analysis()).isEqualTo(DocumentNavigationBuilder.Analysis.SEMANTIC);
        NavigationSection processing = navigation.chapters().get(navigation.chapters().size() - 2);
        assertThat(processing.label()).isEqualTo("Verarbeitung");
        assertThat(processing.generated()).isFalse();
        assertThat(processing.children()).hasSize(2).allSatisfy(topic -> {
            assertThat(topic.generated()).isTrue();
            assertThat(topic.parentId()).isEqualTo(processing.id());
            assertThat(topic.level()).isEqualTo(processing.level() + 1);
        });
        assertThat(processing.children().get(1).start()).isEqualTo(text.text().indexOf(PASSAGES[2]));
        assertThat(navigation.chapters().getLast().children()).as("ein Thema allein unterteilt nichts").isEmpty();
        // Fließtext des aufgelösten Titels steht als eigener Abschnitt vor den Kapiteln.
        assertThat(navigation.chapters().getFirst().generated()).isTrue();
        assertThat(navigation.chapters().getFirst().label()).isEqualTo("Kurze Einleitung");
    }

    @Test
    void aLabelerNamesTopicsAndItsFailureKeepsTheAnalysisLabels() {
        Page page = new Page().paragraphs(PASSAGES);
        SectionLabeler named = (title, texts) -> {
            List<String> labels = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                labels.add("Thema " + (i + 1));
            }
            return labels;
        };
        SectionLabeler broken = (title, texts) -> {
            throw new IllegalStateException("Modell weg");
        };

        assertThat(builder.build("Notiz", page.text(), page.passages, named).chapters().getFirst().label())
                .isEqualTo("Thema 1");
        DocumentNavigationBuilder.Navigation fallback = builder.build("Notiz", page.text(), page.passages, broken);
        assertThat(fallback.analysis()).isEqualTo(DocumentNavigationBuilder.Analysis.SEMANTIC);
        assertThat(fallback.chapters().getFirst().label()).startsWith("Die Anwendung übernimmt Belege");
    }

    @Test
    void fewPassagesKeepTheHeadingsAndSayWhy() {
        Page page = new Page().heading(2, "A").paragraphs(PASSAGES[0]).heading(2, "B").paragraphs(PASSAGES[4]);

        DocumentNavigationBuilder.Navigation navigation = builder.build(null, page.text(), page.passages, null);

        assertThat(navigation.analysis()).isEqualTo(DocumentNavigationBuilder.Analysis.HEADINGS);
        assertThat(navigation.reason()).isEqualTo(NavigationPayload.REASON_NO_TOPIC_CHANGE);
        assertThat(navigation.chapters()).extracting(NavigationSection::label).containsExactly("A", "B");
    }

    @Test
    void oneTopicRunIsNoSubdivision() {
        String[] receipts = {PASSAGES[0], PASSAGES[1], "Jeder Beleg geht durch den Import.",
                "Der Import meldet jeden Beleg."};
        Page flat = new Page().paragraphs(receipts);

        DocumentNavigationBuilder.Navigation navigation = builder.build(null, flat.text(), flat.passages, null);

        assertThat(navigation.analysis()).isEqualTo(DocumentNavigationBuilder.Analysis.HEADINGS);
        assertThat(navigation.chapters()).isEmpty();

        Page chaptered = new Page().heading(2, "Import").paragraphs(receipts).heading(2, "Urlaub")
                .paragraphs(PASSAGES[4]);
        assertThat(builder.build(null, chaptered.text(), chaptered.passages, null).chapters().getFirst().children())
                .isEmpty();
    }

    @Test
    void thePayloadKeepsPositionsAndRestoresTheAnchorsFromTheText() {
        Page page = new Page().heading(2, "Verarbeitung").paragraphs(PASSAGES).heading(2, "Ende")
                .paragraphs("Schluss.");
        PageText text = page.text();
        DocumentNavigationBuilder.Navigation built = builder.build(null, text, page.passages, null);

        String json = NavigationPayload.write(built);

        assertThat(json).doesNotContain("Import prüft");
        assertThat(NavigationPayload.parse(json, text.text())).isEqualTo(built);
    }
}
