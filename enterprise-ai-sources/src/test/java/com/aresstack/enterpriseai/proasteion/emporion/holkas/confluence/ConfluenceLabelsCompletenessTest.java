package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/** Gekürzte {@code metadata.labels} sind in allen Metadatenwegen als unvollständig erkennbar. */
public class ConfluenceLabelsCompletenessTest {

    private final FakeConfluence confluence = new FakeConfluence();
    private ConfluenceKnowledgeSource source;

    @Before
    public void setUp() {
        confluence.space("DEV", "Development");
        confluence.page("100", "Start", "DEV", "<p>Home</p>").labels("a").storage("<p>Home</p>");
        confluence.page("101", "Viele Labels", "DEV", "<p>L</p>").labels("a", "b").moreLabels().storage("<p>L</p>");
        confluence.child("100", "101");
        confluence.homepage("DEV", "100");
        source = new ConfluenceKnowledgeSource(SourceAddress.of("confluence-dc"),
                ConfluenceConfig.builder(FakeConfluence.BASE).credentialRef(RecordingSecretProvider.REF).build(),
                confluence, new RecordingSecretProvider());
    }

    @Test
    public void truncatedLabelsAreMarkedInTheSingleLookupAndTheSnapshot() throws Exception {
        assertIncomplete(source.resourceMetadata("101"));
        assertIncomplete(source.storedContent("101").resource());
    }

    @Test
    public void truncatedLabelsAreMarkedInThePartitionListing() throws Exception {
        for (KnowledgeResource resource : source.resources("DEV", "", 100).resources()) {
            if (resource.id().value().endsWith("/101")) {
                assertIncomplete(resource);
            } else {
                assertFalse(resource.metadata().get(ConfluenceKnowledgeSource.LABELS_COMPLETE).isPresent());
            }
        }
    }

    @Test
    public void completeLabelsCarryNoMarker() throws Exception {
        KnowledgeResource resource = source.resourceMetadata("100");

        assertEquals("a", resource.metadata().get("confluence.labels").get());
        assertFalse(resource.metadata().get(ConfluenceKnowledgeSource.LABELS_COMPLETE).isPresent());
    }

    private static void assertIncomplete(KnowledgeResource resource) {
        assertEquals("a,b", resource.metadata().get("confluence.labels").get());
        assertEquals("false", resource.metadata().get(ConfluenceKnowledgeSource.LABELS_COMPLETE).get());
    }
}
