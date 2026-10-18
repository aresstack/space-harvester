package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.border.ComicBorder;
import com.aresstack.enterpriseai.ui.comic.control.ComicButton;
import com.aresstack.enterpriseai.ui.comic.control.ComicSectionPanel;
import com.aresstack.enterpriseai.ui.comic.theme.ComicPalette;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.application.signin.SignInRequest;
import com.aresstack.spaceharvester.application.signin.SignInRequests;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Die Einmalcode-Abfrage (ADR 0018) wie {@code ReaderSignInPanel} im Enterprise AI Client: „Zusätzliche Anmeldung
 * erforderlich“, Hinweis der Quelle, Fehler des letzten Versuchs, Codefeld, „Code erneut senden“ (nur wenn die Quelle
 * es anbietet), „Bestätigen“ und „Ohne Anmeldung fortfahren“. Dieselbe Komponente steht unter Aufträge und in der
 * Leseansicht; welche Anforderung sie zeigt, sagt die Ansicht ({@code which}). Ohne offene Anforderung ist sie
 * unsichtbar.
 *
 * <p>Senden, Neu-Senden und Ablehnen gehen im Hintergrund an {@link SignInRequests}; der Core reicht den Code an die
 * Quelle weiter und überschreibt ihn. Die Komponente hält ihn nur im Passwortfeld und leert es beim Senden. Nur auf dem
 * EDT.
 */
public final class SignInSlot extends JPanel {

    static final String HEADING = "Zusätzliche Anmeldung erforderlich";
    static final String SUBMIT_TEXT = "Bestätigen";
    static final String RESEND_TEXT = "Code erneut senden";
    static final String DECLINE_TEXT = "Ohne Anmeldung fortfahren";
    static final String SENDING_TEXT = "Wird gesendet …";
    static final String SEND_FAILED = "Senden fehlgeschlagen: die Quelle ist nicht erreichbar.";

    private final Supplier<SignInRequests> source;
    private final Function<SignInRequests, Optional<SignInRequest>> which;
    private final ComicPalette palette = ComicPalette.defaultPalette();
    private final JPasswordField codeField = new JPasswordField(16);
    private final ComicButton resendButton = new ComicButton(RESEND_TEXT);
    private final ComicButton submitButton = new ComicButton(SUBMIT_TEXT);
    private final ComicButton declineButton = new ComicButton(DECLINE_TEXT);
    private final JTextArea message = text();
    private final JTextArea error = text();
    private SignInRequest shown;
    /** Ein Fehler der App (Senden gescheitert), bis die Quelle einen eigenen meldet. */
    private String localError;
    private boolean sending;
    private SignInRequests watched;
    private AutoCloseable subscription;

    /** @param which die Anforderung, die diese Ansicht zeigen soll, etwa die des gewählten Auftrags */
    public SignInSlot(AppContext context, Function<SignInRequests, Optional<SignInRequest>> which) {
        this(() -> {
            AppRuntime runtime = context.runtime();
            return runtime == null ? null : runtime.workspace().signIns();
        }, which);
        context.onChange(ignored -> refresh());
    }

    /** @param source die Anforderungen der aktuellen Laufzeit, {@code null} ohne Laufzeit */
    SignInSlot(Supplier<SignInRequests> source, Function<SignInRequests, Optional<SignInRequest>> which) {
        super(new BorderLayout());
        this.source = Objects.requireNonNull(source, "source");
        this.which = Objects.requireNonNull(which, "which");
        setOpaque(false);
        setVisible(false);

        ComicSectionPanel card = new ComicSectionPanel(palette);
        card.setAccentStripe(palette.getNavigationBlue());
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        JLabel heading = new JLabel(HEADING);
        heading.setFont(Comic.semiBold(15f));
        heading.setForeground(Comic.INK);
        heading.setAlignmentX(LEFT_ALIGNMENT);
        card.add(heading);
        card.add(Box.createVerticalStrut(8));
        card.add(message);
        error.setForeground(palette.getAccentRed());
        card.add(Box.createVerticalStrut(4));
        card.add(error);
        card.add(Box.createVerticalStrut(10));
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        JLabel codeLabel = new JLabel("OTP-Code:");
        codeLabel.setFont(Comic.regular(13f));
        codeLabel.setLabelFor(codeField);
        codeField.setBorder(ComicBorder.roundedBorder(palette, 4));
        row.add(codeLabel);
        row.add(Box.createHorizontalStrut(8));
        row.add(codeField);
        card.add(row);
        card.add(Box.createVerticalStrut(12));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.setOpaque(false);
        actions.setAlignmentX(LEFT_ALIGNMENT);
        actions.add(resendButton);
        actions.add(submitButton);
        actions.add(declineButton);
        card.add(actions);
        card.setBorder(BorderFactory.createCompoundBorder(card.getBorder(),
                BorderFactory.createEmptyBorder(14, 18, 14, 18)));
        add(card, BorderLayout.CENTER);
        setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        declineButton.setToolTipText("Die übrigen Dateien dieser Quelle fehlen dann mit dem Grund „Anmeldung "
                + "erforderlich“; der Auftrag läuft weiter");

        submitButton.addActionListener(event -> submit());
        codeField.addActionListener(event -> submit());
        resendButton.addActionListener(event -> resend());
        declineButton.addActionListener(event -> decline());
        refresh();
    }

    /** Zeigt die Anforderung, die {@code which} jetzt liefert; eine gleiche bleibt samt eingetipptem Code stehen. */
    public void refresh() {
        SignInRequests requests = watch();
        Optional<SignInRequest> current = requests == null ? Optional.empty() : which.apply(requests);
        if (current.isEmpty()) {
            shown = null;
            localError = null;
            codeField.setText("");
            setVisible(false);
            revalidateParent();
            return;
        }
        SignInRequest next = current.get();
        boolean other = shown == null || !shown.id().equals(next.id());
        if (other) {
            codeField.setText("");
            localError = null;
        } else if (!next.error().equals(shown.error())) {
            localError = null;
        }
        shown = next;
        String hint = next.message().isEmpty() ? "Ein Einmalcode wurde per E-Mail versendet." : next.message();
        String where = next.title().isEmpty() ? next.service() : next.title();
        message.setText(hint + (where.isEmpty() ? "" : "\n(" + where + ")"));
        String problem = localError != null ? localError : next.error();
        error.setText(problem);
        error.setVisible(!problem.isEmpty());
        resendButton.setVisible(next.resendAvailable());
        setSending(sending);
        if (!isVisible()) {
            setVisible(true);
            revalidateParent();
            SwingUtilities.invokeLater(codeField::requestFocusInWindow);
        }
    }

    /** Hört auf die Anforderungen der aktuellen Laufzeit; nach einem Neustart auf die der neuen. */
    private SignInRequests watch() {
        SignInRequests current = source.get();
        if (current != watched) {
            close(subscription);
            subscription = null;
            watched = current;
            if (current != null) {
                subscription = current.onChange(() -> SwingUtilities.invokeLater(this::refresh));
            }
        }
        return current;
    }

    private void submit() {
        SignInRequests requests = watch();
        SignInRequest request = shown;
        if (sending || requests == null || request == null) {
            return;
        }
        char[] code = codeField.getPassword();
        if (code.length == 0) {
            codeField.requestFocusInWindow();
            return;
        }
        codeField.setText("");
        setSending(true);
        // Der Core überschreibt das übergebene Feld nach dem Senden.
        Background.run(() -> requests.submit(request.id(), code), answer -> {
            setSending(false);
            // Abgelehnt nennt die Quelle den Grund selbst (error der Anforderung).
            localError = answer == SignInRequest.Answer.FAILED ? SEND_FAILED : null;
            refresh();
        }, failure -> {
            Arrays.fill(code, '\0');
            setSending(false);
            localError = SEND_FAILED;
            refresh();
        });
    }

    private void resend() {
        SignInRequests requests = watch();
        SignInRequest request = shown;
        if (sending || requests == null || request == null) {
            return;
        }
        setSending(true);
        Background.run(() -> requests.resend(request.id()), next -> {
            setSending(false);
            localError = next.isEmpty() ? SEND_FAILED : null;
            refresh();
        }, failure -> {
            setSending(false);
            localError = SEND_FAILED;
            refresh();
        });
    }

    private void decline() {
        SignInRequests requests = watch();
        SignInRequest request = shown;
        if (sending || requests == null || request == null) {
            return;
        }
        setSending(true);
        Background.run(() -> requests.decline(request.id()), ignored -> {
            setSending(false);
            refresh();
        }, failure -> {
            setSending(false);
            refresh();
        });
    }

    private void setSending(boolean value) {
        sending = value;
        submitButton.setText(value ? SENDING_TEXT : SUBMIT_TEXT);
        submitButton.setEnabled(!value);
        resendButton.setEnabled(!value);
        declineButton.setEnabled(!value);
        codeField.setEnabled(!value);
    }

    private void revalidateParent() {
        if (getParent() != null) {
            getParent().revalidate();
            getParent().repaint();
        }
    }

    private static void close(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Abmelden eines Zuhörers scheitert nicht sinnvoll.
        }
    }

    private JTextArea text() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setColumns(36);
        area.setForeground(Comic.INK);
        area.setFont(Comic.regular(13f));
        area.setAlignmentX(LEFT_ALIGNMENT);
        area.setBorder(BorderFactory.createEmptyBorder());
        return area;
    }

    // ---- für Tests

    JPasswordField codeField() {
        return codeField;
    }

    ComicButton submitButton() {
        return submitButton;
    }

    ComicButton resendButton() {
        return resendButton;
    }

    String errorText() {
        return error.isVisible() ? error.getText() : "";
    }

    SignInRequest shown() {
        return shown;
    }
}
