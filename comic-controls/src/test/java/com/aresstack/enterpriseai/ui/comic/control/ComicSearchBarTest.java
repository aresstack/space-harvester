package com.aresstack.enterpriseai.ui.comic.control;

import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The search bar's wiring: Enter and the ▶ button both fire the registered action. */
public class ComicSearchBarTest {

    @Test
    public void enterAndGoButtonBothFireTheSearchAction() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                final ComicSearchBar bar = new ComicSearchBar("Search…");
                final List<String> fired = new ArrayList<String>();
                bar.addSearchAction(new ActionListener() {
                    public void actionPerformed(ActionEvent e) {
                        fired.add(bar.getText());
                    }
                });
                bar.setText("comic ui");
                bar.getTextField().postActionEvent(); // Enter
                assertEquals(1, fired.size());
                bar.getGoButton().doClick(); // ▶ button (protected: same-package test access)
                assertEquals(2, fired.size());
                assertEquals("comic ui", fired.get(1));
                assertTrue("a slim bar", bar.getPreferredSize().height < 40);
            }
        });
    }

    private static void onEdt(Runnable runnable) throws Exception {
        try {
            SwingUtilities.invokeAndWait(runnable);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException) {
                throw (RuntimeException) ex.getCause();
            }
            if (ex.getCause() instanceof Error) {
                throw (Error) ex.getCause();
            }
            throw ex;
        }
    }
}
