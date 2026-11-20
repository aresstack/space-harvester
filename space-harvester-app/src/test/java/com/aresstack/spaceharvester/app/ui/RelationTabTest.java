package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.application.terms.RelationOrigin;
import com.aresstack.spaceharvester.application.terms.RelationScope;
import com.aresstack.spaceharvester.application.terms.RelationType;
import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import com.aresstack.spaceharvester.application.terms.TermRelation;
import com.aresstack.spaceharvester.application.terms.TermRelations;
import com.aresstack.spaceharvester.application.terms.TermText;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Blättern und Buchstabenleiste des Reiters Beziehungen gegen einen Core-Abruf mit Cursor (wie die Bezeichner). */
class RelationTabTest {

    /** Ein Core im Kleinen: alle Beziehungen, nach Schlüssel sortiert, gefiltert und seitenweise mit Cursor. */
    static final class FakeCore implements RelationTab.RelationFetch, RelationTab.RelationActions {
        private final List<TermRelation> all = new ArrayList<>();
        final List<String> calls = new CopyOnWriteArrayList<>();

        private synchronized TermRelation with(String id, long revision, ReviewStatus status) {
            for (int i = 0; i < all.size(); i++) {
                TermRelation r = all.get(i);
                if (r.id().equals(id)) {
                    if (revision >= 0 && r.revision() != revision) {
                        throw new com.aresstack.spaceharvester.application.terms.RelationConflictException(id,
                                revision);
                    }
                    TermRelation changed = new TermRelation(r.id(), r.fromTerm(), r.toTerm(), r.type(), r.scope(),
                            r.origin(), status, r.evidence(), r.note(), r.generation(), r.evidenceStale(),
                            r.createdBy(), r.createdAt(), "test", Instant.EPOCH, r.revision() + 1);
                    all.set(i, changed);
                    return changed;
                }
            }
            throw new IllegalArgumentException(id);
        }

        @Override
        public TermRelation confirm(String id, long revision) {
            return with(id, revision, ReviewStatus.CONFIRMED);
        }

        @Override
        public TermRelation reject(String id, long revision) {
            return with(id, revision, ReviewStatus.REJECTED);
        }

        @Override
        public java.util.Optional<TermRelation> remove(String id, long revision) {
            return java.util.Optional.of(with(id, revision, ReviewStatus.REJECTED));
        }

        @Override
        public TermRelation restore(String id) {
            return with(id, -1, ReviewStatus.PROPOSED);
        }

        @Override
        public TermRelation confirm(String id) {
            return with(id, -1, ReviewStatus.CONFIRMED);
        }

        @Override
        public TermRelation add(TermRelations.NewRelation relation) {
            throw new UnsupportedOperationException();
        }

        void add(String from, String to, RelationType type, ReviewStatus status) {
            String key = TermRelation.key(from, to, type, RelationScope.GLOBAL);
            all.add(new TermRelation(TermRelation.idOf(key), from, to, type, RelationScope.GLOBAL,
                    RelationOrigin.RULE, status, List.of(), null, null, false, "test", Instant.EPOCH, null, null, 1));
            all.sort(RelationTab.ORDER);
        }

        @Override
        public TermRelations.RelationList fetch(RelationType type, ReviewStatus status, String term,
                                                String startsWith, String cursor) {
            calls.add(type + "/" + status + "/" + startsWith + "/" + (cursor == null ? "-" : "cursor"));
            String prefix = startsWith == null ? null : TermText.normalize(startsWith);
            List<TermRelation> found = new ArrayList<>();
            for (TermRelation relation : all) {
                if (cursor != null && RelationTab.ORDER.compare(relation, byKey(cursor)) <= 0
                        || type != null && relation.type() != type
                        || (status == null ? relation.status() == ReviewStatus.REJECTED : relation.status() != status)
                        || prefix != null && !relation.sortTerm().startsWith(prefix)) {
                    continue;
                }
                if (found.size() == RelationTab.RELATION_LIMIT) {
                    return new TermRelations.RelationList(found, found.get(found.size() - 1).key());
                }
                found.add(relation);
            }
            return new TermRelations.RelationList(found, null);
        }

        private TermRelation byKey(String key) {
            return all.stream().filter(r -> r.key().equals(key)).findFirst().orElseThrow();
        }
    }

    @Test
    void moreThanTwoHundredRelationsArriveInPagesWithRejectedOnesLast() throws Exception {
        FakeCore core = new FakeCore();
        for (int i = 0; i < 300; i++) {
            core.add(String.format(Locale.ROOT, "Begriff%03d", i), "Lang " + i, RelationType.ABBREVIATION,
                    i % 2 == 0 ? ReviewStatus.PROPOSED : ReviewStatus.CONFIRMED);
        }
        for (int i = 0; i < 150; i++) {
            core.add(String.format(Locale.ROOT, "Alt%03d", i), "Weg " + i, RelationType.ABBREVIATION,
                    ReviewStatus.REJECTED);
        }
        RelationTab[] tab = new RelationTab[1];
        SwingUtilities.invokeAndWait(() -> {
            tab[0] = newTab(core);
            tab[0].load(null);
        });
        TermDirectory directory = tab[0].directory();
        await("erste Seite", () -> directory.table().getRowCount() >= 200);
        // Jedes Scrollen ans Ende lädt nach: erst die restlichen 100 offenen, dann die 150 abgelehnten.
        await("alle Seiten", () -> {
            directory.loadMore();
            return directory.table().getRowCount() == 450;
        });
        SwingUtilities.invokeAndWait(() -> {
            assertThat(directory.hasMore()).isFalse();
            assertThat(directory.hint()).isEqualTo("450 Einträge");
            assertThat(directory.table().getValueAt(0, 0)).isEqualTo("Begriff000");
            assertThat(directory.table().getValueAt(299, 0)).isEqualTo("Begriff299");
            assertThat(directory.table().getValueAt(300, 0)).isEqualTo("Alt000");
            assertThat(directory.table().getValueAt(300, 3)).isEqualTo("Abgelehnt");
            assertThat(directory.table().getValueAt(449, 0)).isEqualTo("Alt149");
        });
        // Offene auf zwei Seiten mit Cursor, die 150 abgelehnten passen auf eine; mehr fragt der Reiter nicht.
        assertThat(core.calls).containsExactly("null/null/null/-", "null/null/null/cursor", "null/REJECTED/null/-");
    }

    @Test
    void aLetterNarrowsTheListToTheSortTermAndShowsUndirectedEndsInKeyOrder() throws Exception {
        FakeCore core = new FakeCore();
        core.add("Zahlung", "Auszahlung", RelationType.SYNONYM, ReviewStatus.CONFIRMED);
        core.add("Antrag", "Anträge", RelationType.VARIANT, ReviewStatus.PROPOSED);
        core.add("Beleg", "Buchungsbeleg", RelationType.SYNONYM, ReviewStatus.PROPOSED);
        core.add("Bescheid", "Beschluss", RelationType.SYNONYM, ReviewStatus.REJECTED);
        RelationTab[] tab = new RelationTab[1];
        SwingUtilities.invokeAndWait(() -> {
            tab[0] = newTab(core);
            tab[0].load(null);
        });
        TermDirectory directory = tab[0].directory();
        await("alle vier", () -> directory.table().getRowCount() == 4);
        SwingUtilities.invokeAndWait(() -> {
            // Ungerichtet steht das kleinere Ende vorn: „Zahlung ↔ Auszahlung“ heißt in der Liste Auszahlung, Zahlung.
            assertThat(directory.table().getValueAt(0, 0)).isEqualTo("Antrag");
            assertThat(directory.table().getValueAt(1, 0)).isEqualTo("Auszahlung");
            assertThat(directory.table().getValueAt(1, 2)).isEqualTo("Zahlung");
            assertThat(directory.table().getValueAt(3, 0)).isEqualTo("Bescheid");
            directory.letterButton("B").doClick();
        });
        await("nur B", () -> directory.table().getRowCount() == 2);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(directory.letter()).isEqualTo("B");
            assertThat(directory.table().getValueAt(0, 0)).isEqualTo("Beleg");
            assertThat(directory.table().getValueAt(1, 0)).isEqualTo("Bescheid");
            directory.letterButton("Z").doClick();
        });
        await("unter Z nichts", () -> directory.table().getRowCount() == 0);
        SwingUtilities.invokeAndWait(() -> assertThat(directory.hint()).isEqualTo(TermDirectory.NO_MATCH));
        assertThat(core.calls).contains("null/null/B/-", "null/REJECTED/B/-", "null/null/Z/-");
    }

    @Test
    void aJudgementOnTheSecondPageRefreshesOnlyThatRowAndKeepsScopeAndSelection() throws Exception {
        FakeCore core = new FakeCore();
        for (int i = 0; i < 300; i++) {
            core.add(String.format(Locale.ROOT, "Begriff%03d", i), "Lang " + i, RelationType.ABBREVIATION,
                    ReviewStatus.PROPOSED);
        }
        RelationTab[] tab = new RelationTab[1];
        SwingUtilities.invokeAndWait(() -> {
            tab[0] = newTab(core);
            tab[0].actions(core);
            tab[0].load(null);
        });
        TermDirectory directory = tab[0].directory();
        await("beide Seiten", () -> {
            directory.loadMore();
            return directory.table().getRowCount() == 300;
        });
        core.calls.clear();
        SwingUtilities.invokeAndWait(() -> {
            directory.table().setRowSelectionInterval(250, 250);
            tab[0].reviewButtons().get(0).doClick();
        });
        await("Zeile 250 bestätigt", () -> "Bestätigt".equals(directory.table().getValueAt(250, 3)));
        SwingUtilities.invokeAndWait(() -> {
            assertThat(directory.table().getRowCount()).isEqualTo(300);
            assertThat(directory.table().getSelectedRow()).isEqualTo(250);
            assertThat(directory.table().getValueAt(250, 0)).isEqualTo("Begriff250");
            assertThat(tab[0].reviewButtons().get(0).isEnabled()).isFalse();
            // Ablehnen: die Zeile bleibt an ihrem Platz und zeigt „Abgelehnt“.
            tab[0].reviewButtons().get(1).doClick();
        });
        await("Zeile 250 abgelehnt", () -> "Abgelehnt".equals(directory.table().getValueAt(250, 3)));
        SwingUtilities.invokeAndWait(() -> {
            assertThat(directory.table().getRowCount()).isEqualTo(300);
            assertThat(directory.table().getSelectedRow()).isEqualTo(250);
            assertThat(directory.table().getValueAt(251, 0)).isEqualTo("Begriff251");
        });
        // Kein volles Neuladen: der Core wurde nicht erneut nach Seiten gefragt.
        assertThat(core.calls).isEmpty();
    }

    private static RelationTab newTab(RelationTab.RelationFetch fetch) {
        RelationTab tab = new RelationTab(null, "Beziehungen zwischen Begriffen",
                new String[]{"Begriff", "Beziehung", "Zu Begriff", "Status", "Herkunft"},
                r -> List.of(r.fromTerm(), Relation.typeText(r.type()), r.toTerm(), Relation.statusText(r.status()),
                        Relation.originText(r.origin())),
                List.of(), RelationType.ABBREVIATION, RelationTab.NO_RELATIONS);
        tab.fetch(fetch);
        return tab;
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
}
