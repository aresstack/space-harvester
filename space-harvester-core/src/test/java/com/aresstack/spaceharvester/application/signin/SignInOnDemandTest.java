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
