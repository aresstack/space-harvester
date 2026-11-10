package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.signin.SignInRequest;
import com.aresstack.spaceharvester.application.signin.SignInRequests;
import com.aresstack.spaceharvester.application.sources.SignInChallenge;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.JLabel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Der Hinweis in der Statuszeile: erscheint, sobald ein Auftrag auf einen Einmalcode wartet, nennt die Quelle (bei
 * mehreren alle), führt per Klick zur Anforderung und verschwindet, sobald der Code angenommen oder die Anmeldung
 * abgelehnt ist. Er hängt allein an den Änderungsmeldungen der Anforderungen.
 */
class SignInNoticeTest {

    @Test
    void appearsWithTheRequestNamesTheSourceAndDisappearsWhenAccepted() throws Exception {
        SignInRequests requests = new SignInRequests(Duration.ofSeconds(30), Clock.systemUTC());
        AtomicReference<SignInRequest> clicked = new AtomicReference<>();
        SignInNotice[] notice = new SignInNotice[1];
        SwingUtilities.invokeAndWait(() -> {
            notice[0] = new SignInNotice(() -> requests, Map.of("wiki", "Confluence HaVdB")::get, clicked::set);
            // In der Statuszeile ganz links, vor dem Stand des Bereichs und der App.
            new StatusLine(new JProgressBar(), notice[0], new JLabel("Seite 3"), new JLabel(" "), new JLabel(" "),
                    new JLabel("© 2026 AresStack"));
        });
        assertThat(notice[0].isVisible()).as("ohne Anforderung unsichtbar").isFalse();

        CompletableFuture<SignInRequests.Outcome> waiting = CompletableFuture.supplyAsync(() -> requests.await("wiki",
                "job-1", challenge("wiki.example.org"), Instant.now(), null));
        await(() -> notice[0].isVisible());
        assertThat(notice[0].text()).isEqualTo("Confluence HaVdB" + SignInNotice.SUFFIX_ONE);
        assertThat(notice[0].label().getToolTipText()).isEqualTo(SignInNotice.TOOLTIP);

        SwingUtilities.invokeAndWait(notice[0]::open);
        assertThat(clicked.get()).isNotNull();
        assertThat(clicked.get().sourceId()).isEqualTo("wiki");
        assertThat(clicked.get().jobIds()).containsExactly("job-1");

        requests.submit(clicked.get().id(), "123456".toCharArray());
        assertThat(waiting.get(10, TimeUnit.SECONDS)).isEqualTo(SignInRequests.Outcome.UNLOCKED);
        await(() -> !notice[0].isVisible());
    }

    @Test
    void namesEverySourceAndDisappearsWhenDeclined() throws Exception {
        SignInRequests requests = new SignInRequests(Duration.ofSeconds(30), Clock.systemUTC());
        SignInNotice[] notice = new SignInNotice[1];
        SwingUtilities.invokeAndWait(() -> notice[0] = new SignInNotice(() -> requests,
                // Ohne Anzeigename steht der Dienst der Quelle.
                Map.of("wiki", "Confluence")::get, request -> { }));
        CompletableFuture<SignInRequests.Outcome> first = CompletableFuture.supplyAsync(() -> requests.await("wiki",
                "job-1", challenge("wiki.example.org"), Instant.now(), null));
        // Erst wenn die erste Anforderung steht, kommt die zweite: die Reihenfolge der Quellen folgt dem Eingang.
        await(() -> notice[0].isVisible() && notice[0].text().startsWith("Confluence"));
        CompletableFuture<SignInRequests.Outcome> second = CompletableFuture.supplyAsync(() -> requests.await("docs",
                "job-2", challenge("docs.example.org"), Instant.now(), null));
        await(() -> notice[0].text().contains("docs.example.org"));
        assertThat(notice[0].text()).isEqualTo("Confluence, docs.example.org" + SignInNotice.SUFFIX_MANY);

        for (SignInRequest request : requests.pending()) {
            requests.decline(request.id());
        }
        assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(SignInRequests.Outcome.DECLINED);
        assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(SignInRequests.Outcome.DECLINED);
        await(() -> !notice[0].isVisible());
    }

    @Test
    void followsTheRequestsOfTheCurrentRuntime() throws Exception {
        SignInRequests[] current = {null};
        SignInNotice[] notice = new SignInNotice[1];
        SwingUtilities.invokeAndWait(() -> notice[0] = new SignInNotice(() -> current[0], id -> id,
                request -> { }));
        assertThat(notice[0].isVisible()).isFalse();
        current[0] = new SignInRequests(Duration.ofSeconds(30), Clock.systemUTC());
        // Erst die Laufzeit, dann die Anforderung: wie nach „Neu laden“ ohne neue Anforderung.
        SwingUtilities.invokeAndWait(notice[0]::refresh);
        assertThat(notice[0].isVisible()).isFalse();
        CompletableFuture<SignInRequests.Outcome> waiting = CompletableFuture.supplyAsync(() -> current[0].await(
                "wiki", "job-1", challenge("wiki.example.org"), Instant.now(), null));
        await(() -> notice[0].isVisible());
        assertThat(notice[0].text()).isEqualTo("wiki" + SignInNotice.SUFFIX_ONE);
        current[0].decline(current[0].pending().get(0).id());
        assertThat(waiting.get(10, TimeUnit.SECONDS)).isEqualTo(SignInRequests.Outcome.DECLINED);
        await(() -> !notice[0].isVisible());
    }

    @Test
    void disappearsWhenAReaderOnlyRequestExpiresWithoutAnyNotification() throws Exception {
        // Lesen auf Abruf: kein Auftrag wartet, der Core meldet den Ablauf nicht; der Hinweis fragt zum Ablauf nach.
        SignInRequests requests = new SignInRequests(Duration.ofMillis(300), Clock.systemUTC());
        SignInNotice[] notice = new SignInNotice[1];
        SwingUtilities.invokeAndWait(() -> notice[0] = new SignInNotice(() -> requests, id -> id, request -> { }));
        requests.open("wiki", "leser", challenge("wiki.example.org"));
        await(() -> notice[0].isVisible());
        assertThat(notice[0].expiryTimer().isRunning()).isTrue();
        assertThat(notice[0].expiryTimer().getInitialDelay()).isBetween(1000, 1400);
        await(() -> !notice[0].isVisible());
        assertThat(notice[0].expiryTimer().isRunning()).isFalse();
    }

    @Test
    void theLeftPartLeavesRoomForTheCenterAndTheCopyright() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JLabel alert = new JLabel("Confluence HaVdB, docs.example.org" + SignInNotice.SUFFIX_MANY);
            JLabel lead = new JLabel("Seite 3 von 120 · Kurzbeschreibung DATENH V1.0 · Abschnitt 4.2 Schnittstellen");
            JLabel west = new JLabel("Bestand: C:\\Users\\angelo\\.space-harvester\\data\\exports\\confluence");
            JLabel center = new JLabel("Abbrechen");
            JLabel east = new JLabel("© 2026 AresStack");
            StatusLine line = new StatusLine(new JProgressBar(), alert, lead, west, center, east);
            line.setSize(960, 28);
            layout(line);
            assertThat(center.getWidth()).as("die Mitte behält Platz").isGreaterThan(100);
            assertThat(east.getX() + east.getWidth()).isLessThanOrEqualTo(960);
            assertThat(alert.getWidth()).as("der Hinweis ist nicht leer").isGreaterThan(100);
        });
    }

    /** Eine Abfrage der Quelle: „123456“ gilt. Der Code steht nirgends im Test außer beim Senden. */
    private static SignInChallenge challenge(String service) {
        return new SignInChallenge() {
            public String service() {
                return service;
            }

            public String title() {
                return "";
            }

            public String message() {
                return "Ein Code wurde an deine E-Mail-Adresse gesendet.";
            }

            public String error() {
                return "";
            }

            public boolean resendAvailable() {
                return true;
            }

            public SignInChallenge submit(char[] code) {
                return "123456".equals(new String(code)) ? null : this;
            }

            public SignInChallenge resend() {
                return this;
            }
        };
    }

    /** Ohne Fenster legt erst ein ausdrückliches Layout jedes Kind an seinen Platz. */
    private static void layout(java.awt.Container container) {
        container.doLayout();
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof java.awt.Container inner) {
                layout(inner);
            }
        }
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
