package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.sources.SignInChallenge;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.signin.SignInRequests;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Die Einmalcode-Abfrage wie im Enterprise AI Client: erscheint, sobald ein Auftrag auf einen Code wartet, zeigt den
 * Fehler der Quelle nach einem falschen Code, und ein richtiger Code lässt den Auftrag weiterlaufen.
 */
class SignInSlotTest {

    @Test
    void aWrongCodeShowsTheErrorOfTheSourceAndTheRightOneLetsTheJobContinue() throws Exception {
        SignInRequests requests = new SignInRequests(Duration.ofSeconds(30), Clock.systemUTC());
        CompletableFuture<SignInRequests.Outcome> waiting = CompletableFuture.supplyAsync(() -> requests.await("wiki",
                "job-1", challenge(""), Instant.now(), null));
        SignInSlot[] slot = new SignInSlot[1];
        java.util.concurrent.atomic.AtomicInteger accepted = new java.util.concurrent.atomic.AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            slot[0] = new SignInSlot(() -> requests, r -> r.pendingFor("wiki"));
            slot[0].onAccepted(accepted::incrementAndGet);
        });

        await(() -> slot[0].isVisible());
        assertThat(slot[0].resendButton().isVisible()).isTrue();
        assertThat(slot[0].loginFieldsVisible()).isFalse();
        assertThat(slot[0].shown().jobIds()).containsExactly("job-1");

        SwingUtilities.invokeAndWait(() -> {
            slot[0].codeField().setText("000000");
            slot[0].submitButton().doClick();
            assertThat(slot[0].codeField().getPassword()).as("das Feld hält den Code nicht").isEmpty();
        });
        // Der Fehler der Quelle kommt über ihre Änderungsmeldung, bevor das Senden abgeschlossen ist; erst danach
        // nimmt die Abfrage den nächsten Code an.
        await(() -> slot[0].errorText().contains("Code ungültig") && slot[0].submitButton().isEnabled());
        assertThat(waiting).isNotDone();
        assertThat(accepted).as("ein falscher Code lädt nichts neu").hasValue(0);

        SwingUtilities.invokeAndWait(() -> {
            slot[0].codeField().setText("123456");
            slot[0].submitButton().doClick();
        });
        assertThat(waiting.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(SignInRequests.Outcome.UNLOCKED);
        await(() -> !slot[0].isVisible());
        // Angenommen: die Ansicht lädt die gezeigte Seite neu (Bilder und Anhänge ohne Sperre).
        await(() -> accepted.get() == 1);
    }

    @Test
    void aFailedSendShowsTheCauseFromTheCoreAndASuccessfulOneClearsIt() throws Exception {
        SignInRequests requests = new SignInRequests(Duration.ofSeconds(30), Clock.systemUTC());
        SourceAccessException[] transport = {new SourceAccessException("wiki",
                SourceAccessException.Reason.UNAVAILABLE, "Einmalcode nicht gesendet (HttpRetryException)",
                new Exception("Einmalcode nicht gesendet", new java.net.HttpRetryException(
                        "cannot retry due to proxy authentication, in streaming mode", 407)))};
        SignInChallenge challenge = new SignInChallenge() {
            public String service() {
                return "wiki.example.org";
            }

            public String title() {
                return "";
            }

            public String message() {
                return "";
            }

            public String error() {
                return "";
            }

            public boolean resendAvailable() {
                return false;
            }

            public SignInChallenge submit(char[] code) {
                if (transport[0] != null) {
                    throw transport[0];
                }
                return null;
            }

            public SignInChallenge resend() {
                return this;
            }
        };
        CompletableFuture<SignInRequests.Outcome> waiting = CompletableFuture.supplyAsync(() -> requests.await("wiki",
                "job-1", challenge, Instant.now(), null));
        SignInSlot[] slot = new SignInSlot[1];
        SwingUtilities.invokeAndWait(() -> slot[0] = new SignInSlot(() -> requests, r -> r.pendingFor("wiki")));
        await(() -> slot[0].isVisible());

        SwingUtilities.invokeAndWait(() -> {
            slot[0].codeField().setText("000000");
            slot[0].submitButton().doClick();
        });
        // Statt der Pauschalmeldung die Ursache des Core (#571), ohne den Code.
        await(() -> slot[0].errorText().equals("Proxy verlangt Anmeldung (HTTP 407) – Ziel wiki.example.org")
                && slot[0].submitButton().isEnabled());
        assertThat(slot[0].errorText()).doesNotContain(SignInSlot.SEND_FAILED).doesNotContain("000000");
        assertThat(waiting).isNotDone();

        transport[0] = null;
        SwingUtilities.invokeAndWait(() -> {
            slot[0].codeField().setText("123456");
            slot[0].submitButton().doClick();
        });
        assertThat(waiting.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(SignInRequests.Outcome.UNLOCKED);
        await(() -> !slot[0].isVisible());
    }

    /**
     * Token-Quelle in zwei Schritten (Angelo 10.11.2026): erst nur Benutzername und Passwort (ohne Code, ohne „Code
     * erneut senden“), dann nur der Code der neuen Sitzung; erst der zweite Code entsperrt.
     */
    @Test
    void aPatChallengeAsksForTheLoginFirstAndThenOnlyForTheCodeOfTheNewSession() throws Exception {
        SignInRequests requests = new SignInRequests(Duration.ofSeconds(30), Clock.systemUTC());
        java.util.concurrent.atomic.AtomicInteger oldCodes = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> login = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> code = new java.util.concurrent.atomic.AtomicReference<>();
        SignInChallenge newSession = new SignInChallenge() {
            public String service() {
                return "wiki.example.org";
            }

            public String title() {
                return "";
            }

            public String message() {
                return "Neuer Code per E-Mail.";
            }

            public String error() {
                return "";
            }

            public boolean resendAvailable() {
                return true;
            }

            public SignInChallenge submit(char[] value) {
                code.set(new String(value));
                return null;
            }

            public SignInChallenge resend() {
                return this;
            }
        };
        SignInChallenge pat = new SignInChallenge() {
            public String service() {
                return "wiki.example.org";
            }

            public String title() {
                return "";
            }

            public String message() {
                return "Code der Token-Sitzung.";
            }

            public String error() {
                return "";
            }

            public boolean resendAvailable() {
                return true;
            }

            @Override
            public boolean asksForLogin() {
                return true;
            }

            public SignInChallenge submit(char[] value) {
                oldCodes.incrementAndGet();
                return null;
            }

            @Override
            public SignInChallenge submit(char[] value, String username, char[] password) {
                assertThat(value).as("im ersten Schritt kein Code").isEmpty();
                login.set(username + ":" + new String(password));
                return newSession;
            }

            public SignInChallenge resend() {
                return this;
            }
        };
        CompletableFuture<SignInRequests.Outcome> waiting = CompletableFuture.supplyAsync(() ->
                requests.await("wiki", "job-2", pat, Instant.now(), null));
        SignInSlot[] slot = new SignInSlot[1];
        SwingUtilities.invokeAndWait(() -> slot[0] = new SignInSlot(() -> requests, r -> r.pendingFor("wiki")));
        await(() -> slot[0].isVisible());

        SwingUtilities.invokeAndWait(() -> {
            assertThat(slot[0].loginFieldsVisible()).isTrue();
            assertThat(slot[0].usernameField().isVisible()).isTrue();
            assertThat(slot[0].passwordField().isVisible()).isTrue();
            assertThat(slot[0].codeField().isVisible()).isFalse();
            assertThat(slot[0].resendButton().isVisible()).as("noch kein Codefeld").isFalse();
            assertThat(slot[0].submitButton().getText()).isEqualTo(SignInSlot.LOGIN_TEXT);
            slot[0].submitButton().doClick();
            assertThat(slot[0].errorText()).isEqualTo(SignInSlot.LOGIN_REQUIRED);
            slot[0].usernameField().setText("maria");
            slot[0].passwordField().setText("temporary-password");
            slot[0].submitButton().doClick();
            assertThat(slot[0].passwordField().getPassword()).isEmpty();
        });

        // Zweiter Schritt: nur das Codefeld, kein Fehler, die Anforderung bleibt offen.
        await(() -> slot[0].codeField().isVisible() && slot[0].submitButton().isEnabled());
        assertThat(waiting).as("die Anmeldung allein entsperrt nicht").isNotDone();
        SwingUtilities.invokeAndWait(() -> {
            assertThat(slot[0].usernameField().getText()).as("Zugangsdaten nach Schritt 1 geleert").isEmpty();
            assertThat(slot[0].loginFieldsVisible()).isFalse();
            assertThat(slot[0].usernameField().isVisible()).isFalse();
            assertThat(slot[0].resendButton().isVisible()).isTrue();
            assertThat(slot[0].submitButton().getText()).isEqualTo(SignInSlot.SUBMIT_TEXT);
            assertThat(slot[0].errorText()).isEmpty();
            assertThat(slot[0].shown().message()).isEqualTo("Neuer Code per E-Mail.");
            slot[0].codeField().setText("123456");
            slot[0].submitButton().doClick();
            assertThat(slot[0].codeField().getPassword()).isEmpty();
        });
        assertThat(waiting.get(10, java.util.concurrent.TimeUnit.SECONDS))
                .isEqualTo(SignInRequests.Outcome.UNLOCKED);
        assertThat(login).hasValue("maria:temporary-password");
        assertThat(code).hasValue("123456");
        assertThat(oldCodes).hasValue(0);
        await(() -> !slot[0].isVisible());
        SwingUtilities.invokeAndWait(() -> {
            assertThat(slot[0].usernameField().getText()).isEmpty();
            assertThat(slot[0].passwordField().getPassword()).isEmpty();
        });
    }

    /**
     * Neu anmelden (Angelo 11.11.2026): Bei einer Quelle mit Token steht hinter dem Codefeld ein Knopf, der zurück auf
     * Benutzername und Passwort schaltet; nach der Anmeldung kommt wieder nur das Codefeld.
     */
    @Test
    void theReloginButtonSwitchesBackToUsernameAndPassword() throws Exception {
        SignInRequests requests = new SignInRequests(Duration.ofSeconds(30), Clock.systemUTC());
        java.util.concurrent.atomic.AtomicReference<String> login = new java.util.concurrent.atomic.AtomicReference<>();
        SignInChallenge[] codeStep = new SignInChallenge[1];
        codeStep[0] = new SignInChallenge() {
            public String service() {
                return "wiki.example.org";
            }

            public String title() {
                return "";
            }

            public String message() {
                return "Code der Web-Sitzung.";
            }

            public String error() {
                return "";
            }

            public boolean resendAvailable() {
                return true;
            }

            @Override
            public boolean canRestartLogin() {
                return true;
            }

            public SignInChallenge submit(char[] value) {
                return this;
            }

            @Override
            public SignInChallenge submit(char[] value, String username, char[] password) {
                assertThat(value).isEmpty();
                login.set(username + ":" + new String(password));
                return this;
            }

            public SignInChallenge resend() {
                return this;
            }
        };
        CompletableFuture.supplyAsync(() -> requests.await("wiki", "job-3", codeStep[0], Instant.now(), null));
        SignInSlot[] slot = new SignInSlot[1];
        SwingUtilities.invokeAndWait(() -> slot[0] = new SignInSlot(() -> requests, r -> r.pendingFor("wiki")));
        await(() -> slot[0].isVisible());

        SwingUtilities.invokeAndWait(() -> {
            assertThat(slot[0].codeField().isVisible()).isTrue();
            assertThat(slot[0].reloginButton.isVisible()).isTrue();
            assertThat(slot[0].reloginButton.getToolTipText()).isEqualTo(SignInSlot.RELOGIN_TIP);
            slot[0].reloginButton.doClick();
            assertThat(slot[0].usernameField().isVisible()).isTrue();
            assertThat(slot[0].codeField().isVisible()).isFalse();
            assertThat(slot[0].reloginButton.isVisible()).isFalse();
            assertThat(slot[0].submitButton().getText()).isEqualTo(SignInSlot.LOGIN_TEXT);
            slot[0].usernameField().setText("maria");
            slot[0].passwordField().setText("again");
            slot[0].submitButton().doClick();
        });
        await(() -> login.get() != null && slot[0].submitButton().isEnabled());
        SwingUtilities.invokeAndWait(() -> {
            assertThat(slot[0].codeField().isVisible()).as("danach wieder das Codefeld").isTrue();
            assertThat(slot[0].usernameField().isVisible()).isFalse();
            assertThat(slot[0].reloginButton.isVisible()).isTrue();
        });
        assertThat(login).hasValue("maria:again");
    }

    @Test
    void theFieldsStandFlushUnderEachOtherAndAreEquallyWide() throws Exception {
        SignInSlot[] slot = new SignInSlot[1];
        SwingUtilities.invokeAndWait(() -> {
            slot[0] = new SignInSlot(() -> null, r -> java.util.Optional.empty());
            // Alle drei sichtbar machen, um die Spalten zu messen.
            slot[0].usernameField().setVisible(true);
            slot[0].passwordField().setVisible(true);
            slot[0].codeField().setVisible(true);
            java.awt.Container form = slot[0].codeField().getParent();
            form.setSize(form.getPreferredSize());
            form.doLayout();
            int x = slot[0].usernameField().getX();
            int width = slot[0].usernameField().getWidth();
            assertThat(slot[0].passwordField().getX()).isEqualTo(x);
            assertThat(slot[0].codeField().getX()).isEqualTo(x);
            assertThat(slot[0].passwordField().getWidth()).isEqualTo(width);
            assertThat(slot[0].codeField().getWidth()).isEqualTo(width);
            assertThat(slot[0].usernameField().getParent()).isSameAs(form);
        });
    }

    /** Eine Abfrage der Quelle: „123456“ gilt, alles andere bringt „Code ungültig“. */
    private static SignInChallenge challenge(String error) {
        return new SignInChallenge() {
            public String service() {
                return "wiki.example.org";
            }

            public String title() {
                return "";
            }

            public String message() {
                return "Ein Code wurde an deine E-Mail-Adresse gesendet.";
            }

            public String error() {
                return error;
            }

            public boolean resendAvailable() {
                return true;
            }

            public SignInChallenge submit(char[] code) {
                return "123456".equals(new String(code)) ? null : challenge("Code ungültig");
            }

            public SignInChallenge resend() {
                return challenge("");
            }
        };
    }

    private static void await(BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 400; i++) {
            boolean[] done = new boolean[1];
            SwingUtilities.invokeAndWait(() -> done[0] = condition.getAsBoolean());
            if (done[0]) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Bedingung nicht erreicht");
    }
}
