package com.aresstack.spaceharvester.app.ui.markdown;

import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.event.InputEvent;
import java.awt.event.MouseWheelEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Angelos Fehlerbild: Über Codeblöcken und Tabellen blieb das Mausrad stehen; jetzt scrollt der Chat weiter. */
public class NestedScrollWheelRouterTest {

    private static final MarkdownTheme THEME = MarkdownTheme.forBubble(new Color(20, 120, 110), Color.WHITE);

    @Test
    public void shortBlockForwardsWheelToChat() throws Exception {
        onEdt(new Runnable() {
            @Override
            public void run() {
                Fixture fixture = new Fixture(3);
                wheel(fixture.inner, 1.0);
                assertTrue(fixture.outer.getVerticalScrollBar().getValue() > 0);
            }
        });
    }

    @Test
    public void tallBlockScrollsItselfInTheMiddle() throws Exception {
        onEdt(new Runnable() {
            @Override
            public void run() {
                Fixture fixture = new Fixture(200);
                fixture.inner.getVerticalScrollBar().setValue(100);
                wheel(fixture.inner, 1.0);
                assertTrue(fixture.inner.getVerticalScrollBar().getValue() > 100);
                assertEquals(0, fixture.outer.getVerticalScrollBar().getValue());
            }
        });
    }

    @Test
    public void tallBlockAtBottomHandsWheelToChat() throws Exception {
        onEdt(new Runnable() {
            @Override
            public void run() {
                Fixture fixture = new Fixture(200);
                int end = fixture.endOfInner();
                fixture.inner.getVerticalScrollBar().setValue(end);
                wheel(fixture.inner, 1.0);
                assertEquals(end, fixture.inner.getVerticalScrollBar().getValue());
                assertTrue(fixture.outer.getVerticalScrollBar().getValue() > 0);
            }
        });
    }

    @Test
    public void tallBlockAtTopHandsWheelUpToChat() throws Exception {
        onEdt(new Runnable() {
            @Override
            public void run() {
                Fixture fixture = new Fixture(200);
                fixture.outer.getVerticalScrollBar().setValue(300);
                wheel(fixture.inner, -1.0);
                assertEquals(0, fixture.inner.getVerticalScrollBar().getValue());
                assertTrue(fixture.outer.getVerticalScrollBar().getValue() < 300);
            }
        });
    }

    @Test
    public void fineTouchpadRotationsAddUp() throws Exception {
        onEdt(new Runnable() {
            @Override
            public void run() {
                Fixture fixture = new Fixture(200);
                fixture.inner.getVerticalScrollBar().setValue(100);
                for (int i = 0; i < 20; i++) {
                    wheel(fixture.inner, 0.05);
            }
            assertTrue(fixture.inner.getVerticalScrollBar().getValue() > 100);
            }
        });
    }

    @Test
    public void shiftWheelScrollsWideBlockSideways() throws Exception {
        onEdt(new Runnable() {
            @Override
            public void run() {
                JTextArea wide = new JTextArea("x" + new String(new char[400]).replace('\0', 'x'));
                Fixture fixture = new Fixture(wide);
                assertTrue(fixture.inner.getHorizontalScrollBar().isVisible());
                fixture.inner.dispatchEvent(event(fixture.inner, 1.0, InputEvent.SHIFT_DOWN_MASK));
                assertTrue(fixture.inner.getHorizontalScrollBar().getValue() > 0);
                assertEquals(0, fixture.outer.getVerticalScrollBar().getValue());
            }
        });
    }

    private static void onEdt(Runnable body) throws Exception {
        SwingUtilities.invokeAndWait(body);
    }

    @Test
    public void fullHeightWideBlockScrollsChatNormallyAndHorizontallyWithShift() throws Exception {
        onEdt(() -> {
            JTextArea wide = new JTextArea("x".repeat(400));
            CappedScrollPane inner = new CappedScrollPane(wide, 300, 8, THEME,
                    CappedScrollPane.HeightPolicy.FULL_CONTENT);
            JPanel column = new JPanel(null);
            column.setPreferredSize(new Dimension(400, 3000));
            inner.setBounds(0, 0, 300, inner.preferredHeightForWidth(300));
            column.add(inner);
            JScrollPane outer = new JScrollPane(column, ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
                    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            outer.setSize(420, 500);
            outer.doLayout();
            outer.getViewport().doLayout();
            inner.doLayout();
            inner.getViewport().doLayout();
            assertTrue(inner.getHorizontalScrollBar().isVisible());
            wheel(inner, 1.0);
            assertTrue(outer.getVerticalScrollBar().getValue() > 0);
            assertEquals(0, inner.getHorizontalScrollBar().getValue());
            int position = outer.getVerticalScrollBar().getValue();
            inner.dispatchEvent(event(inner, 1.0, InputEvent.SHIFT_DOWN_MASK));
            assertTrue(inner.getHorizontalScrollBar().getValue() > 0);
            assertEquals(position, outer.getVerticalScrollBar().getValue());
        });
    }

    private static void wheel(CappedScrollPane target, double rotation) {
        target.dispatchEvent(event(target, rotation, 0));
    }

    private static MouseWheelEvent event(CappedScrollPane target, double rotation, int modifiers) {
        int clicks = rotation < 0 ? -1 : (rotation >= 1 ? 1 : 0);
        return new MouseWheelEvent(target, MouseWheelEvent.MOUSE_WHEEL, System.currentTimeMillis(), modifiers,
                10, 10, 10, 10, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, clicks, rotation);
    }

    private static String lines(int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            text.append("line ").append(i).append('\n');
        }
        return text.toString();
    }

    /** A chat-like outer scroll pane holding a tall column with one capped block in it. */
    private static final class Fixture {
        final JScrollPane outer;
        final CappedScrollPane inner;

        Fixture(int lineCount) {
            this(new JTextArea(lines(lineCount)));
        }

        Fixture(JTextArea view) {
            inner = new CappedScrollPane(view, 300, 8, THEME);
            JPanel column = new JPanel(null);
            column.setPreferredSize(new Dimension(400, 3000));
            Dimension size = inner.getPreferredSize();
            inner.setBounds(0, 0, 300, size.height);
            column.add(inner);
            outer = new JScrollPane(column, ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
                    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            outer.setSize(420, 500);
            outer.doLayout();
            outer.getViewport().doLayout();
            inner.doLayout();
            inner.getViewport().doLayout();
        }

        int endOfInner() {
            return inner.getVerticalScrollBar().getMaximum() - inner.getVerticalScrollBar().getVisibleAmount();
        }
    }
}
