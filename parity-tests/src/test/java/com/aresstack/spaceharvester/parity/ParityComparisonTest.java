package com.aresstack.spaceharvester.parity;

import com.aresstack.spaceharvester.parity.ParityComparison.Finding;
import com.aresstack.spaceharvester.parity.ParityComparison.Verdict;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ParityComparisonTest {

    @Test
    void droppedRepetitionsCountAsMissingText() {
        String paragraph = "Der Dienst startet nachts und meldet jeden Fehler an den Betrieb.\n";
        Finding text = finding(outline(paragraph.repeat(4), 0, 0), outline(paragraph, 0, 0), "text");
        assertThat(text.verdict()).isEqualTo(Verdict.CORE_WENIGER);
    }

    @Test
    void everyImageOfTheReferenceIsEmbeddedOrReported() {
        assertThat(finding(outline("", 2, 2), outline("", 2, 1), "bildhinweise").verdict())
                .isEqualTo(Verdict.CORE_WENIGER);
        // Ein Hinweis weniger ist in Ordnung, wenn der Core das Bild dafür einbettet
        assertThat(finding(outline("", 2, 2), outline("", 3, 1), "bildhinweise").verdict())
                .isEqualTo(Verdict.GLEICH);
    }

    @Test
    void aRepeatedPageTitleIsNoExtraLevel() {
        DocumentOutline eac = withHeadings(List.of(new DocumentOutline.Heading(1, "Handbuch"),
                new DocumentOutline.Heading(2, "Handbuch"), new DocumentOutline.Heading(2, "Zweck")));
        DocumentOutline core = withHeadings(List.of(new DocumentOutline.Heading(1, "Handbuch"),
                new DocumentOutline.Heading(2, "Zweck")));
        assertThat(finding(eac, core, "ebenen").verdict()).isEqualTo(Verdict.GLEICH);
    }

    @Test
    void aPageWithoutReadingViewInTheCoreIsAGap() {
        List<Finding> findings = ParityComparison.readingViews(java.util.Map.of("1", "Text eins", "2", "Text zwei"),
                java.util.Map.of("1", "Text eins"), java.util.Map.of("1", 1, "2", 0), java.util.Map.of("1", 1));
        assertThat(findings).filteredOn(f -> f.check().equals("seiten")).singleElement()
                .extracting(Finding::verdict).isEqualTo(Verdict.CORE_WENIGER);
    }

    @Test
    void childrenInAnotherOrderAreAGapButExtraLinksAreMore() {
        List<Finding> findings = ParityComparison.navigation(
                java.util.Map.of("space.name", "S", "1.children", "2,3", "1.links", "4"),
                java.util.Map.of("space.name", "S", "1.children", "3,2", "1.links", "4,5"));
        assertThat(findings).filteredOn(f -> f.check().equals("kinder")).singleElement()
                .extracting(Finding::verdict).isEqualTo(Verdict.CORE_WENIGER);
        assertThat(findings).filteredOn(f -> f.check().equals("querverweise")).singleElement()
                .extracting(Finding::verdict).isEqualTo(Verdict.CORE_MEHR);
    }

    private static DocumentOutline withHeadings(List<DocumentOutline.Heading> headings) {
        return new DocumentOutline("md", headings, false, false, false, 0, 0, 0, List.of(), Set.of(), 0, 0, "");
    }

    private static Finding finding(DocumentOutline eac, DocumentOutline core, String check) {
        return ParityComparison.compare(eac, core).stream().filter(f -> f.check().equals(check)).findFirst()
                .orElseThrow();
    }

    @Test
    void everyOtherOutcomeOfASourceManagementStepIsAGap() {
        Map<String, String> eac = new LinkedHashMap<>();
        eac.put("add.listed", "true");
        eac.put("disable", "ok");
        List<Finding> findings = ParityComparison.sourceManagement(eac, Map.of("add.listed", "true"));

        assertThat(findings).extracting(Finding::key).containsExactly("quellen.add.listed", "quellen.disable");
        assertThat(findings).extracting(Finding::verdict).containsExactly(Verdict.GLEICH, Verdict.CORE_WENIGER);
        assertThat(findings.get(1).core()).isEqualTo("fehlt");
    }

    private static DocumentOutline outline(String text, int images, int notes) {
        return new DocumentOutline("md", List.of(), false, false, false, 0, images, notes, List.of(), Set.of(), 0, 0,
                text);
    }
}
