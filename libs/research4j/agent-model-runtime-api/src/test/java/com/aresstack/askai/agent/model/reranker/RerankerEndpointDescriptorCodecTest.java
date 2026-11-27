package com.aresstack.askai.agent.model.reranker;

import org.junit.Test;

import java.util.Arrays;
import java.util.OptionalDouble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A5a: the reranker configuration codec round-trips a descriptor (including OPTIONAL selection rules
 * that are absent, not sentinel), and hard-rejects malformed / typed / out-of-range / unknown-enum
 * configurations rather than guessing.
 */
public class RerankerEndpointDescriptorCodecTest {

    private RerankerConfigurationDocument document(RerankerSelectionConfiguration selection) {
        RerankerEndpointDescriptor descriptor = new RerankerEndpointDescriptor(
                RerankerProvider.ASKAI_LOCAL, "http://127.0.0.1:49183",
                "local/cross-encoder/ms-marco-MiniLM-L6-v2:latest",
                Arrays.asList(RerankerCapability.RERANK), RerankerScoreSemantics.RAW_LOGIT, 15_000L,
                selection);
        return RerankerConfigurationDocument.current(7L, descriptor);
    }

    @Test
    public void roundTripsATopNOnlyConfiguration() {
        RerankerConfigurationDocument original = document(RerankerSelectionConfiguration.topN(10));
        RerankerConfigurationValidationResult decoded = RerankerEndpointDescriptorCodec.parse(
                RerankerEndpointDescriptorCodec.toJson(original));

        assertTrue(decoded.describe(), decoded.valid);
        RerankerEndpointDescriptor d = decoded.document.descriptor;
        assertEquals(RerankerProvider.ASKAI_LOCAL, d.provider);
        assertEquals("http://127.0.0.1:49183", d.baseUrl);
        assertEquals(RerankerScoreSemantics.RAW_LOGIT, d.scoreSemantics);
        assertEquals(15_000L, d.requestTimeoutMillis);
        assertTrue(d.hasCapability(RerankerCapability.RERANK));
        assertEquals(10, d.selectionConfiguration.maximumSelectedCandidates);
        assertFalse("absent optional rule stays absent — no sentinel",
                d.selectionConfiguration.absoluteMinimumScore.isPresent());
        assertEquals(7L, decoded.document.configurationRevision);
    }

    @Test
    public void roundTripsOptionalSelectionRules() {
        RerankerConfigurationDocument original = document(new RerankerSelectionConfiguration(5,
                OptionalDouble.of(-0.30), OptionalDouble.of(0.05), OptionalDouble.of(1.5)));
        RerankerConfigurationValidationResult decoded = RerankerEndpointDescriptorCodec.parse(
                RerankerEndpointDescriptorCodec.toJson(original));

        assertTrue(decoded.valid);
        RerankerSelectionConfiguration s = decoded.document.descriptor.selectionConfiguration;
        assertEquals(-0.30, s.absoluteMinimumScore.getAsDouble(), 1e-9);
        assertEquals(0.05, s.minimumTopScoreMargin.getAsDouble(), 1e-9);
        assertEquals(1.5, s.maximumScoreDropFromBest.getAsDouble(), 1e-9);
    }

    @Test
    public void rejectsMalformedAndTypedAndUnknownEnumConfigurations() {
        assertFalse(RerankerEndpointDescriptorCodec.parse("not json").valid);
        // missing descriptor
        assertFalse(RerankerEndpointDescriptorCodec.parse(
                "{\"schemaVersion\":1,\"configurationRevision\":1}").valid);
        // unknown provider enum
        assertFalse(RerankerEndpointDescriptorCodec.parse(base().replace("ASKAI_LOCAL", "NOPE"))
                .valid);
        // non-http baseUrl
        assertFalse(RerankerEndpointDescriptorCodec.parse(
                base().replace("http://127.0.0.1:49183", "ftp://x")).valid);
        // missing RERANK capability
        assertFalse(RerankerEndpointDescriptorCodec.parse(base().replace("\"RERANK\"", "\"CHAT\""))
                .valid);
        // non-positive timeout
        assertFalse(RerankerEndpointDescriptorCodec.parse(
                base().replace("\"requestTimeoutMillis\":15000", "\"requestTimeoutMillis\":0")).valid);
        // maximumSelectedCandidates < 1
        assertFalse(RerankerEndpointDescriptorCodec.parse(
                base().replace("\"maximumSelectedCandidates\":10",
                        "\"maximumSelectedCandidates\":0")).valid);
        // newer schema version
        assertFalse(RerankerEndpointDescriptorCodec.parse(
                base().replace("\"schemaVersion\":1", "\"schemaVersion\":99")).valid);
    }

    @Test
    public void rejectsNonFiniteOptionalThreshold() {
        // A non-finite optional must be a violation, never silently dropped.
        String withNaN = base().replace("\"maximumSelectedCandidates\":10",
                "\"maximumSelectedCandidates\":10,\"absoluteMinimumScore\":\"x\"");
        assertFalse(RerankerEndpointDescriptorCodec.parse(withNaN).valid);
    }

    private static String base() {
        return RerankerEndpointDescriptorCodec.toJson(
                RerankerConfigurationDocument.current(1L, new RerankerEndpointDescriptor(
                        RerankerProvider.ASKAI_LOCAL, "http://127.0.0.1:49183",
                        "local/cross-encoder/ms-marco-MiniLM-L6-v2:latest",
                        Arrays.asList(RerankerCapability.RERANK), RerankerScoreSemantics.RAW_LOGIT,
                        15_000L, RerankerSelectionConfiguration.topN(10))));
    }
}
