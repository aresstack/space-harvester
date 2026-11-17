package com.aresstack.spaceharvester.app.ui.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.application.jobs.JobListener;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import com.aresstack.spaceharvester.application.knowledge.PageStatements;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.standing.BearerStates;
import com.aresstack.spaceharvester.application.standing.PageMark;
import com.aresstack.spaceharvester.application.standing.PageStanding;
import com.aresstack.spaceharvester.application.standing.SentenceText;
import com.aresstack.spaceharvester.application.standing.Standing;
import com.aresstack.spaceharvester.application.standing.StandingBasis;
import com.aresstack.spaceharvester.application.standing.StandingDecision;
import com.aresstack.spaceharvester.application.standing.StandingReason;
import com.aresstack.spaceharvester.application.standing.StatementKind;
import com.aresstack.spaceharvester.application.standing.StatementStanding;
import com.aresstack.spaceharvester.application.standing.StatusQuoEntry;
import com.aresstack.spaceharvester.application.standing.StatusQuoView;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.app.ui.controls.CountChip;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Reiter „Stand“ (ADR 0032, FL6): Gruppen, Zählchips, Filter, Träger, Overlay, Sprung, nichts springt beim Neulesen. */
class StandingTabTest {

    private static final ExportPanel.Selection SPACE = new ExportPanel.Selection("wiki", "DEV", null, "DEV");

    @Test
    void statementsAreGroupedByStandingWithTheOutdatedOnesGreyAtTheEnd() {
        Fixture fixture = new Fixture(view());
        StandingTab tab = fixture.shownTab();

        assertThat(groups(tab)).containsExactly("gilt · 2", "geplant · 1", "unklar · 1", "überholt · 1");
        List<StandingTab.Row> rows = tab.rows();
        assertThat(rows.getLast()).isInstanceOf(StandingTab.Line.class);
        assertThat(((StandingTab.Line) rows.getLast()).entry().standing().standing()).isEqualTo(Standing.UEBERHOLT);
        assertThat(((StandingTab.Group) rows.get(rows.size() - 2)).tone()).isEqualTo(Comic.STANDING_OUTDATED)
                .isNotEqualTo(Comic.RED);
        // Die Zahlen oben kommen aus dem Core, das Wort steht immer dabei.
        assertThat(tab.chip(Standing.GILT).getText()).isEqualTo("gilt 2");
        assertThat(tab.chip(Standing.UEBERHOLT).getText()).isEqualTo("überholt 1");
        assertThat(fixture.status.get()).isEqualTo("5 Aussagen auf 2 Seiten");
    }

    @Test
    void aChipFiltersOnItsStandingAndASecondClickShowsAllAgain() {
        StandingTab tab = new Fixture(view()).shownTab();

        tab.chip(Standing.GEPLANT).doClick();
        assertThat(groups(tab)).containsExactly("geplant · 1");

        tab.chip(Standing.GILT).doClick();
        assertThat(tab.chip(Standing.GEPLANT).isSelected()).as("ein Stand zur Zeit").isFalse();
        assertThat(groups(tab)).containsExactly("gilt · 2");

        tab.chip(Standing.GILT).doClick();
        assertThat(groups(tab)).hasSize(4);
    }

    @Test
    void negationAndModalityAreAlwaysPartOfTheLine() {
        StatusQuoEntry negated = entry("v2", Standing.GILT, StatementKind.ACTION, "Der Kunde", "unterschreibt",
                ParsedStatements.Negation.NEGATED, ParsedStatements.Modality.MUSS, ParsedStatements.Quality.PLAUSIBLE);

        assertThat(StandingTab.mark(negated)).isEqualTo("muss nicht");
        assertThat(StandingTab.text(negated)).isEqualTo("Der Kunde unterschreibt");
        assertThat(StandingTab.details(negated)).startsWith("Stand: gilt: muss nicht …");
    }

    @Test
    void onlyCertainAndTheSearchNarrowTheList() {
        StandingTab tab = new Fixture(view()).shownTab();

        tab.certainOnlyBox().doClick();
        assertThat(lines(tab)).noneMatch(StatusQuoEntry::uncertain).hasSize(4);

        tab.certainOnlyBox().doClick();
        tab.searchField().setText("antrag");
        assertThat(lines(tab)).extracting(StandingTab::text).containsExactly("Der Antrag wird geprüft");
    }

    @Test
    void byBearerBundlesTheStatesUnderTheirBearer() {
        StandingTab tab = new Fixture(view()).shownTab();

        tab.bearerButton().doClick();

        assertThat(groups(tab)).containsExactly("Sachbearbeiter · 1");
        assertThat(lines(tab)).extracting(StatusQuoEntry::kind).containsOnly(StatementKind.STATE);
        assertThat(tab.bearerButton().getToolTipText()).isEqualTo(StandingTab.AS_LIST_TIP);
        tab.bearerButton().doClick();
        assertThat(groups(tab)).hasSize(4);
    }

    @Test
    void readingAgainKeepsSelectionAndFilter() {
        Fixture fixture = new Fixture(view());
        StandingTab tab = fixture.shownTab();
        tab.chip(Standing.GILT).doClick();
        tab.rowList().setSelectedIndex(2);
        String selected = ((StandingTab.Line) tab.rowList().getSelectedValue()).key();

        // Eine Entscheidung im Core: die Sicht wird neu gelesen, ohne Polling.
        fixture.standingListener.get().run();
        flush();

        assertThat(fixture.reads.get()).isEqualTo(2);
        assertThat(groups(tab)).containsExactly("gilt · 2");
        assertThat(((StandingTab.Line) tab.rowList().getSelectedValue()).key()).isEqualTo(selected);
    }

    @Test
    void theSelectionIsReadOnlyWhileTheTabIsShown() {
        Fixture fixture = new Fixture(view());
        StandingTab tab = fixture.tab;

        tab.selection(SPACE);
        assertThat(fixture.reads.get()).isZero();

        tab.shown(true);
        assertThat(fixture.reads.get()).isEqualTo(1);
        tab.shown(false);
        tab.shown(true);
        assertThat(fixture.reads.get()).as("unverändert, kein zweites Lesen").isEqualTo(1);
    }

    @Test
    void theOverlayNamesReasonAndOriginAndJumpsToTheText() {
        Fixture fixture = new Fixture(view());
        StandingTab tab = fixture.shownTab();
        int planned = indexOf(tab, "Der Antrag wird geprüft");

        tab.click(planned);

        assertThat(tab.popup().titleText()).isEqualTo("Antragsverfahren");
        assertThat(tab.popup().bodyText()).contains("Grund: Die Vorstufe (KI) kennzeichnet den Satz als Planung")
                .contains("Herkunft: Vorstufe „Sätze vereinfachen“ (KI) · Kennzeichen: Planung")
                .contains("Vereinfachter Satz: „Der Antrag wird geprüft“");
        assertThat(tab.popup().linkTexts()).containsExactly(StandingTab.OPEN_TEXT);

        tab.popup().clickLink(0);
        assertThat(fixture.jumps).containsExactly(new StandingTab.Target("wiki", "2", 4, 10));
    }

    @Test
    void anOutdatedPageNamesTheWordThatMadeIt() {
        StatusQuoEntry archived = new StatusQuoEntry(page("3", "Altes Verfahren"), statement("v9",
                ParsedStatements.Negation.AFFIRMED, ParsedStatements.Modality.NONE, ParsedStatements.Quality.PLAUSIBLE,
                ParsedStatements.Derivation.ORIGINAL), StatementKind.ACTION, "Die Stelle", "stelle", null,
                ParsedStatements.Quality.PLAUSIBLE, new StatementStanding(Standing.UEBERHOLT, StandingBasis.PAGE,
                StandingReason.PAGE_OUTDATED, new PageMark(PageMark.Kind.OUTDATED, PageMark.Where.LABEL, "archiv",
                "3")), null, false);

        assertThat(StandingTab.details(archived)).contains("Woher: Seite")
                .contains("Gegriffen hat: Wort „archiv“ im Label der Seite (überholt)");
    }

    @Test
    void decisionsWithoutStatementStandOnTop() {
        StandingDecision orphan = new StandingDecision(new PageKey("wiki", "1"), "wiki/1@old#s1.v1", Standing.GILT, 2,
                "angelo", Instant.parse("2026-11-16T10:00:00Z"), "angelo", Instant.parse("2026-11-16T11:00:00Z"));
        StatusQuoView base = view();
        StatusQuoView withStale = new StatusQuoView(base.entries(), base.states(), base.pages(), base.counts(),
                base.certainCounts(), List.of(orphan));

        StandingTab tab = new Fixture(withStale).shownTab();

        assertThat(tab.rows().get(0)).isEqualTo(new StandingTab.Group(StandingTab.STALE_GROUP,
                Comic.STANDING_UNCLEAR, 1));
        assertThat(tab.rows().get(1)).isEqualTo(new StandingTab.Stale(orphan));
    }

    @Test
    void theOriginalSentenceIsShownWithTheMarkBeforeItAndTheJumpMarksTheWholeSentence() {
        Fixture fixture = new Fixture(view());
        fixture.sentences.put("wiki/1@h#s1.v1", new SentenceText("Der Sachbearbeiter prüft\n den Antrag.", null, 20,
                56, 39, 44, false));
        StandingTab tab = fixture.shownTab();
        int line = indexOf(tab, "Der Sachbearbeiter prüft den Antrag.");

        assertThat(fixture.sentenceCalls).as("einmal für den sichtbaren Ausschnitt").containsExactly(5);
        tab.click(line);
        assertThat(tab.popup().bodyText()).contains("Originalsatz: „Der Sachbearbeiter prüft den Antrag.“");
        tab.doubleClick(line);
        assertThat(fixture.jumps).containsExactly(new StandingTab.Target("wiki", "1", 20, 56));
        // Die Suche findet danach auch Wörter, die nur im Originalsatz stehen.
        tab.searchField().setText("antrag.");
        assertThat(lines(tab)).extracting(entry -> entry.statement().id()).containsExactly("wiki/1@h#s1.v1");
    }

    @Test
    void withoutAFittingTextTheLineKeepsItsReplacementAndSaysWhy() {
        StatusQuoEntry current = view().entries().get(0);
        SentenceText stale = new SentenceText(null, null, -1, -1, 4, 10, true);

        assertThat(StandingTab.text(current, stale)).isEqualTo("Der Sachbearbeiter prüft");
        assertThat(StandingTab.details(current, stale))
                .contains("Originalsatz: nicht mehr ermittelbar, der Text der Seite hat sich geändert.");
        assertThat(StandingTab.text(current, null)).isEqualTo("Der Sachbearbeiter prüft");
    }

    @Test
    void theCountChipAlwaysShowsItsWordAndSaysWhatAClickDoes() {
        CountChip chip = new CountChip("gilt", Comic.STANDING_CURRENT);
        chip.count(12);

        assertThat(chip.getText()).isEqualTo("gilt 12");
        assertThat(chip.getToolTipText()).isEqualTo("Nur „gilt“ zeigen");
        chip.doClick();
        assertThat(chip.getToolTipText()).isEqualTo("Filter „gilt“ aufheben");
        assertThat(chip.getAccessibleContext().getAccessibleName()).startsWith("gilt 12");
    }

    // ---- Hilfen

    private static final class Fixture {
        final StatusQuoView view;
        final AtomicInteger reads = new AtomicInteger();
        final AtomicReference<Runnable> standingListener = new AtomicReference<>();
        final AtomicReference<String> status = new AtomicReference<>();
        final List<StandingTab.Target> jumps = new ArrayList<>();
        final Map<String, SentenceText> sentences = new java.util.HashMap<>();
        final List<Integer> sentenceCalls = new ArrayList<>();
        final StandingTab tab;

        Fixture(StatusQuoView view) {
            this.view = view;
            StandingTab.Actions actions = new StandingTab.Actions() {
                @Override
                public StatusQuoView view(ExportPanel.Selection selection, BooleanSupplier cancelled) {
                    reads.incrementAndGet();
                    return Fixture.this.view;
                }

                @Override
                public Subscription subscribeStanding(Runnable listener) {
                    standingListener.set(listener);
                    return Subscription.NONE;
                }

                @Override
                public Subscription subscribeJobs(JobListener listener) {
                    return Subscription.NONE;
                }

                @Override
                public Map<String, SentenceText> sentences(java.util.Collection<StatusQuoEntry> entries) {
                    sentenceCalls.add(entries.size());
                    Map<String, SentenceText> found = new java.util.HashMap<>();
                    for (StatusQuoEntry entry : entries) {
                        SentenceText text = Fixture.this.sentences.get(entry.statement().id());
                        if (text != null) {
                            found.put(entry.statement().id(), text);
                        }
                    }
                    return found;
                }
            };
            tab = new StandingTab(() -> actions, new StandingTab.Runner() {
                @Override
                public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
                    T result;
                    try {
                        result = work.call();
                    } catch (Exception e) {
                        onFailure.accept(e);
                        return;
                    }
                    onSuccess.accept(result);
                }
            });
            tab.onStatus(status::set);
            tab.onJump(jumps::add);
        }

        StandingTab shownTab() {
            tab.shown(true);
            tab.selection(SPACE);
            return tab;
        }
    }

    private static void flush() {
        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> { });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> groups(StandingTab tab) {
        return tab.rows().stream().filter(StandingTab.Group.class::isInstance).map(StandingTab.Group.class::cast)
                .map(group -> group.title() + " · " + group.count()).toList();
    }

    private static List<StatusQuoEntry> lines(StandingTab tab) {
        return tab.rows().stream().filter(StandingTab.Line.class::isInstance).map(StandingTab.Line.class::cast)
                .map(StandingTab.Line::entry).toList();
    }

    private static int indexOf(StandingTab tab, String text) {
        List<StandingTab.Row> rows = tab.rows();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) instanceof StandingTab.Line line && (StandingTab.text(line.entry()).equals(text)
                    || line.entry().statement().id().equals("wiki/1@h#s1.v1") && text.startsWith("Der Sachbearbeiter prüft "))) {
                return i;
            }
        }
        throw new AssertionError("nicht gefunden: " + text);
    }

    static StatusQuoView view() {
        StatusQuoEntry current = entry("v1", Standing.GILT, StatementKind.ACTION, "Der Sachbearbeiter", "prüft",
                ParsedStatements.Negation.AFFIRMED, ParsedStatements.Modality.NONE, ParsedStatements.Quality.PLAUSIBLE);
        StatusQuoEntry role = entry("v2", Standing.GILT, StatementKind.STATE, "Sachbearbeiter", "ist zuständig",
                ParsedStatements.Negation.AFFIRMED, ParsedStatements.Modality.NONE, ParsedStatements.Quality.PLAUSIBLE);
        StatusQuoEntry planned = new StatusQuoEntry(page("2", "Antragsverfahren"), statement("v3",
                ParsedStatements.Negation.AFFIRMED, ParsedStatements.Modality.NONE, ParsedStatements.Quality.PLAUSIBLE,
                ParsedStatements.Derivation.vorstufe(ParsedStatements.StageFlag.PLANNING, "Der Antrag wird geprüft")),
                StatementKind.ACTION, "Der Antrag", "antrag", null, ParsedStatements.Quality.PLAUSIBLE,
                new StatementStanding(Standing.GEPLANT, StandingBasis.STAGE_FLAG, StandingReason.STAGE_PLANNING),
                null, false);
        StatusQuoEntry unclear = entry("v4", Standing.UNKLAR, StatementKind.ACTION, "Jemand", "entscheidet",
                ParsedStatements.Negation.UNKNOWN, ParsedStatements.Modality.UNKNOWN, ParsedStatements.Quality.UNCERTAIN);
        StatusQuoEntry outdated = entry("v5", Standing.UEBERHOLT, StatementKind.ACTION, "Die Poststelle", "scannte",
                ParsedStatements.Negation.AFFIRMED, ParsedStatements.Modality.NONE, ParsedStatements.Quality.PLAUSIBLE);
        Map<Standing, Integer> counts = new EnumMap<>(Standing.class);
        counts.put(Standing.GILT, 2);
        counts.put(Standing.GEPLANT, 1);
        counts.put(Standing.UNKLAR, 1);
        counts.put(Standing.UEBERHOLT, 1);
        PageStanding one = new PageStanding(page("1", "Seite 1"), counts, counts, Standing.GILT,
                StandingBasis.STATEMENTS, StandingReason.MAJORITY, List.of(), null);
        PageStanding two = new PageStanding(page("2", "Antragsverfahren"), Map.of(), Map.of(), Standing.GEPLANT,
                StandingBasis.STATEMENTS, StandingReason.MAJORITY, List.of(), null);
        return new StatusQuoView(List.of(current, role, planned, unclear, outdated),
                List.of(new BearerStates("Sachbearbeiter", List.of(role))), List.of(one, two), counts, counts,
                List.of());
    }

    static StatusQuoEntry entry(String id, Standing standing, StatementKind kind, String subject, String predicate,
                                ParsedStatements.Negation negation, ParsedStatements.Modality modality,
                                ParsedStatements.Quality quality) {
        ParsedStatements.Statement statement = new ParsedStatements.Statement("wiki/1@h#s1." + id, 0, 4, 10, predicate,
                predicate, ParsedStatements.Voice.ACTIVE, ParsedStatements.Clause.MAIN, true, List.of(), Set.of(), 0,
                quality, List.of(), ParsedStatements.Review.UNREVIEWED,
                new ParsedStatements.Features(negation, modality, ParsedStatements.Temporality.GILT));
        return new StatusQuoEntry(page("1", "Seite 1"), statement, kind, subject, subject.toLowerCase(), null, quality,
                new StatementStanding(standing, StandingBasis.FEATURES, StandingReason.TEMPORALITY_GILT), null, false);
    }

    private static ParsedStatements.Statement statement(String id, ParsedStatements.Negation negation,
                                                        ParsedStatements.Modality modality,
                                                        ParsedStatements.Quality quality,
                                                        ParsedStatements.Derivation derivation) {
        return new ParsedStatements.Statement("wiki/2@h#s1." + id, 0, 4, 10, "geprüft", "prüfen",
                ParsedStatements.Voice.PASSIVE, ParsedStatements.Clause.MAIN, true, List.of(), Set.of(), 0, quality,
                List.of(), ParsedStatements.Review.UNREVIEWED,
                new ParsedStatements.Features(negation, modality, ParsedStatements.Temporality.GILT), derivation);
    }

    private static KnowledgeGraph.PageRef page(String id, String title) {
        return new KnowledgeGraph.PageRef(new PageKey("wiki", id), "DEV", title, PageStatements.State.CURRENT, null,
                null);
    }
}
