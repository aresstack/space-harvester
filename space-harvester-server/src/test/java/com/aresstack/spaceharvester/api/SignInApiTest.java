package com.aresstack.spaceharvester.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.TestClient;
import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.signin.SignInRequests;
import com.aresstack.spaceharvester.application.sources.SignInChallenge;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** ADR 0018: ein Auftrag wartet auf den Einmalcode, der eigene Client sendet ihn über REST. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class SignInApiTest {

    private static final String OTHER_TOKEN = "other-sign-in-token-7a1c";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("space-harvester.sources[0].id", () -> "wiki");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url", () -> "http://127.0.0.1:9");
        registry.add("space-harvester.export.sign-in-wait", () -> "PT1M");
        TestClient.register(registry, "wiki");
        String prefix = "space-harvester.security.clients[1].";
        registry.add(prefix + "id", () -> "other");
        registry.add(prefix + "token-sha256", () -> "sha256:" + java.util.HexFormat.of().formatHex(
                sha256(OTHER_TOKEN)));
        registry.add(prefix + "grants[0].source", () -> "wiki");
        registry.add(prefix + "grants[0].spaces[0]", () -> "*");
        registry.add(prefix + "grants[0].access", () -> "metadata,content,export");
    }

    private static byte[] sha256(String value) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private Environment environment;

    @Autowired
    private Jobs jobs;

    @Autowired
    private SignInRequests signIns;

    private HttpResponse<byte[]> send(String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:"
                        + environment.getProperty("local.server.port") + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        request = token == null ? TestClient.authenticated(request)
                : request.header("Authorization", "Bearer " + token);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void theOwnClientSeesTheRequestAndUnlocksTheWaitingJob() throws Exception {
        List<String> codes = new ArrayList<>();
        AtomicReference<SignInRequests.Outcome> outcome = new AtomicReference<>();
        String owner = AccessContext.technicalClient(TestClient.ID).jobOwner();
        JobStatus job = jobs.submit(JobType.ASSETS, "wiki", "DEV", owner, JobTask.blocking(context ->
                outcome.set(signIns.await("wiki", context.jobId(), challenge(codes, ""), Instant.now(),
                        context::checkpoint))));

        JsonNode request = null;
        for (int i = 0; i < 200 && request == null; i++) {
            JsonNode list = json.readTree(send("GET", "/api/v1/sign-ins", null, null).body());
            if (!list.path("signIns").isEmpty()) {
                request = list.path("signIns").get(0);
            }
            Thread.sleep(25);
        }
        assertThat(request).isNotNull();
        assertThat(request.path("jobIds").get(0).asString()).isEqualTo(job.id());
        assertThat(request.path("message").asString()).isEqualTo("Code per E-Mail gesendet");
        String id = request.path("id").asString();

        // Ein anderer Client sieht sie nicht und kann nichts senden.
        assertThat(json.readTree(send("GET", "/api/v1/sign-ins", null, OTHER_TOKEN).body()).path("signIns"))
                .isEmpty();
        assertThat(send("POST", "/api/v1/sign-ins/" + id + "/code", "{\"code\":\"424242\"}", OTHER_TOKEN)
                .statusCode()).isEqualTo(404);

        JsonNode wrong = json.readTree(send("POST", "/api/v1/sign-ins/" + id + "/code", "{\"code\":\"1\"}", null)
                .body());
        assertThat(wrong.path("answer").asString()).isEqualTo("rejected");
        assertThat(wrong.path("signIn").path("error").asString()).isEqualTo("Code ungültig");

        HttpResponse<byte[]> right = send("POST", "/api/v1/sign-ins/" + id + "/code", "{\"code\":\"424242\"}",
                null);
        assertThat(json.readTree(right.body()).path("answer").asString()).isEqualTo("accepted");
        assertThat(new String(right.body())).doesNotContain("424242");
        for (int i = 0; i < 200 && !jobs.status(job.id()).state().terminal(); i++) {
            Thread.sleep(25);
        }
        assertThat(jobs.status(job.id()).state()).isEqualTo(JobState.COMPLETED);
        assertThat(outcome.get()).isEqualTo(SignInRequests.Outcome.UNLOCKED);
        assertThat(codes).containsExactly("1", "424242");
    }

    private static SignInChallenge challenge(List<String> codes, String error) {
        return new SignInChallenge() {
            @Override
            public String service() {
                return "127.0.0.1";
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
                return error;
            }

            @Override
            public boolean resendAvailable() {
                return false;
            }

            @Override
            public SignInChallenge submit(char[] code) {
                codes.add(new String(code));
                return "424242".equals(new String(code)) ? null : challenge(codes, "Code ungültig");
            }

            @Override
            public SignInChallenge resend() {
                return this;
            }
        };
    }
}
