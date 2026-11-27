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
import com.aresstack.spaceharvester.application.standing.ReadingList;
import com.aresstack.spaceharvester.application.standing.ReadingReason;
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

    private static final ExportPanel.Selection SPACE = new ExportPanel.Selection.Node("wiki", "DEV", null, "DEV");

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
    void statementsFromStaleAnalysesStayHiddenUntilTheChipShowsThem() {
        StatusQuoView base = view();
        List<StatusQuoEntry> entries = new ArrayList<>(base.entries());
        entries.set(0, stale(entries.get(0)));
        entries.set(4, stale(entries.get(4)));
        StatusQuoView view = new StatusQuoView(entries, base.states(), base.pages(), base.counts(),
                base.certainCounts(), List.of());
        Fixture fixture = new Fixture(view);
        StandingTab tab = fixture.shownTab();

        // Angelo 17.11.2026: alte Analysen (MaltParser) mischen sich nicht unter die aktuellen.
        assertThat(lines(tab)).hasSize(3).noneMatch(StatusQuoEntry::analysisStale);
        assertThat(tab.staleChip().isVisible()).isTrue();
        assertThat(tab.staleChip().getText()).isEqualTo("veraltet 2");
        assertThat(tab.staleChip().getToolTipText()).isEqualTo(StandingTab.STALE_TIP);
        assertThat(fixture.status.get()).isEqualTo("3 Aussagen auf 2 Seiten");

        assertThat(groups(tab)).containsExactly("gilt · 1", "geplant · 1", "unklar · 1");

        tab.staleChip().doClick();
        assertThat(lines(tab)).hasSize(5);
        assertThat(tab.staleChip().getToolTipText()).isEqualTo(StandingTab.STALE_HIDE_TIP);
        assertThat(fixture.status.get()).isEqualTo("5 Aussagen auf 2 Seiten");
        // AT: eine eigene Gruppe am Ende; die Gruppen der Stände zählen weiter nur Aktuelles.
        assertThat(groups(tab)).containsExactly("gilt · 1", "geplant · 1", "unklar · 1", "veraltet · 2");
        assertThat(lines(tab).subList(3, 5)).allMatch(StatusQuoEntry::analysisStale);
        // Ein Stand-Chip wirkt nicht auf die veraltete Gruppe, die Suche schon.
        tab.chip(Standing.GEPLANT).doClick();
        assertThat(groups(tab)).containsExactly("geplant · 1", "veraltet · 2");
        tab.chip(Standing.GEPLANT).doClick();
        tab.searchField().setText("Poststelle");
        assertThat(groups(tab)).containsExactly("veraltet · 1");
    }

    @Test
    void theTermsOfTheSelectionLeadTheSameTabRowAndTheStandIsOnlyReadOnItsOwnTabs() {
        Fixture fixture = new Fixture(view());
        List<Boolean> shown = new ArrayList<>();
        fixture.tab.terms(new javax.swing.JPanel(), shown::add);
        StandingTab tab = fixture.shownTab();
        // Eine Zeile, „Begriffe“ vorn und gewählt; rechts nichts vom Stand, gelesen wird der Stand noch nicht.
        assertThat(tab.tabs().names()).containsExactly(StandingTab.TERMS, StandingTab.STATEMENTS,
                StandingTab.READING, StandingTab.CONFLICTS);
        assertThat(tab.tabs().selected()).isEqualTo(StandingTab.TERMS);
        assertThat(shown).last().isEqualTo(true);
        assertThat(tab.searchField().getParent().isVisible()).isFalse();
        assertThat(tab.bearerButton().isVisible()).isFalse();
        assertThat(tab.deleteAnalysesButton().isVisible()).isFalse();
        assertThat(fixture.reads).hasValue(0);
        // „Aussagen“: die Begriffe sind weg, der Stand wird gelesen, Werkzeuge und Papierkorb stehen rechts.
        tab.tabs().select(StandingTab.STATEMENTS);
        assertThat(shown).last().isEqualTo(false);
        assertThat(fixture.reads).hasValue(1);
        assertThat(tab.bearerButton().isVisible()).isTrue();
        assertThat(tab.deleteAnalysesButton().isVisible()).isTrue();
        tab.shown(false);
        tab.tabs().select(StandingTab.TERMS);
        assertThat(shown).as("unsichtbar bleiben die Begriffe aus").last().isEqualTo(false);
    }

    @Test
    void otherViewsHangBehindTheStandWithTheirOwnToolsAndAreToldWhenTheyAreSeen() {
        Fixture fixture = new Fixture(view());
        List<Boolean> glossary = new ArrayList<>();
        javax.swing.JButton add = new javax.swing.JButton("+");
        fixture.tab.tab(StandingTab.GLOSSARY, new javax.swing.JPanel(), glossary::add, add);
        StandingTab tab = fixture.shownTab();
        assertThat(tab.tabs().names()).containsExactly(StandingTab.STATEMENTS, StandingTab.READING,
                StandingTab.CONFLICTS, StandingTab.GLOSSARY);
        assertThat(add.isVisible()).isFalse();
        int reads = fixture.reads.get();
        tab.tabs().select(StandingTab.GLOSSARY);
        assertThat(glossary).last().isEqualTo(true);
        assertThat(add.isVisible()).isTrue();
        assertThat(tab.deleteAnalysesButton().isVisible()).isFalse();
        assertThat(tab.bearerButton().isVisible()).isFalse();
        assertThat(tab.ownTabSelected()).isFalse();
        tab.tabs().select(StandingTab.CONFLICTS);
        assertThat(glossary).last().isEqualTo(false);
        assertThat(add.isVisible()).isFalse();
        assertThat(tab.deleteAnalysesButton().isVisible()).isTrue();
        assertThat(tab.ownTabSelected()).isTrue();
        assertThat(fixture.reads.get()).isGreaterThanOrEqualTo(reads);
    }

    @Test
    void anEmptyListSaysWhyInItsArea() {
        StatusQuoView base = view();
        StandingTab none = new Fixture(new StatusQuoView(List.of(), List.of(), List.of(), Map.of(), Map.of(),
                List.of())).shownTab();
        assertThat(none.placeholder()).isEqualTo("Keine analysierten Aussagen in der Auswahl. "
                + StandingTab.HOW_TO_ANALYZE);
        assertThat(none.staleChip().isVisible()).isFalse();
        none.tabs().select(StandingTab.CONFLICTS);
        assertThat(none.conflictPlaceholder()).isEqualTo("Keine möglichen Widersprüche");

        StandingTab onlyStale = new Fixture(new StatusQuoView(base.entries().stream().map(StandingTabTest::stale)
                .toList(), List.of(), base.pages(), Map.of(), Map.of(), List.of())).shownTab();
        assertThat(onlyStale.placeholder()).isEqualTo("Keine aktuellen Aussagen. 5 stammen aus veralteten Analysen.");
        onlyStale.staleChip().doClick();
        assertThat(onlyStale.placeholder()).isNull();

        StandingTab searched = new Fixture(base).shownTab();
        searched.searchField().setText("gibtesnicht");
        assertThat(searched.placeholder()).isEqualTo("Keine Treffer");
        assertThat(StandingTab.empty(3, 1, false)).isEqualTo("Keine Treffer");
        assertThat(StandingTab.empty(1, 1, false)).isEqualTo("Keine aktuellen Aussagen. Eine stammt aus veralteten "
                + "Analysen.");
    }

    @Test
    void theTrashDeletesTheAnalysesOfTheChosenNodeAfterYesAndRereads() {
        Fixture fixture = new Fixture(view());
        StandingTab tab = fixture.shownTab();
        List<String> questions = new ArrayList<>();
        tab.confirmDeleteWith(question -> {
            questions.add(question);
            return false;
        });
        assertThat(tab.deleteAnalysesButton().getToolTipText()).isEqualTo("Aussagen-Analysen löschen");

        // Nein: nichts geschieht.
        tab.deleteAnalysesButton().doClick();
        assertThat(questions).containsExactly("Wirklich die Aussagen-Analysen von „DEV“ samt Unterseiten löschen? "
                + "Die Seiten bleiben.");
        assertThat(fixture.deletions).isEmpty();

        // Ja: der gewählte Knoten geht an den Core, der Beobachter lässt neu lesen, die Zahl steht in der Statuszeile.
        tab.confirmDeleteWith(question -> true);
        fixture.deleted = 4;
        int reads = fixture.reads.get();
        tab.deleteAnalysesButton().doClick();
        flush();
        assertThat(fixture.deletions).containsExactly(SPACE);
        assertThat(fixture.reads.get()).isGreaterThan(reads);
        assertThat(lines(tab)).isEmpty();
        assertThat(fixture.status.get()).isEqualTo("4 Aussagen-Analysen gelöscht · Keine analysierten Aussagen in "
                + "der Auswahl");
        assertThat(tab.placeholder()).isEqualTo("Keine analysierten Aussagen in der Auswahl. "
                + StandingTab.HOW_TO_ANALYZE);

        // Nichts mehr da: kein Neulesen, eine Zeile dazu.
        fixture.deleted = 0;
        tab.deleteAnalysesButton().doClick();
        assertThat(fixture.status.get()).startsWith("Keine Aussagen-Analysen unter „DEV“");

        // ▾ „Alles löschen“: eigene Rückfrage mit „wirklich“, danach Analysen und KI-Urteile, Meldung nennt beides.
        List<String> asked = new ArrayList<>();
        tab.confirmDeleteWith(question -> asked.add(question));
        fixture.deleted = 2;
        javax.swing.JMenuItem item = (javax.swing.JMenuItem) tab.moreDeleteMenu().getComponent(0);
        assertThat(item.isEnabled()).isTrue();
        item.doClick();
        flush();
        assertThat(asked).singleElement().asString().startsWith("Wirklich alles ").contains("KI-Urteile");
        assertThat(fixture.everything).containsExactly(SPACE);
        assertThat(fixture.status.get()).startsWith("2 Aussagen-Analysen gelöscht · KI-Urteile aus „Aussagen prüfen“ "
                + "gelöscht");

        assertThat(StandingTab.deleteQuestion(ExportPanel.Selection.ALL))
                .isEqualTo("Wirklich die Aussagen-Analysen aller Wissensquellen löschen? Die Seiten bleiben.");
        assertThat(StandingTab.deleteQuestion(new ExportPanel.Selection.Collection("f1", "Betrieb", List.of(), true)))
                .isEqualTo("Wirklich die Aussagen-Analysen der Favoriten in „Betrieb“ samt Unterseiten löschen? "
                        + "Die Seiten bleiben.");
    }

    private static StatusQuoEntry stale(StatusQuoEntry e) {
        return new StatusQuoEntry(e.page(), e.statement(), e.kind(), e.subject(), e.subjectKey(), e.situation(),
                e.quality(), e.standing(), e.decision(), true);
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
                .contains("Herkunft: Vorstufe „Sätze vereinfachen“ (KI) · Kennzeichen der Vorstufe: Planung")
                .contains("Vereinfachter Satz: „Der Antrag wird geprüft“");
        assertThat(tab.popup().linkTexts()).containsExactly(StandingTab.OPEN_TEXT);

        tab.popup().clickLink(0);
        assertThat(fixture.jumps).containsExactly(new StandingTab.Target("wiki", "2", 4, 10));
    }

    @Test
    void theOverlayShowsTheVerdictOfTheModelInTheWordsOfTheStatementsTab() {
        ParsedStatements.Statement judged = statement("v7", ParsedStatements.Negation.AFFIRMED,
                ParsedStatements.Modality.NONE, ParsedStatements.Quality.PLAUSIBLE,
                ParsedStatements.Derivation.ORIGINAL).withCheck(new ParsedStatements.Check(
                ParsedStatements.Verdict.FALSCH, "1", false));
        StatusQuoEntry entry = new StatusQuoEntry(page("2", "Antragsverfahren"), judged, StatementKind.ACTION,
                "Der Antrag", "antrag", null, ParsedStatements.Quality.UNCERTAIN, new StatementStanding(
                Standing.UNKLAR, StandingBasis.FEATURES, StandingReason.NO_FEATURES), null, false);
        ParsedStatements.Statement correction = statement("v8", ParsedStatements.Negation.AFFIRMED,
                ParsedStatements.Modality.NONE, ParsedStatements.Quality.PLAUSIBLE,
                ParsedStatements.Derivation.modell("Der Antrag wird vom Sachbearbeiter geprüft.", "wiki/2@h#s1.v7",
                        ParsedStatements.Origin.ORIGINAL));
        StatusQuoEntry corrected = new StatusQuoEntry(page("2", "Antragsverfahren"), correction,
                StatementKind.ACTION, "Der Antrag", "antrag", null, ParsedStatements.Quality.PLAUSIBLE,
                new StatementStanding(Standing.GILT, StandingBasis.FEATURES, StandingReason.TEMPORALITY_GILT), null,
                false);

        assertThat(StandingTab.details(entry))
                .contains(CheckTexts.VERDICT_LABEL + ": falsch")
                .contains(CheckTexts.QUALITY_LABEL + ": unsicher (vom Parser: plausibel)")
                .contains("Herkunft: Originalsatz");
        assertThat(StandingTab.details(corrected))
                .contains(CheckTexts.ORIGIN_LABEL + ": " + CheckTexts.ORIGIN_MODEL)
                .contains(CheckTexts.MODEL_SENTENCE_LABEL + ": „Der Antrag wird vom Sachbearbeiter geprüft.“")
                .doesNotContain(CheckTexts.VERDICT_LABEL);
        // Die Herkunft steht schon in der grauen Zeile unter dem Satz, als Wort.
        assertThat(StandingTab.meta(corrected)).isEqualTo("Antragsverfahren · Korrektur des Modells (KI)");
        assertThat(StandingTab.meta(entry)).isEqualTo("Antragsverfahren · Vorschlag");
        StatusQuoEntry staged = new StatusQuoEntry(page("2", "Antragsverfahren"), statement("v6",
                ParsedStatements.Negation.AFFIRMED, ParsedStatements.Modality.NONE, ParsedStatements.Quality.PLAUSIBLE,
                ParsedStatements.Derivation.vorstufe(ParsedStatements.StageFlag.FACT, "Der Antrag wird geprüft")),
                StatementKind.ACTION, "Der Antrag", "antrag", null, ParsedStatements.Quality.PLAUSIBLE,
                new StatementStanding(Standing.GILT, StandingBasis.STAGE_FLAG, StandingReason.STAGE_FACT), null, false);
        assertThat(StandingTab.meta(staged)).startsWith("Antragsverfahren · Vorstufe (KI)");
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
    }

    @Test
    void theSameSearchGivesTheSameLinesBeforeAndAfterScrolling() {
        // „Antrag“ steht nur im Originalsatz und als Beteiligter in der Sachlage: gefunden über die Sachlage, die an
        // jedem Eintrag liegt, nie über den nachgeladenen Satz.
        StatusQuoView base = view();
        StatusQuoEntry first = base.entries().get(0);
        StatusQuoEntry withObject = new StatusQuoEntry(first.page(), first.statement(), first.kind(), first.subject(),
                first.subjectKey(), new com.aresstack.spaceharvester.application.standing.Situation(first.kind(),
                "sachbearbeiter", "prüfen", List.of(new com.aresstack.spaceharvester.application.standing.Situation
                .Participant(ParsedStatements.Role.AFFECTED_OBJECT, null, "antrag"))), first.quality(),
                first.standing(), null, false);
        List<StatusQuoEntry> entries = new ArrayList<>(base.entries());
        entries.set(0, withObject);
        StatusQuoView view = new StatusQuoView(entries, base.states(), base.pages(), base.counts(),
                base.certainCounts(), List.of());
        Fixture before = new Fixture(view);
        StandingTab unscrolled = before.tab;
        unscrolled.searchField().setText("nachweis");
        unscrolled.shown(true);
        unscrolled.selection(SPACE);
        assertThat(lines(unscrolled)).isEmpty();
        unscrolled.searchField().setText("antrag");
        List<String> found = lines(unscrolled).stream().map(entry -> entry.statement().id()).toList();

        Fixture after = new Fixture(view);
        after.sentences.put("wiki/1@h#s1.v1", new SentenceText("Der Sachbearbeiter prüft den Antrag und den Nachweis.",
                null, 0, 50, 19, 24, false));
        StandingTab scrolled = after.shownTab();
        scrolled.searchField().setText("nachweis");
        assertThat(lines(scrolled)).as("nur im Originalsatz: nicht gesucht").isEmpty();
        scrolled.searchField().setText("antrag");

        assertThat(lines(scrolled).stream().map(entry -> entry.statement().id()).toList()).isEqualTo(found)
                .containsExactly("wiki/1@h#s1.v1", "wiki/2@h#s1.v3");
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
    void theOverlaySetsTheStandingByHandWithFourWordPills() {
        Fixture fixture = new Fixture(view());
        StandingTab tab = fixture.shownTab();
        int first = indexOf(tab, "Der Sachbearbeiter prüft");

        tab.click(first);
        assertThat(Standing.values()).allSatisfy(standing ->
                assertThat(tab.decisionChip(standing).getText()).isEqualTo(StandingTab.word(standing)));
        assertThat(tab.decisionChip(Standing.GILT).isSelected()).as("ohne Entscheidung keine gefüllt").isFalse();
        assertThat(tab.overlayButton(StandingTab.CLEAR_TIP).isEnabled()).as("nichts zu löschen").isFalse();

        tab.decisionChip(Standing.GEPLANT).doClick();

        assertThat(fixture.writes).containsExactly("decide 1 wiki/1@h#s1.v1 GEPLANT 0");
        assertThat(fixture.status.get()).isEqualTo("Stand von Hand gesetzt: „geplant“");
    }

    @Test
    void onALockedPageThePillsAndTheTrashAreDisabledWithTheReason() {
        StatusQuoView base = view();
        Fixture fixture = new Fixture(new StatusQuoView(base.entries(), base.states(), base.pages(), base.counts(),
                base.certainCounts(), base.staleDecisions(), base.staleEntries(), base.rejectedDecisions(),
                java.util.Set.of(new PageKey("wiki", "1"))));
        StandingTab tab = fixture.shownTab();
        tab.click(indexOf(tab, "Der Sachbearbeiter prüft"));

        assertThat(Standing.values()).allSatisfy(standing -> {
            assertThat(tab.decisionChip(standing).isEnabled()).isFalse();
            assertThat(tab.decisionChip(standing).getToolTipText()).isEqualTo(StandingTab.LOCKED_PAGE);
        });
        assertThat(tab.overlayButton(StandingTab.LOCKED_PAGE).isEnabled()).isFalse();
        assertThat(tab.overlayButton(StandingTab.HISTORY_TIP).isEnabled()).as("das Protokoll bleibt lesbar").isTrue();
        tab.decisionChip(Standing.GEPLANT).doClick();
        assertThat(fixture.writes).isEmpty();
        assertThat(StandingTab.LOCKED_PAGE).isEqualTo("Seite ist gesperrt; der Stand lässt sich nicht ändern");
    }

    @Test
    void aPageLockedMeanwhileIsRefusedByTheCoreRereadsAndSaysSo() {
        Fixture fixture = new Fixture(view());
        fixture.lockedMeanwhile = true;
        StandingTab tab = fixture.shownTab();
        tab.click(indexOf(tab, "Der Sachbearbeiter prüft"));
        assertThat(tab.decisionChip(Standing.UEBERHOLT).isEnabled()).as("laut Sicht noch frei").isTrue();

        // Zwischen Lesen und Klick gesperrt: der Core lehnt ab, die Sicht wird neu gelesen und sperrt die Pillen.
        StatusQuoView base = view();
        fixture.view = new StatusQuoView(base.entries(), base.states(), base.pages(), base.counts(),
                base.certainCounts(), base.staleDecisions(), base.staleEntries(), base.rejectedDecisions(),
                java.util.Set.of(new PageKey("wiki", "1")));
        tab.decisionChip(Standing.UEBERHOLT).doClick();

        assertThat(fixture.writes).hasSize(1);
        assertThat(fixture.reads.get()).isEqualTo(2);
        assertThat(fixture.status.get()).as("die Meldung bleibt nach dem neuen Lesen vor der Zählung")
                .isEqualTo(StandingTab.LOCKED_REFUSED + " · 5 Aussagen auf 2 Seiten");
        assertThat(StandingTab.LOCKED_REFUSED).endsWith("Die Seite ist gesperrt.");
        tab.click(indexOf(tab, "Der Sachbearbeiter prüft"));
        assertThat(tab.decisionChip(Standing.UEBERHOLT).isEnabled()).isFalse();
    }

    @Test
    void aPageLockedMeanwhileKeepsTheRefusalInReadingFirstAfterTheReread() {
        Fixture fixture = new Fixture(view());
        fixture.lockedMeanwhile = true;
        StandingTab tab = fixture.shownTab();
        tab.tabs().select(StandingTab.READING);
        tab.reading().clickRow(0, false);
        StatusQuoView base = view();
        fixture.view = new StatusQuoView(base.entries(), base.states(), base.pages(), base.counts(),
                base.certainCounts(), base.staleDecisions(), base.staleEntries(), base.rejectedDecisions(),
                java.util.Set.of(new PageKey("wiki", "1")));

        tab.decisionChip(Standing.UEBERHOLT).doClick();

        assertThat(fixture.writes).containsExactly("decide 1 null UEBERHOLT 0");
        assertThat(fixture.reads.get()).isEqualTo(2);
        assertThat(fixture.status.get()).as("die Zählung von „Zuerst lesen“ überschreibt die Ablehnung nicht")
                .isEqualTo(StandingTab.LOCKED_REFUSED + " · 2 Seiten nach Lesewert");
    }

    @Test
    void aConflictRereadsAndSaysSo() {
        Fixture fixture = new Fixture(view());
        fixture.conflict = true;
        StandingTab tab = fixture.shownTab();
        tab.click(indexOf(tab, "Der Sachbearbeiter prüft"));

        tab.decisionChip(Standing.UEBERHOLT).doClick();

        assertThat(fixture.reads.get()).isEqualTo(2);
        assertThat(fixture.status.get()).isEqualTo("5 Aussagen auf 2 Seiten");
        assertThat(fixture.writes).hasSize(1);
    }

    @Test
    void aDecisionIsClearedOnlyAfterYes() {
        StandingDecision decided = new StandingDecision(new PageKey("wiki", "1"), "wiki/1@h#s1.v1", Standing.UNKLAR,
                3, "angelo", Instant.parse("2026-11-16T10:00:00Z"), "angelo", Instant.parse("2026-11-16T11:00:00Z"));
        StatusQuoView base = view();
        StatusQuoEntry first = base.entries().get(0);
        List<StatusQuoEntry> entries = new ArrayList<>(base.entries());
        entries.set(0, new StatusQuoEntry(first.page(), first.statement(), first.kind(), first.subject(),
                first.subjectKey(), null, first.quality(), new StatementStanding(Standing.UNKLAR,
                StandingBasis.DECISION, StandingReason.DECISION), decided, false));
        Fixture fixture = new Fixture(new StatusQuoView(entries, base.states(), base.pages(), base.counts(),
                base.certainCounts(), List.of()));
        StandingTab tab = fixture.shownTab();
        int line = -1;
        for (int i = 0; i < tab.rows().size(); i++) {
            if (tab.rows().get(i) instanceof StandingTab.Line l && l.entry().decision() != null) {
                line = i;
            }
        }
        tab.click(line);
        assertThat(tab.decisionChip(Standing.UNKLAR).isSelected()).isTrue();
        assertThat(tab.popup().bodyText()).contains("Von Hand: unklar · angelo");

        tab.confirmClear(() -> false);
        tab.overlayButton(StandingTab.CLEAR_TIP).doClick();
        assertThat(fixture.writes).isEmpty();

        tab.click(line);
        tab.confirmClear(() -> true);
        tab.overlayButton(StandingTab.CLEAR_TIP).doClick();
        assertThat(fixture.writes).containsExactly("clear 1 wiki/1@h#s1.v1 3");
        assertThat(StandingTab.CLEAR_QUESTION).startsWith("Wirklich");
    }

    @Test
    void theHistoryReplacesTheDetailsInTheSameOverlay() {
        StandingTab tab = new Fixture(view()).shownTab();
        tab.click(indexOf(tab, "Der Sachbearbeiter prüft"));

        tab.overlayButton(StandingTab.HISTORY_TIP).doClick();

        assertThat(tab.popup().titleText()).isEqualTo("Protokoll");
        assertThat(tab.popup().bodyText()).contains("angelo · „geplant“ gesetzt");
    }

    @Test
    void aDecisionWithoutStatementNamesThePageByTitle() {
        StandingDecision known = new StandingDecision(new PageKey("wiki", "2"), "wiki/2@old#s1.v1", Standing.GILT, 1,
                "angelo", Instant.parse("2026-11-16T10:00:00Z"), "angelo", Instant.parse("2026-11-16T11:00:00Z"));
        StandingDecision gone = new StandingDecision(new PageKey("wiki", "77"), "wiki/77@old#s1.v1", Standing.GILT, 1,
                "angelo", Instant.parse("2026-11-16T10:00:00Z"), "angelo", Instant.parse("2026-11-16T11:00:00Z"));
        StatusQuoView base = view();
        StandingTab tab = new Fixture(new StatusQuoView(base.entries(), base.states(), base.pages(), base.counts(),
                base.certainCounts(), List.of(known, gone))).shownTab();

        tab.click(1);
        assertThat(tab.popup().bodyText()).contains("Seite: Antragsverfahren");
        assertThat(tab.overlayButton(StandingTab.CLEAR_TIP).isEnabled()).isTrue();
        tab.click(2);
        assertThat(tab.popup().bodyText()).as("Seite nicht mehr im Bestand: ID").contains("Seite: 77");
    }

    @Test
    void anEntryCarryingOnlyAReviewIsShownWithoutStandingAndCannotBeClearedAsStanding() {
        // ADR 0032 W3: ein Eintrag kann nur die Prüfung tragen; der Stand ist dann null.
        Instant at = Instant.parse("2026-11-16T10:00:00Z");
        StandingDecision reviewed = new StandingDecision(new PageKey("wiki", "2"), "wiki/2@old#s1.v1",
                com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice.PARSER, null,
                ParsedStatements.Review.REJECTED, 2, "angelo", at, "angelo", at);
        StatusQuoView base = view();
        StandingTab tab = new Fixture(new StatusQuoView(base.entries(), base.states(), base.pages(), base.counts(),
                base.certainCounts(), List.of(reviewed))).shownTab();

        assertThat(tab.rows().get(1)).isEqualTo(new StandingTab.Stale(reviewed));
        tab.click(1);
        assertThat(tab.popup().bodyText()).contains("Von Hand „verworfen“");
        assertThat(tab.overlayButton(StandingTab.CLEAR_TIP).isEnabled()).isFalse();
        com.aresstack.spaceharvester.application.standing.StandingDecision.Target target = reviewed.target();
        assertThat(StandingTab.history(List.of(
                new com.aresstack.spaceharvester.application.standing.StandingDecisionStore.AuditEntry(
                        target, "review", "angelo", at, null,
                        ParsedStatements.Review.REJECTED, 1),
                new com.aresstack.spaceharvester.application.standing.StandingDecisionStore.AuditEntry(
                        target, "unreview", "angelo", at, null,
                        ParsedStatements.Review.REJECTED, 1))))
                .contains("„verworfen“ gesetzt").contains("Prüfung zurückgenommen (war „verworfen“)");
    }

    @Test
    void reloadedSettingsAreReadAgainWhenShown() {
        Fixture fixture = new Fixture(view());
        StandingTab tab = fixture.shownTab();
        tab.shown(false);

        tab.settingsChanged();
        assertThat(fixture.reads.get()).as("verdeckt: erst beim Zeigen").isEqualTo(1);
        tab.shown(true);

        assertThat(fixture.reads.get()).isEqualTo(2);
    }

    @Test
    void aFailedSubscriptionIsNamedInTheStatusLine() {
        Fixture fixture = new Fixture(view());
        fixture.failSubscribe = true;

        fixture.shownTab();

        assertThat(fixture.status.get()).isEqualTo("5 Aussagen auf 2 Seiten · " + StandingTab.NOT_WATCHING);
    }

    @Test
    void aMissingRightIsSaidInWords() {
        Fixture fixture = new Fixture(view());
        fixture.denied = true;
        StandingTab tab = fixture.shownTab();
        tab.click(indexOf(tab, "Der Sachbearbeiter prüft"));

        tab.decisionChip(Standing.UEBERHOLT).doClick();

        assertThat(fixture.status.get()).isEqualTo(StandingTab.NO_RIGHT_TO_DECIDE);
    }

    @Test
    void withoutASubscriptionTheViewIsReadAgainAfterOwnWrites() {
        Fixture fixture = new Fixture(view());
        fixture.failSubscribe = true;
        StandingTab tab = fixture.shownTab();
        tab.click(indexOf(tab, "Der Sachbearbeiter prüft"));

        tab.decisionChip(Standing.UEBERHOLT).doClick();

        assertThat(fixture.reads.get()).isEqualTo(2);
    }

    @Test
    void theEndOfAnAnalysisRereadsButARunningOneOrASyncDoesNot() {
        assertThat(StandingTab.rereads(job(com.aresstack.spaceharvester.application.jobs.JobType.STATEMENT_ANALYSIS,
                com.aresstack.spaceharvester.application.jobs.JobState.COMPLETED))).isTrue();
        assertThat(StandingTab.rereads(job(com.aresstack.spaceharvester.application.jobs.JobType.STATEMENT_ANALYSIS,
                com.aresstack.spaceharvester.application.jobs.JobState.RUNNING))).isFalse();
        assertThat(StandingTab.rereads(job(com.aresstack.spaceharvester.application.jobs.JobType.STATEMENT_CHECK,
                com.aresstack.spaceharvester.application.jobs.JobState.COMPLETED))).as("Urteile ändern die Stufe")
                .isTrue();
        assertThat(StandingTab.rereads(job(com.aresstack.spaceharvester.application.jobs.JobType.SYNC,
                com.aresstack.spaceharvester.application.jobs.JobState.COMPLETED))).isFalse();
    }

    private static com.aresstack.spaceharvester.application.jobs.JobStatus job(
            com.aresstack.spaceharvester.application.jobs.JobType type,
            com.aresstack.spaceharvester.application.jobs.JobState state) {
        return new com.aresstack.spaceharvester.application.jobs.JobStatus("j1", type, "wiki", "DEV", "local", state,
                new com.aresstack.spaceharvester.application.jobs.JobProgress(1, 1, 1, 0), null, false,
                Instant.parse("2026-11-17T05:00:00Z"), null, null, null, null, Map.of(), false, false, null, null);
    }

    @Test
    void possibleContradictionsShowBothStatementsAndReplacementsStandApart() {
        com.aresstack.spaceharvester.application.standing.Situation same =
                new com.aresstack.spaceharvester.application.standing.Situation(StatementKind.ACTION, "sachbearbeiter",
                        "prüfen", List.of(new com.aresstack.spaceharvester.application.standing.Situation.Participant(
                        ParsedStatements.Role.AFFECTED_OBJECT, null, "antrag")));
        StatusQuoEntry affirmed = compared("1", "v1", same, Standing.GILT, ParsedStatements.Negation.AFFIRMED);
        StatusQuoEntry negated = compared("2", "v1", same, Standing.GILT, ParsedStatements.Negation.NEGATED);
        StatusQuoEntry earlier = compared("3", "v1", same, Standing.UEBERHOLT, ParsedStatements.Negation.NEGATED);
        Map<Standing, Integer> counts = new EnumMap<>(Standing.class);
        counts.put(Standing.GILT, 2);
        counts.put(Standing.UEBERHOLT, 1);
        StatusQuoView view = new StatusQuoView(List.of(affirmed, negated, earlier), List.of(), List.of(), counts,
                counts, List.of());
        Fixture fixture = new Fixture(view);
        fixture.sentences.put(negated.statement().id(), new SentenceText("Der Sachbearbeiter muss den Antrag nicht "
                + "prüfen.", null, 0, 40, 30, 36, false));
        StandingTab tab = fixture.shownTab();

        List<StandingTab.Row> rows = tab.conflictRows();
        assertThat(rows.get(0)).isInstanceOf(StandingTab.Group.class);
        StandingTab.Group head = (StandingTab.Group) rows.get(0);
        assertThat(head.title()).as("mit Wort davor, Handelnder in der Schreibweise des ersten Eintrags")
                .isEqualTo("Sachlage: Der Sachbearbeiter · prüfen · antrag · muss · bejaht und verneint");
        assertThat(tab.tabs().button(StandingTab.CONFLICTS).getText()).as("Zahl an der Pille, ohne Ablösungen")
                .isEqualTo("Mögliche Widersprüche · 1");
        assertThat(head.tone()).isNotEqualTo(Comic.RED);
        assertThat(rows.subList(1, 3)).extracting(row -> ((StandingTab.Line) row).entry())
                .containsExactly(affirmed, negated);
        assertThat(rows).contains(new StandingTab.Group(StandingTab.REPLACEMENTS, Comic.INK, 1));
        assertThat(rows.stream().filter(StandingTab.Group.class::isInstance).map(row -> ((StandingTab.Group) row)
                .title())).anyMatch(title -> title.startsWith("anders verneint: prüfen · antrag"));
        // Beide Sätze im Wortlaut: der Originalsatz ist für die Einträge der Hinweise schon geholt.
        assertThat(StandingTab.mark(negated)).isEqualTo("muss nicht");

        tab.tabs().select(StandingTab.CONFLICTS);
        assertThat(fixture.status.get()).isEqualTo("1 möglicher Widerspruch · 1 Ablösung");
        assertThat(tab.chip(Standing.GILT).getParent().isVisible()).as("Zahlen nur bei „Aussagen“").isFalse();
        tab.tabs().select(StandingTab.STATEMENTS);
        assertThat(tab.chip(Standing.GILT).getParent().isVisible()).isTrue();
    }

    private static StatusQuoEntry compared(String page, String id,
                                           com.aresstack.spaceharvester.application.standing.Situation situation,
                                           Standing standing, ParsedStatements.Negation negation) {
        ParsedStatements.Statement statement = new ParsedStatements.Statement("wiki/" + page + "@h#s1." + id, 0, 4, 10,
                "prüft", "prüfen", ParsedStatements.Voice.ACTIVE, ParsedStatements.Clause.MAIN, true, List.of(),
                Set.of(), 0, ParsedStatements.Quality.PLAUSIBLE, List.of(), ParsedStatements.Review.UNREVIEWED,
                new ParsedStatements.Features(negation, ParsedStatements.Modality.MUSS,
                        standing == Standing.GILT ? ParsedStatements.Temporality.GILT
                                : ParsedStatements.Temporality.FRUEHER));
        return new StatusQuoEntry(page(page, "Seite " + page), statement, StatementKind.ACTION, "Der Sachbearbeiter",
                "sachbearbeiter", situation, ParsedStatements.Quality.PLAUSIBLE, new StatementStanding(standing,
                StandingBasis.FEATURES, standing == Standing.GILT ? StandingReason.TEMPORALITY_GILT
                : StandingReason.TEMPORALITY_FRUEHER), null, false);
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

    @Test
    void readingFirstListsPagesWithSumAndOpensPartsOrPage() {
        Fixture fixture = new Fixture(view());
        StandingTab tab = fixture.shownTab();
        assertThat(fixture.topics).as("gerechnet erst, wenn „Zuerst lesen“ sichtbar ist").isEmpty();
        assertThat(tab.tabs().button(StandingTab.CONFLICTS).getText()).as("ohne Zahl bei null")
                .isEqualTo(StandingTab.CONFLICTS);
        assertThat(tab.reading().topicField().isVisible()).isFalse();

        tab.tabs().select(StandingTab.READING);
        ReadingFirstView reading = tab.reading();

        assertThat(fixture.topics).containsExactly("null");
        assertThat(reading.topicField().isVisible()).isTrue();
        assertThat(tab.chip(Standing.GILT).getParent().isVisible()).isFalse();
        assertThat(reading.rows()).extracting(r -> r.page().title()).containsExactly("Seite 1", "Antragsverfahren");
        assertThat(fixture.status.get()).isEqualTo("2 Seiten nach Lesewert");
        assertThat(ReadingFirstView.brief(reading.rows().getFirst())).isEqualTo("3 gilt · 2 Begriffe · neu/geändert");
        assertThat(reading.suggestButton().isEnabled()).isTrue();
        assertThat(reading.suggestionMenu().getComponentCount()).isEqualTo(2);

        reading.clickRow(0, false);
        assertThat(reading.popup().titleText()).isEqualTo("Seite 1");
        assertThat(reading.popup().bodyText()).startsWith("Summe: +10")
                .contains("+3  3 sichere Aussagen, die gelten (je +1)")
                .contains("+4  2 häufigste Begriffe der Auswahl (je +2): Antrag, Bescheid")
                .contains("+3  neu oder geändert seit 19.08.2026")
                .endsWith("Stand der Seite: „gilt“");
        assertThat(reading.popup().linkTexts()).containsExactly(ReadingFirstView.OPEN_PAGE);
        assertThat(reading.popup()).as("ein Overlay für ganz „Stand“").isSameAs(tab.popup());
        assertThat(tab.overlayButton(StandingTab.CLEAR_TIP).isEnabled()).as("noch keine Entscheidung zur Seite")
                .isFalse();
        tab.decisionChip(Standing.UEBERHOLT).doClick();
        assertThat(fixture.writes).as("Stand der ganzen Seite von Hand").containsExactly("decide 1 null UEBERHOLT 0");
        assertThat(fixture.status.get()).isEqualTo("Stand von Hand gesetzt: „überholt“");
        assertThat(fixture.topics).as("neu gerechnet nach dem eigenen Schreiben erst über den Core").hasSize(1);
        assertThat(ReadingFirstView.details(reading.rows().get(1), null)).startsWith("Summe: \u22125")
                .contains("\u22125  Seite ist Entwurf");

        reading.clickRow(1, true);
        assertThat(fixture.jumps).as("Titel öffnet die Seite ohne Stelle")
                .containsExactly(new StandingTab.Target("wiki", "2", -1, -1));

        reading.rowList().setSelectedIndex(1);
        ((javax.swing.JMenuItem) reading.suggestionMenu().getComponent(0)).doClick();
        assertThat(reading.topic().getText()).isEqualTo("Antrag");
        assertThat(fixture.topics).containsExactly("null", "Antrag");
        assertThat(reading.rows()).extracting(r -> r.page().title()).containsExactly("Antragsverfahren");
        assertThat(reading.rowList().getSelectedIndex()).as("gewählte Seite bleibt").isZero();
        assertThat(fixture.status.get()).isEqualTo("1 Seite erwähnt „Antrag“");

        reading.topic().setText("");
        assertThat(fixture.topics).as("✕ gilt wieder für die ganze Auswahl").containsExactly("null", "Antrag", "null");

        fixture.standingListener.get().run();
        flush();
        assertThat(fixture.topics).as("neu gerechnet nach einer Entscheidung").hasSize(4);

        tab.tabs().select(StandingTab.STATEMENTS);
        fixture.standingListener.get().run();
        flush();
        assertThat(fixture.topics).as("nicht gerechnet, solange „Zuerst lesen“ verdeckt ist").hasSize(4);
        assertThat(reading.topicField().isVisible()).isFalse();
    }

    // ---- Hilfen

    private static final class Fixture {
        StatusQuoView view;
        final List<ExportPanel.Selection> deletions = new ArrayList<>();
        final List<ExportPanel.Selection> everything = new ArrayList<>();
        final AtomicReference<Runnable> analysesListener = new AtomicReference<>();
        int deleted;
        final AtomicInteger reads = new AtomicInteger();
        final AtomicReference<Runnable> standingListener = new AtomicReference<>();
        final AtomicReference<String> status = new AtomicReference<>();
        final List<StandingTab.Target> jumps = new ArrayList<>();
        final Map<String, SentenceText> sentences = new java.util.HashMap<>();
        final List<Integer> sentenceCalls = new ArrayList<>();
        final List<String> writes = new ArrayList<>();
        boolean conflict;
        boolean failSubscribe;
        final List<String> topics = new ArrayList<>();
        boolean denied;
        /** Der Core lehnt ab, weil die Seite inzwischen gesperrt ist. */
        boolean lockedMeanwhile;
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
                    if (failSubscribe) {
                        throw new IllegalStateException("kein Core");
                    }
                    return Subscription.NONE;
                }

                @Override
                public StandingDecision decide(PageKey page, String statementId, Standing standing,
                                               long expectedRevision) {
                    writes.add("decide " + page.pageId() + " " + statementId + " " + standing + " " + expectedRevision);
                    if (denied) {
                        throw denied();
                    }
                    if (lockedMeanwhile) {
                        throw new com.aresstack.spaceharvester.application.standing.LockedPageException();
                    }
                    if (conflict) {
                        throw new com.aresstack.spaceharvester.application.standing.StandingConflictException(
                                expectedRevision + 1);
                    }
                    return new StandingDecision(page, statementId, standing, expectedRevision + 1, "angelo",
                            Instant.parse("2026-11-17T05:00:00Z"), "angelo", Instant.parse("2026-11-17T05:00:00Z"));
                }

                @Override
                public boolean clear(PageKey page, String statementId, long expectedRevision) {
                    writes.add("clear " + page.pageId() + " " + statementId + " " + expectedRevision);
                    return true;
                }

                @Override
                public List<com.aresstack.spaceharvester.application.standing.StandingDecisionStore.AuditEntry> history(
                        PageKey page, String statementId) {
                    return List.of(new com.aresstack.spaceharvester.application.standing.StandingDecisionStore
                            .AuditEntry(new StandingDecision.Target(page, statementId), "set", "angelo",
                            Instant.parse("2026-11-16T10:00:00Z"), Standing.GEPLANT, 1));
                }

                @Override
                public ReadingList reading(ExportPanel.Selection selection, String topic, BooleanSupplier cancelled) {
                    topics.add(String.valueOf(topic));
                    return StandingTabTest.reading(topic, Fixture.this.view);
                }

                @Override
                public int deleteAnalyses(ExportPanel.Selection selection) {
                    deletions.add(selection);
                    int count = deleted;
                    if (count > 0) {
                        Fixture.this.view = new StatusQuoView(List.of(), List.of(), List.of(), Map.of(), Map.of(),
                                List.of());
                        Runnable listener = analysesListener.get();
                        if (listener != null) {
                            listener.run();
                        }
                    }
                    return count;
                }

                @Override
                public boolean canDeleteEverything() {
                    return true;
                }

                @Override
                public int deleteEverything(ExportPanel.Selection selection) {
                    everything.add(selection);
                    return deleteAnalyses(selection);
                }

                @Override
                public Subscription subscribeAnalyses(Runnable listener) {
                    analysesListener.set(listener);
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

    /** Eine Leseempfehlung über die Seiten der Sicht; mit Thema nur die zweite Seite. */
    static ReadingList reading(String topic, StatusQuoView view) {
        List<ReadingList.Recommendation> pages = new ArrayList<>();
        for (PageStanding standing : view.pages()) {
            boolean first = standing.page().key().pageId().equals("1");
            if (topic != null && first) {
                continue;
            }
            List<ReadingList.Part> parts = first
                    ? List.of(new ReadingList.Part(ReadingReason.CURRENT_STATEMENTS, 3, 3, List.of()),
                    new ReadingList.Part(ReadingReason.TOP_TERMS, 2, 4, List.of("Antrag", "Bescheid")),
                    new ReadingList.Part(ReadingReason.RECENT_CHANGE, 1, 3, List.of()))
                    : List.of(new ReadingList.Part(ReadingReason.PAGE_DRAFT, 1, -5, List.of()));
            pages.add(new ReadingList.Recommendation(standing.page(),
                    parts.stream().mapToInt(ReadingList.Part::points).sum(), parts, standing));
        }
        return new ReadingList(topic, List.of("Antrag", "Bescheid"), Instant.parse("2026-08-19T00:00:00Z"), pages);
    }

    /** Wie der Core sie wirft; der Konstruktor ist nur im Paket sichtbar. */
    private static RuntimeException denied() {
        try {
            var constructor = com.aresstack.spaceharvester.application.access.AccessDeniedException.class
                    .getDeclaredConstructor(boolean.class, String.class);
            constructor.setAccessible(true);
            return constructor.newInstance(false, "wiki");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
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
