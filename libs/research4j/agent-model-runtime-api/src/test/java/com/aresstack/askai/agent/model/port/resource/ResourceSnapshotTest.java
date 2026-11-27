package com.aresstack.askai.agent.model.port.resource;

import com.aresstack.askai.agent.model.port.ModelSlot;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ResourceSnapshotTest {

    private static final ModelSlot JUDGE = ModelSlot.of("judge");
    private static final ModelSlot SCOPE = ModelSlot.of("scope");
    private static final ModelSlot SYNTHESIS = ModelSlot.of("synthesis");
    private static final ModelHandle SMALL = ModelHandle.of("m1");
    private static final ModelHandle LARGE = ModelHandle.of("m2");

    @Test
    public void resolvesCapacityAndResidencyPerSlot() {
        ResourceSnapshot snapshot = new ResourceSnapshot(Instant.EPOCH,
                Collections.singletonList(new ModelEndpoint("local", "Local Ollama",
                        ModelEndpoint.Kind.LOCAL, 16L << 30, true)),
                Arrays.asList(new SlotBinding(JUDGE, "local", SMALL, 8192, "small"),
                        new SlotBinding(SCOPE, "local", SMALL, 8192, "small"),
                        new SlotBinding(SYNTHESIS, "local", LARGE, 16384, "large")),
                Arrays.asList(new ModelCapacity("local", SMALL, 4, 3, 2),
                        new ModelCapacity("local", LARGE, 1, 0, 0)),
                Collections.singletonList(new ResidentModel("local", SMALL, 7L << 30, 7L << 30,
                        null, 8192)),
                true);

        assertEquals(snapshot.capacityOf(JUDGE).get().getModel(), snapshot.capacityOf(SCOPE).get().getModel());
        assertEquals(1, snapshot.capacityOf(JUDGE).get().getFreeCapacity());
        assertTrue(snapshot.isResident(JUDGE));
        assertFalse(snapshot.isResident(SYNTHESIS));
        assertFalse(snapshot.bindingOf(ModelSlot.of("narrator")).isPresent());
        assertFalse(snapshot.isResident(ModelSlot.of("narrator")));
    }

    @Test
    public void unreportedValuesNormaliseToMinusOne() {
        ResidentModel resident = new ResidentModel("local", SMALL, -5, -5, null, -5);
        assertEquals(-1, resident.getSizeBytes());
        assertEquals(-1, resident.getVramBytes());
        assertEquals(-1, resident.getContextLength());
        assertEquals(-1, new ModelEndpoint("x", null, ModelEndpoint.Kind.REMOTE, -7, false).getMemoryBudgetBytes());
    }

    @Test(expected = IllegalArgumentException.class)
    public void capacityBelowOneIsRejected() {
        new ModelCapacity("local", SMALL, 0, 0, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void negativeWaitIsRejected() {
        new SlotRequest(JUDGE, 0, Duration.ofSeconds(-1), "task-1");
    }

    @Test
    public void unavailableNamesSlotAndReason() {
        SlotUnavailableException failure = new SlotUnavailableException(JUDGE,
                SlotUnavailableException.Reason.WAIT_TIMEOUT, "30s");
        assertEquals(SlotUnavailableException.Reason.WAIT_TIMEOUT, failure.getReason());
        assertEquals("WAIT_TIMEOUT for slot judge: 30s", failure.getMessage());
    }
}
