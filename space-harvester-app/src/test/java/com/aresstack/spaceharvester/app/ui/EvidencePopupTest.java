package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.ui.controls.ReviewToggle;
import com.aresstack.spaceharvester.app.ui.controls.StatusNote;
import com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice;
import com.aresstack.spaceharvester.application.knowledge.DomainTermGraph;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import com.aresstack.spaceharvester.application.knowledge.ModelStatementAnalyzer;
import com.aresstack.spaceharvester.application.knowledge.PageStatements;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.standing.LockedPageException;
import com.aresstack.spaceharvester.application.standing.SentenceText;
import com.aresstack.spaceharvester.application.standing.StandingConflictException;
import com.aresstack.spaceharvester.application.standing.StandingDecision;
import com.aresstack.spaceharvester.application.standing.StatusQuo;
import com.aresstack.spaceharvester.application.standing.UnknownStandingTargetException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * ✓/✕ je Beleg im Overlay „Belege“ (ADR 0032, W3), mit erfundenen Seiten (Quelle „wiki“, Space „DEV“) und einem
 * Core-Ersatz, der wie {@code StatusQuo} Revisionen zählt und beim Zurücknehmen prüft.
 */
class EvidencePopupTest {

    private static final PageKey PLAN = new PageKey("wiki", "1");
    private static final PageKey ZIEL = new PageKey("wiki", "2");
    private static final PageKey NACHTLAUF = new PageKey("wiki", "3");

    @Test
    void confirmShowsCheckedAtOnceAndSavesTheFixedValueWithoutRevision() throws Exception {
        Core core = new Core();
        List<Object> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            EvidencePopup popup = popup(core);
            seen.add(popup.toggle(0).state());
            seen.add(popup.toggle(0).confirmButton().isEnabled());
            popup.toggle(0).clickConfirm();
            seen.add(popup.toggle(0).state());
            seen.add(popup.stageTexts().getFirst());
            seen.add(popup.statusText());
            seen.add(popup.linkTexts());
        });
        assertThat(seen).containsExactly(ReviewToggle.State.OPEN, true, ReviewToggle.State.CONFIRMED,
                GraphSource.STAGE_BY_HAND, "", List.of("Plan (wiki / DEV)", "Ziel (wiki / DEV)",
                        "Nachtlauf (wiki / DEV)"));
        assertThat(core.calls).containsExactly("reviews 3", "review " + id(PLAN) + " CONFIRMED");
        assertThat(core.decisions.get(id(PLAN)).review()).isEqualTo(ParsedStatements.Review.CONFIRMED);
    }

    @Test
    void rejectKeepsTheRowInPlaceDimsTheSentenceAndShowsAneutralDarkPill() throws Exception {
        Core core = new Core();
        List<Object> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            EvidencePopup popup = popup(core);
            List<String> before = popup.linkTexts();
            popup.toggle(1).clickReject();
            seen.add(popup.toggle(1).state());
            seen.add(popup.stageTexts().get(1));
            seen.add(popup.stageDark(1));
            seen.add(popup.sentenceColor(1).equals(Comic.MUTED));
            seen.add(popup.sentenceColor(0).equals(Comic.INK));
            seen.add(popup.linkTexts().equals(before));
            // Zurücknehmen stellt die vorherige Stufe wieder her.
            popup.toggle(1).clickReject();
            seen.add(popup.toggle(1).state());
            seen.add(popup.stageTexts().get(1));
            seen.add(popup.stageDark(1));
            seen.add(popup.sentenceColor(1).equals(Comic.INK));
        });
        assertThat(seen).containsExactly(ReviewToggle.State.REJECTED, EvidencePopup.REJECTED_STAGE, true, true, true,
                true, ReviewToggle.State.OPEN, "vom Prüfschritt bestätigt", false, true);
        assertThat(core.calls).containsExactly("reviews 3", "review " + id(ZIEL) + " REJECTED",
                "withdraw " + id(ZIEL) + " 1");
    }

    @Test
    void withdrawPassesTheRevisionTheOverlayShowedAndRestoresThePreviousStage() throws Exception {
        Core core = new Core();
        core.decisions.put(id(NACHTLAUF), decision(NACHTLAUF, ParsedStatements.Review.CONFIRMED, 7));
        List<Object> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            EvidencePopup popup = popup(core);
            seen.add(popup.toggle(2).state());
            seen.add(popup.stageTexts().get(2));
            seen.add(popup.toggle(2).confirmButton().getToolTipText());
            popup.toggle(2).clickConfirm();
            seen.add(popup.toggle(2).state());
            seen.add(popup.stageTexts().get(2));
            // Eine eben selbst gesetzte Prüfung nimmt das Overlay mit der zurückgegebenen Revision zurück.
            popup.toggle(0).clickConfirm();
            popup.toggle(0).clickConfirm();
            seen.add(popup.toggle(0).state());
            seen.add(popup.stageTexts().getFirst());
        });
        assertThat(seen).containsExactly(ReviewToggle.State.CONFIRMED, GraphSource.STAGE_BY_HAND,
                "Bestätigung zurücknehmen", ReviewToggle.State.OPEN,
                "nur " + com.aresstack.spaceharvester.app.ui.content.StatementProvenance.analyzer("maltparser-de"),
                ReviewToggle.State.OPEN, "nur "
                        + com.aresstack.spaceharvester.app.ui.content.StatementProvenance.analyzer("maltparser-de"));
        assertThat(core.calls).containsExactly("reviews 3", "withdraw " + id(NACHTLAUF) + " 7",
                "review " + id(PLAN) + " CONFIRMED", "withdraw " + id(PLAN) + " 1");
        assertThat(core.decisions).doesNotContainKeys(id(NACHTLAUF), id(PLAN));
    }

    @Test
    void aConflictOnWithdrawShowsTheCurrentValueAndAYellowNoteWithoutAQuestion() throws Exception {
        Core core = new Core();
        core.decisions.put(id(NACHTLAUF), decision(NACHTLAUF, ParsedStatements.Review.CONFIRMED, 7));
        List<Object> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            EvidencePopup popup = popup(core);
            // Jemand anderes verwirft die Aussage, während das Overlay offen ist.
            core.decisions.put(id(NACHTLAUF), decision(NACHTLAUF, ParsedStatements.Review.REJECTED, 8));
            popup.toggle(2).clickConfirm();
            seen.add(popup.toggle(2).state());
            seen.add(popup.stageTexts().get(2));
            seen.add(popup.statusTone());
            seen.add(popup.statusText());
            // Danach gilt die neu gelesene Revision.
            popup.toggle(2).clickReject();
            seen.add(popup.toggle(2).state());
            popup.closeStatus();
            seen.add(popup.statusText());
        });
        assertThat(seen).containsExactly(ReviewToggle.State.REJECTED, EvidencePopup.REJECTED_STAGE,
                StatusNote.Tone.WARNING, "„Der Nachtlauf startet um zwei Uhr.“ wurde inzwischen geändert; die Zeile "
                        + "zeigt den aktuellen Stand.", ReviewToggle.State.OPEN, "");
        assertThat(core.calls).containsExactly("reviews 3", "withdraw " + id(NACHTLAUF) + " 7", "reviews 1",
                "withdraw " + id(NACHTLAUF) + " 8");
        assertThat(core.decisions).doesNotContainKey(id(NACHTLAUF));
    }

    @Test
    void withoutTheRightBothKnobsAreLockedVisibleWithTheReason() throws Exception {
        Core core = new Core();
        core.readOnly.add(ZIEL);
        List<Object> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            EvidencePopup popup = popup(core);
            ReviewToggle locked = popup.toggle(1);
            seen.add(locked.isVisible());
            seen.add(locked.confirmButton().isEnabled());
            seen.add(locked.rejectButton().isEnabled());
            seen.add(locked.confirmButton().getToolTipText());
            seen.add(locked.rejectButton().getToolTipText());
            locked.clickConfirm();
            seen.add(locked.state());
            seen.add(popup.toggle(0).confirmButton().isEnabled());
        });
        assertThat(seen).containsExactly(true, false, false, EvidencePopup.NO_RIGHT, EvidencePopup.NO_RIGHT,
                ReviewToggle.State.OPEN, true);
        assertThat(EvidencePopup.NO_RIGHT).isEqualTo("Kein Recht, Aussagen dieser Seite zu prüfen");
        assertThat(core.calls).containsExactly("reviews 3");
    }

    @Test
    void aRefusalOfTheCoreSpringsBackWithOneRedMessageNamingTheStatement() throws Exception {
        Core core = new Core();
        List<Object> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            EvidencePopup popup = popup(core);
            core.refuse = new UnknownStandingTargetException("Aussage gehört nicht zur aktuellen Analyse der Seite");
            popup.toggle(0).clickConfirm();
            seen.add(popup.toggle(0).state());
            seen.add(popup.stageTexts().getFirst());
            seen.add(popup.statusTone());
            seen.add(popup.statusText());
            core.refuse = new IllegalStateException("kaputt");
            popup.toggle(1).clickReject();
            seen.add(popup.toggle(1).state());
            seen.add(popup.stageDark(1));
            seen.add(popup.statusText());
        });
        assertThat(seen).containsExactly(ReviewToggle.State.OPEN, "nur "
                        + com.aresstack.spaceharvester.app.ui.content.StatementProvenance.analyzer("maltparser-de"),
                StatusNote.Tone.ERROR,
                "„Der Plan beschreibt den Start des Nachtlaufs und …“ nicht bestätigt: Die Aussage gehört nicht mehr zur "
                        + "aktuellen Analyse der Seite.",
                ReviewToggle.State.OPEN, false,
                "„Das Ziel bleibt der pünktliche Start.“ nicht verworfen. Bitte erneut versuchen.");
    }

    @Test
    void untilTheReviewsAreReadTheKnobsWaitAndALongTitleShortensBeforeThem() throws Exception {
        Core core = new Core();
        List<Runnable> later = new ArrayList<>();
        List<Object> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            EvidencePopup popup = new EvidencePopup().reviewing(core, new EvidencePopup.Runner() {
                @Override
                public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
                    later.add(() -> {
                        try {
                            onSuccess.accept(work.call());
                        } catch (Exception e) {
                            onFailure.accept(e);
                        }
                    });
                }
            });
            String longTitle = "Plan für den Nachtlauf mit Start, Ziel und allen Abhängigkeiten der Sicherung im "
                    + "Rechenzentrum";
            popup.fill("Plan startet Nachtlauf", 2, List.of(evidence(PLAN, "Plan", false),
                    evidence(ZIEL, longTitle, false)), sentences(), false);
            seen.add(popup.toggle(0).lockReason());
            popup.toggle(0).clickConfirm();
            seen.add(popup.toggle(0).state());
            later.removeFirst().run();
            seen.add(popup.toggle(0).lockReason());
            popup.setSize(popup.getPreferredSize());
            layout(popup);
            seen.add(popup.shownLinkText(1).endsWith("…"));
            seen.add(popup.linkTexts().get(1).startsWith(longTitle));
            int right0 = SwingUtilities.convertPoint(popup.toggle(0), 0, 0, popup).x;
            int right1 = SwingUtilities.convertPoint(popup.toggle(1), 0, 0, popup).x;
            seen.add(right0 == right1);
            seen.add(SwingUtilities.convertPoint(popup.link(1), popup.link(1).getWidth(), 0, popup).x <= right1);
        });
        assertThat(seen).containsExactly(EvidencePopup.READING, ReviewToggle.State.OPEN, null, true, true, true,
                true);
    }

    @Test
    void reopenedWhileSavingTheNewRowWaitsForTheWriteAndTakesItsValueAndRevision() throws Exception {
        Core core = new Core();
        List<Runnable> later = new ArrayList<>();
        List<Object> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            EvidencePopup popup = new EvidencePopup().reviewing(core, deferred(later));
            List<DomainTermGraph.Evidence> evidence = List.of(evidence(PLAN, "Plan", false));
            popup.fill("Plan startet Nachtlauf", 1, evidence, sentences(), false);
            later.removeFirst().run();
            popup.toggle(0).clickConfirm();
            Runnable write = later.removeFirst();
            // Overlay zu und gleich wieder auf: das Lesen sieht den Stand vor dem Schreiben.
            popup.fill("Plan startet Nachtlauf", 1, evidence, sentences(), false);
            Runnable read = later.removeFirst();
            read.run();
            seen.add(popup.toggle(0).state());
            popup.toggle(0).clickReject();
            seen.add(popup.toggle(0).state());
            seen.add(later.isEmpty());
            write.run();
            seen.add(popup.toggle(0).state());
            seen.add(popup.stageTexts().getFirst());
            popup.toggle(0).clickConfirm();
            later.removeFirst().run();
            seen.add(popup.toggle(0).state());
        });
        assertThat(seen).containsExactly(ReviewToggle.State.OPEN, ReviewToggle.State.OPEN, true,
                ReviewToggle.State.CONFIRMED, GraphSource.STAGE_BY_HAND, ReviewToggle.State.OPEN);
        assertThat(core.calls).as("das zweite Lesen läuft vor dem Schreiben").containsExactly("reviews 1",
                "reviews 1", "review " + id(PLAN) + " CONFIRMED", "withdraw " + id(PLAN) + " 1");
    }

    @Test
    void aWriteThatFailsAfterClosingGoesToTheTabAndItsRetryRepeatsTheSameReview() throws Exception {
        Core core = new Core();
        List<Runnable> later = new ArrayList<>();
        List<EvidencePopup.ClosedFailure> failures = new ArrayList<>();
        List<Object> seen = new ArrayList<>();
        int[] cleared = {0};
        SwingUtilities.invokeAndWait(() -> {
            EvidencePopup popup = new EvidencePopup().reviewing(core, deferred(later)).onClosedFailure(failures::add);
            popup.openForTests(true);
            popup.fill("Plan startet Nachtlauf", 1, List.of(evidence(PLAN, "Plan", false)), sentences(), false);
            later.removeFirst().run();
            popup.toggle(0).clickReject();
            // Das Overlay geht zu, bevor das Speichern scheitert.
            popup.openForTests(false);
            core.refuse = new IllegalStateException("kaputt");
            later.removeFirst().run();
            seen.add(popup.statusText());
            seen.add(failures.size());
            EvidencePopup.ClosedFailure failure = failures.getFirst();
            StatusNote note = failure.note(() -> cleared[0]++);
            seen.add(note.text());
            seen.add(note.tone());
            seen.add(note.icon().glyph());
            seen.add(note.icon().tooltip());
            // Der Kreispfeil wiederholt dasselbe Schreiben (derselbe Wert), wieder abseits des EDT.
            core.refuse = null;
            note.icon().action().run();
            seen.add(cleared[0]);
            later.removeFirst().run();
            seen.add(failures.size());
        });
        assertThat(seen).containsExactly("", 1, "Entscheidung zu „Der Plan beschreibt den Start des Nachtlaufs und …“"
                        + " nicht gespeichert", StatusNote.Tone.ERROR, Glyph.REFRESH, EvidencePopup.RETRY_TIP, 1, 1);
        assertThat(core.calls).containsExactly("reviews 1", "review " + id(PLAN) + " REJECTED",
                "review " + id(PLAN) + " REJECTED");
        assertThat(core.decisions.get(id(PLAN)).review()).isEqualTo(ParsedStatements.Review.REJECTED);
    }

    @Test
    void aWithdrawThatFailsAfterClosingRetriesWithTheSameRevisionAndAConflictTurnsYellow() throws Exception {
        Core core = new Core();
        core.decisions.put(id(PLAN), decision(PLAN, ParsedStatements.Review.CONFIRMED, 3));
        List<Runnable> later = new ArrayList<>();
        List<EvidencePopup.ClosedFailure> failures = new ArrayList<>();
        List<Object> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            EvidencePopup popup = new EvidencePopup().reviewing(core, deferred(later)).onClosedFailure(failures::add);
            popup.openForTests(true);
            popup.fill("Plan startet Nachtlauf", 1, List.of(evidence(PLAN, "Plan", false)), sentences(), false);
            later.removeFirst().run();
            popup.toggle(0).clickConfirm(); // bestätigt → zurücknehmen
            popup.openForTests(false);
            core.refuse = new IllegalStateException("kaputt");
            later.removeFirst().run();
            seen.add(failures.getLast().text());
            seen.add(failures.getLast().conflict());
            // Inzwischen hat jemand anderes entschieden: die Wiederholung mit Revision 3 trifft auf Revision 4.
            core.refuse = null;
            core.decisions.put(id(PLAN), decision(PLAN, ParsedStatements.Review.REJECTED, 4));
            failures.getLast().note(() -> { }).icon().action().run();
            later.removeFirst().run();
            EvidencePopup.ClosedFailure conflict = failures.getLast();
            seen.add(failures.size());
            seen.add(conflict.text());
            seen.add(conflict.note(() -> { }).tone());
            seen.add(conflict.note(() -> { }).icon());
            seen.add(conflict.retry());
        });
        assertThat(seen).containsExactly("Entscheidung zu „Der Plan beschreibt den Start des Nachtlaufs und …“"
                        + " nicht gespeichert", false, 2, "Entscheidung zu „Der Plan beschreibt den Start des "
                        + "Nachtlaufs und …“ inzwischen geändert", StatusNote.Tone.WARNING, null, null);
        assertThat(core.calls).containsExactly("reviews 1", "withdraw " + id(PLAN) + " 3",
                "withdraw " + id(PLAN) + " 3");
    }

    @Test
    void aWithdrawConflictAfterClosingIsYellowWithoutRetryAndNothingReachesTheClosedOverlay() throws Exception {
        Core core = new Core();
        core.decisions.put(id(PLAN), decision(PLAN, ParsedStatements.Review.CONFIRMED, 3));
        List<Runnable> later = new ArrayList<>();
        List<EvidencePopup.ClosedFailure> failures = new ArrayList<>();
        List<Object> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            EvidencePopup popup = new EvidencePopup().reviewing(core, deferred(later)).onClosedFailure(failures::add);
            popup.openForTests(true);
            popup.fill("Plan startet Nachtlauf", 1, List.of(evidence(PLAN, "Plan", false)), sentences(), false);
            later.removeFirst().run();
            popup.toggle(0).clickConfirm();
            core.decisions.put(id(PLAN), decision(PLAN, ParsedStatements.Review.REJECTED, 4));
            popup.openForTests(false);
            later.removeFirst().run();
            seen.add(later.isEmpty());
            seen.add(failures.size());
            seen.add(failures.getFirst().conflict());
            seen.add(failures.getFirst().text());
            seen.add(popup.statusText());
        });
        assertThat(seen).containsExactly(true, 1, true, "Entscheidung zu „Der Plan beschreibt den Start des "
                + "Nachtlaufs und …“ inzwischen geändert", "");
        assertThat(core.calls).as("kein erneutes Lesen für ein geschlossenes Overlay")
                .containsExactly("reviews 1", "withdraw " + id(PLAN) + " 3");
    }

    @Test
    void theTitleLinkStaysBlueAndShowsTheYellowRingOnlyForKeyboardFocus() throws Exception {
        Core core = new Core();
        List<Object> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            EvidencePopup popup = popup(core);
            EvidencePopup.TitleLink link = popup.titleLink(0);
            seen.add(link.getForeground());
            // Erster Fokus beim Öffnen (nicht über die Tastatur): blau, kein Ring.
            focus(link, java.awt.event.FocusEvent.FOCUS_GAINED, java.awt.event.FocusEvent.Cause.ACTIVATION);
            seen.add(link.getForeground());
            seen.add(link.showsKeyboardFocus());
            // Mit Tab erreicht: blau, gelber Ring.
            focus(link, java.awt.event.FocusEvent.FOCUS_GAINED, java.awt.event.FocusEvent.Cause.TRAVERSAL_FORWARD);
            seen.add(link.getForeground());
            seen.add(link.showsKeyboardFocus());
            focus(link, java.awt.event.FocusEvent.FOCUS_LOST, java.awt.event.FocusEvent.Cause.TRAVERSAL_FORWARD);
            seen.add(link.getForeground());
            seen.add(link.showsKeyboardFocus());
        });
        assertThat(seen).containsExactly(Comic.BLUE, Comic.BLUE, false, Comic.BLUE, true, Comic.BLUE, false);
    }

    private static void focus(javax.swing.JComponent component, int id, java.awt.event.FocusEvent.Cause cause) {
        java.awt.event.FocusEvent event = new java.awt.event.FocusEvent(component, id, false, null, cause);
        for (java.awt.event.FocusListener listener : component.getFocusListeners()) {
            if (id == java.awt.event.FocusEvent.FOCUS_GAINED) {
                listener.focusGained(event);
            } else {
                listener.focusLost(event);
            }
        }
    }

    @Test
    void onALockedPageBothKnobsAreLockedWithTheReasonAndALockRefusalSaysSo() throws Exception {
        Core core = new Core();
        core.locked.add(ZIEL);
        List<Object> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            EvidencePopup popup = popup(core);
            ReviewToggle locked = popup.toggle(1);
            seen.add(locked.confirmButton().isEnabled());
            seen.add(locked.lockReason());
            seen.add(locked.confirmButton().getToolTipText());
            seen.add(popup.toggle(0).lockReason());
            // Gesperrt, während das Overlay offen war: der Core lehnt ab, die Zeile sperrt sich mit Grund.
            core.refuse = new LockedPageException();
            popup.toggle(0).clickConfirm();
            seen.add(popup.toggle(0).state());
            seen.add(popup.toggle(0).lockReason());
            seen.add(popup.statusText());
        });
        assertThat(seen).containsExactly(false, EvidencePopup.LOCKED_PAGE, EvidencePopup.LOCKED_PAGE, null,
                ReviewToggle.State.OPEN, EvidencePopup.LOCKED_PAGE,
                "„Der Plan beschreibt den Start des Nachtlaufs und …“ nicht bestätigt: Die Seite ist gesperrt.");
        assertThat(EvidencePopup.LOCKED_PAGE).isEqualTo("Seite ist gesperrt; Aussagen lassen sich nicht prüfen");
    }

    @Test
    void readingAndWritingUseTheAnalyzerOfTheEvidenceEvenIfTheSettingChangesInBetween() throws Exception {
        Core core = new Core();
        List<Runnable> later = new ArrayList<>();
        List<Object> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            EvidencePopup popup = new EvidencePopup().reviewing(core, deferred(later));
            popup.openForTests(true);
            // Ein Beleg aus der KI-Analyse, einer aus dem MaltParser, auf derselben Seite.
            popup.fill("Plan startet Nachtlauf", 2, List.of(
                    evidence(PLAN, "Plan", false, ModelStatementAnalyzer.ID),
                    evidence(ZIEL, "Ziel", false, "maltparser-de")), sentences(), false);
            later.removeFirst().run();
            seen.add(popup.toggle(0).lockReason());
            // Die Einstellung wechselt hier zum MaltParser (nicht sichtbar für das Overlay): ✓ bleibt bei der KI.
            popup.toggle(0).clickConfirm();
            later.removeFirst().run();
            popup.toggle(0).clickConfirm();
            later.removeFirst().run();
            popup.toggle(1).clickReject();
            later.removeFirst().run();
            seen.add(popup.toggle(0).state());
            seen.add(popup.toggle(1).state());
        });
        assertThat(seen).containsExactly(null, ReviewToggle.State.OPEN, ReviewToggle.State.REJECTED);
        assertThat(core.calls).containsExactly("reviews 1", "reviews 1", "review " + id(PLAN) + " CONFIRMED",
                "withdraw " + id(PLAN) + " 1", "review " + id(ZIEL) + " REJECTED");
        assertThat(core.analyzers).as("je Analysator ein Lesen, dann jede Zeile in ihrer Ablage").containsExactly(
                AnalyzerChoice.PARSER, AnalyzerChoice.MODEL, AnalyzerChoice.MODEL, AnalyzerChoice.MODEL,
                AnalyzerChoice.PARSER);
    }

    @Test
    void onlyTheTitleItselfOpensThePageNotTheEmptySpaceBesideIt() throws Exception {
        Core core = new Core();
        List<Object> seen = new ArrayList<>();
        List<EvidencePopup.Target> opened = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            EvidencePopup popup = popup(core);
            popup.onOpen(opened::add);
            popup.setSize(popup.getPreferredSize());
            layout(popup);
            javax.swing.JLabel link = popup.link(0);
            java.awt.Container foot = link.getParent().getParent();
            seen.add(link.getWidth() == link.getPreferredSize().width);
            java.awt.Point linkEnd = SwingUtilities.convertPoint(link, link.getWidth(), link.getHeight() / 2, popup);
            int toggleStart = SwingUtilities.convertPoint(popup.toggle(0), 0, 0, popup).x;
            seen.add(toggleStart - linkEnd.x > 100);
            // Mitten in die Lücke zwischen Titel und ✓: dort liegt nicht der Link, ein Klick öffnet nichts.
            java.awt.Point gap = new java.awt.Point((linkEnd.x + toggleStart) / 2, linkEnd.y);
            java.awt.Component hit = SwingUtilities.getDeepestComponentAt(popup, gap.x, gap.y);
            seen.add(hit != link);
            seen.add(SwingUtilities.isDescendingFrom(hit, foot));
            java.awt.Point local = SwingUtilities.convertPoint(popup, gap, hit);
            java.awt.event.MouseEvent click = new java.awt.event.MouseEvent(hit, java.awt.event.MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(), 0, local.x, local.y, 1, false);
            for (java.awt.event.MouseListener listener : hit.getMouseListeners()) {
                listener.mouseClicked(click);
            }
            seen.add(opened.size());
            popup.clickLink(0);
            seen.add(opened.size());
        });
        assertThat(seen).containsExactly(true, true, true, true, 0, 1);
    }

    @Test
    void aRedMessageAfterEnterOnTheKnobGivesTheKeyboardFocusBackWithItsRing() throws Exception {
        Core core = new Core();
        List<String> requests = new ArrayList<>();
        java.awt.Component[] owner = new java.awt.Component[1];
        List<Object> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            EvidencePopup popup = popup(core);
            popup.focusForTests(new EvidencePopup.Focus() {
                @Override
                public java.awt.Component owner() {
                    return owner[0];
                }

                @Override
                public void request(java.awt.Component target, java.awt.event.FocusEvent.Cause cause) {
                    requests.add(name(popup, target) + " " + cause);
                    owner[0] = target;
                }
            });
            // Mit Tab auf ✓ der ersten Zeile, dann Enter: der Core lehnt ab, die Meldung baut das Overlay neu.
            javax.swing.JButton confirm = popup.toggle(0).confirmButton();
            focus(confirm, java.awt.event.FocusEvent.FOCUS_GAINED, java.awt.event.FocusEvent.Cause.TRAVERSAL_FORWARD);
            owner[0] = confirm;
            core.refuse = new IllegalStateException("kaputt");
            confirm.doClick();
            seen.add(popup.statusTone());
            // Mit der Maus auf ✕ der zweiten Zeile: zurück ohne Ring.
            javax.swing.JButton reject = popup.toggle(1).rejectButton();
            focus(confirm, java.awt.event.FocusEvent.FOCUS_LOST, java.awt.event.FocusEvent.Cause.MOUSE_EVENT);
            focus(reject, java.awt.event.FocusEvent.FOCUS_GAINED, java.awt.event.FocusEvent.Cause.MOUSE_EVENT);
            owner[0] = reject;
            reject.doClick();
            // Das ✕ der Meldung verschwindet mit ihr: der Fokus geht an den ersten Link.
            owner[0] = popup.statusCloseForTests();
            popup.closeStatus();
        });
        assertThat(seen).containsExactly(StatusNote.Tone.ERROR);
        assertThat(requests).containsExactly("✓0 TRAVERSAL_FORWARD", "✕1 ACTIVATION", "link0 ACTIVATION");
    }

    private static String name(EvidencePopup popup, java.awt.Component target) {
        for (int i = 0; i < 3; i++) {
            if (target == popup.toggle(i).confirmButton()) {
                return "✓" + i;
            }
            if (target == popup.toggle(i).rejectButton()) {
                return "✕" + i;
            }
            if (target == popup.link(i)) {
                return "link" + i;
            }
        }
        return String.valueOf(target);
    }

    @Test
    void aMessageNamesTheSentenceBrieflyOrElseThePage() {
        assertThat(EvidencePopup.brief("Kurz.", "Plan")).isEqualTo("„Kurz.“");
        assertThat(EvidencePopup.brief("Der Plan beschreibt den Start des Nachtlaufs und das Ziel der Sicherung.",
                "Plan")).isEqualTo("„Der Plan beschreibt den Start des Nachtlaufs und …“");
        assertThat(EvidencePopup.brief(null, "Plan")).isEqualTo("Aussage auf „Plan“");
    }

    // ---- Daten

    private static EvidencePopup popup(Core core) {
        EvidencePopup popup = new EvidencePopup().reviewing(core, new EvidencePopup.Runner() {
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
        popup.openForTests(true);
        popup.fill("Plan startet Nachtlauf", 3, List.of(evidence(PLAN, "Plan", false),
                checkedEvidence(ZIEL, "Ziel"), evidence(NACHTLAUF, "Nachtlauf", true)), sentences(), false);
        return popup;
    }

    private static EvidencePopup.Runner deferred(List<Runnable> later) {
        return new EvidencePopup.Runner() {
            @Override
            public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
                later.add(() -> {
                    T result;
                    try {
                        result = work.call();
                    } catch (Exception e) {
                        onFailure.accept(e);
                        return;
                    }
                    onSuccess.accept(result);
                });
            }
        };
    }

    static Map<String, SentenceText> sentences() {
        Map<String, SentenceText> sentences = new HashMap<>();
        sentences.put(id(PLAN), new SentenceText("Der Plan beschreibt den Start des Nachtlaufs und das Ziel.", null,
                0, 58, 9, 19, false));
        sentences.put(id(ZIEL), new SentenceText("Das Ziel bleibt der pünktliche Start.", null, 0, 37, 9, 15,
                false));
        sentences.put(id(NACHTLAUF), new SentenceText("Der Nachtlauf startet um zwei Uhr.", null, 0, 34, 14, 21,
                false));
        return sentences;
    }

    static String id(PageKey page) {
        return page.sourceId() + "/" + page.pageId() + "@x#s0.v1";
    }

    /** Ein Beleg der Fachsicht; {@code confirmed}: wie der Graph eine von Hand bestätigte Aussage liefert. */
    static DomainTermGraph.Evidence evidence(PageKey page, String title, boolean confirmed) {
        return evidence(page, title, confirmed, "maltparser-de");
    }

    /** Wie oben, aus der Analyse des Analysators {@code analyzerId}. */
    static DomainTermGraph.Evidence evidence(PageKey page, String title, boolean confirmed, String analyzerId) {
        ParsedStatements.Statement statement = new ParsedStatements.Statement(id(page), 0, 4, 10, "startet",
                "starten", ParsedStatements.Voice.ACTIVE, ParsedStatements.Clause.MAIN, true, List.of(), Set.of(), 0,
                ParsedStatements.Quality.PLAUSIBLE, List.of(), confirmed ? ParsedStatements.Review.CONFIRMED
                : ParsedStatements.Review.UNREVIEWED);
        return new DomainTermGraph.Evidence(new KnowledgeGraph.PageRef(page, "DEV", title,
                PageStatements.State.CURRENT, null), statement, analyzerId, "Plan", "Nachtlauf",
                confirmed ? DomainTermGraph.Trust.GEPRUEFT : DomainTermGraph.Trust.UNGEPRUEFT);
    }

    /** Ein Beleg, den der Prüfschritt bestätigt hat. */
    static DomainTermGraph.Evidence checkedEvidence(PageKey page, String title) {
        DomainTermGraph.Evidence plain = evidence(page, title, false);
        ParsedStatements.Statement s = plain.statement();
        ParsedStatements.Statement checked = new ParsedStatements.Statement(s.id(), s.sentence(), s.start(), s.end(),
                s.predicateText(), s.predicateLemma(), s.voice(), s.clause(), s.core(), s.arguments(), s.findings(),
                s.paragraph(), s.quality(), s.qualityReasons(), s.review(), s.features(), s.derivation(),
                new ParsedStatements.Check(ParsedStatements.Verdict.STIMMT, "v1", false));
        return new DomainTermGraph.Evidence(plain.page(), checked, plain.analyzerId(), plain.fromText(),
                plain.toText(), DomainTermGraph.Trust.GEPRUEFT);
    }

    static StandingDecision decision(PageKey page, ParsedStatements.Review review, long revision) {
        return new StandingDecision(page, id(page), AnalyzerChoice.PARSER, null, review, revision, "kollegin",
                Instant.EPOCH, "kollegin", Instant.EPOCH);
    }

    private static void layout(java.awt.Container container) {
        container.doLayout();
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof java.awt.Container inner) {
                layout(inner);
            }
        }
    }

    /** Wie {@code StatusQuo}: Revision je Eintrag, Zurücknehmen nur bei passender Revision, ✓/✕ ohne Prüfung. */
    static final class Core implements EvidencePopup.Reviewing {

        final Map<String, StandingDecision> decisions = new LinkedHashMap<>();
        final Set<PageKey> readOnly = new HashSet<>();
        final Set<PageKey> locked = new HashSet<>();
        final List<String> calls = new ArrayList<>();
        /** Der Analysator jedes Aufrufs, in derselben Reihenfolge wie {@link #calls}. */
        final List<AnalyzerChoice> analyzers = new ArrayList<>();
        RuntimeException refuse;

        @Override
        public Map<PageKey, StatusQuo.Reviews> reviews(AnalyzerChoice analyzer, Map<PageKey, List<String>> statements) {
            calls.add("reviews " + statements.values().stream().mapToInt(List::size).sum());
            analyzers.add(analyzer);
            Map<PageKey, StatusQuo.Reviews> result = new LinkedHashMap<>();
            statements.forEach((page, ids) -> {
                Map<String, StandingDecision> found = new LinkedHashMap<>();
                ids.forEach(id -> {
                    if (decisions.containsKey(id)) {
                        found.put(id, decisions.get(id));
                    }
                });
                result.put(page, new StatusQuo.Reviews(!readOnly.contains(page), found, locked.contains(page)));
            });
            return result;
        }

        @Override
        public StandingDecision review(PageKey page, String statementId, AnalyzerChoice analyzer,
                                       ParsedStatements.Review review) {
            calls.add("review " + statementId + " " + review);
            analyzers.add(analyzer);
            if (refuse != null) {
                throw refuse;
            }
            StandingDecision old = decisions.get(statementId);
            StandingDecision next = decision(page, review, old == null ? 1 : old.revision() + 1);
            decisions.put(statementId, next);
            return next;
        }

        @Override
        public boolean withdraw(PageKey page, String statementId, AnalyzerChoice analyzer, long expectedRevision) {
            calls.add("withdraw " + statementId + " " + expectedRevision);
            analyzers.add(analyzer);
            if (refuse != null) {
                throw refuse;
            }
            StandingDecision old = decisions.get(statementId);
            long current = old == null ? 0 : old.revision();
            if (current != expectedRevision) {
                throw new StandingConflictException(current);
            }
            return decisions.remove(statementId) != null;
        }
    }
}
