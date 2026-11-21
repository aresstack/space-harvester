package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.runtime.GlossaryAccess;
import com.aresstack.spaceharvester.app.runtime.GlossaryAccess.Entry;
import com.aresstack.spaceharvester.app.ui.content.ExportPanel;
import com.aresstack.spaceharvester.application.glossary.GlossaryDerivation;
import com.aresstack.spaceharvester.application.glossary.GlossaryOverview;
import com.aresstack.spaceharvester.application.jobs.JobError;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.terms.RelationEvidence;
import com.aresstack.spaceharvester.application.terms.RelationOrigin;
import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import com.aresstack.spaceharvester.application.terms.TermQueries;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Der Reiter „Fachglossar“ unter Begriffe (ADR 0032, FL5b) mit einem Glossar im Speicher: die Sicht kommt fertig aus
 * dem Core, Chips und Suche verfeinern die gehaltene Sicht, eine neue Auswahl und das Ende eines Auftrags holen die
 * volle Sicht, Meldungen des Glossars verfeinern; nie ein Zeitgeber.
 */
class GlossaryTabTest {

    private static final Instant T = Instant.parse("2026-11-10T08:00:00Z");
    private static final String QUERY_DEFAULT = "q=null categories=[FACHLICH, ROLLE, UNKLAR] status=[PROPOSED, CONFIRMED]";

    @Test
    void theSearchStandsOnlyInTheTabRowAndNotAgainAboveTheTable() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            GlossaryTab tab = new GlossaryTab(null, java.util.Optional::empty);
            assertThat(tab.directory().showsHead()).isFalse();
        });
    }

    @Test
    void entriesComeReadyFromTheCoreAndTheToolsBelongToTheGlossaryTab() throws Exception {
        FakeGlossary glossary = new FakeGlossary();
        glossary.put(entry("1", "Datenprodukt", "wiki", "DH", RelationOrigin.AI, ReviewStatus.PROPOSED, 3, true),
                GlossaryDerivation.Category.FACHLICH, new GlossaryDerivation.Importance(12, 30, 4, 1, 0));
        glossary.put(entry("2", "Haushaltsverfahren", null, null, RelationOrigin.MANUAL, ReviewStatus.CONFIRMED,
                0, false), GlossaryDerivation.Category.UNKLAR, null);
        glossary.put(entry("3", "Datenbankschema", "wiki", null, RelationOrigin.RULE, ReviewStatus.PROPOSED, 1,
                false), GlossaryDerivation.Category.TECHNISCH, new GlossaryDerivation.Importance(2, 2, 0, 0, 0));
        GlossaryTab[] tab = new GlossaryTab[1];
        List<String> status = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            tab[0] = new GlossaryTab(null, () -> Optional.of(glossary));
            tab[0].onStatus(status::add);
            tab[0].load();
        });
        await("zwei Einträge ohne Technik", () -> tab[0].directory().table().getRowCount() == 2);
        assertThat(glossary.reads).containsExactly("full null " + QUERY_DEFAULT);
        SwingUtilities.invokeAndWait(() -> {
            javax.swing.JTable table = tab[0].directory().table();
            assertThat(row(table, 0)).containsExactly("Datenprodukt", "fachlich", "12 Seiten",
                    "Vorgeschlagen", "KI", "Beschreibt ein Produkt", "");
            assertThat(row(table, 1)).containsExactly("Haushaltsverfahren", "unklar", GlossaryTab.NO_IMPORTANCE,
                    "Bestätigt", "Manuell", "Beschreibt ein Produkt", "");
            // Die Zahlen kommen vom Core: Kategorien unter dem Status, Status unter den Kategorien.
            assertThat(tab[0].category(GlossaryDerivation.Category.FACHLICH).count()).isEqualTo(1);
            assertThat(tab[0].category(GlossaryDerivation.Category.TECHNISCH).count()).isEqualTo(1);
            assertThat(tab[0].category(GlossaryDerivation.Category.TECHNISCH).isSelected()).isFalse();
            assertThat(tab[0].status(ReviewStatus.PROPOSED).count()).isEqualTo(1);
            assertThat(tab[0].status(ReviewStatus.CONFIRMED).count()).isEqualTo(1);
            assertThat(status).last().isEqualTo("Alle Wissensquellen · 2 Fachbegriffe · 40 Seiten ausgewertet · 1 Analyse "
                    + "veraltet");
            List<RowActions.Action> proposedAi = tab[0].actions(tab[0].entryAtView(0));
            assertThat(proposedAi.stream().map(RowActions.Action::tooltip)).containsExactly(GlossaryTab.CONFIRM,
                    GlossaryTab.REJECT, GlossaryTab.EDIT, GlossaryTab.ONLY_MANUAL);
            assertThat(proposedAi.stream().map(RowActions.Action::enabled)).containsExactly(true, true, true, false);
            List<RowActions.Action> confirmedManual = tab[0].actions(tab[0].entryAtView(1));
            assertThat(confirmedManual.stream().map(RowActions.Action::tooltip)).containsExactly(
                    GlossaryTab.ALREADY_CONFIRMED, GlossaryTab.REJECT, GlossaryTab.EDIT, GlossaryTab.REMOVE);
            // Auch ohne Modell startbar (Phase „Begriffe ableiten“); Hinzufügen geht.
            assertThat(tab[0].detectButton().isEnabled()).isTrue();
            assertThat(tab[0].detectButton().getToolTipText()).isEqualTo(GlossaryTab.DETECT_TIP);
            assertThat(tab[0].addButton().isEnabled()).isTrue();
        });
    }

    @Test
    void chipsAndSearchRefineTheHeldViewAndANewSelectionReadsItFully() throws Exception {
        FakeGlossary glossary = new FakeGlossary();
        glossary.put(entry("1", "Datenprodukt", "wiki", "DH", RelationOrigin.AI, ReviewStatus.PROPOSED, 1, false),
                GlossaryDerivation.Category.FACHLICH, null);
        glossary.put(entry("2", "Berechtigungsmanagement", "wiki", null, RelationOrigin.AI, ReviewStatus.REJECTED,
                1, false), GlossaryDerivation.Category.FACHLICH, null);
        glossary.put(entry("3", "Datenbankschema", "wiki", null, RelationOrigin.RULE, ReviewStatus.PROPOSED, 1,
                false), GlossaryDerivation.Category.TECHNISCH, null);
        GlossaryTab[] tab = new GlossaryTab[1];
        SwingUtilities.invokeAndWait(() -> {
            tab[0] = new GlossaryTab(null, () -> Optional.of(glossary));
            tab[0].load();
        });
        await("ein Eintrag", () -> tab[0].directory().table().getRowCount() == 1);
        SwingUtilities.invokeAndWait(() -> {
            // Status-Chips sind Umschalter: vorgeschlagen und bestätigt sind an, abgelehnt kommt dazu.
            assertThat(tab[0].status(ReviewStatus.PROPOSED).isSelected()).isTrue();
            assertThat(tab[0].status(ReviewStatus.CONFIRMED).isSelected()).isTrue();
            assertThat(tab[0].status(ReviewStatus.REJECTED).isSelected()).isFalse();
            tab[0].status(ReviewStatus.REJECTED).doClick();
        });
        await("mit abgelehnten", () -> tab[0].directory().table().getRowCount() == 2);
        SwingUtilities.invokeAndWait(() -> onlyRejected(tab[0]));
        await("Sperrliste", () -> tab[0].directory().table().getRowCount() == 1
                && "Berechtigungsmanagement".equals(tab[0].directory().table().getValueAt(0, 0)));
        SwingUtilities.invokeAndWait(() -> {
            // Der letzte Status aus: wieder die Vorgabe, sichtbar an den Chips.
            tab[0].status(ReviewStatus.REJECTED).doClick();
            assertThat(tab[0].status(ReviewStatus.PROPOSED).isSelected()).isTrue();
            assertThat(tab[0].status(ReviewStatus.CONFIRMED).isSelected()).isTrue();
            assertThat(tab[0].status(ReviewStatus.REJECTED).isSelected()).isFalse();
            tab[0].category(GlossaryDerivation.Category.TECHNISCH).doClick();
        });
        await("mit Technik", () -> tab[0].directory().table().getRowCount() == 2);
        SwingUtilities.invokeAndWait(() -> tab[0].search().setText("daten"));
        await("Suche nach daten", () -> tab[0].directory().table().getRowCount() == 2
                && glossary.reads.get(glossary.reads.size() - 1).contains("q=daten"));
        // Chips und Suche verfeinern nur; die volle Sicht lief einmal.
        assertThat(glossary.reads.stream().filter(read -> read.startsWith("full"))).hasSize(1);
        assertThat(glossary.reads).last().asString().startsWith("refine ");

        ExportPanel.Selection space = new ExportPanel.Selection("wiki", "DH", null, "Datenhaus");
        SwingUtilities.invokeAndWait(() -> {
            tab[0].showing = () -> true;
            tab[0].selection(space);
        });
        await("volle Sicht der neuen Auswahl", () -> glossary.reads.stream().filter(r -> r.startsWith("full"))
                .count() == 2);
        // Ohne Laufzeit gibt es keine Auswahl im Bestand; der Test sieht nur, dass neu gelesen wird.
        assertThat(glossary.reads).last().asString().startsWith("full null");
        SwingUtilities.invokeAndWait(() -> {
            tab[0].search().setText("");
            tab[0].create(new GlossaryAccess.NewEntry("Haushaltsverfahren", null, null, "Verfahren des Haushalts"));
        });
        await("drei Einträge, der neue gewählt", () -> tab[0].directory().table().getRowCount() == 3
                && tab[0].directory().table().getSelectedRow() >= 0
                && "Haushaltsverfahren".equals(tab[0].directory().table().getValueAt(
                        tab[0].directory().table().getSelectedRow(), 0)));
        SwingUtilities.invokeAndWait(() -> {
            int row = tab[0].directory().table().getSelectedRow();
            assertThat(tab[0].directory().table().getValueAt(row, 4)).isEqualTo("Manuell");
            List<String> asked = new ArrayList<>();
            tab[0].confirmWith((heading, question) -> asked.add(question));
            tab[0].actions(tab[0].entryAtView(row)).get(3).run().run();
            assertThat(asked).containsExactly("Wirklich „Haushaltsverfahren“ aus dem Fachglossar entfernen?");
        });
        await("wieder zwei", () -> tab[0].directory().table().getRowCount() == 2);
        assertThat(glossary.entries).doesNotContainKey("4");
    }

    @Test
    void tooNarrowForBothChipGroupsTheStatusMovesIntoAFilterMenu() throws Exception {
        FakeGlossary glossary = new FakeGlossary();
        glossary.put(entry("1", "Datenprodukt", "wiki", "DH", RelationOrigin.AI, ReviewStatus.PROPOSED, 1, false),
                GlossaryDerivation.Category.FACHLICH, null);
        glossary.put(entry("2", "Berechtigungsmanagement", "wiki", null, RelationOrigin.AI, ReviewStatus.REJECTED,
                1, false), GlossaryDerivation.Category.FACHLICH, null);
        GlossaryTab[] tab = new GlossaryTab[1];
        SwingUtilities.invokeAndWait(() -> {
            tab[0] = new GlossaryTab(null, () -> Optional.of(glossary));
            tab[0].component().setSize(1100, 400);
            tab[0].component().doLayout();
            tab[0].load();
        });
        await("geladen", () -> tab[0].directory().table().getRowCount() == 1);
        SwingUtilities.invokeAndWait(() -> {
            tab[0].fitChips();
            assertThat(tab[0].status(ReviewStatus.REJECTED).getParent().isVisible()).isTrue();
            assertThat(tab[0].statusFilter().isVisible()).isFalse();
            tab[0].component().setSize(420, 400);
            tab[0].component().doLayout();
            ((javax.swing.JComponent) tab[0].component().getComponent(0)).setSize(400, 30);
            tab[0].fitChips();
            assertThat(tab[0].status(ReviewStatus.REJECTED).getParent().isVisible()).isFalse();
            assertThat(tab[0].statusFilter().isVisible()).isTrue();
        });
        SwingUtilities.invokeAndWait(() -> {
            tab[0].buildStatusMenu();
            List<String> items = new ArrayList<>();
            for (java.awt.Component item : tab[0].statusMenu().getComponents()) {
                if (item instanceof javax.swing.JMenuItem menuItem) {
                    items.add(menuItem.getText()
                            + (menuItem instanceof javax.swing.JCheckBoxMenuItem && menuItem.isSelected() ? " ✓" : ""));
                }
            }
            assertThat(items).containsExactly("vorgeschlagen 1 ✓", "bestätigt 0 ✓", "abgelehnt 1",
                    GlossaryTab.OPEN_AND_CONFIRMED);
            ((javax.swing.JMenuItem) tab[0].statusMenu().getComponent(2)).doClick();
        });
        await("abgelehnte über das Menü dazu", () -> tab[0].directory().table().getRowCount() == 2);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(tab[0].statusFilter().getToolTipText())
                    .endsWith("gezeigt: vorgeschlagen, bestätigt, abgelehnt");
            tab[0].buildStatusMenu();
            ((javax.swing.JMenuItem) tab[0].statusMenu().getComponent(4)).doClick();
        });
        await("Vorgabe über das Menü", () -> tab[0].directory().table().getRowCount() == 1
                && !tab[0].status(ReviewStatus.REJECTED).isSelected());
    }

    @Test
    void reviewGoesToTheGlossaryWithTheShownRevisionAndAConflictIsNamedUnderTheTable() throws Exception {
        FakeGlossary glossary = new FakeGlossary();
        glossary.put(entry("1", "Datenprodukt", "wiki", "DH", RelationOrigin.AI, ReviewStatus.PROPOSED, 1, false),
                GlossaryDerivation.Category.FACHLICH, null);
        GlossaryTab[] tab = new GlossaryTab[1];
        SwingUtilities.invokeAndWait(() -> {
            tab[0] = new GlossaryTab(null, () -> Optional.of(glossary));
            tab[0].load();
        });
        await("ein Eintrag", () -> tab[0].directory().table().getRowCount() == 1);
        SwingUtilities.invokeAndWait(() -> tab[0].actions(tab[0].entryAtView(0)).get(1).run().run());
        await("abgelehnt und weg", () -> tab[0].directory().table().getRowCount() == 0
                && tab[0].status(ReviewStatus.REJECTED).count() == 1);
        assertThat(glossary.calls).containsExactly("reject 1@1");
        SwingUtilities.invokeAndWait(() -> onlyRejected(tab[0]));
        await("auf der Sperrliste", () -> tab[0].directory().table().getRowCount() == 1);
        SwingUtilities.invokeAndWait(() -> {
            javax.swing.JTable table = tab[0].directory().table();
            table.clearSelection();
            java.awt.Component cell = table.prepareRenderer(table.getCellRenderer(0, 0), 0, 0);
            assertThat(cell.getBackground()).isEqualTo(TermDirectory.REJECTED_BACKGROUND);
            List<RowActions.Action> actions = tab[0].actions(tab[0].entryAtView(0));
            assertThat(actions.get(1).tooltip()).isEqualTo(GlossaryTab.RESTORE);
            actions.get(1).run().run();
        });
        await("Sperrliste leer", () -> tab[0].directory().table().getRowCount() == 0);
        assertThat(glossary.calls).containsExactly("reject 1@1", "restore 1@2");
        // Den letzten Status aus: wieder die Vorgabe.
        SwingUtilities.invokeAndWait(() -> tab[0].status(ReviewStatus.REJECTED).doClick());
        await("wieder vorgeschlagen", () -> tab[0].directory().table().getRowCount() == 1
                && "Vorgeschlagen".equals(tab[0].directory().table().getValueAt(0, 3)));
        glossary.conflictOnce = true;
        SwingUtilities.invokeAndWait(() -> tab[0].actions(tab[0].entryAtView(0)).get(0).run().run());
        await("Konflikt gemeldet", () -> GlossaryTab.CONFLICT.equals(tab[0].directory().hint()));
    }

    @Test
    void detectingStartsForTheSelectionAndJobEndsReadTheFullView() throws Exception {
        FakeGlossary glossary = new FakeGlossary();
        GlossaryTab[] tab = new GlossaryTab[1];
        SwingUtilities.invokeAndWait(() -> {
            tab[0] = new GlossaryTab(null, () -> Optional.of(glossary));
            tab[0].showing = () -> true;
            tab[0].selection(new ExportPanel.Selection("wiki", "DH", "42", "Datenprodukt"));
        });
        await("geladen, leer", () -> GlossaryTab.EMPTY.equals(tab[0].directory().hint()));
        SwingUtilities.invokeAndWait(() -> tab[0].detectButton().doClick());
        await("Auftrag läuft", () -> !tab[0].jobIds().isEmpty());
        // Eine Seite zählt mit ihrem Space.
        assertThat(glossary.calls).contains("classify wiki/DH");
        SwingUtilities.invokeAndWait(() -> {
            assertThat(tab[0].detectButton().isEnabled()).isFalse();
            assertThat(tab[0].detectButton().getToolTipText()).isEqualTo(GlossaryTab.RUNNING);
        });
        // Teilergebnis während des Laufs: die Meldung des Glossars verfeinert, ohne dass der Auftrag endet.
        glossary.put(entry("9", "Datenprodukt", "wiki", null, RelationOrigin.RULE, ReviewStatus.PROPOSED, 2,
                false), GlossaryDerivation.Category.FACHLICH, null);
        glossary.changed();
        await("Teilergebnis sichtbar", () -> tab[0].directory().table().getRowCount() == 1);
        assertThat(glossary.reads).last().asString().startsWith("refine ");
        long fullBefore = glossary.reads.stream().filter(read -> read.startsWith("full")).count();
        // Das Ende meldet der Core (ADR 0027): Rückmeldung und volle Sicht, nie ein Zeitgeber.
        glossary.state = JobState.COMPLETED;
        glossary.jobChanged(JobType.GLOSSARY);
        await("Auftrag zu Ende", () -> tab[0].jobIds().isEmpty() && glossary.reads.stream()
                .filter(read -> read.startsWith("full")).count() == fullBefore + 1);
        SwingUtilities.invokeAndWait(() -> assertThat(tab[0].detectButton().isEnabled()).isTrue());
        // Auch das Ende von „Aussagen analysieren“ ändert die Wichtigkeit, ein Abgleich nicht.
        glossary.jobChanged(JobType.STATEMENT_ANALYSIS);
        await("nach der Analyse neu", () -> glossary.reads.stream().filter(read -> read.startsWith("full"))
                .count() == fullBefore + 2);
        glossary.jobChanged(JobType.SYNC);
        glossary.jobChanged(JobType.STATEMENT_CHECK);
        await("nach der Prüfung neu", () -> glossary.reads.stream().filter(read -> read.startsWith("full"))
                .count() == fullBefore + 3);
    }

    @Test
    void aHiddenTabRemembersWhatToReadUntilItIsShown() throws Exception {
        FakeGlossary glossary = new FakeGlossary();
        glossary.put(entry("1", "Datenprodukt", "wiki", "DH", RelationOrigin.AI, ReviewStatus.PROPOSED, 1, false),
                GlossaryDerivation.Category.FACHLICH, null);
        GlossaryTab[] tab = new GlossaryTab[1];
        SwingUtilities.invokeAndWait(() -> {
            tab[0] = new GlossaryTab(null, () -> Optional.of(glossary));
            tab[0].load();
        });
        await("geladen", () -> tab[0].directory().table().getRowCount() == 1);
        SwingUtilities.invokeAndWait(() -> tab[0].showing = () -> false);
        glossary.state = JobState.COMPLETED;
        glossary.changed();
        glossary.jobChanged(JobType.STATEMENT_ANALYSIS);
        Thread.sleep(200);
        assertThat(glossary.reads).hasSize(1);
        SwingUtilities.invokeAndWait(() -> tab[0].load());
        await("einmal voll gelesen", () -> glossary.reads.size() == 2);
        assertThat(glossary.reads).last().asString().startsWith("full ");
    }

    @Test
    void categorizingByHandGoesToTheCoreAndTheListShowsTheNewCategory() throws Exception {
        FakeGlossary glossary = new FakeGlossary();
        glossary.put(entry("1", "Sachbearbeiter", "wiki", "DH", RelationOrigin.RULE, ReviewStatus.PROPOSED, 1,
                false), GlossaryDerivation.Category.UNKLAR, null);
        GlossaryTab[] tab = new GlossaryTab[1];
        SwingUtilities.invokeAndWait(() -> {
            tab[0] = new GlossaryTab(null, () -> Optional.of(glossary));
            tab[0].load();
        });
        await("unklar", () -> tab[0].directory().table().getRowCount() == 1
                && "unklar".equals(tab[0].directory().table().getValueAt(0, 1)));
        SwingUtilities.invokeAndWait(() -> tab[0].categorize(tab[0].entryAtView(0), GlossaryDerivation.Category.ROLLE));
        await("als Rolle", () -> "Rolle".equals(tab[0].directory().table().getValueAt(0, 1)));
        assertThat(glossary.calls).containsExactly("categorize 1 ROLLE@1");
        assertThat(glossary.reads).last().asString().startsWith("refine ");
    }

    @Test
    void textsForProgressOutcomeAndDetails() {
        assertThat(GlossaryTab.outcomeText(List.of(job("j", JobState.CANCELLED, new JobProgress(5, 2, 1, 0), null))))
                .isEqualTo("Fachbegriffe ermitteln abgebrochen; 1 Eintrag bleibt, Entscheidungen unverändert");
        assertThat(GlossaryTab.outcomeText(List.of(job("j", JobState.COMPLETED, new JobProgress(5, 5, 2, 0), null),
                job("k", JobState.FAILED, JobProgress.NONE, new JobError("ai", "Dienst nicht erreichbar")))))
                .isEqualTo("Fachbegriffe ermitteln fehlgeschlagen (Quelle wiki): Dienst nicht erreichbar · "
                        + "Vorgeschlagenes bleibt, Entscheidungen unverändert");
        assertThat(GlossaryTab.outcomeText(List.of(job("j", JobState.COMPLETED, new JobProgress(5, 5, 2, 0), null))))
                .isEqualTo("Fachbegriffe ermitteln abgeschlossen: 2 Einträge aus 5 geprüften Begriffen");
        Entry stale = entry("1", "Datenprodukt", "wiki", "DH", RelationOrigin.AI, ReviewStatus.CONFIRMED, 2, true);
        GlossaryAccess.Row row = new GlossaryAccess.Row(stale, new GlossaryDerivation.Importance(12, 30, 4, 1, 0),
                GlossaryDerivation.Category.ROLLE, new com.aresstack.spaceharvester.application.glossary
                .GlossaryEntry.Classification(GlossaryDerivation.Category.ROLLE,
                com.aresstack.spaceharvester.application.glossary.GlossaryEntry.ClassifiedBy.RULE, "ROLE_HEAD"),
                null);
        com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Origin original =
                com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Origin.ORIGINAL;
        com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Origin staged =
                com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Origin.VORSTUFE;
        GlossaryDerivation.Term term = new GlossaryDerivation.Term("Datenprodukt", "datenprodukt", false,
                row.importance(), List.of(new GlossaryDerivation.Use("prüfen", original, false, 5, List.of()),
                new GlossaryDerivation.Use("ablehnen", original, true, 1, List.of()),
                new GlossaryDerivation.Use("genehmigen", staged, false, 2, List.of())),
                List.of(new GlossaryDerivation.Use("liefern", original, false, 3, List.of())), List.of(),
                List.of(new GlossaryDerivation.Use("Datenangebot", original, false, 2, List.of(),
                                GlossaryDerivation.Relation.BESTIMMUNG, 2),
                        new GlossaryDerivation.Use("Prüfstelle", original, false, 1, List.of(),
                                GlossaryDerivation.Relation.ROLLE, 1),
                        new GlossaryDerivation.Use("Datenset", original, false, 1, List.of(),
                                GlossaryDerivation.Relation.GLEICHSETZUNG, 1),
                        new GlossaryDerivation.Use("Angebot", original, false, 1, List.of())), List.of(),
                GlossaryDerivation.Category.ROLLE, GlossaryDerivation.CategoryRule.ROLE_HEAD);
        String text = GlossaryTab.overlayText(stale, row, term, List.of("Seite A: … das Datenprodukt liefert …"),
                List.of(new GlossaryAccess.History("propose", "rule:glossary", T, ReviewStatus.PROPOSED),
                        new GlossaryAccess.History("confirm", "USER:angelo@app", T, ReviewStatus.CONFIRMED)));
        assertThat(text).startsWith("Beschreibt ein Produkt\n\nEinordnung: Rolle · nach Regel (Rollenwort am "
                + "Wortende)\nGilt für: wiki · DH\nHerkunft: KI (gpt-4o/p1)\nStatus: bestätigt am ");
        assertThat(text).contains("Wichtigkeit: Auf 12 Seiten, 30 Erwähnungen, handelnd 4×, betroffen 1×, als "
                        + "Träger 0×")
                .contains("Handelt: prüfen 5×\nHandelt nicht: ablehnen 1×\nIst betroffen: liefern 3×\n"
                        + "Ist ein (Begriffsbestimmung): Datenangebot 2×\nIst (Rolle): Prüfstelle 1×\n"
                        + "Ist gleich (Gleichsetzung): Datenset 1×\nIst ein: Angebot 1×\n")
                .contains("Aus der Vorstufe (KI), zählt nicht für die Wichtigkeit:\n   Handelt: genehmigen 2×")
                .contains("2 Belege · Beleg veraltet").contains("• Seite A: … das Datenprodukt liefert …")
                .contains("… und 1 weitere")
                .contains("Protokoll:\n• " + GlossaryTab.minute(T) + " · von der Regel vorgeschlagen\n• "
                        + GlossaryTab.minute(T) + " · von angelo bestätigt")
                .doesNotContain("→").doesNotContain("rule:glossary").doesNotContain("USER:");
        assertThat(GlossaryTab.categorized(GlossaryDerivation.Category.UNKLAR, null))
                .isEqualTo("unklar (noch nicht eingeordnet)");
        // Die Regel vergibt nur Technik und Rolle; sonst bleibt der Begriff „unklar“ (fachlich nur von Hand/KI).
        assertThat(GlossaryTab.categorized(GlossaryDerivation.Category.UNKLAR, new com.aresstack.spaceharvester
                .application.glossary.GlossaryEntry.Classification(GlossaryDerivation.Category.UNKLAR,
                com.aresstack.spaceharvester.application.glossary.GlossaryEntry.ClassifiedBy.RULE, null)))
                .isEqualTo("unklar · nach Regel (kein Technik- oder Rollenwort)");
        assertThat(GlossaryTab.categorized(GlossaryDerivation.Category.FACHLICH, new com.aresstack.spaceharvester
                .application.glossary.GlossaryEntry.Classification(GlossaryDerivation.Category.FACHLICH,
                com.aresstack.spaceharvester.application.glossary.GlossaryEntry.ClassifiedBy.MANUAL, null)))
                .isEqualTo("fachlich · von Hand");
        assertThat(GlossaryTab.actorText("ai:glossary")).isEqualTo("von der KI");
        assertThat(GlossaryTab.invalidTerm("  ")).isEqualTo("Den Fachbegriff angeben.");
        assertThat(GlossaryTab.invalidTerm("RSL")).isNull();
        assertThat(GlossaryTab.snippetText(new TermQueries.Snippet("vor  dem ", "Begriff", " danach", true, false)))
                .isEqualTo("…vor dem Begriff danach");
        // Die bewusst ausgelassene Modellphase in Worten, nie als Fehler.
        assertThat(GlossaryTab.modelSkipped("GLOSSARY_MODEL_SKIPPED:NO_MODEL"))
                .isEqualTo(GlossaryTab.MODEL_SKIPPED_NO_MODEL);
        assertThat(GlossaryTab.modelSkipped("GLOSSARY_MODEL_SKIPPED:NOT_RELEASED"))
                .isEqualTo(GlossaryTab.MODEL_SKIPPED_NOT_RELEASED);
        assertThat(GlossaryTab.modelSkipped("GLOSSARY_RULES_DONE")).isNull();
        // D4: eigener Grund „Aussagenanalyse fehlt“ mit der Zahl aus dem Lauf.
        assertThat(GlossaryTab.modelSkipped("GLOSSARY_MODEL_SKIPPED:NO_ANALYSIS",
                "„Fachbegriffe ermitteln“ ausgelassen: 3 Seiten ohne aktuelle Aussagenanalyse"))
                .startsWith(GlossaryTab.MODEL_SKIPPED_NO_ANALYSIS).endsWith("3 Seiten ohne aktuelle Aussagenanalyse")
                .doesNotContain("freigegeben");
        assertThat(GlossaryTab.importance(new GlossaryDerivation.Importance(1, 3, 0, 0, 0))).isEqualTo("1 Seite");
        assertThat(GlossaryTab.importanceTip(new GlossaryDerivation.Importance(12, 30, 4, 1, 2)))
                .isEqualTo("Auf 12 Seiten, 30 Erwähnungen, handelnd 4×, betroffen 1×, als Träger 2×");
    }

    @Test
    void everyReasonOfRuleAndModelIsShownInWords() {
        GlossaryDerivation.Category fachlich = GlossaryDerivation.Category.FACHLICH;
        GlossaryDerivation.Category rolle = GlossaryDerivation.Category.ROLLE;
        GlossaryDerivation.Category technik = GlossaryDerivation.Category.TECHNISCH;
        GlossaryDerivation.Category unklar = GlossaryDerivation.Category.UNKLAR;
        // Die KI: je Kennung der Grund in Klammern, eine unbekannte ohne Klammer.
        assertThat(by(AI, fachlich, "DOMAIN_OBJECT")).isEqualTo("fachlich · von der KI (fachlicher Gegenstand)");
        assertThat(by(AI, fachlich, "DOMAIN_PROCESS")).isEqualTo("fachlich · von der KI (fachlicher Ablauf)");
        assertThat(by(AI, fachlich, "LEGAL_TERM")).isEqualTo("fachlich · von der KI (Rechtsbegriff)");
        assertThat(by(AI, rolle, "PERSON_ROLE")).isEqualTo("Rolle · von der KI (Rolle einer Person)");
        assertThat(by(AI, rolle, "ORGANISATION")).isEqualTo("Rolle · von der KI (Stelle oder Organisation)");
        assertThat(by(AI, technik, "SYSTEM_COMPONENT")).isEqualTo("Technik · von der KI (technisches System)");
        assertThat(by(AI, technik, "DATA_FORMAT")).isEqualTo("Technik · von der KI (Datenformat)");
        // Auch das UNKLAR der KI wird gespeichert: man sieht, dass sie schon geschaut hat.
        assertThat(by(AI, unklar, "GENERIC_WORD")).isEqualTo("unklar · von der KI (allgemeines Wort)");
        assertThat(by(AI, unklar, "CONTEXT_UNCLEAR"))
                .isEqualTo("unklar · von der KI (aus den Belegen nicht zu entscheiden)");
        assertThat(by(AI, fachlich, "SOMETHING_NEW")).isEqualTo("fachlich · von der KI");
        assertThat(by(AI, fachlich, null)).isEqualTo("fachlich · von der KI");
        // Die Regel: je Kennung der Grund; der Vorgabesatz nur bei UNKLAR, sonst ohne Klammer.
        assertThat(by(RULE, technik, "TECHNICAL_HEAD")).isEqualTo("Technik · nach Regel (Technikwort am Wortende)");
        assertThat(by(RULE, technik, "TECHNICAL_SHAPE"))
                .isEqualTo("Technik · nach Regel (Schreibweise eines Bezeichners)");
        assertThat(by(RULE, rolle, "ROLE_HEAD")).isEqualTo("Rolle · nach Regel (Rollenwort am Wortende)");
        assertThat(by(RULE, rolle, "ROLE_OF_PERSON")).isEqualTo("Rolle · nach Regel (als Rolle einer Person genannt)");
        assertThat(by(RULE, unklar, null)).isEqualTo("unklar · nach Regel (kein Technik- oder Rollenwort)");
        assertThat(by(RULE, unklar, "SOMETHING_NEW")).isEqualTo("unklar · nach Regel (kein Technik- oder Rollenwort)");
        assertThat(by(RULE, technik, "SOMETHING_NEW")).isEqualTo("Technik · nach Regel");
        assertThat(by(RULE, rolle, null)).isEqualTo("Rolle · nach Regel");
        assertThat(by(MANUAL, fachlich, "LEGAL_TERM")).isEqualTo("fachlich · von Hand");
    }

    @Test
    void theModelPhaseStepsOfTheProtocolReadInWordsWithoutInternalNames() {
        // Glossary#classify: die KI ordnet ein.
        assertThat(GlossaryTab.historyLine(new GlossaryAccess.History("classify", "ai:glossary", T,
                ReviewStatus.PROPOSED))).isEqualTo(GlossaryTab.minute(T) + " · von der KI eingeordnet");
        // Glossary#withdrawAi: die Freigabe endet; ohne Handelnden, nie „von ai-release“.
        assertThat(GlossaryTab.historyLine(new GlossaryAccess.History("unclassify", "ai-release", T,
                ReviewStatus.PROPOSED))).isEqualTo(GlossaryTab.minute(T) + " · Einordnung der KI zurückgenommen "
                + "(Seite nicht mehr für die KI freigegeben)");
        // Ein Handelnder ohne Doppelpunkt ist kein Benutzer und erscheint nie als „von …“.
        assertThat(GlossaryTab.actorText("ai-release")).isEmpty();
        assertThat(GlossaryTab.historyLine(new GlossaryAccess.History("stale", "evidence-check", T,
                ReviewStatus.PROPOSED))).isEqualTo(GlossaryTab.minute(T) + " · als „Beleg veraltet“ markiert");
        assertThat(GlossaryTab.actorText("USER:angelo@app")).isEqualTo("von angelo");
        // Von Hand eingeordnet bleibt „categorize“ mit dem Benutzer.
        assertThat(GlossaryTab.historyLine(new GlossaryAccess.History("categorize", "USER:angelo", T,
                ReviewStatus.PROPOSED))).isEqualTo(GlossaryTab.minute(T) + " · von angelo eingeordnet");
    }

    private static final com.aresstack.spaceharvester.application.glossary.GlossaryEntry.ClassifiedBy AI =
            com.aresstack.spaceharvester.application.glossary.GlossaryEntry.ClassifiedBy.AI;
    private static final com.aresstack.spaceharvester.application.glossary.GlossaryEntry.ClassifiedBy RULE =
            com.aresstack.spaceharvester.application.glossary.GlossaryEntry.ClassifiedBy.RULE;
    private static final com.aresstack.spaceharvester.application.glossary.GlossaryEntry.ClassifiedBy MANUAL =
            com.aresstack.spaceharvester.application.glossary.GlossaryEntry.ClassifiedBy.MANUAL;

    private static String by(com.aresstack.spaceharvester.application.glossary.GlossaryEntry.ClassifiedBy who,
                             GlossaryDerivation.Category category, String reason) {
        return GlossaryTab.categorized(category,
                new com.aresstack.spaceharvester.application.glossary.GlossaryEntry.Classification(category, who,
                        reason));
    }

    /** Nur die Sperrliste: abgelehnt an, vorgeschlagen und bestätigt aus, über die Chips. */
    static void onlyRejected(GlossaryTab tab) {
        for (ReviewStatus status : List.of(ReviewStatus.REJECTED, ReviewStatus.PROPOSED, ReviewStatus.CONFIRMED)) {
            if (tab.status(status).isSelected() != (status == ReviewStatus.REJECTED)) {
                tab.status(status).doClick();
            }
        }
    }

    private static List<Object> row(javax.swing.JTable table, int row) {
        List<Object> cells = new ArrayList<>();
        for (int c = 0; c < table.getColumnCount(); c++) {
            cells.add(table.getValueAt(row, c));
        }
        return cells;
    }

    private static Entry entry(String id, String term, String sourceId, String spaceKey, RelationOrigin origin,
                               ReviewStatus status, int evidence, boolean stale) {
        List<RelationEvidence> proofs = new ArrayList<>();
        for (int i = 0; i < evidence; i++) {
            proofs.add(new RelationEvidence("wiki", "p" + i, 10, 20, "sha" + i));
        }
        return new Entry(id, term, term.toLowerCase(java.util.Locale.GERMAN), sourceId, spaceKey, origin, status,
                proofs, stale, "Beschreibt ein Produkt", origin == RelationOrigin.AI ? "gpt-4o/p1" : null, T, T,
                status == ReviewStatus.PROPOSED ? null : T, 1);
    }

    private static JobStatus job(String id, JobState state, JobProgress progress, JobError error) {
        return new JobStatus(id, JobType.GLOSSARY, "wiki", null, "app", state, progress, error, false, T,
                T, state.terminal() ? T : null);
    }

    private static void await(String step, BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 400; i++) {
            boolean[] done = new boolean[1];
            SwingUtilities.invokeAndWait(() -> done[0] = condition.getAsBoolean());
            if (done[0]) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Nicht erreicht: " + step);
    }

    /** Ein Glossar im Speicher, das jede Entscheidung mitschreibt. */
    static final class FakeGlossary implements GlossaryAccess {

        final Map<String, Entry> entries = new LinkedHashMap<>();
        final List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> searches = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile boolean aiAvailable;
        volatile boolean conflictOnce;
        volatile JobProgress progress = JobProgress.NONE;
        volatile JobState state = JobState.RUNNING;
        /** Hält {@link #classify} auf, bis der Test ihn freigibt. */
        volatile java.util.concurrent.CountDownLatch startGate;
        final List<Runnable> observers = new java.util.concurrent.CopyOnWriteArrayList<>();
        private int next = 4;

        synchronized void put(Entry entry) {
            entries.put(entry.id(), entry);
        }

        /** Meldet eine Änderung wie der Core, aus einem fremden Thread. */
        void changed() {
            new Thread(() -> observers.forEach(Runnable::run)).start();
        }

        final List<com.aresstack.spaceharvester.application.jobs.JobListener> jobObservers =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        /** Welche Sicht gelesen wurde: „full …“ oder „refine …“ samt Anfrage. */
        final List<String> reads = new java.util.concurrent.CopyOnWriteArrayList<>();
        final Map<String, GlossaryDerivation.Category> categories = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<String, GlossaryDerivation.Importance> importance = new java.util.concurrent.ConcurrentHashMap<>();

        synchronized void put(Entry entry, GlossaryDerivation.Category category,
                              GlossaryDerivation.Importance weight) {
            entries.put(entry.id(), entry);
            categories.put(entry.id(), category);
            if (weight != null) {
                importance.put(entry.id(), weight);
            }
        }

        /** Ein Auftrag dieser Art meldet seinen Stand wie der Core, aus einem fremden Thread. */
        void jobChanged(JobType type) {
            JobStatus status = new JobStatus("job-1", type, "wiki", null, "app", state, progress, null, false, T, T,
                    state.terminal() ? T : null);
            new Thread(() -> jobObservers.forEach(observer -> observer.changed(status))).start();
        }

        @Override
        public Runnable watchJobs(com.aresstack.spaceharvester.application.jobs.JobListener listener) {
            jobObservers.add(listener);
            return () -> jobObservers.remove(listener);
        }

        @Override
        public Overview overview(com.aresstack.spaceharvester.application.holdings.Selection selection,
                                 GlossaryOverview.Query query, BooleanSupplier cancelled) {
            reads.add("full " + selection + " " + text(query));
            return view(query);
        }

        @Override
        public Overview refine(Overview previous, GlossaryOverview.Query query, BooleanSupplier cancelled) {
            reads.add("refine " + text(query));
            return view(query);
        }

        static String text(GlossaryOverview.Query query) {
            return "q=" + query.q() + " categories=" + (query.categories() == null ? null
                    : new java.util.TreeSet<>(query.categories())) + " status="
                    + new java.util.TreeSet<>(query.statuses());
        }

        /** Wie der Core: gefiltert, gezählt, Einträge mit Wichtigkeit zuerst, sonst alphabetisch. */
        private synchronized Overview view(GlossaryOverview.Query query) {
            java.util.function.Predicate<Entry> text = e -> query.q() == null
                    || e.normalized().contains(query.q().toLowerCase(java.util.Locale.GERMAN));
            java.util.function.Predicate<Entry> byStatus = e -> query.statuses().contains(e.status());
            java.util.function.Predicate<Entry> byCategory = e -> query.categories() == null
                    || query.categories().contains(category(e));
            List<Row> rows = entries.values().stream().filter(text).filter(byStatus).filter(byCategory)
                    .sorted(Comparator.comparing((Entry e) -> importance.containsKey(e.id()) ? 0 : 1)
                            .thenComparing(e -> -(importance.containsKey(e.id()) ? importance.get(e.id()).pages()
                                    : 0))
                            .thenComparing(Entry::normalized))
                    .map(e -> new Row(e, importance.get(e.id()), category(e))).toList();
            Map<GlossaryDerivation.Category, Integer> byCat = new java.util.EnumMap<>(
                    GlossaryDerivation.Category.class);
            for (GlossaryDerivation.Category c : GlossaryDerivation.Category.values()) {
                byCat.put(c, (int) entries.values().stream().filter(text).filter(byStatus)
                        .filter(e -> category(e) == c).count());
            }
            Map<ReviewStatus, Integer> byStat = new java.util.EnumMap<>(ReviewStatus.class);
            for (ReviewStatus st : ReviewStatus.values()) {
                byStat.put(st, (int) entries.values().stream().filter(text).filter(byCategory)
                        .filter(e -> e.status() == st).count());
            }
            return new Overview(rows, byCat, byStat, 40, 1, null);
        }

        private GlossaryDerivation.Category category(Entry e) {
            return categories.getOrDefault(e.id(), GlossaryDerivation.Category.UNKLAR);
        }

        @Override
        public Runnable observe(Runnable onChange) {
            observers.add(onChange);
            return () -> observers.remove(onChange);
        }

        @Override
        public synchronized Page search(Search search) {
            // Der Reiter liest über die fertige Sicht; die Suche bleibt für den Dienst.
            searches.add("status=" + search.status() + " q=" + search.q());
            List<Entry> found = entries.values().stream()
                    .filter(e -> search.status() == null || e.status() == search.status())
                    .filter(e -> search.q() == null || e.normalized().contains(search.q().toLowerCase(
                            java.util.Locale.GERMAN)))
                    .sorted(Comparator.comparing(Entry::normalized))
                    .toList();
            return new Page(found, null, aiAvailable);
        }

        @Override
        public synchronized Entry add(NewEntry entry) {
            String id = String.valueOf(next++);
            Entry created = new Entry(id, entry.term(), entry.term().toLowerCase(java.util.Locale.GERMAN),
                    entry.sourceId(), entry.spaceKey(), RelationOrigin.MANUAL, ReviewStatus.CONFIRMED, List.of(),
                    false, entry.explanation(), null, T, T, T, 1);
            entries.put(id, created);
            return created;
        }

        @Override
        public synchronized Entry explanation(String id, String explanation, long revision) {
            Entry e = check(id, revision);
            return store(new Entry(e.id(), e.term(), e.normalized(), e.sourceId(), e.spaceKey(), e.origin(),
                    e.status(), e.evidence(), e.evidenceStale(), explanation, e.generation(), e.createdAt(), T,
                    e.reviewedAt(), e.revision() + 1));
        }

        @Override
        public synchronized Entry categorize(String id, GlossaryDerivation.Category category, long revision) {
            calls.add("categorize " + id + " " + category + "@" + revision);
            Entry e = check(id, revision);
            categories.put(id, category);
            return store(new Entry(e.id(), e.term(), e.normalized(), e.sourceId(), e.spaceKey(), e.origin(),
                    e.status(), e.evidence(), e.evidenceStale(), e.explanation(), e.generation(), e.createdAt(), T,
                    e.reviewedAt(), e.revision() + 1));
        }

        @Override
        public List<History> history(String id) {
            return List.of(new History("create", "angelo", T, ReviewStatus.CONFIRMED));
        }

        @Override
        public synchronized Entry confirm(String id, long revision) {
            calls.add("confirm " + id + "@" + revision);
            return status(check(id, revision), ReviewStatus.CONFIRMED);
        }

        @Override
        public synchronized Entry reject(String id, long revision) {
            calls.add("reject " + id + "@" + revision);
            return status(check(id, revision), ReviewStatus.REJECTED);
        }

        @Override
        public synchronized Entry restore(String id, long revision) {
            calls.add("restore " + id + "@" + revision);
            return status(check(id, revision), ReviewStatus.PROPOSED);
        }

        @Override
        public synchronized Optional<Entry> remove(String id, long revision) {
            calls.add("remove " + id + "@" + revision);
            Entry e = check(id, revision);
            if (e.origin() == RelationOrigin.MANUAL) {
                entries.remove(id);
                return Optional.empty();
            }
            return Optional.of(status(e, ReviewStatus.REJECTED));
        }

        private Entry check(String id, long revision) {
            Entry e = entries.get(id);
            if (e == null || e.revision() != revision || conflictOnce) {
                conflictOnce = false;
                throw new ConflictException(id);
            }
            return e;
        }

        private Entry status(Entry e, ReviewStatus status) {
            return store(new Entry(e.id(), e.term(), e.normalized(), e.sourceId(), e.spaceKey(), e.origin(), status,
                    e.evidence(), e.evidenceStale(), e.explanation(), e.generation(), e.createdAt(), T, T,
                    e.revision() + 1));
        }

        private Entry store(Entry e) {
            entries.put(e.id(), e);
            return e;
        }

        @Override
        public List<JobStatus> classify(String sourceId, String spaceKey) {
            calls.add("classify " + sourceId + "/" + spaceKey);
            if (startGate != null) {
                try {
                    startGate.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return List.of(classification("job-1"));
        }

        @Override
        public JobStatus classification(String jobId) {
            return job(jobId, state, progress, null);
        }

        @Override
        public JobStatus cancelClassification(String jobId) {
            calls.add("cancel " + jobId);
            state = JobState.CANCELLED;
            return classification(jobId);
        }

        @Override
        public Optional<TermQueries.Snippet> snippet(RelationEvidence evidence, int chars) {
            return Optional.of(new TermQueries.Snippet("das ", "Datenprodukt", " liefert", true, true));
        }

        @Override
        public Optional<String> pageTitle(String sourceId, String pageId) {
            return Optional.of("Seite " + pageId);
        }
    }
}
