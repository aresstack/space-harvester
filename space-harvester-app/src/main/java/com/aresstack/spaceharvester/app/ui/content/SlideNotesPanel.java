package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.ui.Comic;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * Die Folienkommentare einer Marke unter ihrem Standbild (Angelo, 20.11.2026): je Modell eine Fassung, oben zum
 * Umschalten wie die Fassungen des Transkripts; ohne Kommentar ein Hinweis, wie er entsteht.
 */
final class SlideNotesPanel extends JPanel {

    /** Hinweis ohne Kommentar. */
    static final String NONE = "Noch kein Folienkommentar: „In PPTX umwandeln“ am Video schreibt ihn.";

    /**
     * Eine Fassung des Kommentars.
     *
     * @param label etwa {@code Kommentar gemma4:12b}
     * @param text  der Vortragstext zur Folie
     */
    record Version(String label, String text) {
    }

    private final JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final JTextArea text = new JTextArea(5, 60);
    private List<Version> versions = List.of();
    private int shown;

    SlideNotesPanel() {
        super(new BorderLayout(0, 4));
        setOpaque(false);
        bar.setOpaque(false);
        JLabel heading = Comic.text("Folienkommentar");
        heading.setFont(Comic.semiBold(13f));
        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setOpaque(false);
        top.add(heading, BorderLayout.WEST);
        top.add(bar, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setFont(Comic.regular(13f));
        add(Comic.scroll(text), BorderLayout.CENTER);
        show(List.of());
    }

    /** Zeigt die Fassungen, die erste zuerst; leer: der Hinweis {@link #NONE}. */
    void show(List<Version> found) {
        versions = List.copyOf(found);
        shown = 0;
        render();
    }

    /** Für Tests: die Knöpfe der Fassungen. */
    List<ComposerButton> buttons() {
        List<ComposerButton> buttons = new ArrayList<>();
        for (java.awt.Component component : bar.getComponents()) {
            buttons.add((ComposerButton) component);
        }
        return buttons;
    }

    /** Für Tests: der gezeigte Text. */
    String shownText() {
        return text.getText();
    }

    private void render() {
        bar.removeAll();
        if (versions.size() > 1) {
            for (int i = 0; i < versions.size(); i++) {
                int index = i;
                ComposerButton button = Comic.button(versions.get(i).label(), "Diese Fassung des Kommentars zeigen");
                button.setEmphasized(i == shown);
                button.addActionListener(event -> {
                    shown = index;
                    render();
                });
                bar.add(button);
            }
        } else if (versions.size() == 1) {
            bar.add(Comic.button(versions.get(0).label(), "Der Kommentar dieses Modells"));
        }
        text.setText(versions.isEmpty() ? NONE : versions.get(shown).text());
        text.setCaretPosition(0);
        bar.revalidate();
        bar.repaint();
    }
}
