package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.runtime.GlossaryAccess;
import com.aresstack.spaceharvester.app.runtime.GlossaryAccess.Entry;
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
 * Der Reiter „Fachglossar“ unter Begriffe (#122 Stufe 2) mit einem Glossar im Speicher: Einträge lesen sich in Worten,
 * die Zeilenaktionen folgen Herkunft und Stand, Filter und Suche gehen an das Glossar, der KI-Lauf zeigt seinen
 * Fortschritt im Schleier.
 */
class GlossaryTabTest {

    private static final Instant T = Instant.parse("2026-11-10T08:00:00Z");

    @Test
    void entriesReadAsTermScopeOriginStatusEvidenceAndTheButtonsBelongToTheGlossaryTab() throws Exception {
        FakeGlossary glossary = new FakeGlossary();
        glossary.put(entry("1", "Datenprodukt", "wiki", "DH", RelationOrigin.AI, ReviewStatus.PROPOSED, 3, true));
        glossary.put(entry("2", "Haushaltsverfahren", null, null, RelationOrigin.MANUAL, ReviewStatus.CONFIRMED,
                0, false));
        TermsView[] view = new TermsView[1];
        List<Object> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            view[0] = new TermsView(null);
            seen.add(view[0].glossary().addButton().isVisible());
            view[0].tabs().select(TermsView.GLOSSARY);
            seen.add(view[0].glossary().addButton().isVisible());
            seen.add(view[0].glossary().detectButton().isVisible());
            view[0].tabs().select(TermsView.GRAPH);
            seen.add(view[0].glossary().detectButton().isVisible());
        });
        assertThat(seen).containsExactly(false, true, true, false);

        GlossaryTab[] tab = new GlossaryTab[1];
        SwingUtilities.invokeAndWait(() -> {
            tab[0] = new GlossaryTab(null, () -> Optional.of(glossary));
            tab[0].load();
        });
        await("zwei Einträge", () -> tab[0].directory().table().getRowCount() == 2);
        SwingUtilities.invokeAndWait(() -> {
            javax.swing.JTable table = tab[0].directory().table();
            assertThat(row(table, 0)).containsExactly("Datenprodukt", "wiki · DH", "KI", "Vorgeschlagen",
                    "3 · veraltet", "Beschreibt ein Produkt", "");
            assertThat(row(table, 1)).containsExactly("Haushaltsverfahren", "Alle Quellen", "Manuell", "Bestätigt",
                    "–", "Beschreibt ein Produkt", "");
            List<RowActions.Action> proposedAi = tab[0].actions(tab[0].entryAtView(0));
            assertThat(proposedAi.stream().map(RowActions.Action::tooltip)).containsExactly(GlossaryTab.CONFIRM,
                    GlossaryTab.REJECT, GlossaryTab.EDIT, GlossaryTab.ONLY_MANUAL);
            assertThat(proposedAi.stream().map(RowActions.Action::enabled)).containsExactly(true, true, true, false);
            List<RowActions.Action> confirmedManual = tab[0].actions(tab[0].entryAtView(1));
            assertThat(confirmedManual.stream().map(RowActions.Action::tooltip)).containsExactly(
                    GlossaryTab.ALREADY_CONFIRMED, GlossaryTab.REJECT, GlossaryTab.EDIT, GlossaryTab.REMOVE);
            assertThat(confirmedManual.stream().map(RowActions.Action::enabled)).containsExactly(false, true, true,
                    true);
            // Ohne KI-Dienst bleibt „Fachbegriffe ermitteln“ gesperrt, Hinzufügen geht.
            assertThat(tab[0].detectButton().isEnabled()).isFalse();
            assertThat(tab[0].detectButton().getToolTipText()).isEqualTo(GlossaryTab.NO_AI);
            assertThat(tab[0].addButton().isEnabled()).isTrue();
        });
    }

    @Test
    void reviewGoesToTheGlossaryWithTheShownRevisionAndRejectedStayGreyUntilRestored() throws Exception {
        FakeGlossary glossary = new FakeGlossary();
        glossary.put(entry("1", "Datenprodukt", "wiki", "DH", RelationOrigin.AI, ReviewStatus.PROPOSED, 1, false));
        GlossaryTab[] tab = new GlossaryTab[1];
        SwingUtilities.invokeAndWait(() -> {
            tab[0] = new GlossaryTab(null, () -> Optional.of(glossary));
            tab[0].load();
        });
        await("ein Eintrag", () -> tab[0].directory().table().getRowCount() == 1);
        SwingUtilities.invokeAndWait(() -> tab[0].actions(tab[0].entryAtView(0)).get(1).run().run());
        await("abgelehnt", () -> "Abgelehnt".equals(tab[0].directory().table().getValueAt(0, 3)));
        assertThat(glossary.calls).containsExactly("reject 1@1");
        SwingUtilities.invokeAndWait(() -> {
            javax.swing.JTable table = tab[0].directory().table();
            table.clearSelection();
            java.awt.Component cell = table.prepareRenderer(table.getCellRenderer(0, 0), 0, 0);
            assertThat(cell.getBackground()).isEqualTo(TermsView.REJECTED_BACKGROUND);
            // Abgelehnt bietet die Zeile „Ablehnung zurücknehmen“ statt „Ablehnen“.
            List<RowActions.Action> actions = tab[0].actions(tab[0].entryAtView(0));
            assertThat(actions.get(1).tooltip()).isEqualTo(GlossaryTab.RESTORE);
            actions.get(1).run().run();
        });
        await("wieder vorgeschlagen", () -> "Vorgeschlagen".equals(tab[0].directory().table().getValueAt(0, 3)));
        assertThat(glossary.calls).containsExactly("reject 1@1", "restore 1@2");
        // Bestätigt bleibt der Eintrag als KI-erzeugt erkennbar.
        SwingUtilities.invokeAndWait(() -> tab[0].actions(tab[0].entryAtView(0)).get(0).run().run());
        await("bestätigt", () -> "Bestätigt".equals(tab[0].directory().table().getValueAt(0, 3)));
        SwingUtilities.invokeAndWait(() ->
                assertThat(tab[0].directory().table().getValueAt(0, 2)).isEqualTo("KI"));
        // Ein fremder Stand lädt die Liste neu und meldet es unter der Tabelle.
        glossary.conflictOnce = true;
        SwingUtilities.invokeAndWait(() -> tab[0].actions(tab[0].entryAtView(0)).get(1).run().run());
        await("Konflikt gemeldet", () -> GlossaryTab.CONFLICT.equals(tab[0].directory().hint()));
        SwingUtilities.invokeAndWait(() ->
                assertThat(tab[0].directory().table().getValueAt(0, 3)).isEqualTo("Bestätigt"));
    }

    @Test
    void filterAndSearchGoToTheGlossaryAndAManualEntryIsCreatedAndRemoved() throws Exception {
        FakeGlossary glossary = new FakeGlossary();
        glossary.put(entry("1", "Datenprodukt", "wiki", "DH", RelationOrigin.AI, ReviewStatus.PROPOSED, 1, false));
        glossary.put(entry("2", "Berechtigungsmanagement", "wiki", null, RelationOrigin.AI, ReviewStatus.REJECTED,
                1, false));
        GlossaryTab[] tab = new GlossaryTab[1];
        SwingUtilities.invokeAndWait(() -> {
            tab[0] = new GlossaryTab(null, () -> Optional.of(glossary));
            tab[0].load();
        });
        await("zwei Einträge", () -> tab[0].directory().table().getRowCount() == 2);
        SwingUtilities.invokeAndWait(() -> tab[0].filter("Abgelehnt").doClick());
        await("nur der abgelehnte", () -> tab[0].directory().table().getRowCount() == 1
                && "Berechtigungsmanagement".equals(tab[0].directory().table().getValueAt(0, 0)));
        assertThat(glossary.searches).last().isEqualTo("status=REJECTED q=null");
        SwingUtilities.invokeAndWait(() -> {
            tab[0].filter(GlossaryTab.ALL).doClick();
            tab[0].directory().search().setText("daten");
        });
        await("Suche nach daten", () -> tab[0].directory().table().getRowCount() == 1
                && "Datenprodukt".equals(tab[0].directory().table().getValueAt(0, 0)));
        assertThat(glossary.searches).last().isEqualTo("status=null q=daten");
        SwingUtilities.invokeAndWait(() -> {
            tab[0].directory().search().setText("");
            tab[0].create(new GlossaryAccess.NewEntry("Haushaltsverfahren", null, null, "Verfahren des Haushalts"));
        });
        await("drei Einträge, der neue gewählt", () -> tab[0].directory().table().getRowCount() == 3
                && tab[0].directory().table().getSelectedRow() >= 0
                && "Haushaltsverfahren".equals(tab[0].directory().table().getValueAt(
                        tab[0].directory().table().getSelectedRow(), 0)));
        SwingUtilities.invokeAndWait(() -> {
            int row = tab[0].directory().table().getSelectedRow();
            assertThat(tab[0].directory().table().getValueAt(row, 2)).isEqualTo("Manuell");
            assertThat(tab[0].directory().table().getValueAt(row, 3)).isEqualTo("Bestätigt");
            tab[0].actions(tab[0].entryAtView(row)).get(3).run().run();
        });
        await("wieder zwei", () -> tab[0].directory().table().getRowCount() == 2);
        assertThat(glossary.entries).doesNotContainKey("3");
    }

    @Test
    void detectingShowsProgressInTheOverlayAndReloadsWhenTheJobEnds() throws Exception {
        FakeGlossary glossary = new FakeGlossary();
        glossary.aiAvailable = true;
        GlossaryTab[] tab = new GlossaryTab[1];
        SwingUtilities.invokeAndWait(() -> {
            tab[0] = new GlossaryTab(null, () -> Optional.of(glossary));
            tab[0].load();
        });
        await("geladen, leer", () -> GlossaryTab.EMPTY.equals(tab[0].directory().hint()));
        SwingUtilities.invokeAndWait(() -> tab[0].detect("wiki", null));
        await("Lauf läuft im Schleier", () -> !tab[0].jobIds().isEmpty() && tab[0].overlay().isBusy());
        assertThat(glossary.calls).contains("classify wiki/null");
        glossary.progress = new JobProgress(40, 12, 3, 0);
        await("Fortschritt", () -> tab[0].overlay().text().equals(
                "Fachbegriffe ermitteln · 12 von 40 Begriffen · 3 Einträge"));
        SwingUtilities.invokeAndWait(() -> assertThat(tab[0].detectButton().isEnabled()).isFalse());
        glossary.put(entry("9", "Datenprodukt", "wiki", null, RelationOrigin.AI, ReviewStatus.PROPOSED, 2, false));
        glossary.state = JobState.COMPLETED;
        await("Schleier weg, Liste neu", () -> !tab[0].overlay().isBusy() && tab[0].jobIds().isEmpty()
                && tab[0].directory().table().getRowCount() == 1);
        SwingUtilities.invokeAndWait(() -> assertThat(tab[0].detectButton().isEnabled()).isTrue());
    }

    @Test
    void textsForProgressOutcomeAndDetails() {
        assertThat(GlossaryTab.progressText(JobProgress.NONE, false))
                .isEqualTo("Fachbegriffe ermitteln · Begriffe werden gesammelt …");
        assertThat(GlossaryTab.progressText(new JobProgress(5, 2, 1, 0), true))
                .isEqualTo("Fachbegriffe ermitteln · 2 von 5 Begriffen · 1 Eintrag · wird abgebrochen …");
        // Mehrere Quellen: Summen und die Zahl der Quellen.
        assertThat(GlossaryTab.progressText(List.of(job("a", JobState.RUNNING, new JobProgress(5, 2, 1, 0), null),
                job("b", JobState.RUNNING, new JobProgress(3, 3, 2, 0), null))))
                .isEqualTo("Fachbegriffe ermitteln · 5 von 8 Begriffen · 3 Einträge · 2 Quellen");
        assertThat(GlossaryTab.outcomeText(List.of(job("j", JobState.CANCELLED, new JobProgress(5, 2, 1, 0), null))))
                .isEqualTo("Fachbegriffe ermitteln abgebrochen; 1 Eintrag bleibt, Entscheidungen unverändert");
        assertThat(GlossaryTab.outcomeText(List.of(job("j", JobState.COMPLETED, new JobProgress(5, 5, 2, 0), null),
                job("k", JobState.FAILED, JobProgress.NONE, new JobError("ai", "Dienst nicht erreichbar")))))
                .isEqualTo("Fachbegriffe ermitteln fehlgeschlagen (Quelle wiki): Dienst nicht erreichbar · "
                        + "Entscheidungen unverändert");
        assertThat(GlossaryTab.outcomeText(List.of(job("j", JobState.COMPLETED, new JobProgress(5, 5, 2, 0), null))))
                .isEqualTo("Fachbegriffe ermitteln abgeschlossen: 2 Einträge aus 5 geprüften Begriffen");
        Entry stale = entry("1", "Datenprodukt", "wiki", "DH", RelationOrigin.AI, ReviewStatus.CONFIRMED, 2, true);
        String text = GlossaryTab.detailText(stale, List.of("Seite A: … das Datenprodukt liefert …"));
        assertThat(text).startsWith("Beschreibt ein Produkt\n\nGilt für: wiki · DH\nHerkunft: KI (gpt-4o/p1) · "
                + "Stand: Bestätigt am ");
        assertThat(text).contains("2 Belege · Beleg veraltet").contains("• Seite A: … das Datenprodukt liefert …")
                .contains("… und 1 weitere");
        assertThat(GlossaryTab.invalidTerm("  ")).isEqualTo("Den Fachbegriff angeben.");
        assertThat(GlossaryTab.invalidTerm("RSL")).isNull();
        assertThat(GlossaryTab.snippetText(new TermQueries.Snippet("vor  dem ", "Begriff", " danach", true, false)))
                .isEqualTo("…vor dem Begriff danach");
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
        return new JobStatus(id, JobType.ENRICHMENT, "wiki", null, "app", state, progress, error, false, T,
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
        private int next = 3;

        synchronized void put(Entry entry) {
            entries.put(entry.id(), entry);
        }

        @Override
        public synchronized Page search(Search search) {
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
