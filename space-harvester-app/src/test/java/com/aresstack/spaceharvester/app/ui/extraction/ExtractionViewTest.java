package com.aresstack.spaceharvester.app.ui.extraction;

import com.aresstack.spaceharvester.application.extraction.AnalysisState;
import com.aresstack.spaceharvester.application.extraction.DocumentAnalysis;
import com.aresstack.spaceharvester.application.extraction.DocumentAnalysisProvider;
import com.aresstack.spaceharvester.application.extraction.DocumentRef;
import com.aresstack.spaceharvester.application.extraction.FieldStatus;
import com.aresstack.spaceharvester.application.extraction.InMemoryDocumentAnalyses;
import com.aresstack.spaceharvester.application.observe.Subscription;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractionViewTest {

    private static final DocumentRef FIRST = new DocumentRef("eakte", "/fsc/webdav/a/erlass.msg", "\"1\"");
    private static final DocumentRef SECOND = new DocumentRef("eakte", "/fsc/webdav/a/vermerk.docx", "\"7\"");

    /** Hält Aufträge zurück, bis der Test sie freigibt; so lässt sich ein verspätetes Ergebnis nachstellen. */
    private static final class HeldRunner implements ExtractionView.Runner {
        final List<Runnable> pending = new ArrayList<>();

        @Override
        public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
            pending.add(() -> {
                try {
                    onSuccess.accept(work.call());
                } catch (Exception e) {
                    onFailure.accept(e);
                }
            });
        }

        void release(int index) {
            pending.get(index).run();
        }
    }

    private static final ExtractionView.Runner NOW = new ExtractionView.Runner() {
        @Override
        public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
            try {
                onSuccess.accept(work.call());
            } catch (Exception e) {
                onFailure.accept(e);
            }
        }
    };

    @Test
    void withoutProviderShowsNotAnalyzedAndStartIsDisabled() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ExtractionView view = new ExtractionView(DocumentAnalysisProvider.NONE, NOW);
            assertThat(view.messageText()).isEqualTo("Kein Dokument ausgewählt");

            view.show(FIRST);

            assertThat(view.analysis().state()).isEqualTo(AnalysisState.NOT_ANALYZED);
            assertThat(view.messageText()).isEqualTo("Noch nicht analysiert");
            assertThat(view.table().getRowCount()).isZero();
            assertThat(view.startButton().isEnabled()).isFalse();
            assertThat(view.startButton().getToolTipText()).isEqualTo(ExtractionView.START_LATER);
            assertThat(view.experimentalText()).isEqualTo(ExtractionView.EXPERIMENTAL);
        });
    }

    @Test
    void demoShowsGroupsStatusesAndSampleMark() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ExtractionView view = new ExtractionView(new DemoDocumentAnalysisProvider(), NOW);
            view.show(FIRST);

            var table = view.table();
            assertThat(table.getValueAt(0, 0)).isEqualTo("Dokumentinformationen");
            assertThat(table.getValueAt(1, 0)).isEqualTo("Dokumentart");
            assertThat(table.getValueAt(1, 2)).isEqualTo("ausdrücklich");
            assertThat(table.getValueAt(4, 1)).isEqualTo(ExtractionRows.NOT_FOUND_VALUE);
            assertThat(table.getValueAt(4, 2)).isEqualTo("nicht gefunden");
            assertThat(table.getValueAt(5, 0)).isEqualTo("Konto 1 – Haushalts- und Kontoinformationen");
            assertThat(column(table, 2)).contains("abgeleitet", "Widerspruch");
            assertThat(column(table, 0)).contains("Fristverlängerung (beantragt)", "Fristverlängerung (genehmigt)");
            assertThat(column(table, 1)).contains("„bis zum 31.12.2026 abzurechnen“", "„spätestens zum 30.06.2027“");
            assertThat(table.getValueAt(3, 3)).isEqualTo("Kopf · S. 1 · Abs. 2");
            assertThat(table.getValueAt(2, 3)).isEqualTo("");
            assertThat(view.experimentalText()).isEqualTo(ExtractionView.EXPERIMENTAL + ExtractionView.SAMPLE);
        });
    }

    @Test
    void lateResultOfPreviousDocumentIsDropped() throws Exception {
        HeldRunner held = new HeldRunner();
        SwingUtilities.invokeAndWait(() -> {
            ExtractionView view = new ExtractionView(new DemoDocumentAnalysisProvider(), held);
            view.show(FIRST);
            view.show(SECOND);
            assertThat(view.table().getRowCount()).isZero();

            held.release(0);
            assertThat(view.analysis()).isNull();
            assertThat(view.table().getRowCount()).isZero();

            held.release(1);
            assertThat(view.analysis().document()).isEqualTo(SECOND);
            assertThat(view.table().getRowCount()).isPositive();
        });
    }

    @Test
    void switchingDocumentClearsTableImmediately() throws Exception {
        HeldRunner held = new HeldRunner();
        SwingUtilities.invokeAndWait(() -> {
            ExtractionView view = new ExtractionView(new DemoDocumentAnalysisProvider(), held);
            view.show(FIRST);
            held.release(0);
            assertThat(view.table().getRowCount()).isPositive();

            view.show(SECOND);
            assertThat(view.table().getRowCount()).isZero();
            assertThat(view.messageText()).isEqualTo(ExtractionView.LOADING);
            view.show(null);
            assertThat(view.messageText()).isEqualTo("Kein Dokument ausgewählt");
        });
    }

    @Test
    void resultForAnotherVersionIsNeverShown() throws Exception {
        DocumentAnalysisProvider wrong = document -> DocumentAnalysis.completed(
                new DocumentRef(document.origin(), document.id(), "\"alt\""), new DemoDocumentAnalysisProvider()
                        .result(document).sections());
        SwingUtilities.invokeAndWait(() -> {
            ExtractionView view = new ExtractionView(wrong, NOW);
            view.show(FIRST);
            assertThat(view.analysis().state()).isEqualTo(AnalysisState.NOT_ANALYZED);
            assertThat(view.table().getRowCount()).isZero();
        });
    }

    @Test
    void failureIsShownWithReason() throws Exception {
        DocumentAnalysisProvider broken = document -> {
            throw new IllegalStateException("Dienst nicht erreichbar");
        };
        SwingUtilities.invokeAndWait(() -> {
            ExtractionView view = new ExtractionView(broken, NOW);
            view.show(FIRST);
            assertThat(view.analysis().state()).isEqualTo(AnalysisState.FAILED);
            assertThat(view.messageText()).isEqualTo("Analyse fehlgeschlagen: Dienst nicht erreichbar");
        });
    }

    @Test
    void connectedAnalysisEnablesStartAndShowsResultWhenObserved() throws Exception {
        InMemoryDocumentAnalyses store = new InMemoryDocumentAnalyses();
        DocumentAnalysisProvider service = new DocumentAnalysisProvider() {
            @Override
            public DocumentAnalysis result(DocumentRef document) {
                return store.result(document);
            }

            @Override
            public Subscription observe(Consumer<DocumentRef> changed) {
                return store.observe(changed);
            }

            @Override
            public boolean canAnalyze(DocumentRef document) {
                return true;
            }

            @Override
            public void startAnalysis(DocumentRef document) {
                store.record(DocumentAnalysis.inProgress(document));
            }
        };
        ExtractionView[] view = new ExtractionView[1];
        SwingUtilities.invokeAndWait(() -> {
            view[0] = new ExtractionView(service, NOW);
            view[0].show(FIRST);
            assertThat(view[0].startButton().isEnabled()).isTrue();

            view[0].startButton().doClick();
            assertThat(view[0].analysis().state()).isEqualTo(AnalysisState.IN_PROGRESS);
            assertThat(view[0].startButton().isEnabled()).isFalse();
        });

        // Der Dienst meldet das fertige Ergebnis über den Beobachter, auf einem beliebigen Thread
        store.record(new DemoDocumentAnalysisProvider().result(FIRST));
        SwingUtilities.invokeAndWait(() -> {
            assertThat(view[0].analysis().state()).isEqualTo(AnalysisState.COMPLETED);
            assertThat(view[0].table().getRowCount()).isPositive();
            assertThat(view[0].startButton().isEnabled()).isTrue();
        });

        // Nach close() kommt keine Meldung mehr an
        SwingUtilities.invokeAndWait(() -> view[0].close());
        store.forget(FIRST);
        SwingUtilities.invokeAndWait(() ->
                assertThat(view[0].analysis().state()).isEqualTo(AnalysisState.COMPLETED));
    }

    @Test
    void copiesOnlyFieldValues() throws Exception {
        List<String> copied = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            ExtractionView view = new ExtractionView(new DemoDocumentAnalysisProvider(), NOW);
            view.clipboard(copied::add);
            view.show(FIRST);

            view.table().setRowSelectionInterval(3, 3);
            view.copySelectedValue();
            view.table().setRowSelectionInterval(0, 0);
            view.copySelectedValue();
            view.table().setRowSelectionInterval(4, 4);
            view.copySelectedValue();
        });
        assertThat(copied).containsExactly("GZD-H 0000-BEISPIEL");
    }

    @Test
    void statusWordsAndCounts() {
        assertThat(ExtractionRows.word(FieldStatus.EXPLICIT)).isEqualTo("ausdrücklich");
        assertThat(ExtractionRows.word(FieldStatus.DERIVED)).isEqualTo("abgeleitet");
        assertThat(ExtractionRows.word(FieldStatus.NOT_FOUND)).isEqualTo("nicht gefunden");
        assertThat(ExtractionRows.word(FieldStatus.CONFLICT)).isEqualTo("Widerspruch");
        assertThat(ExtractionRows.status(new DemoDocumentAnalysisProvider().result(FIRST)))
                .isEqualTo("Analysiert: 10 Informationen, davon 2 Widersprüche");
    }

    private static List<Object> column(javax.swing.JTable table, int column) {
        List<Object> values = new ArrayList<>();
        for (int row = 0; row < table.getRowCount(); row++) {
            values.add(table.getValueAt(row, column));
        }
        return values;
    }
}
