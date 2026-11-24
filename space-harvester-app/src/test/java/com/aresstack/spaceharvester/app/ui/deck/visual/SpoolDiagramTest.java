package com.aresstack.spaceharvester.app.ui.deck.visual;

import com.aresstack.spaceharvester.mainframe.diagram.OutlineToMermaidConverter.DiagramType;
import org.junit.jupiter.api.Test;

import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** „Visuell“ in der JES-Spool-Ansicht: Text ↔ Diagramm, Typen, Suche in den Knoten, ohne Struktur zurück zum Text. */
class SpoolDiagramTest {

    private static final String JESJCL = """
            //BUILD    JOB (ACCT),'BAU',CLASS=A
            //STEP1    EXEC PGM=IDCAMS
            //SYSIN    DD   DUMMY
            //STEP2    EXEC PGM=PROG1
            """;

    @Test
    void togglesSpoolTextToReadOnlyDiagramAndSearchesNodes() throws Exception {
        JTextArea area = new JTextArea(JESJCL);
        SpoolDiagram spool = onEdt(() -> new SpoolDiagram(area::getText, () -> "BUILD", area));
        onEdt(() -> {
            spool.toggle();
            return null;
        });
        await(() -> onEdtQuiet(() -> spool.diagram().hasDiagram()), "Diagramm gezeichnet");
        assertThat(onEdt(() -> spool.diagram().getMermaidSource())).contains("STEP1").contains("IDCAMS");
        assertThat(onEdt(() -> spool.toggleButton().isSelected())).isTrue();
        assertThat(onEdt(() -> spool.toggleButton().getToolTipText())).isEqualTo(SpoolDiagram.BACK);
        assertThat(onEdt(() -> spool.search("STEP2"))).isPositive();
        assertThat(onEdt(spool::searchIndex)).isEqualTo(1);

        onEdt(() -> {
            spool.typeButtons().get(DiagramType.FLOWCHART).doClick();
            return null;
        });
        await(() -> onEdtQuiet(() -> spool.diagram().hasDiagram()), "Ablauf gezeichnet");

        onEdt(() -> {
            spool.toggle();
            return null;
        });
        assertThat(onEdt(spool::active)).isFalse();
        assertThat(onEdt(() -> spool.toggleButton().isSelected())).isFalse();
        assertThat(onEdt(() -> spool.toggleButton().getToolTipText())).isEqualTo(SpoolDiagram.SHOW);
    }

    @Test
    void spoolWithoutStructureStaysText() throws Exception {
        JTextArea area = new JTextArea("IEF142I BUILD STEP1 - STEP WAS EXECUTED - COND CODE 0000\n");
        AtomicBoolean told = new AtomicBoolean();
        SpoolDiagram spool = onEdt(() -> new SpoolDiagram(area::getText, () -> "BUILD", area));
        spool.onNoStructure(() -> told.set(true));
        onEdt(() -> {
            spool.toggle();
            return null;
        });
        await(told::get, "Hinweis ohne Struktur");
        assertThat(onEdt(spool::active)).isFalse();
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long until = System.currentTimeMillis() + 120_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > until) {
                throw new AssertionError("Zeit abgelaufen: " + what);
            }
            Thread.sleep(100);
        }
    }

    private static boolean onEdtQuiet(Callable<Boolean> call) {
        try {
            return onEdt(call);
        } catch (Exception e) {
            return false;
        }
    }

    private static <T> T onEdt(Callable<T> call) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                ref.set(call.call());
            } catch (Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return ref.get();
    }
}
