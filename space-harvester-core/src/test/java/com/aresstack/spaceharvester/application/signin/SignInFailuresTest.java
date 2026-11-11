package com.aresstack.spaceharvester.application.signin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.sources.SignInChallenge;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.HttpRetryException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

/**
 * Ursache eines gescheiterten Sendens des Einmalcodes (#571): kurz, bereinigt, an der Anforderung sichtbar statt
 * pauschal „Quelle nicht erreichbar“; im Protokoll nur Quelle, Art und Ausnahmetyp, nie der Code.
 */
class SignInFailuresTest {

    private static final String HOST = "wiki.example.org";

    /** Wie der Core die Meldung des Adapters einpackt: SourceAccessException ← Adapterfehler ← Transportfehler. */
    private static SourceAccessException wrapped(String adapterMessage, Throwable transport) {
        return new SourceAccessException("wiki", SourceAccessException.Reason.UNAVAILABLE, adapterMessage,
                new Exception(adapterMessage, transport));
    }

    @Test
    void onlyATunnelRefusedWithAServerErrorNeverReachedTheTarget() {
        assertThat(SignInFailures.refusedTunnel(wrapped("Einmalcode nicht gesendet (IOException)", new IOException(
                "Unable to tunnel through proxy. Proxy returns \"HTTP/1.1 503 Service Unavailable\"")))).isEqualTo(503);
        assertThat(SignInFailures.refusedTunnel(wrapped("x", new IOException("Unable to tunnel through proxy. Proxy "
                + "returns \"HTTP/1.1 407 Proxy Authentication Required\"")))).isEqualTo(-1);
        assertThat(SignInFailures.refusedTunnel(new SourceAccessException("wiki",
                SourceAccessException.Reason.ACCESS_DENIED, "Einmalcode gesendet, Confluence antwortet mit HTTP 503",
                null))).isEqualTo(-1);
        assertThat(SignInFailures.describe(new SourceAccessException("wiki", SourceAccessException.Reason.UNAVAILABLE,
                SignInFailures.codeNotDelivered(503), null), HOST))
                .isEqualTo(SignInFailures.codeNotDelivered(503) + " – Ziel wiki.example.org");
    }

    @Test
    void eachKindOfFailureIsNamedWithTheTargetHost() {
        assertThat(SignInFailures.describe(wrapped("Einmalcode nicht gesendet (HttpRetryException: cannot retry)",
                new HttpRetryException("cannot retry due to proxy authentication, in streaming mode", 407)), HOST))
                .isEqualTo("Proxy verlangt Anmeldung (HTTP 407) – Ziel wiki.example.org");
        assertThat(SignInFailures.describe(wrapped("x", new IOException("Unable to tunnel through proxy. Proxy "
                + "returns \"HTTP/1.1 407 Proxy Authentication Required\"")), HOST))
                .isEqualTo("Proxy verlangt Anmeldung (HTTP 407) – Ziel wiki.example.org");
        assertThat(SignInFailures.describe(wrapped("x", new IOException("Unable to tunnel through proxy. Proxy "
                + "returns \"HTTP/1.1 502 Bad Gateway\"")), HOST))
                .isEqualTo("Proxy-Tunnel abgelehnt (HTTP 502) – Ziel wiki.example.org");
        assertThat(SignInFailures.describe(wrapped("x", new IOException("proxy route for wiki.example.org "
                + "unavailable: PAC nicht ladbar")), HOST))
                .isEqualTo("Proxy-Route nicht verfügbar – Ziel wiki.example.org");
        assertThat(SignInFailures.describe(wrapped("x", new SSLHandshakeException("Received fatal alert: "
                + "bad_certificate")), HOST))
                .isEqualTo("TLS-Fehler (SSLHandshakeException: Received fatal alert: bad_certificate) – Ziel "
                        + "wiki.example.org");
        assertThat(SignInFailures.describe(wrapped("x", new SocketTimeoutException("Read timed out")), HOST))
                .isEqualTo("Zeitüberschreitung (SocketTimeoutException) – Ziel wiki.example.org");
        assertThat(SignInFailures.describe(wrapped("x", new UnknownHostException("wiki.example.org")), HOST))
                .isEqualTo("Host nicht auflösbar (UnknownHostException) – Ziel wiki.example.org");
        assertThat(SignInFailures.describe(wrapped("x", new ConnectException("Connection refused")), HOST))
                .isEqualTo("Verbindung abgelehnt (ConnectException) – Ziel wiki.example.org");
        assertThat(SignInFailures.describe(new SourceAccessException("wiki", SourceAccessException.Reason.ACCESS_DENIED,
                "Einmalcode gesendet, Confluence antwortet mit HTTP 403", null), HOST))
                .isEqualTo("Quelle antwortet mit HTTP 403 – Ziel wiki.example.org");
        assertThat(SignInFailures.describe(wrapped("x", new IOException("Stream closed")), HOST))
                .isEqualTo("Verbindungsfehler (IOException) – Ziel wiki.example.org");
    }

    @Test
    void theTargetIsOnlyTheHostAndMessagesLoseQueriesAndSecrets() {
        assertThat(SignInFailures.describe(wrapped("x", new IOException("Stream closed")),
                "https://user:pw@wiki.example.org:8443/plugins/otp?code=123456"))
                .isEqualTo("Verbindungsfehler (IOException) – Ziel wiki.example.org");
        assertThat(SignInFailures.describe(wrapped("x", new IOException("Stream closed")), null))
                .isEqualTo("Verbindungsfehler (IOException)");

        String clean = SignInFailures.sanitize("Einmalcode nicht gesendet (IOException: POST "
                + "https://wiki.example.org/otp?atl_token=abc&code=123456 Cookie: JSESSIONID=XYZ123 "
                + "Proxy-Authorization: Basic cHJveHk6Z2VoZWlt token=eyJhbGciOiJIUzI1NiJ9abcdefghijklmnopqrstuvwxyz)");
        assertThat(clean).contains("https://wiki.example.org/otp?…").doesNotContain("atl_token=abc")
                .doesNotContain("123456").doesNotContain("XYZ123").doesNotContain("cHJveHk6Z2VoZWlt")
                .doesNotContain("eyJhbGci").hasSizeLessThanOrEqualTo(SignInFailures.MAX_DETAIL);
        assertThat(SignInFailures.without("Fehler 123456 und 123456", "123456".toCharArray()))
                .isEqualTo("Fehler *** und ***");
    }

    @Test
    void aFailedSendKeepsTheRequestOpenWithTheCauseAndASuccessfulOneClearsIt() {
        SignInRequests signIns = new SignInRequests(Duration.ofMinutes(15), Clock.systemUTC());
        Failing challenge = new Failing(wrapped("Einmalcode nicht gesendet (HttpRetryException: cannot retry)",
                new HttpRetryException("cannot retry due to proxy authentication, in streaming mode", 407)));
        SignInRequest request = signIns.open("wiki", "anna", challenge);
        assertThat(request.failure()).isEmpty();
        List<String> logged = capture();
        char[] code = "987654".toCharArray();

        assertThat(signIns.submit(request.id(), code)).isEqualTo(SignInRequest.Answer.FAILED);

        assertThat(code).containsOnly('\0');
        assertThat(signIns.request(request.id())).get().extracting(SignInRequest::failure)
                .isEqualTo("Proxy verlangt Anmeldung (HTTP 407) – Ziel wiki.example.org");
        assertThat(logged).contains("Einmalcode für Quelle wiki nicht gesendet (UNAVAILABLE, HttpRetryException)")
                .allSatisfy(line -> assertThat(line).doesNotContain("987654").doesNotContain("cannot retry"));

        challenge.failure = null;
        assertThat(signIns.submit(request.id(), "111111".toCharArray())).isEqualTo(SignInRequest.Answer.REJECTED);
        assertThat(signIns.request(request.id())).get().extracting(SignInRequest::failure).isEqualTo("");
    }

    @Test
    void aFailedResendIsVisibleAtTheRequestAndStillThrows() {
        SignInRequests signIns = new SignInRequests(Duration.ofMinutes(15), Clock.systemUTC());
        Failing challenge = new Failing(wrapped("Neuer Einmalcode nicht angefordert",
                new SocketTimeoutException("connect timed out")));
        SignInRequest request = signIns.open("wiki", "anna", challenge);

        assertThatThrownBy(() -> signIns.resend(request.id())).isInstanceOf(SourceAccessException.class);

        assertThat(signIns.request(request.id())).get().extracting(SignInRequest::failure)
                .isEqualTo("Zeitüberschreitung (SocketTimeoutException) – Ziel wiki.example.org");
    }

    @Test
    void theRequestKeepsItsFormerShape() {
        SignInRequest request = new SignInRequest("id", "wiki", HOST, "", "", "", true, List.of(),
                java.time.Instant.EPOCH);

        assertThat(request.failure()).isEmpty();
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

    /** Scheitert mit {@link #failure}, solange gesetzt; sonst lehnt sie den Code ab. */
    private static final class Failing implements SignInChallenge {

        SourceAccessException failure;

        Failing(SourceAccessException failure) {
            this.failure = failure;
        }

        @Override
        public String service() {
            return HOST;
        }

        @Override
        public String title() {
            return "";
        }

        @Override
        public String message() {
            return "";
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
            if (failure != null) {
                throw failure;
            }
            return this;
        }

        @Override
        public SignInChallenge resend() {
            if (failure != null) {
                throw failure;
            }
            return this;
        }
    }
}
