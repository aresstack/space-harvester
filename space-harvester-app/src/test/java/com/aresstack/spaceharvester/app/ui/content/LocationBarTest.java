package com.aresstack.spaceharvester.app.ui.content;

import org.junit.jupiter.api.Test;

import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Die Bereichsanzeige über der Seitenansicht: klickbar, und bei Platznot mittig mit „…“ gekürzt. */
class LocationBarTest {

    @Test
    void everyPartIsALinkAndTheMiddleCollapsesToAnEllipsisWhenSpaceRunsOut() throws Exception {
        List<String> clicked = new ArrayList<>();
        List<Object> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            LocationBar bar = new LocationBar();
            List<LocationBar.Part> parts = new ArrayList<>();
            for (String name : List.of("Wiki", "DEV", "Start", "Bereich", "Unterbereich", "Kapitel")) {
                parts.add(new LocationBar.Part(name, null, () -> clicked.add(name)));
            }
            bar.show(parts);
            seen.add(bar.text());

            int full = bar.getPreferredSize().width;
            bar.setSize(full, 20);
            bar.doLayout();
            seen.add(bar.visibleTexts());
            seen.add(bar.hidden().size());

            // Platz für den ersten Teil, „…“ und ungefähr die letzten zwei.
            bar.setSize(full * 2 / 3, 20);
            bar.doLayout();
            List<String> visible = bar.visibleTexts();
            seen.add(visible.get(0) + " " + visible.get(1) + " " + visible.get(visible.size() - 1));
            seen.add(bar.hidden().get(0).text());
            seen.add(visible.size() + bar.hidden().size() - 1);

            // Das Menü hinter „…“ führt zu den ausgelassenen Ebenen.
            ((JMenuItem) bar.hiddenMenu().getComponent(0)).doClick();
            // Ein Klick auf einen sichtbaren Teil führt zu ihm.
            MouseListener[] listeners = bar.getComponent(0).getMouseListeners();
            listeners[listeners.length - 1].mouseClicked(new MouseEvent(bar, 0, 0, 0, 0, 0, 1, false));

            // Extrem schmal: erster Teil, „…“ und der letzte, der sich selbst kürzt.
            bar.setSize(60, 20);
            bar.doLayout();
            seen.add(bar.visibleTexts());
        });
        assertThat(seen).containsExactly("Wiki › DEV › Start › Bereich › Unterbereich › Kapitel",
                List.of("Wiki", "DEV", "Start", "Bereich", "Unterbereich", "Kapitel"), 0,
                "Wiki … Kapitel", "DEV", 6,
                List.of("Wiki", "…", "Kapitel"));
        assertThat(clicked).containsExactly("DEV", "Wiki");
    }

    @Test
    void aPlainHintIsNoLink() throws Exception {
        List<Object> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            LocationBar bar = new LocationBar();
            bar.show(List.of(new LocationBar.Part("Seite im Explorer wählen", null, null)));
            seen.add(bar.text());
            seen.add(bar.getComponent(0).getMouseListeners().length);
        });
        assertThat(seen).containsExactly("Seite im Explorer wählen", 0);
    }

    @Test
    void aLongFirstPartShortensItselfAndLeavesRoomForTheEllipsisAndTheLastLevel() throws Exception {
        List<Object> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            LocationBar bar = new LocationBar();
            List<LocationBar.Part> parts = new ArrayList<>();
            for (String name : List.of("Corporate Engineering Documentation and Knowledge Base", "SPACE", "Start",
                    "Kapitel")) {
                parts.add(new LocationBar.Part(name, null, () -> { }));
            }
            bar.show(parts);
            bar.setSize(180, 20);
            bar.doLayout();
            seen.add(bar.visibleTexts());
            for (java.awt.Component component : bar.getComponents()) {
                if (component.isVisible()) {
                    seen.add(component.getWidth() > 0 && component.getX() + component.getWidth() <= 180);
                }
            }
        });
        assertThat(seen.get(0)).isEqualTo(List.of("Corporate Engineering Documentation and Knowledge Base", "…",
                "Kapitel"));
        assertThat(seen.subList(1, seen.size())).isNotEmpty().containsOnly(true);
    }
}
