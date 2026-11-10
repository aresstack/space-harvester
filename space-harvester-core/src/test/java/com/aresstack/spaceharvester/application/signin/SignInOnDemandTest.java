package com.aresstack.spaceharvester.application.signin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.sources.SignInChallenge;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Lesen auf Abruf (ADR 0018): Anforderung ohne Warten, je Quelle geteilt, nur für ihre Halter sichtbar. */
class SignInOnDemandTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-19T08:00:00Z"));
    private final SignInRequests signIns = new SignInRequests(Duration.ofMinutes(15), clock);

    @Test
    void openingTwiceForTheSameSourceSharesOneRequestAndBothCallersHoldIt() {
        List<Integer> changes = new ArrayList<>();
        signIns.onChange(() -> changes.add(1));

        SignInRequest first = signIns.open("wiki", "anna", new Challenge());
        SignInRequest second = signIns.open("wiki", "anna", new Challenge());
        SignInRequest other = signIns.open("wiki", "bernd", new Challenge());

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(other.id()).isEqualTo(first.id());
        assertThat(first.jobIds()).isEmpty();
        assertThat(signIns.pendingFor("wiki")).map(SignInRequest::id).contains(first.id());
        assertThat(signIns.ownedBy(first.id(), "anna")).isTrue();
        assertThat(signIns.ownedBy(first.id(), "bernd")).isTrue();
        assertThat(signIns.ownedBy(first.id(), "carla")).isFalse();
        assertThat(signIns.ownedBy("fremd", "anna")).isFalse();
        // Neu angelegt und ein weiterer Halter: zwei Meldungen; derselbe Halter noch einmal: keine.
        assertThat(changes).hasSize(2);
    }

    @Test
    void anAcceptedCodeEndsTheRequestAndALaterAttemptIsUnlocked() {
        Instant failedAt = clock.instant();
        SignInRequest request = signIns.open("wiki", "anna", new Challenge());

        assertThat(signIns.submit(request.id(), "123456".toCharArray())).isEqualTo(SignInRequest.Answer.ACCEPTED);

        assertThat(signIns.pendingFor("wiki")).isEmpty();
        assertThat(signIns.ownedBy(request.id(), "anna")).isFalse();
        assertThat(signIns.unlockedSince("wiki", failedAt)).isTrue();
        assertThat(signIns.unlockedSince("andere", failedAt)).isFalse();
    }

    @Test
    void aNewerPageReplacesTheChallengeOfTheOpenRequestAndTheCodeGoesToIt() throws Exception {
        List<String> logs = capture();
        List<Integer> changes = new java.util.concurrent.CopyOnWriteArrayList<>();
        signIns.onChange(() -> changes.add(1));
        Page first = new Page("Code per E-Mail gesendet");
        Page second = new Page("Neuer Code per E-Mail gesendet");

        SignInRequest request = signIns.open("wiki", "anna", first);
        // Eine Abfrage, die schon lief, bringt eine neuere Zwei-Faktor-Seite mit: deren Cookie steht jetzt in der
        // Sitzung, deren Code wurde zuletzt verschickt (#571).
        SignInRequest again = signIns.open("wiki", "bernd", second);

        assertThat(again.id()).isEqualTo(request.id());
        assertThat(again.message()).isEqualTo("Neuer Code per E-Mail gesendet");
        assertThat(signIns.pendingChallenge("wiki")).containsSame(second);
        assertThat(changes).hasSize(2);
        assertThat(signIns.pendingChallenge("andere")).isEmpty();

        // Eine Seite, die schon galt, ersetzt die neuere nicht wieder; dieselbe Seite ändert nichts.
        signIns.open("wiki", "anna", first);
        signIns.open("wiki", "anna", signIns.pendingChallenge("wiki").orElseThrow());
        assertThat(signIns.pendingChallenge("wiki")).containsSame(second);
        assertThat(changes).hasSize(2);

        // Auch ein wartender Auftrag, der mit einer neueren Seite dazukommt, ersetzt sie.
        Page third = new Page("Neuer Code per E-Mail gesendet");
        java.util.concurrent.atomic.AtomicReference<SignInRequests.Outcome> outcome =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread job = new Thread(() -> outcome.set(signIns.await("wiki", "job-1", third, clock.instant(), null)));
        job.start();
        for (int i = 0; i < 400 && signIns.waiting("job-1").isEmpty(); i++) {
            Thread.sleep(10);
        }
        assertThat(signIns.waiting("job-1")).map(SignInRequest::id).contains(request.id());
        assertThat(signIns.pendingChallenge("wiki")).containsSame(third);

        assertThat(signIns.submit(request.id(), "123456".toCharArray())).isEqualTo(SignInRequest.Answer.ACCEPTED);
        job.join(5_000);

        assertThat(outcome.get()).isEqualTo(SignInRequests.Outcome.UNLOCKED);
        assertThat(first.codes).isEmpty();
        assertThat(second.codes).isEmpty();
        assertThat(third.codes).containsExactly("123456");
        assertThat(signIns.pendingChallenge("wiki")).isEmpty();
        assertThat(logs).isNotEmpty().noneMatch(line -> line.contains("123456"));
    }

    @Test
    void whileACodeIsBeingSentANewerPageDoesNotReplaceTheChallenge() throws Exception {
        java.util.concurrent.CountDownLatch sending = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Page rejected = new Page("Code per E-Mail gesendet");
        Page first = new Page("Code per E-Mail gesendet") {
            @Override
            public SignInChallenge submit(char[] code) {
                codes.add(new String(code));
                sending.countDown();
                try {
                    release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return rejected;
            }
        };
        SignInRequest request = signIns.open("wiki", "anna", first);
        java.util.concurrent.atomic.AtomicReference<SignInRequest.Answer> answer =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread submit = new Thread(() -> answer.set(signIns.submit(request.id(), "123456".toCharArray())));
        submit.start();
        assertThat(sending.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThat(signIns.open("wiki", "bernd", new Page("Neuer Code")).id()).isEqualTo(request.id());
        assertThat(signIns.pendingChallenge("wiki")).containsSame(first);

        release.countDown();
        submit.join(5_000);
        assertThat(answer.get()).isEqualTo(SignInRequest.Answer.REJECTED);
        assertThat(signIns.pendingChallenge("wiki")).containsSame(rejected);
        assertThat(signIns.request(request.id())).isPresent();
        // Die Seite vor dem Senden gilt nie wieder.
        signIns.open("wiki", "anna", first);
        assertThat(signIns.pendingChallenge("wiki")).containsSame(rejected);
    }

    @Test
    void aRequestNobodyAnswersEndsAfterTheWait() {
        SignInRequest request = signIns.open("wiki", "anna", new Challenge());

        clock.advance(Duration.ofMinutes(14));
        assertThat(signIns.request(request.id())).isPresent();
        clock.advance(Duration.ofMinutes(2));

        assertThat(signIns.request(request.id())).isEmpty();
        assertThat(signIns.pending()).isEmpty();
        assertThat(signIns.submit(request.id(), "1".toCharArray())).isEqualTo(SignInRequest.Answer.GONE);
    }

    @Test
    void withoutAUserInterfaceThereIsNoRequestOnDemand() {
        assertThatThrownBy(() -> SignInRequests.none().open("wiki", "anna", new Challenge()))
                .isInstanceOf(IllegalStateException.class);
    }

    /** Protokollzeilen von {@link SignInRequests} ({@code System.Logger} läuft hier über java.util.logging). */
    private static List<String> capture() {
        List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.logging.Logger.getLogger(SignInRequests.class.getName()).addHandler(new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                lines.add(java.text.MessageFormat.format(record.getMessage(), record.getParameters()));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return lines;
    }

    /** Eine Zwei-Faktor-Seite der Quelle mit eigenem Hinweis; merkt sich, welche Codes an sie gingen. */
    private static class Page implements SignInChallenge {

        final String message;
        final List<String> codes = new java.util.concurrent.CopyOnWriteArrayList<>();

        Page(String message) {
            this.message = message;
        }

        @Override
        public String service() {
            return "wiki.example.org";
        }

        @Override
        public String title() {
            return "Zwei-Faktor-Anmeldung";
        }

        @Override
        public String message() {
            return message;
        }

        @Override
        public String error() {
            return "";
        }

        @Override
        public boolean resendAvailable() {
            return true;
        }

        @Override
        public SignInChallenge submit(char[] code) {
            codes.add(new String(code));
            return null;
        }

        @Override
        public SignInChallenge resend() {
            return this;
        }
    }

    /** Nimmt jeden Code an; enthält nie einen Code. */
    private static final class Challenge implements SignInChallenge {

        @Override
        public String service() {
            return "wiki.example.org";
        }

        @Override
        public String title() {
            return "Zwei-Faktor-Anmeldung";
        }

        @Override
        public String message() {
            return "Code per E-Mail gesendet";
        }

        @Override
        public String error() {
            return "";
        }

        @Override
        public boolean resendAvailable() {
            return false;
        }

        @Override
        public SignInChallenge submit(char[] code) {
            return null;
        }

        @Override
        public SignInChallenge resend() {
            return this;
        }
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
