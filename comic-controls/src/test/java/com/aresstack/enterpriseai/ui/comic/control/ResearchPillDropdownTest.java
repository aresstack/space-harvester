package com.aresstack.enterpriseai.ui.comic.control;

import org.junit.Test;

import javax.accessibility.AccessibleRole;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The mode pill works from the keyboard like a combo box and announces itself as one. */
public class ResearchPillDropdownTest {

    private static ResearchPillDropdown pill(final List<Integer> selected) {
        ResearchPillDropdown pill = new ResearchPillDropdown(28, 10, 0, 12, 12);
        pill.setItems(Arrays.asList(
                new ResearchPillDropdown.Item("Chat", null, true, null),
                new ResearchPillDropdown.Item("Agent", null, false, "Kein Agent konfiguriert"),
                new ResearchPillDropdown.Item("Werkzeuge", null, true, null)));
        pill.setSelectedIndex(0);
        pill.setSelectionListener(selected::add);
        return pill;
    }

    @Test
    public void arrowKeysChangeTheValueAndSkipDisabledItems() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                List<Integer> selected = new ArrayList<Integer>();
                ResearchPillDropdown pill = pill(selected);
                assertTrue("reachable with Tab", pill.isFocusable());
                assertFalse("a click leaves the focus where it is", pill.isRequestFocusEnabled());

                fire(pill, KeyEvent.VK_DOWN);
                assertEquals("the disabled Agent row is skipped", Arrays.asList(2), selected);
                pill.setSelectedIndex(2);
                fire(pill, KeyEvent.VK_DOWN);
                assertEquals("no wrap-around at the end", Arrays.asList(2), selected);
                fire(pill, KeyEvent.VK_UP);
                assertEquals(Arrays.asList(2, 0), selected);
                pill.setSelectedIndex(0);
                fire(pill, KeyEvent.VK_LEFT);
                assertEquals("nothing before the first item", Arrays.asList(2, 0), selected);
            }
        });
    }

    @Test
    public void assistiveTechnologySeesAComboBoxNamedAfterTheValue() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                ResearchPillDropdown pill = pill(new ArrayList<Integer>());
                assertEquals(AccessibleRole.COMBO_BOX, pill.getAccessibleContext().getAccessibleRole());
                assertEquals("Chat", pill.getAccessibleContext().getAccessibleName());
                pill.setSelectedIndex(2);
                assertEquals("Werkzeuge", pill.getAccessibleContext().getAccessibleName());
            }
        });
    }

    /** Runs the action bound to the key, the way the focused component would on that key press. */
    private static void fire(ResearchPillDropdown pill, int keyCode) {
        Object name = pill.getInputMap(ResearchPillDropdown.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(keyCode, 0));
        pill.getActionMap().get(name).actionPerformed(new ActionEvent(pill, ActionEvent.ACTION_PERFORMED, ""));
    }
}
