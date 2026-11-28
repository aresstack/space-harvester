package com.aresstack.spaceharvester.app.ui.perspective;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;

/** Wahl, Reihenfolge und Rückrufe der Perspektiven (#1141), ohne Fenster. */
class PerspectivesTest {

    @Test
    void theFirstPerspectiveIsCurrentAndSelectingNotifiesOnceInOrder() {
        List<String> events = new ArrayList<>();
        Perspectives perspectives = new Perspectives()
                .add(new Perspective("a", "A", "", true, null, new JPanel(), null, null,
                        () -> events.add("a shown"), () -> events.add("a hidden")))
                .add(new Perspective("b", "B", "", false, null, new JPanel(), null, null,
                        () -> events.add("b shown"), () -> events.add("b hidden")));
        perspectives.onChange(next -> events.add("change " + next.id()));

        assertThat(perspectives.current().id()).isEqualTo("a");
        perspectives.select("a");
        assertThat(events).as("dieselbe Perspektive noch einmal wählen tut nichts").isEmpty();

        perspectives.select("b");
        assertThat(events).containsExactly("change b", "a hidden", "b shown");
        assertThat(perspectives.isCurrent("b")).isTrue();
        assertThat(perspectives.all()).extracting(Perspective::id).containsExactly("a", "b");
    }

    @Test
    void unknownAndDuplicateIdsAreRejected() {
        Perspectives perspectives = new Perspectives().add(Perspective.of("a", "A", "", true, null, new JPanel(),
                null));
        assertThatThrownBy(() -> perspectives.select("x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> perspectives.add(Perspective.of("a", "A2", "", true, null, new JPanel(), null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missionControlIsOnlyAPlaceholderWithoutNavigationOrDrawer() {
        Perspective mission = Perspectives.missionControl();
        assertThat(mission.ready()).isFalse();
        assertThat(mission.navigation()).isNull();
        assertThat(mission.drawer()).isNull();
        assertThat(mission.title()).isEqualTo("Mission Control");
    }
}
