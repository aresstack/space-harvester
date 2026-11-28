package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.controls.Cursors;
import com.aresstack.spaceharvester.app.ui.controls.WrappedText;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Objects;

/**
 * Ein Textausschnitt einer Fundstelle (Angelo 28.11.2026): oben der Titel der Seite als Link, darunter der Ausschnitt
 * mit dem gesuchten Wort gelb hervorgehoben. Der Text bricht um; ein Klick auf den Titel öffnet die Seite.
 */
public final class ExcerptCard extends JPanel {

    static final String ELLIPSIS = "… ";
    static final String ELLIPSIS_AFTER = " …";
    static final String OPEN_TIP = "Seite öffnen";
    private static final int PAD_X = 12;
    private static final int PAD_Y = 8;
    private static final int GAP = 2;

    private final JLabel title = new JLabel();
    private final WrappedText text;
    private final int markStart;
    private final int markEnd;

    public ExcerptCard(WordSearch.Excerpt excerpt, Runnable onOpen) {
        super(new BorderLayout(0, GAP));
        Objects.requireNonNull(excerpt, "excerpt");
        Objects.requireNonNull(onOpen, "onOpen");
        setOpaque(false);
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Comic.LINE),
                BorderFactory.createEmptyBorder(PAD_Y, PAD_X, PAD_Y, PAD_X)));
        title.setText(excerpt.place().title());
        title.setFont(Comic.semiBold(13f));
        title.setForeground(Comic.BLUE);
        title.setToolTipText(OPEN_TIP);
        Cursors.set(title, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        title.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                onOpen.run();
            }
        });
        String before = (excerpt.cutBefore() ? ELLIPSIS : "") + excerpt.before();
        String after = excerpt.after() + (excerpt.cutAfter() ? ELLIPSIS_AFTER : "");
        text = Comic.wrapped(before + excerpt.match() + after, Comic.TEXT, 13f);
        markStart = before.length();
        markEnd = markStart + excerpt.match().length();
        try {
            text.getHighlighter().addHighlight(markStart, markEnd,
                    new DefaultHighlighter.DefaultHighlightPainter(Comic.YELLOW));
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        }
        add(title, BorderLayout.NORTH);
        add(text, BorderLayout.CENTER);
        setAlignmentX(LEFT_ALIGNMENT);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    /** Der Titel der Seite. */
    public String titleText() {
        return title.getText();
    }

    /** Der gezeigte Ausschnitt. */
    public String text() {
        return text.getText();
    }

    /** Der hervorgehobene Teil des Ausschnitts. */
    public String marked() {
        return text.getText().substring(markStart, markEnd);
    }

    JLabel title() {
        return title;
    }
}
