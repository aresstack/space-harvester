package com.aresstack.enterpriseai.ui.comic.control;

import org.junit.Test;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The comic look keeps an editable combo box a real text field: typing, selection and a reloaded list. */
public class ComicComboBoxUITest {

    @Test
    public void editableComboKeepsATypableTransparentEditor() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                JComboBox<String> combo = new JComboBox<String>(new String[] {"gpt-4o", "gpt-4o-mini"});
                combo.setEditable(true);
                ComicComboBoxUI.install(combo);
                assertTrue(combo.getUI() instanceof ComicComboBoxUI);
                assertTrue(combo.isEditable());

                JTextComponent field = (JTextComponent) combo.getEditor().getEditorComponent();
                assertFalse("the rounded surface shows through", field.isOpaque());
                assertTrue("still a focusable text field", field.isFocusable() && field.isEditable());

                field.setText("my-own-model");
                field.selectAll();
                assertEquals("my-own-model", field.getSelectedText());
                assertEquals("typed text is the editor value", "my-own-model", combo.getEditor().getItem());

                combo.setModel(new DefaultComboBoxModel<String>(new String[] {"a", "b"}));
                combo.getEditor().setItem("my-own-model");
                assertEquals("a reload keeps what the caller puts back", "my-own-model",
                        combo.getEditor().getItem());
            }
        });
    }

    @Test
    public void installingTwiceDoesNotStackEditorListeners() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                JComboBox<String> combo = new JComboBox<String>();
                combo.setEditable(true);
                ComicComboBoxUI.install(combo);
                JTextField field = (JTextField) combo.getEditor().getEditorComponent();
                int listeners = field.getFocusListeners().length;
                combo.updateUI(); // the look-and-feel default, then the comic look again
                ComicComboBoxUI.install(combo);
                JTextField again = (JTextField) combo.getEditor().getEditorComponent();
                assertTrue(again.getFocusListeners().length <= listeners + 1);
            }
        });
    }

    @Test
    public void selectionOnlyComboStaysNonEditable() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                JComboBox<String> combo = ComicComboBoxUI.install(
                        new JComboBox<String>(new String[] {"KeePass", "Manuell"}));
                assertFalse(combo.isEditable());
                combo.setSelectedItem("Manuell");
                assertEquals("Manuell", combo.getSelectedItem());
            }
        });
    }
}
