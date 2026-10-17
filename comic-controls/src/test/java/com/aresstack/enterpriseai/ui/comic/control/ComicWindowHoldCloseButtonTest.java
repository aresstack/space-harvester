package com.aresstack.enterpriseai.ui.comic.control;

import com.aresstack.enterpriseai.ui.comic.theme.ComicPalette;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Click minimizes, only a full hold closes; hover shows the minimize glyph and the hold tooltip. */
public class ComicWindowHoldCloseButtonTest {

    @Test
    public void clickMinimizesAndHoldCloses() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                final AtomicInteger minimized = new AtomicInteger();
                final AtomicInteger closed = new AtomicInteger();
                ComicWindowHoldCloseButton button = new ComicWindowHoldCloseButton(
                        ComicPalette.defaultPalette(), minimized::incrementAndGet, closed::incrementAndGet, 24);
                assertEquals("Gedrückt halten zum Schließen", button.getToolTipText());
                button.getModel().setRollover(true);
                assertTrue(button.showsMinimize());

                button.doClick();
                assertEquals(1, minimized.get());
                assertEquals(0, closed.get());

                button.startHold();
                button.completeHold();
                assertEquals(1, closed.get());
                button.doClick(); // the release after a completed hold does not minimize
                assertEquals(1, minimized.get());
            }
        });
    }
}
