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

    @Test
    void aSourceAskingForLoginShowsUserAndPasswordAndSendsThemOnlyWithThatSubmit() throws Exception {
        SignInRequests requests = new SignInRequests(Duration.ofSeconds(30), Clock.systemUTC());
        java.util.List<String> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
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

            @Override
            public boolean asksForLogin() {
                return true;
            }

            public SignInChallenge submit(char[] code) {
                sent.add("ohne Anmeldung " + new String(code));
                return this;
            }

            @Override
            public SignInChallenge submit(char[] code, String username, char[] password) {
                sent.add(username + "/" + (password == null ? null : new String(password)) + " " + new String(code));
                return "123456".equals(new String(code)) ? null : this;
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
        assertThat(slot[0].asksForLogin()).isTrue();
        assertThat(slot[0].userField().getText()).as("nichts vorbelegt").isEmpty();
        assertThat(slot[0].passwordField().getPassword()).isEmpty();
        assertThat(slot[0].passwordField().getEchoChar()).as("Passwort maskiert").isNotEqualTo((char) 0);

        // Leere Felder gehen als null mit; der Core sendet dann wie ohne Anmeldung.
        SwingUtilities.invokeAndWait(() -> {
            slot[0].codeField().setText("000000");
            slot[0].submitButton().doClick();
        });
        await(() -> sent.size() == 1 && slot[0].submitButton().isEnabled());
        assertThat(sent).containsExactly("ohne Anmeldung 000000");

        SwingUtilities.invokeAndWait(() -> {
            slot[0].codeField().setText("123456");
            slot[0].userField().setText(" alice ");
            slot[0].passwordField().setText("geheim");
            slot[0].submitButton().doClick();
            assertThat(slot[0].userField().getText()).as("Benutzer wird nicht behalten").isEmpty();
            assertThat(slot[0].passwordField().getPassword()).as("Passwort wird nicht behalten").isEmpty();
        });
        assertThat(waiting.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(SignInRequests.Outcome.UNLOCKED);
        assertThat(sent).containsExactly("ohne Anmeldung 000000", "alice/geheim 123456");
        await(() -> !slot[0].isVisible());
    }

    @Test
    void aSourceWithoutLoginHidesUserAndPassword() throws Exception {
        SignInRequests requests = new SignInRequests(Duration.ofSeconds(30), Clock.systemUTC());
        CompletableFuture.runAsync(() -> requests.await("wiki", "job-1", challenge(""), Instant.now(), null));
        SignInSlot[] slot = new SignInSlot[1];
        SwingUtilities.invokeAndWait(() -> slot[0] = new SignInSlot(() -> requests, r -> r.pendingFor("wiki")));
        await(() -> slot[0].isVisible());
        assertThat(slot[0].asksForLogin()).isFalse();
        SwingUtilities.invokeAndWait(() -> requests.pendingFor("wiki").ifPresent(r -> requests.decline(r.id())));
        await(() -> !slot[0].isVisible());
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
