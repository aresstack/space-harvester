package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.glossary.GlossaryEntry;
import com.aresstack.spaceharvester.application.glossary.TechnicalTerms;
import com.aresstack.spaceharvester.application.terms.RelationOrigin;
import com.aresstack.spaceharvester.application.terms.RelationScope;
import com.aresstack.spaceharvester.application.terms.RelationType;
import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import com.aresstack.spaceharvester.application.terms.TermRelation;
import com.aresstack.spaceharvester.application.terms.TermText;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Suchmethode „Nur Fachbegriffe“ (K5b): Ablehnen, Nachrücken, Synonyme, Hinzufügen; erfundene Begriffe. */
class TechnicalTermsPanelTest {

    private static final Instant T = Instant.parse("2026-11-21T18:00:00Z");
    private static final AccessContext CALLER = AccessContext.technicalClient("test");

    @Test
    void aRejectedTermDropsOutTheNextMovesUpAndIsSelected() throws Exception {
        FakeAccess access = FakeAccess.sample();
        TechnicalTermsPanel panel = open(access, null);
        await("Liste", () -> panel.termTexts().size() == 4);
        SwingUtilities.invokeAndWait(() -> panel.selectTerm("Kieselgrund"));
        await("Synonyme", () -> panel.synonymTexts().equals(List.of("Schotterbett")));

        SwingUtilities.invokeAndWait(() -> panel.termRow("Kieselgrund").reject.doClick());

        await("nachgerückt", () -> panel.termTexts().equals(List.of("Ankerschein", "Schattenmarke", "Wolkenpegel")));
        assertThat(access.calls).contains("rejectTerm Kieselgrund");
        SwingUtilities.invokeAndWait(() -> assertThat(panel.selectedTerm()).as("der nächste an derselben Stelle")
                .isEqualTo("Schattenmarke"));
    }

    @Test
    void aHitThroughASynonymSaysSoAndConfirmedTermsCannotBeConfirmedAgain() throws Exception {
        FakeAccess access = FakeAccess.sample();
        TechnicalTermsPanel panel = open(access, "Dunst");
        await("Treffer", () -> panel.termTexts().equals(List.of("Wolkenpegel")));

        SwingUtilities.invokeAndWait(() -> {
            TechnicalTermsPanel.Row row = panel.termRow("Wolkenpegel");
            assertThat(row.confirmed).isTrue();
            assertThat(row.confirm.isEnabled()).isFalse();
            assertThat(row.reject.getAccessibleContext().getAccessibleName()).isEqualTo(GlossaryTab.REJECT);
            assertThat(panel.termCount.count()).isEqualTo(1);
        });
    }

    @Test
    void synonymsCanBeRejectedAndAddedAndTheFieldWaitsForATerm() throws Exception {
        FakeAccess access = FakeAccess.sample();
        TechnicalTermsPanel panel = open(access, null);
        await("Liste", () -> panel.termTexts().size() == 4);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(panel.synonymField.isEnabled()).isFalse();
            assertThat(panel.addButton.getToolTipText()).isEqualTo(TechnicalTermsPanel.ADD_DISABLED);
            panel.selectTerm("Wolkenpegel");
        });
        await("Synonyme", () -> panel.synonymTexts().equals(List.of("Dunsthöhe", "Nebelmaß")));

        SwingUtilities.invokeAndWait(() -> panel.synonymRow("Dunsthöhe").reject.doClick());
        await("abgelehnt", () -> panel.synonymTexts().equals(List.of("Nebelmaß")));
        SwingUtilities.invokeAndWait(() -> {
            panel.synonymField.setText("Wolkenstand");
            panel.synonymField.postActionEvent();
        });

        await("hinzugefügt", () -> panel.synonymTexts().contains("Wolkenstand"));
        assertThat(access.calls).contains("rejectSynonym Dunsthöhe", "addSynonym Wolkenpegel Wolkenstand");
        SwingUtilities.invokeAndWait(() -> assertThat(panel.synonymCount.count()).isEqualTo(2));
    }

    @Test
    void aChangeElsewhereReloadsWithoutAClick() throws Exception {
        FakeAccess access = FakeAccess.sample();
        TechnicalTermsPanel panel = open(access, null);
        await("Liste", () -> panel.termTexts().size() == 4);

        access.entries.add(entry("Bernsteinfuge", ReviewStatus.PROPOSED));
        access.changed();

        await("neu gelesen", () -> panel.termTexts().contains("Bernsteinfuge"));
    }

    /** Bilder bei 1280 und 960 Breite für den UX-Manager; nur mit {@code SCREENSHOTS_DIR}. */
    @Test
    void renderScreenshots() throws Exception {
        String directory = System.getenv("SCREENSHOTS_DIR");
        Assumptions.assumeTrue(directory != null && !directory.isBlank());
        Path out = Path.of(directory);
        Files.createDirectories(out);
        FakeAccess access = FakeAccess.sample();
        TechnicalTermsPanel panel = open(access, null);
        await("Liste", () -> panel.termTexts().size() == 4);
        SwingUtilities.invokeAndWait(() -> panel.selectTerm("Wolkenpegel"));
        await("Synonyme", () -> panel.synonymTexts().size() == 2);
        for (int width : new int[] {1280, 960}) {
            SwingUtilities.invokeAndWait(() -> {
                javax.swing.JPanel surface = new javax.swing.JPanel(new java.awt.BorderLayout());
                surface.setBackground(Comic.SURFACE);
                surface.setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 12, 12, 12));
                surface.add(panel, java.awt.BorderLayout.CENTER);
                surface.setSize(width, 420);
                panel.split.setDividerLocation(0.5);
                Screenshots.write(surface, out.resolve("nur-fachbegriffe-" + width + ".png"));
            });
        }
    }

    private static TechnicalTermsPanel open(FakeAccess access, String q) throws Exception {
        TechnicalTermsPanel[] panel = new TechnicalTermsPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            panel[0] = new TechnicalTermsPanel(error -> {
                throw new AssertionError(error);
            });
            panel[0].access(access);
            panel[0].search(q, null);
        });
        return panel[0];
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

    static GlossaryEntry entry(String term, ReviewStatus status) {
        return new GlossaryEntry("g-" + term, term, TermText.normalize(term), RelationScope.GLOBAL,
                RelationOrigin.AI, status, List.of(), false, null, null, "ai", T, T, null, null, 1);
    }

    static TermRelation synonym(String from, String to, ReviewStatus status) {
        String id = TermRelation.idOf(TermRelation.key(from, to, RelationType.SYNONYM, RelationScope.GLOBAL));
        return new TermRelation(id, from, to, RelationType.SYNONYM, RelationScope.GLOBAL, RelationOrigin.AI, status,
                List.of(), null, null, false, "ai", T, null, null, 1);
    }

    /** Glossar und Beziehungen im Speicher; die Suche ist die echte des Core. */
    static final class FakeAccess implements TechnicalTermsPanel.Access {

        final List<GlossaryEntry> entries = new CopyOnWriteArrayList<>();
        final List<TermRelation> relations = new CopyOnWriteArrayList<>();
        final List<String> calls = new CopyOnWriteArrayList<>();
        private final List<Runnable> observers = new CopyOnWriteArrayList<>();
        private final TechnicalTerms search = new TechnicalTerms(
                (caller, scope) -> entries.stream().filter(e -> e.status() != ReviewStatus.REJECTED).toList(),
                (caller, scope) -> relations.stream().filter(r -> r.status() != ReviewStatus.REJECTED).toList());

        static FakeAccess sample() {
            FakeAccess access = new FakeAccess();
            access.entries.addAll(List.of(entry("Wolkenpegel", ReviewStatus.CONFIRMED),
                    entry("Kieselgrund", ReviewStatus.PROPOSED), entry("Ankerschein", ReviewStatus.CONFIRMED),
                    entry("Schattenmarke", ReviewStatus.PROPOSED)));
            access.relations.addAll(List.of(synonym("Wolkenpegel", "Dunsthöhe", ReviewStatus.PROPOSED),
                    synonym("Nebelmaß", "Wolkenpegel", ReviewStatus.CONFIRMED),
                    synonym("Kieselgrund", "Schotterbett", ReviewStatus.PROPOSED)));
            return access;
        }

        void changed() {
            observers.forEach(Runnable::run);
        }

        @Override
        public TechnicalTerms.Page terms(String q, TechnicalTerms.Scope scope, int limit, String cursor) {
            return search.search(CALLER, q, scope, limit, cursor);
        }

        @Override
        public List<TermRelation> synonyms(GlossaryEntry term) {
            String self = TermText.normalize(term.term());
            return relations.stream().filter(r -> r.status() != ReviewStatus.REJECTED && r.touches(self))
                    .sorted(java.util.Comparator.comparing(r -> TermText.normalize(r.other(self)))).toList();
        }

        @Override
        public void confirmTerm(GlossaryEntry term) {
            calls.add("confirmTerm " + term.term());
            replace(term, ReviewStatus.CONFIRMED);
        }

        @Override
        public void rejectTerm(GlossaryEntry term) {
            calls.add("rejectTerm " + term.term());
            replace(term, ReviewStatus.REJECTED);
        }

        private void replace(GlossaryEntry term, ReviewStatus status) {
            entries.replaceAll(e -> e.id().equals(term.id()) ? entry(e.term(), status) : e);
            changed();
        }

        @Override
        public void confirmSynonym(TermRelation relation) {
            calls.add("confirmSynonym " + relation.toTerm());
            relations.replaceAll(r -> r.id().equals(relation.id())
                    ? synonym(r.fromTerm(), r.toTerm(), ReviewStatus.CONFIRMED) : r);
            changed();
        }

        @Override
        public void rejectSynonym(TermRelation relation) {
            calls.add("rejectSynonym " + relation.toTerm());
            relations.replaceAll(r -> r.id().equals(relation.id())
                    ? synonym(r.fromTerm(), r.toTerm(), ReviewStatus.REJECTED) : r);
            changed();
        }

        @Override
        public void addSynonym(GlossaryEntry term, String text) {
            calls.add("addSynonym " + term.term() + " " + text);
            relations.add(synonym(term.term(), text, ReviewStatus.CONFIRMED));
            changed();
        }

        @Override
        public Runnable observe(Runnable onChange) {
            observers.add(onChange);
            return () -> observers.remove(onChange);
        }
    }
}
