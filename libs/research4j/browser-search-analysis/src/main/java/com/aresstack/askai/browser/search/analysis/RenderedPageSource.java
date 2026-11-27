package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.LegacySearchEngineAttemptResult;
import com.aresstack.askai.browser.render.RenderedPageDocument;
import com.aresstack.askai.browser.search.repair.SearchChallengeState;

import java.util.Collections;
import java.util.List;

/**
 * The source of captured engine pages for {@code web_search_prepare} — the seam that keeps the repair
 * tools independent of Playwright. Production supplies a browser-backed implementation that reuses the
 * SAME engine-navigation as {@code web_search} (templates, fallback engines, domain-family locks,
 * consent, challenge detection, provider hosts, capture, per-engine attempts); tests supply synthetic
 * captures. The source itself never calls a model, and it carries the navigation metadata so the
 * prepared result loses none of the legacy {@code web_search} behaviour.
 */
public interface RenderedPageSource {

    /** One captured engine page: the neutral rendered document and the engine host it came from. */
    final class Captured {
        public final RenderedPageDocument document;
        public final String engineHost;

        public Captured(RenderedPageDocument document, String engineHost) {
            this.document = document;
            this.engineHost = engineHost == null ? "" : engineHost;
        }
    }

    /**
     * What one captured page turned out to be. DELIVERED and EMPTY are answers; UNUSABLE is the
     * page's problem, not the engine's — a layout the mechanical analysis did not understand may
     * still be rescued downstream (AI repair, link harvest) and must not end the user's pagination.
     */
    enum PageVerdict {
        DELIVERED,
        EMPTY,
        UNUSABLE
    }

    /**
     * Asked about every captured page, in order: what did THIS page deliver? The answer decides
     * whether the engine behind it has done its job and whether its deeper result pages are still
     * worth fetching — never whether the search stops, which is the acquisition mode's call.
     */
    interface PageEvaluator {
        PageVerdict judge(RenderedPageDocument document, String engineHost);
    }

    /** The result of navigating and capturing the configured engines for one query. */
    final class EngineCapture {
        public final List<Captured> pages;
        public final List<String> providerHosts;
        public final List<LegacySearchEngineAttemptResult> engineAttempts;
        public final List<SearchChallengeState> challenges;

        public EngineCapture(List<Captured> pages, List<String> providerHosts,
                             List<LegacySearchEngineAttemptResult> engineAttempts,
                             List<SearchChallengeState> challenges) {
            this.pages = unmodifiable(pages);
            this.providerHosts = strings(providerHosts);
            this.engineAttempts = engineAttempts == null
                    ? Collections.<LegacySearchEngineAttemptResult>emptyList()
                    : Collections.unmodifiableList(engineAttempts);
            this.challenges = challenges == null
                    ? Collections.<SearchChallengeState>emptyList()
                    : Collections.unmodifiableList(challenges);
        }

        private static List<Captured> unmodifiable(List<Captured> value) {
            return value == null ? Collections.<Captured>emptyList()
                    : Collections.unmodifiableList(value);
        }

        private static List<String> strings(List<String> value) {
            return value == null ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(value);
        }
    }

    /**
     * Navigate the enabled engines in order, capturing each result page and handing it to the
     * evaluator as it arrives. Evaluating INSIDE the navigation is what makes "stop at the first
     * engine that delivers" possible at all: a judgement made after every engine has been visited
     * comes too late to save the visits. Never null.
     */
    EngineCapture capture(String query, PageEvaluator evaluator);
}
