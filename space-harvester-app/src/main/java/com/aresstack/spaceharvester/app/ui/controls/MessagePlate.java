package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.enterpriseai.ui.comic.control.ComicWindowCloseButton;
import com.aresstack.spaceharvester.app.ui.Comic;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.TextAttribute;
import java.util.Map;
import java.util.Objects;

/**
 * Eine Meldungsplatte über einer Ansicht: Satz, dahinter wahlweise eine Aktion als Link, rechts das ✕ zum Wegklicken.
 * Die Aktion ist per Tastatur erreichbar (Tab, dann Enter oder Leertaste); Esc auf ihr schließt die Platte. Unsichtbar,
 * bis {@link #show} sie füllt; die Aktion und das ✕ blenden sie wieder aus.
 */
public final class MessagePlate extends JPanel {

    private final JLabel text = new JLabel();
    private final JLabel action = new JLabel();
    private final ComicWindowCloseButton close;
    private Runnable onAction = () -> { };

    public MessagePlate() {
        super(new BorderLayout(6, 0));
        setOpaque(true);
        setBackground(Comic.NOTICE);
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Comic.YELLOW, 1, true),
                BorderFactory.createEmptyBorder(2, 8, 2, 4)));
        text.setFont(Comic.regular(13f));
        text.setForeground(Comic.TEXT);
        Map<TextAttribute, Object> underline = Map.of(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON);
        action.setFont(Comic.semiBold(13f));
        action.setForeground(Comic.BLUE);
        action.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        action.setFocusable(true);
        action.setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));
        action.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                clickAction();
            }
        });
        action.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_ENTER || event.getKeyCode() == KeyEvent.VK_SPACE) {
                    event.consume();
                    clickAction();
                } else if (event.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    event.consume();
                    dismiss();
                }
            }
        });
        action.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent event) {
                action.setFont(Comic.semiBold(13f).deriveFont(underline));
            }

            @Override
            public void focusLost(FocusEvent event) {
                action.setFont(Comic.semiBold(13f));
            }
        });
        close = new ComicWindowCloseButton(Comic.PALETTE, this::dismiss, "Hinweis schließen", 20);
        JPanel line = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        line.setOpaque(false);
        line.add(text);
        line.add(action);
        JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 2));
        east.setOpaque(false);
        east.add(close);
        add(line, BorderLayout.CENTER);
        add(east, BorderLayout.EAST);
        setVisible(false);
    }

    /** Ob die Platte gerade als Fehler (rot) steht. */
    private boolean error;

    /**
     * Wie {@link #show}, aber als Fehler: roter Rand auf hellrotem Grund (Ampel: rot nur bei Fehlern). Ein späteres
     * {@link #show} zeigt wieder die gelbe Platte.
     */
    public void showError(String sentence, String actionText, Runnable onAction) {
        show(sentence, actionText, onAction);
        error = true;
        style();
    }

    /** Ob die gezeigte Platte ein Fehler ist (für Tests). */
    public boolean error() {
        return error && isVisible();
    }

    private void style() {
        setBackground(error ? Comic.CHANGE_REMOVED : Comic.NOTICE);
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(error ? Comic.RED : Comic.YELLOW,
                1, true), BorderFactory.createEmptyBorder(2, 8, 2, 4)));
        repaint();
    }

    /** Zeigt {@code sentence} und dahinter {@code actionText} als Link; ohne Aktion ({@code null}) nur den Satz. */
    public void show(String sentence, String actionText, Runnable onAction) {
        if (error) {
            error = false;
            style();
        }
        text.setText(sentence == null ? "" : sentence);
        action.setText(actionText == null ? "" : actionText);
        action.setVisible(actionText != null);
        action.getAccessibleContext().setAccessibleName(actionText);
        this.onAction = onAction == null ? () -> { } : onAction;
        setVisible(true);
        revalidate();
        repaint();
    }

    /** Blendet die Platte aus; die Aktion entfällt. */
    public void dismiss() {
        onAction = () -> { };
        if (isVisible()) {
            setVisible(false);
            revalidate();
            repaint();
        }
    }

    /** Die Aktion, wie ein Klick auf den Link: blendet die Platte aus und führt sie aus. */
    public void clickAction() {
        Runnable run = onAction;
        dismiss();
        run.run();
    }

    /** Der gezeigte Satz, leer ohne Platte (für Tests). */
    public String sentence() {
        return isVisible() ? text.getText() : "";
    }

    /** Ein Klick auf das ✕ (für Tests). */
    public void clickClose() {
        close.doClick();
    }

    /** Esc auf dem Link, wie ein Tastaturbenutzer (für Tests). */
    public void pressEscapeOnAction() {
        KeyEvent escape = new KeyEvent(action, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED);
        for (java.awt.event.KeyListener listener : action.getKeyListeners()) {
            listener.keyPressed(escape);
        }
    }

    /** Enter auf dem Link, wie ein Tastaturbenutzer (für Tests). */
    public void pressEnterOnAction() {
        KeyEvent enter = new KeyEvent(action, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_ENTER, '\n');
        for (java.awt.event.KeyListener listener : action.getKeyListeners()) {
            listener.keyPressed(enter);
        }
    }

    /** Ob der Link per Tastatur erreichbar ist (für Tests). */
    public boolean actionFocusable() {
        return action.isFocusable() && action.isVisible();
    }
}
