package com.aresstack.askai.research.runtime.scope;

import com.aresstack.askai.agent.model.embedding.EmbeddingEndpointDescriptor;
import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.mcp.api.McpToolHandler;
import com.aresstack.askai.mcp.api.McpToolResult;
import com.aresstack.askai.research.agentwire.InProcessResearchSessionBackend;
import com.aresstack.askai.research.runtime.loop.ContributionToolInvoker;
import com.aresstack.askai.research.backend.ResearchBackendEvent;
import com.aresstack.askai.research.backend.ResearchBackendEventType;
import com.aresstack.askai.research.backend.ResearchProjectRequest;
import com.aresstack.askai.research.backend.ResearchSessionHandle;
import com.aresstack.askai.research.backend.ResearchSessionListener;
import com.aresstack.askai.research.domain.scope.ProbeReading;
import com.aresstack.askai.research.domain.scope.ResearchScopeDraft;
import com.aresstack.askai.research.domain.scope.ScopeFacet;
import com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator;
import com.aresstack.askai.research.domain.scope.ScopeSweepOutcome;
import com.aresstack.askai.research.scope.BackendScopeProbeGenerator;
import com.aresstack.askai.research.scope.EmbeddingSnapshotSweepEmbedder;
import com.aresstack.askai.research.scope.ScopeSweepConfiguration;
import com.aresstack.askai.research.scope.ScopeSweepPlanAssembler;
import com.aresstack.askai.research.scope.ScopeSweepService;
import com.aresstack.askai.research.store.FileResearchScopeDraftStore;
import com.aresstack.askai.research.store.ScopeAnchorVectorIndex;

import org.junit.Assume;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The Z3b-3/Z4 END-TO-END LIVE GATE — one REAL full check round trip with
 * everything that the unit tests cannot see at once — the real in-process agent session, the
 * real {@code #RSC1# generate_probes} / {@code #RSX1# probes} wire, a real main-model call in the
 * runtime, real host-side correlation on the backend callback thread, real Ollama anchor +
 * transient embeddings on ONE frozen descriptor, the calibration, all gates, and a READY outcome
 * bound to (revision, fingerprint). This is the ResearchAgentSession.runScopeSweep() chain minus
 * only the Swing session shell (same backend, same wire client, same service, same assembler).
 * <p>
 * Skips itself without the staged agent jar, a local Ollama, a chat model, or the embedding model (bge-m3 unless -Daskai.live.embed.model names another).
 */
public class ScopeSweepEndToEndLiveTest {

    /** The app's default model (ModelPortSettings.DEFAULT_MODEL), preferred when installed. */
    private static final String PREFERRED_MODEL = com.aresstack.askai.modelport.ModelPortSettings.DEFAULT_MODEL;

    private static final String OLLAMA = "http://127.0.0.1:11434";
    private static final String EMBED_MODEL = System.getProperty("askai.live.embed.model", "bge-m3:latest");

    @Test
    public void oneRealSweepRoundTripEndsReadyBoundToItsSnapshot() throws Exception {
        String tags = getOrSkip(OLLAMA + "/api/tags");
        Assume.assumeTrue(EMBED_MODEL + " not installed",
                tags.contains(EMBED_MODEL.replace(":latest", "")));
        String chatModel = chatModelOrSkip(tags);
        System.err.println("[sweep-live] chat model=" + chatModel);

        // --- the REAL canonical draft: negotiated wearables fence in a real store ---------------
        File projectDir = Files.createTempDirectory("askai-sweep-live").toFile();
        FileResearchScopeDraftStore store = new FileResearchScopeDraftStore(projectDir);
        ResearchScopeDraft draft = store.save(ResearchScopeDraft.builder()
                .mission("Welche Wearables sind für den Arbeitsschutz auf Baustellen relevant?")
                .domains(Arrays.asList("Arbeitsschutz", "Bauwesen"))
                .contexts(Arrays.asList("Baustellen in Deutschland"))
                .facets(Arrays.asList(
                        new ScopeFacet("f1", "Schutzhelme mit integrierter Sensorik für Baustellen",
                                ScopeFacet.Status.CONFIRMED, ""),
                        new ScopeFacet("f2", "Wearables zur Gasdetektion im industriellen "
                                + "Arbeitsschutz", ScopeFacet.Status.CONFIRMED, ""),
                        new ScopeFacet("f3", "Fitness-Armbänder für private Läufer und Hobbysport",
                                ScopeFacet.Status.EXCLUDED, "")))
                .build());
        System.err.println("[sweep-live] draft revision=" + draft.getRevision()
                + " anchors=" + draft.getAnchors().size());

        // --- the frozen embedding snapshot: probe the real dimension once, then bind ------------
        int dimension = embedDimensionOrSkip();
        EmbeddingEndpointDescriptor embeddingDescriptor = new EmbeddingEndpointDescriptor(
                EMBED_MODEL, OLLAMA, "/api/embed", dimension, "none", "live-gate", 60_000L);
        EmbeddingSnapshotSweepEmbedder embedder =
                new EmbeddingSnapshotSweepEmbedder(embeddingDescriptor);
        List<ScopeFenceEvaluator.AnchorVector> anchorVectors =
                new ScopeAnchorVectorIndex(new File(projectDir, "scope-anchor-vectors.json"))
                        .vectorsFor(draft, embedder.modelFingerprint(), embedder);
        System.err.println("[sweep-live] anchor vectors reconciled: " + anchorVectors.size()
                + " @ " + embedder.modelFingerprint());

        // --- the real in-process agent session, main model via the inference descriptor + model port ----
        ContributionToolInvoker research = new ContributionToolInvoker(Collections.singletonList(
                McpToolContribution.of("research_status", "Current research state.",
                        new McpToolHandler() {
                            public McpToolResult invoke(McpToolCall call) {
                                return McpToolResult.ok("SCOPING/exploring rev=1");
                            }
                        })));
        File inferenceConfig = new File(projectDir, "inference-config.json");
        Files.write(inferenceConfig.toPath(), ("{\"formatVersion\":1,"
                + "\"configurationRevision\":1,\"model\":\"" + chatModel
                + "\",\"baseUrl\":\"" + OLLAMA + "\",\"chatPath\":\"/api/chat\","
                + "\"timeoutMillis\":300000}").getBytes(Charset.forName("UTF-8")));
        java.util.Map<String, String> env = new java.util.LinkedHashMap<String, String>();
        env.put("ASKAI_INFERENCE_CONFIG", inferenceConfig.getAbsolutePath());
        // The model roles reach the model through the host's port (ADR 0001, 3.2), here over the local Ollama.
        InProcessResearchSessionBackend backend = new InProcessResearchSessionBackend(env, research, null, null,
                com.aresstack.askai.modelport.CentralModelDirectories.fixedModelPort(OLLAMA, chatModel,
                        java.time.Duration.ofMinutes(5)));

        final BackendScopeProbeGenerator[] activeGenerator = {null};
        final com.aresstack.askai.research.scope.BackendScopeAdviceChooser[] activeChooser =
                {null};
        ResearchSessionHandle handle = backend.createSession(
                new ResearchProjectRequest("sweep-live", "p1", "Sweep live gate"),
                new ResearchSessionListener() {
                    public void onEvent(ResearchBackendEvent event) {
                        // The EXACT productive routing (ResearchAgentSession.onEvent): the
                        // correlated transport answer is delivered on THIS backend callback
                        // thread, never via a UI executor.
                        if (event.getType() == ResearchBackendEventType.PROBE_GENERATION) {
                            BackendScopeProbeGenerator current = activeGenerator[0];
                            if (current != null) {
                                current.deliver(event.getTitle(), event.getText());
                            }
                        }
                        if (event.getType() == ResearchBackendEventType.ADVICE_DECISION) {
                            com.aresstack.askai.research.scope.BackendScopeAdviceChooser chooser =
                                    activeChooser[0];
                            if (chooser != null) {
                                chooser.deliver(event.getTitle(), event.getText());
                            }
                        }
                    }
                });
        try {
            // --- the sweep, exactly as runScopeSweep wires it ------------------------------------
            // Width override for small central models: -Daskai.live.probe.target=20 gates
            // gemma-class models with the width the settings would carry productively.
            ScopeSweepConfiguration d = ScopeSweepConfiguration.defaults();
            ScopeSweepConfiguration configuration = new ScopeSweepConfiguration(
                    Integer.parseInt(System.getProperty("askai.live.probe.target",
                            String.valueOf(d.targetBroadProbes))),
                    d.controlsPerAnchor, d.generatorTemperature, d.generatorMaxOutputTokens,
                    d.generationTimeoutSeconds, d.fenceThresholds, d.calibrationParameters,
                    d.boundaryMargin, d.sweepNoveltyGap, d.selectorParameters,
                    d.chooserTemperature, d.chooserMaxOutputTokens, d.choiceTimeoutSeconds);
            BackendScopeProbeGenerator generator = new BackendScopeProbeGenerator(backend, handle,
                    new BackendScopeProbeGenerator.WireSettings(
                            configuration.generatorTemperature,
                            configuration.generatorMaxOutputTokens,
                            configuration.controlsPerAnchor,
                            configuration.generationTimeoutSeconds));
            activeGenerator[0] = generator;
            final FileResearchScopeDraftStore revisionStore = store;
            ScopeSweepService service = new ScopeSweepService(generator, embedder,
                    new ScopeSweepService.ScopeRevisionProbe() {
                        public long currentRevision() {
                            return revisionStore.load().getDraft().getRevision();
                        }
                    });

            long startedAt = System.nanoTime();
            ScopeSweepOutcome outcome = service.run(ScopeSweepPlanAssembler.planOf(
                    draft, embedder.modelFingerprint(), anchorVectors, configuration));
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

            System.err.println("[sweep-live] outcome=" + outcome.getStatus()
                    + " elapsedMs=" + elapsedMillis
                    + " broad=" + outcome.getAcceptedBroadCount() + "/"
                    + outcome.getRequestedBroadCount()
                    + " diagnostics=" + outcome.getDiagnostics());
            if (outcome.isReady()) {
                System.err.println(String.format(Locale.ROOT,
                        "[sweep-live] calibration missionFloor=%.3f knownRegionFloor=%.3f (%s)",
                        outcome.getCalibration().minimumMissionRelevance,
                        outcome.getCalibration().knownRegionFloor,
                        outcome.getCalibration().confidence));
                for (ProbeReading reading : outcome.getSweep().getReadings()) {
                    System.err.println(String.format(Locale.ROOT,
                            "[sweep-live]   %-40s %s/%s -> %s",
                            snippet(reading.getProbe().getSemanticText()),
                            reading.getFenceRelation(), reading.getNoveltyRelation(),
                            reading.getCategory()));
                }
                for (ProbeReading candidate : outcome.getDiverseCandidates()) {
                    System.err.println("[sweep-live] CANDIDATE: "
                            + candidate.getProbe().getSemanticText());
                }
            }
            assertEquals("the full real round trip must end READY (status="
                    + outcome.getStatus() + " diagnostics=" + outcome.getDiagnostics() + ")",
                    ScopeSweepOutcome.Status.READY, outcome.getStatus());
            assertEquals("READY is bound to the draft revision it was computed on",
                    draft.getRevision(), outcome.getScopeRevision());
            assertEquals("READY is bound to the frozen embedding snapshot",
                    embedder.modelFingerprint(), outcome.getEmbeddingFingerprint());
            assertTrue("a real 50-probe sweep classifies every probe",
                    outcome.getSweep().getReadings().size()
                            == configuration.targetBroadProbes);

            // ---- Z4: the FULL check — reason-aware advice + the real chooser call -------------
            com.aresstack.askai.research.domain.scope.ScopeAdviceSet advice =
                    outcome.getAdviceSet();
            System.err.println("[sweep-live] advice: candidates="
                    + advice.getQuestionCandidates().size()
                    + " driftGuards=" + advice.getDriftGuards().size());
            for (com.aresstack.askai.research.domain.scope.ScopeAdviceCandidate candidate
                    : advice.getQuestionCandidates()) {
                System.err.println("[sweep-live]   offer " + candidate.getCandidateId()
                        + " (" + candidate.getReason() + ", x" + candidate.getGroupSize()
                        + "): " + candidate.getProbeText());
            }
            assertTrue("advice stays bound to the sweep snapshot",
                    advice.appliesTo(draft.getRevision()));
            com.aresstack.askai.research.domain.scope.ScopeAdviceChooser.ChoiceResult choice;
            if (advice.getQuestionCandidates().isEmpty()) {
                System.err.println("[sweep-live] no candidates — deterministic NONE, "
                        + "no chooser call");
                choice = com.aresstack.askai.research.domain.scope.ScopeAdviceChooser
                        .ChoiceResult.ok(com.aresstack.askai.research.domain.scope
                                .ScopeAdviceChooser.AdviceDecision.none("nichts offen"));
            } else {
                com.aresstack.askai.research.scope.BackendScopeAdviceChooser chooser =
                        new com.aresstack.askai.research.scope.BackendScopeAdviceChooser(
                                backend, handle,
                                new com.aresstack.askai.research.scope.BackendScopeAdviceChooser
                                        .WireSettings(configuration.chooserTemperature,
                                        configuration.chooserMaxOutputTokens,
                                        configuration.choiceTimeoutSeconds));
                activeChooser[0] = chooser;
                long chooseStart = System.nanoTime();
                choice = chooser.choose(com.aresstack.askai.research.scope
                        .ScopeAdviceOfferRenderer.render(advice, draft));
                System.err.println("[sweep-live] chooser elapsedMs="
                        + (System.nanoTime() - chooseStart) / 1_000_000L);
            }
            System.err.println("[sweep-live] choice status=" + choice.getStatus()
                    + (choice.isOk() ? " decision=" + choice.getDecision().getDecision()
                            + " candidate=" + choice.getDecision().getCandidateId()
                            + " message=" + choice.getDecision().getAssistantMessage()
                    : " message=" + choice.getMessage()));
            assertTrue("the real chooser round trip must be typed OK (status="
                    + choice.getStatus() + " message=" + choice.getMessage() + ")",
                    choice.isOk());
            if (choice.getDecision().getDecision() == com.aresstack.askai.research.domain.scope
                    .ScopeAdviceChooser.AdviceDecision.Decision.ASK) {
                assertTrue("the chosen id must be one of the OFFERED candidates",
                        advice.candidateById(choice.getDecision().getCandidateId()) != null);
                assertTrue("ASK carries a phrased question",
                        !choice.getDecision().getAssistantMessage().trim().isEmpty());
            }
        } finally {
            activeGenerator[0] = null;
            activeChooser[0] = null;
            backend.close(handle);
        }
    }

    private static String snippet(String text) {
        return text.length() <= 40 ? text : text.substring(0, 40);
    }

    // ---------------------------------------------------------------- Ollama probing / skipping

    private static String chatModelOrSkip(String tags) {
        String configured = System.getProperty("askai.live.main.model",
                System.getenv("ASKAI_LIVE_MAIN_MODEL"));
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        String fallback = null;
        int position = 0;
        while (true) {
            int key = tags.indexOf("\"name\":\"", position);
            if (key < 0) {
                break;
            }
            int start = key + "\"name\":\"".length();
            int end = tags.indexOf('"', start);
            String name = tags.substring(start, end);
            position = end;
            String lower = name.toLowerCase(Locale.ROOT);
            // The scope slot needs a model that can turn thinking off (plan 5.1): never auto-pick gpt-oss.
            if (!lower.contains("embed") && !lower.contains("rerank") && !lower.contains("bge")
                    && !com.aresstack.askai.modelport.ModelPortSettings.isLevelOnlyModel(name, null)) {
                if (name.equals(PREFERRED_MODEL)) {
                    return name;
                }
                if (fallback == null) {
                    fallback = name;
                }
            }
        }
        if (fallback != null) {
            return fallback;
        }
        Assume.assumeTrue("no chat-capable model that can turn thinking off installed on local Ollama", false);
        throw new IllegalStateException("unreachable");
    }

    /** Probe the embedding dimension with one real call — the descriptor must carry the truth. */
    private static int embedDimensionOrSkip() {
        try {
            HttpURLConnection connection =
                    (HttpURLConnection) new URL(OLLAMA + "/api/embed").openConnection();
            connection.setConnectTimeout(2_000);
            connection.setReadTimeout(30_000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            OutputStream out = connection.getOutputStream();
            out.write(("{\"model\":\"" + EMBED_MODEL + "\",\"input\":[\"dimension probe\"]}")
                    .getBytes(Charset.forName("UTF-8")));
            out.close();
            Assume.assumeTrue("embed model answered " + connection.getResponseCode(),
                    connection.getResponseCode() == 200);
            String body = readAll(connection.getInputStream());
            int open = body.indexOf("[[");
            int close = body.indexOf("]]", open);
            Assume.assumeTrue("embedding matrix present", open > 0 && close > open);
            return body.substring(open + 2, close).split(",").length;
        } catch (IOException noOllama) {
            Assume.assumeNoException("no local Ollama — live gate skipped", noOllama);
            throw new IllegalStateException("unreachable");
        }
    }

    private static String getOrSkip(String url) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(2_000);
            connection.setReadTimeout(10_000);
            Assume.assumeTrue("Ollama answered " + connection.getResponseCode(),
                    connection.getResponseCode() == 200);
            return readAll(connection.getInputStream());
        } catch (IOException noOllama) {
            Assume.assumeNoException("no local Ollama — live gate skipped", noOllama);
            throw new IllegalStateException("unreachable");
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        in.close();
        return new String(buffer.toByteArray(), Charset.forName("UTF-8"));
    }
}
