package com.aresstack.writing4j.memory;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Component 7 with the merge (15): a topic can be a subtopic of another (Unterthema von), a merged topic keeps no copy
 * of the findings of the topics merged into it, and the memory reads them over the merge.
 */
public class ResearchTopicParentTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void aSubtopicNeedsAKnownParentAndNoCircle() throws Exception {
        ResearchMemory memory = new ResearchMemory();
        memory.addTopic(ResearchTopic.found("T-rad", "Radverkehr", "", null, "", 1L).withFence(ResearchTopic.Fence.INNEN, "", "", 1L));
        try {
            memory.addTopic(ResearchTopic.found("thema-0001", "Radwege", "", null, "m", 2L)
                    .withParent("T-unbekannt", "m", 2L));
            fail("unknown parent");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("T-unbekannt"));
        }
        memory.addTopic(ResearchTopic.found("thema-0001", "Radwege", "", null, "m", 2L)
                .withParent("T-rad", "m", 2L));
        memory.addTopic(ResearchTopic.found("thema-0002", "Radwege an Landstraßen", "", null, "m", 3L)
                .withParent("thema-0001", "m", 3L));
        try {
            memory.updateTopic(memory.topic("thema-0001").withParent("thema-0002", "m", 4L));
            fail("a circle");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("subtopic of itself"));
        }
        try {
            ResearchTopic.found("thema-0003", "x", "", null, "m", 1L).withParent("thema-0003", "m", 1L);
            fail("itself");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("subtopic of itself"));
        }
        assertEquals("T-rad", memory.topic("thema-0001").getParentId());
        assertEquals(Arrays.asList("T-rad", "thema-0001", "thema-0002"), ids(memory));
    }

    @Test
    public void theParentIsReadThroughMergesAndATopicCannotTakeItsOwnParentIn() throws Exception {
        ResearchMemory memory = new ResearchMemory();
        memory.addTopic(ResearchTopic.found("T-rad", "Radverkehr", "", null, "", 1L).withFence(ResearchTopic.Fence.INNEN, "", "", 1L));
        memory.addTopic(ResearchTopic.found("thema-0001", "Radwege", "", null, "m", 2L)
                .withParent("T-rad", "m", 2L));
        memory.addTopic(ResearchTopic.found("thema-0002", "Radwege an Landstraßen", "", null, "m", 3L)
                .withParent("thema-0001", "m", 3L));
        memory.addTopic(ResearchTopic.found("thema-0003", "Radwegenetz", "", null, "m", 3L));
        assertEquals("thema-0001", memory.parentOf("thema-0002").getId());

        memory.updateTopic(memory.topic("thema-0003").withMerged(
                Collections.singletonList(memory.topic("thema-0001")), "m", 4L));
        assertEquals("the parent was merged into thema-0003", "thema-0003", memory.parentOf("thema-0002").getId());
        assertEquals("thema-0001", memory.topic("thema-0002").getParentId());
        assertEquals("thema-0001 stands in thema-0003 now, which has no parent", null, memory.parentOf("thema-0001"));
        assertEquals(null, memory.parentOf("thema-0003"));
        assertEquals(null, memory.parentOf("T-unbekannt"));

        try {
            memory.updateTopic(memory.topic("thema-0002").withMerged(
                    Collections.singletonList(memory.topic("thema-0003")), "m", 5L));
            fail("thema-0002 would hang under a part of itself");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("subtopic of itself"));
        }
        ResearchTopic upper = memory.topic("thema-0003");
        memory.updateTopic(memory.topic("thema-0002").withMerged(Collections.singletonList(upper), "m", 5L)
                .withParent("T-rad", "m", 5L));
        assertEquals("T-rad", memory.parentOf("thema-0002").getId());
        assertEquals("thema-0002", memory.currentTopic("thema-0001").getId());
        assertEquals(Arrays.asList("T-rad", "thema-0002"), ids(memory));
    }


    private static java.util.List<String> ids(ResearchMemory memory) {
        java.util.List<String> ids = new java.util.ArrayList<String>();
        for (ResearchTopic topic : memory.topics()) {
            ids.add(topic.getId());
        }
        return ids;
    }
}
