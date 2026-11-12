package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.ui.controls.PasswordReveal;
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
 * <p>Senden, Neu-Senden und Ablehnen gehen im Hintergrund an {@link SignInRequests}; der Core reicht den Code an die
 * Quelle weiter und überschreibt ihn. Die Komponente hält ihn nur im Passwortfeld und leert es beim Senden. Nur auf dem
 * EDT.
 */
public final class SignInSlot extends JPanel {

    static final String HEADING = "Zusätzliche Anmeldung erforderlich";
    static final String SUBMIT_TEXT = "Bestätigen";
    /** Knopf im ersten Schritt bei einem Token. */
    static final String LOGIN_TEXT = "Anmelden";
    static final String RESEND_TEXT = "Code erneut senden";
    static final String DECLINE_TEXT = "Ohne Anmeldung fortfahren";
    static final String SENDING_TEXT = "Wird gesendet …";
    static final String RELOGIN_TIP = "Neu anmelden: zurück zu Benutzername und Passwort, etwa wenn die Web-Sitzung "
            + "abgelaufen ist; danach kommt wieder ein Code per E-Mail";
    static final String LOGIN_REQUIRED = "Bitte Benutzername und Passwort eingeben.";
    /** Erster Schritt bei einem Token: erst anmelden, den Code fragt das Feld danach ab. */
    static final String LOGIN_HINT = "Erst mit Benutzername und Passwort anmelden; den Code aus der nächsten E-Mail "
            + "fragt die App danach ab.";
    /** Alle Felder gleich breit. */
    static final int FIELD_COLUMNS = 22;
    /** Nur, wenn der Core keine Ursache an der Anforderung nennt ({@code SignInRequest.failure()} leer). */
    static final String SEND_FAILED = "Senden fehlgeschlagen: die Quelle ist nicht erreichbar.";

    private final Supplier<SignInRequests> source;
    private final Function<SignInRequests, Optional<SignInRequest>> which;
    private final ComicPalette palette = ComicPalette.defaultPalette();
    private final JPasswordField codeField = new JPasswordField(FIELD_COLUMNS);
    private final JTextField usernameField = new JTextField(FIELD_COLUMNS);
    private final JPasswordField passwordField = new JPasswordField(FIELD_COLUMNS);
    /** Merker „Anmeldung gefragt“ für Tests und Layout; die Felder selbst stehen im gemeinsamen Formular. */
    private final JPanel loginFields = new JPanel();
    private final JLabel loginHint = new JLabel(LOGIN_HINT);
    private javax.swing.JComponent[] loginRows;
    private javax.swing.JComponent[] codeRow;
    private final ComicButton resendButton = new ComicButton(RESEND_TEXT);
    private final ComicButton submitButton = new ComicButton(SUBMIT_TEXT);
    private final ComicButton declineButton = new ComicButton(DECLINE_TEXT);
    /**
     * Hinter dem Codefeld (Angelo 11.11.2026): zurück zu Benutzername und Passwort, wenn die Web-Sitzung einer Quelle
     * mit Token abgelaufen ist und der Code allein nicht mehr ankommt. Danach folgt wie beim ersten Mal das Codefeld.
     */
    final IconButton reloginButton = new IconButton(Glyph.BOUNCE, RELOGIN_TIP);
    /** Der Nutzer hat {@link #reloginButton} gedrückt: das Formular fragt wieder Benutzername und Passwort ab. */
    private boolean relogin;
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
        // Ein Formular mit einer Spalte für die Beschriftungen und gleich breiten Feldern bündig daneben (Angelo
        // 10.11.2026). Bei einem Token fragt der erste Schritt nur Benutzername und Passwort ab, der zweite nur den
        // Code; Quellen mit Benutzer und Passwort sehen nur das Codefeld.
        loginHint.setForeground(Comic.MUTED);
        loginHint.setFont(Comic.regular(12f));
        loginHint.setAlignmentX(LEFT_ALIGNMENT);
        loginHint.setVisible(false);
        card.add(loginHint);
        usernameField.setBorder(ComicBorder.roundedBorder(palette, 4));
        passwordField.setBorder(ComicBorder.roundedBorder(palette, 4));
        codeField.setBorder(ComicBorder.roundedBorder(palette, 4));
        PasswordReveal.install(passwordField);
        PasswordReveal.install(codeField);
        loginFields.setLayout(new java.awt.GridBagLayout());
        loginFields.setOpaque(false);
        loginFields.setAlignmentX(LEFT_ALIGNMENT);
        loginFields.setVisible(false);
        JPanel form = new JPanel(new java.awt.GridBagLayout());
        form.setOpaque(false);
        form.setAlignmentX(LEFT_ALIGNMENT);
        JLabel userLabel = formLabel("Benutzername:", usernameField);
        JLabel passwordLabel = formLabel("Passwort:", passwordField);
        JLabel codeLabel = formLabel("OTP-Code:", codeField);
        formRow(form, 0, userLabel, usernameField);
        formRow(form, 1, passwordLabel, passwordField);
        formRow(form, 2, codeLabel, codeField);
        java.awt.GridBagConstraints next = new java.awt.GridBagConstraints();
        next.gridx = 2;
        next.gridy = 2;
        next.anchor = java.awt.GridBagConstraints.LINE_START;
        next.insets = new java.awt.Insets(3, 6, 3, 0);
        form.add(reloginButton, next);
        loginRows = new javax.swing.JComponent[] {userLabel, usernameField, passwordLabel, passwordField};
        codeRow = new javax.swing.JComponent[] {codeLabel, codeField};
        card.add(form);
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
        passwordField.addActionListener(event -> submit());
        resendButton.addActionListener(event -> resend());
        declineButton.addActionListener(event -> decline());
        reloginButton.addActionListener(event -> {
            relogin = true;
            codeField.setText("");
            localError = null;
            refresh();
        });
        refresh();
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
            SwingUtilities.invokeLater(firstField()::requestFocusInWindow);
        }
    }

    /** Zeigt die Anforderung, die {@code which} jetzt liefert; eine gleiche bleibt samt eingetipptem Code stehen. */
    public void refresh() {
        SignInRequests requests = watch();
        Optional<SignInRequest> current = requests == null ? Optional.empty() : which.apply(requests);
        if (current.isEmpty()) {
            shown = null;
            relogin = false;
            localError = null;
            codeField.setText("");
            clearLoginFields();
            showLogin(false);
            setVisible(false);
            revalidateParent();
            return;
        }
        SignInRequest next = current.get();
        boolean other = shown == null || !shown.id().equals(next.id());
        if (other) {
            codeField.setText("");
            clearLoginFields();
            localError = null;
            relogin = false;
        } else if (!next.error().equals(shown.error())) {
            localError = null;
        }
        shown = next;
        if (!next.canRestartLogin()) {
            relogin = false;
        }
        boolean login = loginMode(next);
        boolean loginVisibilityChanged = loginFields.isVisible() != login;
        if (loginVisibilityChanged && !login) {
            clearLoginFields();
        }
        showLogin(login);
        reloginButton.setVisible(!login && next.canRestartLogin());
        // Im ersten Schritt gibt es noch keinen Code für diese Anmeldung: nur der Hinweis über den Feldern.
        String hint = login ? "Für geschützte Bilder und Anhänge ist eine eigene Web-Anmeldung nötig."
                : next.message().isEmpty() ? "Ein Einmalcode wurde per E-Mail versendet." : next.message();
        String where = next.title().isEmpty() ? next.service() : next.title();
        message.setText(hint + (where.isEmpty() ? "" : "\n(" + where + ")"));
        // Die Ursache eines gescheiterten Sendens (407, TLS, Zeitüberschreitung …) nennt seit #571 der Core an der
        // Anforderung; die Pauschalmeldung der App bleibt nur für den Fall, dass er keine hat.
        String problem = !next.failure().isEmpty() ? next.failure()
                : localError != null ? localError : next.error();
        error.setText(problem);
        error.setVisible(!problem.isEmpty());
        // „Code erneut senden“ erst im zweiten Schritt, wenn es ein Codefeld gibt.
        resendButton.setVisible(!login && next.resendAvailable());
        setSending(sending);
        if (loginVisibilityChanged) {
            revalidateParent();
        }
        if (!isVisible()) {
            setVisible(true);
            revalidateParent();
            SwingUtilities.invokeLater(firstField()::requestFocusInWindow);
        } else if (loginVisibilityChanged) {
            SwingUtilities.invokeLater(firstField()::requestFocusInWindow);
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
        // Bei einem Token geht im ersten Schritt kein Code mit: Die neue Sitzung meldet sich nur an (leerer Code).
        boolean login = loginMode(request);
        char[] code = login ? new char[0] : codeField.getPassword();
        if (!login && code.length == 0) {
            codeField.requestFocusInWindow();
            return;
        }
        String username = login ? usernameField.getText().trim() : null;
        char[] password = login ? passwordField.getPassword() : null;
        if (login && (username.isEmpty() || password.length == 0)) {
            Arrays.fill(code, '\0');
            Arrays.fill(password, '\0');
            localError = LOGIN_REQUIRED;
            error.setText(localError);
            error.setVisible(true);
            (username.isEmpty() ? usernameField : passwordField).requestFocusInWindow();
            return;
        }
        codeField.setText("");
        passwordField.setText("");
        setSending(true);
        // Clear secrets after the background call, including exceptional completion.
        Background.run(() -> {
            try {
                return username != null ? requests.beginWebLogin(request.id(), username, password)
                        : requests.submit(request.id(), code);
            } finally {
                Arrays.fill(code, '\0');
                if (password != null) {
                    Arrays.fill(password, '\0');
                }
            }
        }, answer -> {
            setSending(false);
            if (answer != SignInRequest.Answer.FAILED) {
                // Angemeldet: die neue Sitzung fragt jetzt ihren Code ab, wie beim ersten Mal.
                relogin = false;
            }
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
        submitButton.setText(value ? SENDING_TEXT
                : shown != null && loginMode(shown) ? LOGIN_TEXT : SUBMIT_TEXT);
        reloginButton.setEnabled(!value);
        submitButton.setEnabled(!value);
        resendButton.setEnabled(!value);
        declineButton.setEnabled(!value);
        codeField.setEnabled(!value);
        usernameField.setEnabled(!value);
        passwordField.setEnabled(!value);
    }

    private void clearLoginFields() {
        usernameField.setText("");
        passwordField.setText("");
    }

    /** Erster Schritt bei einem Token nur Benutzername und Passwort, sonst nur der Code. */
    private void showLogin(boolean login) {
        loginFields.setVisible(login);
        loginHint.setVisible(login);
        for (javax.swing.JComponent part : loginRows) {
            part.setVisible(login);
        }
        for (javax.swing.JComponent part : codeRow) {
            part.setVisible(!login);
        }
    }

    /** Benutzername und Passwort statt des Codes: erster Schritt bei einem Token oder nach „Neu anmelden“. */
    private boolean loginMode(SignInRequest request) {
        return request.asksForLogin() || relogin && request.canRestartLogin();
    }

    private JTextField firstField() {
        return loginFields.isVisible() ? usernameField : codeField;
    }

    private static JLabel formLabel(String text, JTextField field) {
        JLabel caption = new JLabel(text);
        caption.setFont(Comic.regular(13f));
        caption.setLabelFor(field);
        return caption;
    }

    /** Beschriftung links in ihrer Spalte, Feld bündig daneben; alle Felder gleich breit ({@link #FIELD_COLUMNS}). */
    private static void formRow(JPanel form, int row, JLabel label, JTextField field) {
        java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
        c.gridy = row;
        c.gridx = 0;
        c.anchor = java.awt.GridBagConstraints.LINE_START;
        c.insets = new java.awt.Insets(row == 0 ? 6 : 3, 0, 3, 10);
        form.add(label, c);
        c.gridx = 1;
        c.insets = new java.awt.Insets(row == 0 ? 6 : 3, 0, 3, 0);
        // Gleich breit auch mit Auge im Passwortfeld: die Spalte nimmt das breiteste Feld, alle füllen sie.
        c.fill = java.awt.GridBagConstraints.HORIZONTAL;
        form.add(field, c);
        if (row == 0) {
            // Füller rechts, damit das Formular links bündig bleibt statt mittig zu stehen.
            java.awt.GridBagConstraints rest = new java.awt.GridBagConstraints();
            rest.gridx = 2;
            rest.gridy = 0;
            rest.weightx = 1;
            form.add(Box.createHorizontalGlue(), rest);
        }
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
        Comic.noAutoScroll(area);
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

    JTextField usernameField() {
        return usernameField;
    }

    JPasswordField passwordField() {
        return passwordField;
    }

    boolean loginFieldsVisible() {
        return loginFields.isVisible();
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
