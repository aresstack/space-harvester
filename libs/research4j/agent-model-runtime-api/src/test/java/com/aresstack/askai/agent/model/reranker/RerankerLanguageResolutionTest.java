package com.aresstack.askai.agent.model.reranker;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The language-aware resolution seam on {@link RerankerConfigurationSnapshotProvider}: the DEFAULT ignores the
 * language and resolves the one configured selection (the deliberate initial "en/de → same reranker"
 * configuration), while a language-aware provider may select per language — and must FAIL explicitly (never
 * silently fall back) when a language has no usable model.
 */
public class RerankerLanguageResolutionTest {

    private static RerankerConfigurationSnapshot snapshot(String model) {
        RerankerEndpointDescriptor descriptor = new RerankerEndpointDescriptor(
                RerankerProvider.ASKAI_LOCAL, "http://127.0.0.1:1", model,
                java.util.Collections.singletonList(RerankerCapability.RERANK),
                RerankerScoreSemantics.RAW_LOGIT, 1000L,
                RerankerSelectionConfiguration.topN(5));
        return new RerankerConfigurationSnapshot(new File("x.json"),
                RerankerConfigurationDocument.current(0L, descriptor));
    }

    /** A provider implementing ONLY the 3-arg method — the language default must delegate to it. */
    private static final class SingleModelProvider implements RerankerConfigurationSnapshotProvider {
        String lastSelected;

        public RerankerConfigurationSnapshot prepareForSession(String sessionId, File dir, String selected)
                throws RerankerConfigurationException {
            this.lastSelected = selected;
            return snapshot("single-model");
        }
    }

    /** A language-aware provider: per-language models, explicit error for an unconfigured language. */
    private static final class PerLanguageProvider implements RerankerConfigurationSnapshotProvider {
        public RerankerConfigurationSnapshot prepareForSession(String sessionId, File dir, String selected)
                throws RerankerConfigurationException {
            return prepareForSession(sessionId, dir, selected, "en");
        }

        @Override
        public RerankerConfigurationSnapshot prepareForSession(String sessionId, File dir, String selected,
                                                               String languageCode)
                throws RerankerConfigurationException {
            if ("en".equals(languageCode)) {
                return snapshot("local/cross-encoder/ms-marco-MiniLM-L6-v2:latest");
            }
            if ("de".equals(languageCode)) {
                return snapshot("local/multilingual-reranker:latest");
            }
            throw new RerankerConfigurationException(
                    "No reranker model is configured for language \"" + languageCode + "\".");
        }
    }

    @Test
    public void theDefaultIgnoresTheLanguageAndResolvesTheOneConfiguredSelection() throws Exception {
        SingleModelProvider provider = new SingleModelProvider();
        RerankerConfigurationSnapshot en = provider.prepareForSession("s", new File("."), "m", "en");
        RerankerConfigurationSnapshot de = provider.prepareForSession("s", new File("."), "m", "de");
        assertEquals("single-model", en.getModelName());
        assertEquals("single-model", de.getModelName());
        assertEquals("the explicit selection still reaches the resolution", "m", provider.lastSelected);
    }

    @Test
    public void aLanguageAwareProviderSelectsPerLanguage() throws Exception {
        PerLanguageProvider provider = new PerLanguageProvider();
        assertEquals("local/cross-encoder/ms-marco-MiniLM-L6-v2:latest",
                provider.prepareForSession("s", new File("."), "", "en").getModelName());
        assertEquals("local/multilingual-reranker:latest",
                provider.prepareForSession("s", new File("."), "", "de").getModelName());
    }

    @Test
    public void anUnconfiguredLanguageFailsExplicitlyNeverSilently() {
        try {
            new PerLanguageProvider().prepareForSession("s", new File("."), "", "fr");
            fail("expected an explicit configuration error for the unconfigured language");
        } catch (RerankerConfigurationException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("fr"));
        }
    }
}
