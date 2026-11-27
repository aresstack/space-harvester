package com.aresstack.askai.research.runtime.scope;

import com.aresstack.askai.research.domain.scope.ScopeAnchor;
import com.aresstack.askai.research.domain.scope.ScopeCalibrationProbe;
import com.aresstack.askai.research.domain.scope.ScopeProbe;
import com.aresstack.askai.research.domain.scope.ScopeProbeGenerator.ProbeGenerationRequest;
import com.aresstack.askai.research.domain.scope.ScopeProbeGenerator.ProbeGenerationResult;
import com.aresstack.askai.research.runtime.scope.MainModelScopeProbeGenerator.GeneratorSettings;
import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;
import com.aresstack.askai.research.runtime.team.PortMainModelChat;

import org.junit.Assume;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.assertTrue;

/**
 * The Z3b-2 LIVE GATE, demanded before any host orchestration: can a real local main model deliver
 * a realistic generation — dozens of broad probes plus 2 controls per negotiated anchor — as the
 * strict JSON contract in ONE call, with NO repair? This test MEASURES; the interesting output is
 * the logged requested/accepted/dropped counts and diagnostics, which feed the generator
 * configuration (targetCount, maxOutputTokens) before Z3b-3. Assertions are deliberately about
 * contract survival, not about breadth quality — an incomplete broad sample is a FINDING
 * (broadSampleComplete()=false), not a test failure.
 * <p>
 * Skips itself without a local Ollama on 127.0.0.1:11434. The model comes from
 * {@code -Daskai.live.main.model=...} / {@code ASKAI_LIVE_MAIN_MODEL}, otherwise gemma4:e2b when
 * installed, else the first installed non-embedding model that can turn thinking off (the scope slot
 * requires it, so gpt-oss is never picked).
 */
public class MainModelScopeProbeGeneratorLiveTest {

    /** The app's default model (ModelPortSettings.DEFAULT_MODEL), preferred when installed. */
    private static final String PREFERRED_MODEL = com.aresstack.askai.modelport.ModelPortSettings.DEFAULT_MODEL;

    private static final String BASE_URL = "http://127.0.0.1:11434";
    /** Local generation of ~50 probes on CPU can be slow — a generous, visible test timeout. */
    private static final long TIMEOUT_MILLIS = 300_000L;
    /** Overridable per gate run: -Daskai.live.probe.target=20 measures small central models. */
    private static final int TARGET_BROAD = Integer.parseInt(
            System.getProperty("askai.live.probe.target", "50"));
    private static final int CONTROLS_PER_ANCHOR = 2;
    /** Live finding: 4096 leaves reasoning models no room for the 50-entry German answer. */
    private static final int MAX_OUTPUT_TOKENS = 8192;

    @Test
    public void aRealModelSurvivesTheStrictContractInOneCall() {
        String model = resolveModelOrSkip();
        System.err.println("[gen-live] model=" + model);

        ProbeGenerationRequest request = new ProbeGenerationRequest(
                "Welche Wearables sind für den Arbeitsschutz auf Baustellen relevant?",
                Arrays.asList("Arbeitsschutz", "Bauwesen"),
                Arrays.asList("Baustellen in Deutschland", "gewerbliche Arbeitgeber"),
                Arrays.asList("Sensorhelme", "Gasdetektions-Wearables", "Ermüdungserkennung"),
                Arrays.asList(
                        new ScopeAnchor("anchor-helme", "f1",
                                "Schutzhelme mit integrierter Sensorik für Baustellen",
                                ScopeAnchor.Membership.IN),
                        new ScopeAnchor("anchor-gas", "f2",
                                "Wearables zur Gasdetektion im industriellen Arbeitsschutz",
                                ScopeAnchor.Membership.IN),
                        new ScopeAnchor("anchor-ermuedung", "f3",
                                "Ermüdungserkennung bei Kranführern durch tragbare Sensoren",
                                ScopeAnchor.Membership.IN),
                        new ScopeAnchor("anchor-fitness", "f4",
                                "Fitness-Armbänder für private Läufer und Hobbysport",
                                ScopeAnchor.Membership.OUT)),
                TARGET_BROAD);
        MainModelScopeProbeGenerator generator = new MainModelScopeProbeGenerator(
                new PortMainModelChat(com.aresstack.askai.modelport.CentralModelDirectories.fixedModelPort(
                        BASE_URL, model, java.time.Duration.ofMillis(TIMEOUT_MILLIS)),
                        ResearchModelSlots.SCOPE, java.time.Duration.ofMillis(TIMEOUT_MILLIS)),
                new GeneratorSettings(0.7d, MAX_OUTPUT_TOKENS, CONTROLS_PER_ANCHOR));

        long startedAt = System.nanoTime();
        ProbeGenerationResult result = generator.generate(request);
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        System.err.println("[gen-live] status=" + result.getStatus()
                + " elapsedMs=" + elapsedMillis);
        if (!result.getMessage().isEmpty()) {
            System.err.println("[gen-live] diagnostics: " + result.getMessage());
        }
        assertTrue("the strict one-call contract must survive a real model (status="
                + result.getStatus() + " message=" + result.getMessage() + ")", result.isOk());

        int requested = result.getGeneration().getRequestedBroadCount();
        int accepted = result.getGeneration().getAcceptedBroadCount();
        Map<String, Integer> controlsPerParent = new LinkedHashMap<String, Integer>();
        for (ScopeCalibrationProbe control : result.getGeneration().getCalibrationProbes()) {
            Integer sofar = controlsPerParent.get(control.getParentAnchorId());
            controlsPerParent.put(control.getParentAnchorId(), sofar == null ? 1 : sofar + 1);
        }
        System.err.println(String.format(Locale.ROOT,
                "[gen-live] broad requested=%d accepted=%d broadSampleComplete=%s",
                requested, accepted, result.getGeneration().broadSampleComplete()));
        System.err.println("[gen-live] controls per anchor: " + controlsPerParent
                + " (requested " + CONTROLS_PER_ANCHOR + " each for 4 anchors)");
        for (ScopeProbe probe : result.getGeneration().getBroadProbes()) {
            System.err.println("[gen-live]   broad: " + probe.getSemanticText());
        }
        for (ScopeCalibrationProbe control : result.getGeneration().getCalibrationProbes()) {
            System.err.println("[gen-live]   control " + control.getParentAnchorId()
                    + ": " + control.getSemanticText());
        }

        // Contract facts, not breadth quality: material exists, and every control the validator
        // kept points at a negotiated anchor (the validator guarantees it — this pins it live).
        assertTrue("some broad material arrived", accepted > 0);
        for (ScopeCalibrationProbe control : result.getGeneration().getCalibrationProbes()) {
            assertTrue(control.getParentAnchorId().startsWith("anchor-"));
        }
    }

    // ---------------------------------------------------------------- model resolution / skipping

    private static String resolveModelOrSkip() {
        String configured = System.getProperty("askai.live.main.model",
                System.getenv("ASKAI_LIVE_MAIN_MODEL"));
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        String tags = getOrSkip(BASE_URL + "/api/tags");
        // Minimal scan of {"models":[{"name":"..."}]} — first non-embedding/reranker model wins.
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

    private static String getOrSkip(String url) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(2_000);
            connection.setReadTimeout(10_000);
            int status = connection.getResponseCode();
            Assume.assumeTrue("Ollama answered " + status, status == 200);
            InputStream in = connection.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            in.close();
            return new String(buffer.toByteArray(), Charset.forName("UTF-8"));
        } catch (IOException noOllama) {
            Assume.assumeNoException("no local Ollama on 127.0.0.1:11434 — live gate skipped",
                    noOllama);
            throw new IllegalStateException("unreachable");
        }
    }
}
