package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.sources.SignInChallenge;
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
