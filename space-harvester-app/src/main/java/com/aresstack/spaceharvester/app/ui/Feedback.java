package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComicSectionPanel;
import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Color;

/**
 * Rückmeldung einer Ansicht in einer schmalen Platte: läuft, erfolgreich, Warnung oder Problem. Probleme zeigen,
 * was zu tun ist; die technische Zeile klappt nur auf Wunsch auf. Unsichtbar, solange es nichts zu melden gibt.
 */
public final class Feedback extends JPanel {

    private final ComicSectionPanel plate = new ComicSectionPanel(Comic.PALETTE);
    private final JLabel title = new JLabel();
    private final JTextArea message = Comic.note("");
    private final JTextArea technical = Comic.note("");
    private final ComposerButton details = Comic.button("Technische Details", "Klasse und Meldung der Ausnahme");

    public Feedback() {
        super(new BorderLayout());
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        plate.setLayout(new BorderLayout(0, 4));
        plate.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 12));
        title.setFont(Comic.semiBold(13f));
        title.setForeground(Comic.INK);
        technical.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 11));
        technical.setVisible(false);
        details.addActionListener(e -> {
            technical.setVisible(!technical.isVisible());
            revalidate();
        });
        JPanel body = new JPanel(new BorderLayout(0, 4));
        body.setOpaque(false);
        body.add(message, BorderLayout.NORTH);
        body.add(technical, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.add(details, BorderLayout.WEST);
        body.add(south, BorderLayout.SOUTH);
        plate.add(title, BorderLayout.NORTH);
        plate.add(body, BorderLayout.CENTER);
        add(plate, BorderLayout.CENTER);
        setVisible(false);
    }

    public void working(String text) {
        show(Comic.BLUE, text, null, null);
    }

    public void success(String text) {
        show(Comic.GREEN, text, null, null);
    }

    public void info(String text, String detail) {
        show(Comic.PETROL, text, detail, null);
    }

    public void warning(String text, String detail) {
        show(Comic.ORANGE, text, detail, null);
    }

    public void problem(Throwable error) {
        Problems.Problem problem = Problems.describe(error);
        show(Comic.RED, problem.title(), problem.message() + (problem.hint() == null ? "" : "\n" + problem.hint()),
                problem.technical());
    }

    /** Wie {@link #problem(Throwable)}, mit dem gescheiterten Schritt in der Überschrift. */
    public void problem(Throwable error, Problems.Step step) {
        Problems.Problem problem = Problems.describe(error, step);
        show(Comic.RED, problem.title(), problem.message() + (problem.hint() == null ? "" : "\n" + problem.hint()),
                problem.technical());
    }

    /** Wie {@link #problem(Throwable, Problems.Step)}; {@code subject} nennt, worum es ging (z. B. den Space). */
    public void problem(Throwable error, Problems.Step step, String subject) {
        Problems.Problem problem = Problems.describe(error, step, subject);
        show(Comic.RED, problem.title(), problem.message() + (problem.hint() == null ? "" : "\n" + problem.hint()),
                problem.technical());
    }

    /** Für Tests: Überschrift und Text der Meldung. */
    public String text() {
        return title.getText() + "\n" + message.getText();
    }

    public void clear() {
        setVisible(false);
    }

    private void show(Color stripe, String heading, String text, String tech) {
        plate.setAccentStripe(stripe);
        title.setText(heading);
        message.setText(text == null ? "" : text);
        message.setVisible(text != null && !text.isEmpty());
        technical.setText(tech == null ? "" : tech);
        technical.setVisible(false);
        details.setVisible(tech != null);
        setVisible(true);
        revalidate();
        repaint();
    }
}
