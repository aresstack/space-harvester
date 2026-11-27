package com.aresstack.spaceharvester.app.ui;

import com.aresstack.knowledge4j.scope.Fence;
import com.aresstack.spaceharvester.app.research.BookFields;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BasketResearchFormTest {

    @Test
    void ausDenFeldernWirdDerWeidezaun() {
        BasketResearchForm form = new BasketResearchForm();
        form.mission.setText("  Kompostieren im Schrebergarten  ");
        form.edge.setText("nur Hausgarten");
        form.included.setText("Wurmkiste\n\n  Thermokomposter \n");
        form.excluded.setText("Vereinsfeste");

        Fence fence = form.fence().orElseThrow();

        assertThat(fence.getMission()).isEqualTo("Kompostieren im Schrebergarten");
        assertThat(fence.getEdge()).isEqualTo("nur Hausgarten");
        assertThat(fence.getIncluded()).isEqualTo(List.of("Wurmkiste", "Thermokomposter"));
        assertThat(fence.getExcluded()).isEqualTo(List.of("Vereinsfeste"));
    }

    @Test
    void dieLaengeKommtAlsZahlMitEinheitZurueckUndZaehltIntern() {
        BasketResearchForm form = BasketResearchForm.book().prefill(
                com.aresstack.spaceharvester.app.research.BookNotes.of(Fence.of("M"), 2000).withLength(5, "PAGES"));

        assertThat(form.length.amount()).isEqualTo(5);
        assertThat(form.length.unit()).isEqualTo(com.aresstack.spaceharvester.app.ui.controls.LengthField.Unit.PAGES);
        assertThat(form.length.wordsPerPage()).as("die Umrechnung dieses Buchs").isEqualTo(400);
        assertThat(form.targetWords()).isEqualTo(2000);
        assertThat(new BasketResearchForm().targetWords()).as("Korb füllen kennt keine Länge").isZero();
    }

    @Test
    void ohneAuftragStartetNichts() {
        BasketResearchForm form = new BasketResearchForm();
        form.excluded.setText("Vereinsfeste");

        assertThat(form.fence()).isEmpty();
    }

    @Test
    void derAuftragWaechstMitDemFensterUndErweitertZeigtDieFelder() {
        BasketResearchForm form = BasketResearchForm.book();
        assertThat(form.targets().keySet()).containsExactly(BookFields.Field.MISSION, BookFields.Field.QUESTIONS,
                BookFields.Field.EDGE, BookFields.Field.INCLUDED, BookFields.Field.EXCLUDED);
        assertThat(new BasketResearchForm().targets()).doesNotContainKey(BookFields.Field.QUESTIONS);
        javax.swing.JComponent body = form.body("Streuobst");
        body.setSize(800, 900);
        layout(body);
        java.awt.Component mission = form.mission.getParent().getParent();
        assertThat(mission.getHeight()).isGreaterThan(mission.getMinimumSize().height);

        button(body, "Erweitert").doClick();
        body.setSize(800, 1300);
        layout(body);

        java.awt.Component edge = form.edge.getParent().getParent();
        assertThat(edge.isShowing() || edge.isVisible()).isTrue();
        assertThat(edge.getHeight()).isGreaterThanOrEqualTo(edge.getMinimumSize().height);
        assertThat(form.mission.getParent().getParent().getHeight())
                .isGreaterThanOrEqualTo(form.mission.getParent().getParent().getMinimumSize().height);
    }

    private static void layout(java.awt.Container container) {
        container.doLayout();
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof java.awt.Container inner) {
                layout(inner);
            }
        }
    }

    private static javax.swing.AbstractButton button(java.awt.Container root, String text) {
        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof javax.swing.AbstractButton b && text.equals(b.getText())) {
                return b;
            }
            if (child instanceof java.awt.Container inner) {
                javax.swing.AbstractButton found = button(inner, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
