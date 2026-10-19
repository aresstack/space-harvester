package com.aresstack.spaceharvester.source.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import com.aresstack.spaceharvester.ai.AiServiceException;
import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.passages.NavigationLabelModel;
import com.aresstack.spaceharvester.source.TrustBundles;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Ein nicht erreichbares Chat-Modell hält die Hintergrund-Warteschlange nicht auf (#181, ADR 0020). */
class ChatNavigationLabelerTest {

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int status = 503;
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-19T05:00:00Z"));

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = (status == 200
                    ? "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"1: Belege\"}}]}"
                    : "{\"error\":{\"message\":\"nein\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void afterAFailureNoFurtherBatchAndNoFurtherPageGoesToTheNetworkForAWhile() {
        AiServiceSettings settings = settings("gpt-x", base());
        ChatNavigationLabeler labeler = labeler(() -> settings);
        List<String> texts = IntStream.range(0, 100).mapToObj(i -> "Abschnitt " + i + ".").toList();

        NavigationLabelModel.Labels labels = labeler.labels("Titel", texts);

        assertThat(labels.complete()).isFalse();
        assertThat(labels.labels()).hasSize(100);
        assertThat(requests).hasValue(1); // drei Pakete, nur das erste ging ans Netz

        status = 200;
        assertThatThrownBy(() -> labeler.labels("Titel", List.of("Belege.")))
                .isInstanceOfSatisfying(AiServiceException.class,
                        e -> assertThat(e.reason()).isEqualTo(AiServiceException.Reason.UNREACHABLE));
        assertThat(requests).hasValue(1);

        now.set(now.get().plus(ChatNavigationLabeler.PAUSE));
        assertThat(labeler.labels("Titel", List.of("Belege.")).complete()).isTrue();
        assertThat(requests).hasValue(2);
    }

    @Test
    void aCallerThatWaitedForASlotWhileTheOthersFailedStaysOffTheNetwork() throws Exception {
        AiServiceSettings settings = settings("gpt-x", base());
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch busy = new CountDownLatch(ChatNavigationLabeler.PARALLELISM);
        CountDownLatch fail = new CountDownLatch(1);
        ChatNavigationLabeler labeler = new ChatNavigationLabeler(() -> settings, current -> {
            attempts.incrementAndGet();
            busy.countDown();
            try {
                fail.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new AiServiceException(AiServiceException.Reason.UNREACHABLE, "weg");
        }, clock());
        ExecutorService pool = Executors.newFixedThreadPool(ChatNavigationLabeler.PARALLELISM + 1);
        try {
            List<Future<?>> first = IntStream.range(0, ChatNavigationLabeler.PARALLELISM)
                    .<Future<?>>mapToObj(i -> pool.submit(() -> labeler.labels("Titel", List.of("Belege."))))
                    .toList();
            assertThat(busy.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> waiting = pool.submit(() -> labeler.labels("Titel", List.of("Belege.")));
            Thread.sleep(200); // wartet jetzt auf einen Platz
            fail.countDown();

            for (Future<?> future : first) {
                assertThatThrownBy(future::get).hasCauseInstanceOf(AiServiceException.class);
            }
            assertThatThrownBy(waiting::get).hasCauseInstanceOf(AiServiceException.class);
            assertThat(attempts).hasValue(ChatNavigationLabeler.PARALLELISM);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anotherModelIsTriedAtOnce() {
        AtomicReference<AiServiceSettings> settings = new AtomicReference<>(settings("gpt-x", base()));
        ChatNavigationLabeler labeler = labeler(settings::get);
        labeler.labels("Titel", List.of("Belege."));

        status = 200;
        settings.set(settings("gpt-y", base()));

        assertThat(labeler.labels("Titel", List.of("Belege.")).complete()).isTrue();
        assertThat(requests).hasValue(2);
    }

    @Test
    void anotherServiceWithTheSameModelNameIsAnotherVersionWithoutShowingItsAddress() {
        AtomicReference<AiServiceSettings> settings = new AtomicReference<>(settings("gpt-x",
                URI.create("https://ki-a.example.org/v1")));
        ChatNavigationLabeler labeler = labeler(settings::get);
        String first = labeler.version();

        settings.set(settings("gpt-x", URI.create("https://ki-b.example.org/v1")));

        assertThat(labeler.version()).isNotEqualTo(first).startsWith(ChatNavigationLabeler.ID + ":gpt-x@")
                .doesNotContain("example");
        assertThat(first).startsWith(ChatNavigationLabeler.ID + ":gpt-x@");
    }

    private ChatNavigationLabeler labeler(java.util.function.Supplier<AiServiceSettings> settings) {
        return new ChatNavigationLabeler(settings,
                current -> new RemoteAi(current, HttpRoutePort.direct(), TrustBundles.NONE, "space-harvester-test"),
                clock());
    }

    private Clock clock() {
        return new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
    }

    private static AiServiceSettings settings(String chatModel, URI base) {
        return new AiServiceSettings(base, "sk-test", chatModel, null, null, null, null, Set.of());
    }

    private URI base() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    }
}
