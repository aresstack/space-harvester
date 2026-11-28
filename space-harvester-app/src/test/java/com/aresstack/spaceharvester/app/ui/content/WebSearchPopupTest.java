package com.aresstack.spaceharvester.app.ui.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.ui.comic.theme.ComicPalette;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Das Menü am Pfeil der Websuche im einheitlichen Design (Angelo 28.11.2026). */
class WebSearchPopupTest {

    @Test
    void everyControlWearsTheComicLookAndTheCheckBoxesShareOneLine() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicInteger delay = new AtomicInteger();
            AtomicBoolean human = new AtomicBoolean();
            AtomicBoolean internal = new AtomicBoolean();
            WebSearchPopup popup = new WebSearchPopup(ComicPalette.defaultPalette(),
                    new WebSearchPopup.Check(WebSearchPopup.PROFILE_TITLE, WebSearchPopup.PROFILE_TIP, false,
                            internal::set),
                    List.of(new WebSearchPopup.Row(WebSearchPopup.DELAY_TITLE, 0, 0, 5000, 0, 2000, delay::set)),
                    List.of(new WebSearchPopup.Check(WebSearchPopup.HUMAN_TITLE, WebSearchPopup.HUMAN_TIP, false,
                                    human::set),
                            new WebSearchPopup.Check(WebSearchPopup.MOUSE_TITLE, WebSearchPopup.MOUSE_TIP, true,
                                    value -> { })));
            popup.setSize(popup.getPreferredSize());
            layout(popup);

            // Keine Standard-Steuerelemente mehr: Schieber, Zahlenfeld und Umschalter zeichnet das Comic-Design.
            String controls = "com.aresstack.enterpriseai.ui.comic.control";
            assertThat(popup.slider().getUI().getClass().getName()).startsWith(controls);
            javax.swing.JSpinner spinner = (javax.swing.JSpinner) SwingUtilities.getAncestorOfClass(
                    javax.swing.JSpinner.class, find(popup, javax.swing.JFormattedTextField.class));
            assertThat(spinner.getUI().getClass().getName()).startsWith(controls);
            assertThat(popup.profileToggle().getUI().getClass().getName()).startsWith(controls);
            assertThat(popup.checkBox(0).getIcon().getClass().getName()).startsWith(controls);
            assertThat(popup.checkBox(0).isOpaque()).isFalse();
            assertThat(popup.slider().isOpaque()).isFalse();

            // Human Emulation und Mausbewegungen stehen nebeneinander.
            assertThat(popup.checkBox(0).getParent()).isSameAs(popup.checkBox(1).getParent());
            assertThat(popup.checkBox(1).getY()).isEqualTo(popup.checkBox(0).getY());
            assertThat(popup.checkBox(1).getX()).isGreaterThan(popup.checkBox(0).getX());

            // Das Verhalten bleibt: jede Änderung geht sofort an ihren Empfänger.
            popup.slider().setValue(500);
            assertThat(delay).hasValue(500);
            assertThat(popup.value()).isEqualTo(500);
            popup.checkBox(0).doClick();
            assertThat(human).isTrue();
            popup.profileToggle().doClick();
            assertThat(internal).isTrue();
            assertThat(popup.profileToggle().getText()).isEqualTo(WebSearchPopup.PROFILE_INTERNAL);
        });
    }

    private static <T extends java.awt.Component> T find(java.awt.Container root, Class<T> type) {
        for (java.awt.Component child : root.getComponents()) {
            if (type.isInstance(child)) {
                return type.cast(child);
            }
            if (child instanceof java.awt.Container inner) {
                T found = find(inner, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void layout(java.awt.Container container) {
        container.doLayout();
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof java.awt.Container inner) {
                layout(inner);
            }
        }
    }
}
