package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.ui.controls.PasswordReveal;
import com.aresstack.spaceharvester.app.ui.controls.WrappedText;
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
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextArea;
import javax.swing.JTextField;
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
 * <p>Fragt die Quelle neben dem Code einmalig Benutzer und Passwort ab ({@link SignInRequest#asksForLogin()}, Quelle
 * mit Personal Access Token, #571), stehen unter dem Codefeld „Benutzer“ und „Passwort“ (mit Auge) samt Hinweis; beide
 * sind leer, werden nur für dieses Absenden mitgeschickt und nie gespeichert.
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
    /** Nur, wenn der Core keine Ursache an der Anforderung nennt ({@code SignInRequest.failure()} leer). */
    static final String SEND_FAILED = "Senden fehlgeschlagen: die Quelle ist nicht erreichbar.";
    static final String LOGIN_NOTE = "Confluence verlangt für Bilder und Anhänge eine Web-Anmeldung. Benutzer und "
            + "Passwort werden nur für dieses Absenden verwendet und nicht gespeichert.";

    private final Supplier<SignInRequests> source;
    private final Function<SignInRequests, Optional<SignInRequest>> which;
    private final ComicPalette palette = ComicPalette.defaultPalette();
    private final JPasswordField codeField = new JPasswordField(16);
    private final JTextField userField = new JTextField(16);
    private final JPasswordField passwordField = new JPasswordField(16);
    /** Benutzer, Passwort und Hinweis; nur bei {@link SignInRequest#asksForLogin()} sichtbar. */
    private final JPanel login = new JPanel();
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
    private Runnable onAccepted = () -> { };

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
        codeField.setBorder(ComicBorder.roundedBorder(palette, 4));
        PasswordReveal.install(codeField);
        card.add(fieldRow("OTP-Code:", codeField));
        login.setLayout(new BoxLayout(login, BoxLayout.Y_AXIS));
        login.setOpaque(false);
        login.setAlignmentX(LEFT_ALIGNMENT);
        login.add(Box.createVerticalStrut(8));
        userField.setBorder(ComicBorder.roundedBorder(palette, 4));
        userField.setFont(Comic.regular(13f));
        login.add(fieldRow("Benutzer:", userField));
        login.add(Box.createVerticalStrut(8));
        passwordField.setBorder(ComicBorder.roundedBorder(palette, 4));
        PasswordReveal.install(passwordField);
        login.add(fieldRow("Passwort:", passwordField));
        login.add(Box.createVerticalStrut(8));
        WrappedText note = new WrappedText(LOGIN_NOTE);
        note.setFont(Comic.regular(12.5f));
        note.setForeground(Comic.MUTED);
        note.setAlignmentX(LEFT_ALIGNMENT);
        login.add(note);
        login.setVisible(false);
        card.add(login);
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
        userField.addActionListener(event -> submit());
        passwordField.addActionListener(event -> submit());
        resendButton.addActionListener(event -> resend());
        declineButton.addActionListener(event -> decline());
        refresh();
    }

    /** Beschriftung links, Feld rechts, wie die Zeile des Codefelds. */
    private static JPanel fieldRow(String caption, JComponent field) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        JLabel label = new JLabel(caption);
        label.setFont(Comic.regular(13f));
        label.setLabelFor(field);
        row.add(label);
        row.add(Box.createHorizontalStrut(8));
        row.add(field);
        return row;
    }

    /**
     * Läuft im EDT, sobald die Quelle einen hier eingegebenen Code annimmt, etwa um Bilder und Anhänge der gezeigten
     * Seite neu zu laden (wie {@code UnlockingDocumentReader} im Enterprise AI Client).
     */
    public void onAccepted(Runnable action) {
        this.onAccepted = action == null ? () -> { } : action;
    }

    /** Setzt den Fokus ins Codefeld, sobald es sichtbar ist (Klick auf den Hinweis in der Statuszeile). */
    public void focusCode() {
        if (isVisible()) {
            SwingUtilities.invokeLater(codeField::requestFocusInWindow);
        }
    }

    /** Zeigt die Anforderung, die {@code which} jetzt liefert; eine gleiche bleibt samt eingetipptem Code stehen. */
    public void refresh() {
        SignInRequests requests = watch();
        Optional<SignInRequest> current = requests == null ? Optional.empty() : which.apply(requests);
        if (current.isEmpty()) {
            shown = null;
            localError = null;
            codeField.setText("");
            clearLogin();
            setVisible(false);
            revalidateParent();
            return;
        }
        SignInRequest next = current.get();
        boolean other = shown == null || !shown.id().equals(next.id());
        if (other) {
            codeField.setText("");
            clearLogin();
            localError = null;
        } else if (!next.error().equals(shown.error())) {
            localError = null;
        }
        shown = next;
        String hint = next.message().isEmpty() ? "Ein Einmalcode wurde per E-Mail versendet." : next.message();
        String where = next.title().isEmpty() ? next.service() : next.title();
        message.setText(hint + (where.isEmpty() ? "" : "\n(" + where + ")"));
        // Die Ursache eines gescheiterten Sendens (407, TLS, Zeitüberschreitung …) nennt seit #571 der Core an der
        // Anforderung; die Pauschalmeldung der App bleibt nur für den Fall, dass er keine hat.
        String problem = !next.failure().isEmpty() ? next.failure()
                : localError != null ? localError : next.error();
        error.setText(problem);
        error.setVisible(!problem.isEmpty());
        resendButton.setVisible(next.resendAvailable());
        if (login.isVisible() != next.asksForLogin()) {
            login.setVisible(next.asksForLogin());
            revalidateParent();
        }
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
        // Benutzer und Passwort nur bei einer Anforderung, die sie verlangt, und nur für dieses Absenden; leer heißt
        // null, der Core sendet dann wie ohne Anmeldung.
        String username = request.asksForLogin() && !userField.getText().isBlank() ? userField.getText().trim() : null;
        char[] password = request.asksForLogin() && passwordField.getPassword().length > 0
                ? passwordField.getPassword() : null;
        codeField.setText("");
        clearLogin();
        setSending(true);
        // Der Core überschreibt Code und Passwort nach dem Senden.
        Background.run(() -> requests.submit(request.id(), code, username, password), answer -> {
            setSending(false);
            // Abgelehnt nennt die Quelle den Grund selbst (error der Anforderung).
            localError = answer == SignInRequest.Answer.FAILED ? SEND_FAILED : null;
            refresh();
            if (answer == SignInRequest.Answer.ACCEPTED) {
                onAccepted.run();
            }
        }, failure -> {
            Arrays.fill(code, '\0');
            if (password != null) {
                Arrays.fill(password, '\0');
            }
            setSending(false);
            localError = SEND_FAILED;
            refresh();
        });
    }

    /** Leert Benutzer und Passwort: beide gelten nur für ein Absenden und werden nie behalten. */
    private void clearLogin() {
        userField.setText("");
        passwordField.setText("");
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
        userField.setEnabled(!value);
        passwordField.setEnabled(!value);
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

    JTextField userField() {
        return userField;
    }

    JPasswordField passwordField() {
        return passwordField;
    }

    /** Ob Benutzer und Passwort gerade abgefragt werden. */
    boolean asksForLogin() {
        return login.isVisible();
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
