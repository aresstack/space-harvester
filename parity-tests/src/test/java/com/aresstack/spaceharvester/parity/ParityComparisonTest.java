package com.aresstack.spaceharvester.parity;

import com.aresstack.spaceharvester.parity.ParityComparison.Finding;
import com.aresstack.spaceharvester.parity.ParityComparison.Verdict;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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

    @Test
    void aMacroSentenceMissingOrReportedAsOmittedIsAGap() {
        List<String> sentences = List.of("Satz Hotel.", "Satz India.");
        DocumentOutline eac = outline("Satz Hotel. Satz  India.", 0, 0);

        assertThat(ParityComparison.macros("md", eac, outline("Satz Hotel.\nSatz India.", 0, 0), sentences,
                List.of()).verdict()).isEqualTo(Verdict.GLEICH);
        Finding missing = ParityComparison.macros("md", eac, outline("Satz Hotel.", 0, 0), sentences, List.of());
        assertThat(missing.verdict()).isEqualTo(Verdict.CORE_WENIGER);
        assertThat(missing.detail()).contains("Satz India.");
        assertThat(ParityComparison.macros("md", eac, eac, sentences, List.of("1009 children (not-collected)"))
                .verdict()).isEqualTo(Verdict.CORE_WENIGER);
        // Ungleiche Zählung von Ansicht und Storage: gewollt nicht eingesetzt (#426), steht im Detail, keine Lücke
        Finding mismatch = ParityComparison.macros("md", eac, eac, sentences,
                List.of("1012 contentbylabel (view-count-mismatch)"));
        assertThat(mismatch.verdict()).isEqualTo(Verdict.GLEICH);
        assertThat(mismatch.detail()).startsWith("gewollt anders: ").contains("1012 contentbylabel");
        assertThat(ParityComparison.macros("md", eac, eac, sentences, List.of(
                "1012 contentbylabel (view-count-mismatch)", "1009 children (not-collected)")).verdict())
                .isEqualTo(Verdict.CORE_WENIGER);
    }

    @Test
    void anAddressLeadingElsewhereIsAGapButOnlyInTheCoreIsMore() {
        Map<String, String> eac = new LinkedHashMap<>();
        eac.put("display-title", "1002");
        eac.put("space-home", "1001");
        eac.put("foreign-host", "-");
        List<Finding> findings = ParityComparison.addresses(eac,
                Map.of("display-title", "1002", "space-home", "-", "foreign-host", "1002"));

        assertThat(findings).extracting(Finding::verdict)
                .containsExactly(Verdict.GLEICH, Verdict.CORE_WENIGER, Verdict.CORE_MEHR);
        assertThat(ParityComparison.addresses(Map.of("unknown-title", "-"), Map.of()).get(0).verdict())
                .isEqualTo(Verdict.CORE_WENIGER);
    }

    @Test
    void aSearchFindingLessIsAGapAndMissingResultsToo() {
        Map<String, String> eac = new LinkedHashMap<>();
        eac.put("search.two-files", "betrieb.md,notfall.md");
        eac.put("search.two-files.snippet", "ja");
        eac.put("search.one-file", "urlaub.txt");
        eac.put("search.none", "-");
        eac.put("prefetch.1002", "ja");
        eac.put("prefetch.1003", "ja");
        Map<String, String> core = new LinkedHashMap<>();
        core.put("search.two-files", "betrieb.md,notfall.md");
        core.put("search.two-files.snippet", "nein");
        core.put("search.one-file", "kantine.txt,urlaub.txt");
        core.put("search.none", "-");
        core.put("prefetch.1002", "nein");

        assertThat(ParityComparison.search(eac, core)).extracting(Finding::verdict).containsExactly(Verdict.GLEICH,
                Verdict.CORE_WENIGER, Verdict.CORE_MEHR, Verdict.GLEICH, Verdict.CORE_WENIGER, Verdict.CORE_WENIGER);
    }

    @Test
    void aMissingAcronymOrDefinitionIsAGapAndMoreIsMore() {
        Map<String, String> eac = new LinkedHashMap<>();
        eac.put("facts.glossar.md.candidates", "API,LAN");
        eac.put("facts.glossar.md.definitions", "API=application programming interface");
        eac.put("facts.kantine.txt.candidates", "-");
        eac.put("facts.urlaub.txt.candidates", "-");
        Map<String, String> core = new LinkedHashMap<>();
        core.put("facts.glossar.md.candidates", "API");
        core.put("facts.glossar.md.definitions", "API=application programming interface,LAN=local area network");
        core.put("facts.kantine.txt.candidates", "-");

        assertThat(ParityComparison.facts(eac, core)).extracting(Finding::verdict).containsExactly(
                Verdict.CORE_WENIGER, Verdict.CORE_MEHR, Verdict.GLEICH, Verdict.CORE_WENIGER);
    }

    @Test
    void aGitSourceLacksWhatTheCoreDoesNotListOrLoad() {
        Map<String, String> eac = new LinkedHashMap<>();
        eac.put("git.main.documents", "README.md,docs/bilder/logo.png");
        eac.put("git.main.title.README.md", "README.md");
        eac.put("git.main.sentences.README.md", "0");
        eac.put("git.tag.sentences.docs/a.md", "old");
        Map<String, String> core = new LinkedHashMap<>();
        core.put("git.main.documents", "README.md");
        core.put("git.main.title.README.md", "README.md");
        core.put("git.main.sentences.README.md", "0,1");

        assertThat(ParityComparison.git(eac, core)).extracting(Finding::verdict).containsExactly(
                Verdict.CORE_WENIGER, Verdict.GLEICH, Verdict.CORE_MEHR, Verdict.CORE_WENIGER);
        assertThat(ParityComparison.git(eac, core)).extracting(Finding::key).startsWith("git.main.documents");
    }

    @Test
    void aSearchRunDiffersOnlyWhereTheCoreLoggedSomethingElse() {
        Map<String, String> eac = new LinkedHashMap<>();
        eac.put("run.live.query", "Architektur");
        eac.put("run.live.sources", "1");
        eac.put("run.live.candidates", "2");
        Map<String, String> core = Map.of("run.live.query", "Architektur", "run.live.sources", "1");

        assertThat(ParityComparison.searchRuns(eac, core)).extracting(Finding::verdict).containsExactly(
                Verdict.GLEICH, Verdict.GLEICH, Verdict.CORE_WENIGER);
    }

    @Test
    void anotherTypeMissingWordsOrNoTextAreGaps() {
        Map<String, String> eacTypes = Map.of("a.docx.content-type", "application/msword", "b.pdf.content-type",
                "application/pdf", "c.rtf.content-type", "application/rtf");
        Map<String, String> coreTypes = Map.of("a.docx.content-type", "application/msword", "b.pdf.content-type",
                "-", "c.rtf.content-type", "application/rtf");
        Map<String, String> eacTexts = new LinkedHashMap<>();
        eacTexts.put("a.docx", "Wartungsfenster am Sonntag");
        eacTexts.put("b.pdf", "Notfallplan Rechenzentrum");
        eacTexts.put("c.rtf", "Protokoll der Lenkungsrunde");
        Map<String, String> coreTexts = Map.of("a.docx", "Wartungsfenster am Sonntag und mehr", "b.pdf",
                "Notfallplan");

        assertThat(ParityComparison.documents(eacTypes, coreTypes, eacTexts, coreTexts))
                .extracting(Finding::key, Finding::verdict).containsExactly(
                        tuple("dokument-a.docx.typ", Verdict.GLEICH), tuple("dokument-a.docx.text", Verdict.GLEICH),
                        tuple("dokument-b.pdf.typ", Verdict.CORE_WENIGER),
                        tuple("dokument-b.pdf.text", Verdict.CORE_WENIGER),
                        tuple("dokument-c.rtf.typ", Verdict.GLEICH),
                        tuple("dokument-c.rtf.text", Verdict.CORE_WENIGER));
    }

    @Test
    void theChapterOrderCountsOnlyOnceTheFakeKnowsPositions() {
        DocumentOutline eac = withHeadings(List.of(new DocumentOutline.Heading(1, "Handbuch"),
                new DocumentOutline.Heading(2, "Zweck"), new DocumentOutline.Heading(2, "Betrieb"),
                new DocumentOutline.Heading(2, "Nur EAC")));
        DocumentOutline sameOrder = withHeadings(List.of(new DocumentOutline.Heading(1, "Handbuch"),
                new DocumentOutline.Heading(2, "Zweck"), new DocumentOutline.Heading(2, "Nur Core"),
                new DocumentOutline.Heading(2, "Betrieb")));
        DocumentOutline otherOrder = withHeadings(List.of(new DocumentOutline.Heading(1, "Handbuch"),
                new DocumentOutline.Heading(2, "Betrieb"), new DocumentOutline.Heading(2, "Zweck")));

        // Was nur eine Seite hat, zählt hier nicht (das prüft „kapitel“), nur die Folge der gemeinsamen.
        assertThat(ParityComparison.chapterOrder("md", eac, sameOrder, true).verdict()).isEqualTo(Verdict.GLEICH);
        Finding loose = ParityComparison.chapterOrder("md", eac, otherOrder, false);
        assertThat(loose.verdict()).isEqualTo(Verdict.NICHT_MESSBAR);
        assertThat(loose.detail()).contains("Stelle 2", "zweck", "betrieb", "Patch 09");
        assertThat(ParityComparison.chapterOrder("md", eac, otherOrder, true).verdict())
                .isEqualTo(Verdict.CORE_WENIGER);
    }

    @Test
    void aMentionShowsTheNameOfTheViewAndNothingTheEacHides() {
        DocumentOutline eac = outline("Ansprechpartnerin ist Anna Schmidt. Von Bernd Wagner.", 0, 0);
        List<String> names = List.of("Anna Schmidt", "Bernd Wagner", "Carla Conrad");
        List<String> absent = List.of("8a7f0c1d", "Versteckte Person");

        Finding same = ParityComparison.mentions("md", eac, eac, names, absent);
        assertThat(same.verdict()).isEqualTo(Verdict.GLEICH);
        assertThat(same.eac()).isEqualTo("2 Namen");
        Finding id = ParityComparison.mentions("md", eac,
                outline("Ansprechpartnerin ist @8a7f0c1d. Von Bernd Wagner.", 0, 0), names, absent);
        assertThat(id.verdict()).isEqualTo(Verdict.CORE_WENIGER);
        assertThat(id.detail()).contains("Name fehlt: Anna Schmidt", "nur im Core: 8a7f0c1d");
        assertThat(ParityComparison.mentions("md", eac, outline(eac.text() + " Versteckte Person", 0, 0), names,
                absent).verdict()).isEqualTo(Verdict.CORE_WENIGER);
    }

    private static DocumentOutline outline(String text, int images, int notes) {
        return new DocumentOutline("md", List.of(), false, false, false, 0, images, notes, List.of(), Set.of(), 0, 0,
                text);
    }
}
