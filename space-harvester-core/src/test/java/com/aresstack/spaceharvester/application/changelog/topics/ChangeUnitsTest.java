package com.aresstack.spaceharvester.application.changelog.topics;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.changelog.ChangeKind;
import com.aresstack.spaceharvester.application.changelog.ChangeReport;
import com.aresstack.spaceharvester.application.changelog.ChangeUpdate;
import com.aresstack.spaceharvester.application.changelog.ContentChange;
import com.aresstack.spaceharvester.application.changelog.ContentDiff;
import com.aresstack.spaceharvester.application.changelog.ParagraphChange;
import com.aresstack.spaceharvester.application.changelog.ReportedPage;
import com.aresstack.spaceharvester.application.changelog.topics.ChangeUnit.Kind;
import com.aresstack.spaceharvester.application.changelog.topics.ChangeUnit.Nature;
import com.aresstack.spaceharvester.application.changelog.topics.ChangeUnit.Role;
import com.aresstack.spaceharvester.application.passages.SentenceSegmenter;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Änderungseinheiten (ADR 0030, Nachtrag Abschnitt 3, TZ3). Erfundene Beispieldaten, keine echten Inhalte. */
class ChangeUnitsTest {

    private static final Instant MONDAY = Instant.parse("2026-11-16T08:00:00Z");
    private static final Instant FRIDAY = Instant.parse("2026-11-20T08:00:00Z");
    /** Satzgrenze nach Punkt und Leerzeichen; genügt für die Beispiele. */
    private static final SentenceSegmenter SENTENCES = new SentenceSegmenter() {
        @Override
        public List<String> sentences(String paragraph) {
            return Arrays.asList(paragraph.split("(?<=\\.)\\s+"));
        }

        @Override
        public String id() {
            return "test";
        }
    };

    private final ChangeUnits units = new ChangeUnits(SENTENCES);

    @Test
    void onlyTheChangedSentenceOfAParagraphIsKeptWithItsNature() {
        ReportedPage page = changed("1", "Antrag", MONDAY, new ParagraphChange(ParagraphChange.Kind.CHANGED,
                "Der Antrag wird geprüft. Die Frist beträgt 10 Tage. Danach entscheidet die Stelle.",
                "Der Antrag wird geprüft. Die Frist beträgt 14 Tage. Danach entscheidet die Stelle."));

        ChangeUnits.Result result = units.build(report(page), key -> true);

        assertThat(result.units()).singleElement().satisfies(unit -> {
            assertThat(unit.kind()).isEqualTo(Kind.PARAGRAPH_CHANGED);
            assertThat(unit.nature()).isEqualTo(Nature.NUMBER_OR_DATE);
            assertThat(unit.changedBefore()).containsExactly("Die Frist beträgt 10 Tage.");
            assertThat(unit.changedAfter()).containsExactly("Die Frist beträgt 14 Tage.");
            assertThat(unit.terms()).contains("frist").doesNotContain("die", "antrag");
            assertThat(unit.aiReleased()).isTrue();
            assertThat(unit.id()).startsWith("u-").doesNotContain("Antrag");
        });
    }

    @Test
    void theNatureFollowsFixedRules() {
        assertThat(ChangeUnits.nature(List.of("Das Formular liegt im Intranett."),
                List.of("Das Formular liegt im Intranet."))).isEqualTo(Nature.CORRECTION);
        assertThat(ChangeUnits.nature(List.of("Gültig ab 01.01.2026."), List.of("Gültig ab 01.03.2026.")))
                .isEqualTo(Nature.NUMBER_OR_DATE);
        assertThat(ChangeUnits.nature(List.of("Anträge gehen per Post an die Poststelle."),
                List.of("Anträge werden ausschließlich elektronisch über das Portal eingereicht.")))
                .isEqualTo(Nature.MAJOR_REVISION);
        // Ein anderes Wort ist eine inhaltliche Änderung, keine Korrektur, auch wenn der Rest gleich bleibt.
        assertThat(ChangeUnits.nature(List.of("Anträge gehen per Post an die zentrale Poststelle im Haus."),
                List.of("Anträge gehen per E-Mail an die zentrale Poststelle im Haus.")))
                .isEqualTo(Nature.EDIT);
        assertThat(ChangeUnits.nature(List.of("Die Prüfung übernimmt das Referat A für alle Anträge des Hauses."),
                List.of("Die Prüfung übernimmt künftig das Referat B gemeinsam mit dem Referat C für alle Anträge.")))
                .isEqualTo(Nature.EDIT);
    }

    @Test
    void textThatWandersFromOnePageToAnotherIsOneRelocationNotTwoChanges() {
        String text = "Für Rückfragen steht das Servicezentrum montags bis freitags von 8 bis 16 Uhr zur Verfügung.";
        ReportedPage from = changed("1", "Antrag", MONDAY, new ParagraphChange(ParagraphChange.Kind.REMOVED, text,
                null));
        ReportedPage to = changed("2", "Kontakt", FRIDAY, new ParagraphChange(ParagraphChange.Kind.ADDED, null,
                text.replace("16 Uhr", "16 Uhr")));

        ChangeUnits.Result result = units.build(report(from, to), key -> true);

        assertThat(result.units()).singleElement().satisfies(unit -> {
            assertThat(unit.kind()).isEqualTo(Kind.PARAGRAPH_MOVED);
            assertThat(unit.nature()).isEqualTo(Nature.RELOCATION);
            assertThat(unit.occurrences()).extracting(ChangeUnit.Occurrence::role)
                    .containsExactly(Role.FROM, Role.TO);
            assertThat(unit.pages()).containsExactly(new PageKey("wiki", "1"), new PageKey("wiki", "2"));
        });
    }

    @Test
    void theSameChangeOnManyPagesIsOneUnitWithSeveralOccurrences() {
        List<ReportedPage> pages = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            pages.add(changed(String.valueOf(i), "Verfahren " + i, MONDAY.plusSeconds(86_400L * (i % 5)),
                    new ParagraphChange(ParagraphChange.Kind.CHANGED,
                            "Verfahren " + i + " ist beschrieben. Zuständig ist das Referat Alt.",
                            "Verfahren " + i + " ist beschrieben. Zuständig ist das Referat Neu.")));
        }

        ChangeUnits.Result result = units.build(report(pages.toArray(ReportedPage[]::new)), key -> true);

        assertThat(result.units()).singleElement().satisfies(unit -> {
            assertThat(unit.occurrences()).hasSize(6);
            assertThat(unit.changedAfter()).containsExactly("Zuständig ist das Referat Neu.");
        });
    }

    @Test
    void pageEventsAreUnitsWithoutTextAndMissingDiffsAreCounted() {
        ReportedPage renamed = page("1", "Neuer Titel", Set.of(ChangeKind.RENAMED), MONDAY, null);
        ReportedPage fresh = page("2", "Leitfaden Archivierung", Set.of(ChangeKind.NEW), MONDAY, null);
        ReportedPage pending = page("3", "Offen", Set.of(ChangeKind.CONTENT_CHANGED), MONDAY,
                new ContentDiff(ContentDiff.Status.PREVIOUS_MISSING, List.of()));
        ReportedPage loaded = page("4", "Nur geladen", Set.of(ChangeKind.LOADED), MONDAY, null);

        ChangeUnits.Result result = units.build(report(renamed, fresh, pending, loaded), key -> true);

        assertThat(result.units()).extracting(ChangeUnit::kind).containsExactly(Kind.RENAMED, Kind.PAGE_NEW);
        assertThat(result.units()).allSatisfy(unit -> assertThat(unit.hasText()).isFalse());
        assertThat(result.units().get(1).terms()).contains("leitfaden", "archivierung");
        assertThat(result.notEvaluated()).isEqualTo(1);
    }

    @Test
    void aUnitIsReleasedOnlyWhenEveryOccurrenceIs() {
        List<ReportedPage> pages = new ArrayList<>();
        for (String id : List.of("1", "2")) {
            pages.add(changed(id, "Seite " + id, MONDAY, new ParagraphChange(ParagraphChange.Kind.ADDED, null,
                    "Neu ist die Pflicht zur Zweitprüfung.")));
        }

        ChangeUnits.Result result = units.build(report(pages.toArray(ReportedPage[]::new)),
                key -> !key.pageId().equals("2"));

        assertThat(result.units()).singleElement()
                .satisfies(unit -> assertThat(unit.aiReleased()).isFalse());
    }

    @Test
    void theResultIsTheSameForTheSameReport() {
        ReportedPage a = changed("1", "A", MONDAY, new ParagraphChange(ParagraphChange.Kind.ADDED, null,
                "Erster neuer Absatz zur Archivierung."));
        ReportedPage b = changed("2", "B", FRIDAY, new ParagraphChange(ParagraphChange.Kind.CHANGED,
                "Alte Regel zur Löschung.", "Neue Regel zur Löschung nach zehn Jahren."));

        assertThat(units.build(report(a, b), key -> true)).isEqualTo(units.build(report(a, b), key -> true));
    }

    private static ReportedPage changed(String pageId, String title, Instant at, ParagraphChange... paragraphs) {
        ContentChange step = new ContentChange("wiki", pageId, "1", "2", at, List.of(paragraphs), false);
        return page(pageId, title, Set.of(ChangeKind.CONTENT_CHANGED), at,
                new ContentDiff(ContentDiff.Status.AVAILABLE, List.of(step)));
    }

    private static ReportedPage page(String pageId, String title, Set<ChangeKind> kinds, Instant at,
                                     ContentDiff diff) {
        return new ReportedPage("wiki", "DEV", pageId, kinds.iterator().next(), kinds, title, List.of("Handbuch"),
                null, "1", "2", title, title, "0", "0", "DEV", "DEV", false, at, diff);
    }

    private static ChangeReport report(ReportedPage... pages) {
        ChangeUpdate update = new ChangeUpdate("1", "wiki", MONDAY, FRIDAY, null, false, Map.of(), Map.of(),
                pages.length, 0, 0, false);
        return new ChangeReport(update, List.of(pages));
    }
}
