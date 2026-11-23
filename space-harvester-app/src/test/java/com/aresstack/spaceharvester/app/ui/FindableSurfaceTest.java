package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;

/** Die Lupe sucht auch in selbst gezeichneten Flächen wie dem Terminalbild. */
class FindableSurfaceTest {

    /** Merkt sich, was die Lupe zu markieren bittet. */
    private static final class Surface implements FindableSurface {
        String text;
        List<int[]> found = List.of();
        int current = -1;

        Surface(String text) {
            this.text = text;
        }

        @Override
        public String findText() {
            return text;
        }

        @Override
        public void showFound(List<int[]> found, int current) {
            this.found = new ArrayList<>(found);
            this.current = current;
        }
    }

    @Test
    void findsMarksStepsAndClearsInASurface() {
        JPanel root = new JPanel();
        JPanel screen = new JPanel();
        Surface surface = new Surface("LOGON  ISPF\nISPF READY");
        screen.putClientProperty(FindableSurface.KEY, surface);
        root.add(screen);
        ReaderFind find = new ReaderFind(root);

        assertThat(find.search("ispf")).isEqualTo(2);
        assertThat(surface.found).extracting(range -> range[0]).containsExactly(7, 12);
        assertThat(surface.current).isZero();
        assertThat(find.hitComponent(0)).isSameAs(screen);

        find.next();
        assertThat(surface.current).isEqualTo(1);
        assertThat(find.stale()).isFalse();
        surface.text = "NEUES BILD";
        assertThat(find.stale()).as("neues Bild vom Host").isTrue();

        find.clear();
        assertThat(surface.found).isEmpty();
        assertThat(surface.current).isEqualTo(-1);
    }
}
