package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import com.aresstack.askai.browser.search.analysis.SearchLayoutRepairCoordinator;
import com.aresstack.askai.browser.search.analysis.SleepingRetryDelay;
import com.aresstack.askai.browser.search.analysis.UnavailableStructuredInferencePort;
import com.aresstack.askai.browser.search.inference.InferenceBudgetGate;
import com.aresstack.askai.browser.search.inference.StructuredInferencePort;
import com.aresstack.askai.research.runtime.loop.McpLayoutRepairClient;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;

import java.util.function.LongSupplier;

/**
 * Builds the default {@link LegacyBrowserSearchStrategy} — the unchanged browser SERP path over MCP. This
 * is the ONLY place in the runtime (besides the strategy itself) that knows about the layout-repair client,
 * so the {@link com.aresstack.askai.research.runtime.loop.ResearchLoop} depends only on the neutral
 * {@link SearchStrategy} seam and never on {@link McpLayoutRepairClient} directly.
 *
 * <p>Model-free by default: no {@code StructuredInferencePort} adapter is wired, so a low-confidence SERP
 * yields no results (honest) until the research-model runtime injects a real port at a higher layer.</p>
 */
public final class LegacyBrowserSearchStrategyFactory {

    private LegacyBrowserSearchStrategyFactory() {
    }

    public static SearchStrategy createDefault(ToolInvoker browser, LegacyBrowserSearchSettings settings,
                                               LongSupplier nowEpochMillis) {
        // No inference port wired: a low-confidence SERP stays honestly unresolvable (never fabricated).
        return createDefault(browser, settings, nowEpochMillis, new UnavailableStructuredInferencePort());
    }

    /**
     * As {@link #createDefault(ToolInvoker, LegacyBrowserSearchSettings, LongSupplier)} but with an explicit
     * structured-inference port for the model-backed SERP layout repair. AskAI publishes the port's endpoint
     * (the central main model); when none is available the caller passes {@code UnavailableStructuredInferencePort}.
     */
    public static SearchStrategy createDefault(ToolInvoker browser, LegacyBrowserSearchSettings settings,
                                               LongSupplier nowEpochMillis, StructuredInferencePort inferencePort) {
        McpLayoutRepairClient repairClient = new McpLayoutRepairClient(browser,
                new SearchLayoutRepairCoordinator(settings, inferencePort,
                        InferenceBudgetGate.ALLOW_ALL, new SleepingRetryDelay(), profileStore()));
        return new LegacyBrowserSearchStrategy(repairClient, nowEpochMillis);
    }

    /**
     * The PERSISTENT layout-profile store (issue #35, first slice): the coordinator ran with
     * {@code null} here, so every AI-repaired SERP layout was validated, used once and thrown away —
     * the next search paid the same multi-second repair for the same page structure again. Profiles
     * now live on disk ({@code ~/agents/research/layout-profiles.jsonl}, overridable via
     * {@code -Daskai.research.layoutProfiles=<file>}) and are consulted BEFORE the AI resolver;
     * reuse stays gated by the service's structural re-validation, never by trust in stale storage.
     * One store per runtime process; an unusable path degrades to the old no-store behaviour, loudly.
     */
    private static volatile com.aresstack.askai.browser.search.layout.SearchPageLayoutProfileStore
            sharedProfileStore;

    static com.aresstack.askai.browser.search.layout.SearchPageLayoutProfileStore profileStore() {
        com.aresstack.askai.browser.search.layout.SearchPageLayoutProfileStore store = sharedProfileStore;
        if (store != null) {
            return store;
        }
        synchronized (LegacyBrowserSearchStrategyFactory.class) {
            if (sharedProfileStore == null) {
                sharedProfileStore = openProfileStore();
            }
            return sharedProfileStore;
        }
    }

    static com.aresstack.askai.browser.search.layout.SearchPageLayoutProfileStore
            openProfileStore() {
        try {
            String configured = System.getProperty("askai.research.layoutProfiles", "").trim();
            java.nio.file.Path path = configured.isEmpty()
                    ? java.nio.file.Paths.get(System.getProperty("user.home"), "agents", "research",
                            "layout-profiles.jsonl")
                    : java.nio.file.Paths.get(configured);
            if (path.getParent() != null) {
                java.nio.file.Files.createDirectories(path.getParent());
            }
            com.aresstack.askai.browser.search.analysis.FileSearchPageLayoutProfileStore store =
                    new com.aresstack.askai.browser.search.analysis.FileSearchPageLayoutProfileStore(path);
            System.err.println("[layout-profiles] store=" + path + " profiles=" + store.size());
            return store;
        } catch (RuntimeException | java.io.IOException cannotOpen) {
            System.err.println("[layout-profiles] store unavailable (" + cannotOpen.getMessage()
                    + ") — repairs will not be remembered this session");
            return null;
        }
    }
}
