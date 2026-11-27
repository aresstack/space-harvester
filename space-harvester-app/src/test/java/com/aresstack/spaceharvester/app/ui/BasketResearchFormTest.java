package com.aresstack.spaceharvester.app.ui;

import com.aresstack.knowledge4j.scope.Fence;
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
    void ohneAuftragStartetNichts() {
        BasketResearchForm form = new BasketResearchForm();
        form.excluded.setText("Vereinsfeste");

        assertThat(form.fence()).isEmpty();
    }
}
