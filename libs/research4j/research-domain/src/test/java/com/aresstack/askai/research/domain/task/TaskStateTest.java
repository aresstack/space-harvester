package com.aresstack.askai.research.domain.task;

import org.junit.Test;

import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The transition table of ADR 0001, section 4.4, pinned pair by pair. */
public class TaskStateTest {

    @Test
    public void theTableAdmitsExactlyTheseTransitions() {
        for (TaskState from : TaskState.values()) {
            for (TaskState to : TaskState.values()) {
                assertEquals(from + " -> " + to, expected(from).contains(to), from.canTransitionTo(to));
            }
        }
    }

    @Test
    public void terminalStatesHaveNoWayOut() {
        assertEquals(EnumSet.of(TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELED, TaskState.REJECTED),
                terminals());
        assertFalse(TaskState.SUBMITTED.canTransitionTo(null));
    }

    @Test
    public void wireNamesMatchTheAdrAndRoundTrip() {
        assertEquals("submitted", TaskState.SUBMITTED.wireName());
        assertEquals("working", TaskState.WORKING.wireName());
        assertEquals("input-required", TaskState.INPUT_REQUIRED.wireName());
        assertEquals("completed", TaskState.COMPLETED.wireName());
        assertEquals("failed", TaskState.FAILED.wireName());
        assertEquals("canceled", TaskState.CANCELED.wireName());
        assertEquals("rejected", TaskState.REJECTED.wireName());
        for (TaskState state : TaskState.values()) {
            assertEquals(state, TaskState.fromWireName(state.wireName()));
            assertEquals(state, TaskState.fromWireName(state.name()));
        }
    }

    @Test
    public void unknownWireNamesAreRejectedNotGuessed() {
        try {
            TaskState.fromWireName("cancelled");
            fail("unknown state accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("cancelled"));
        }
    }

    private static EnumSet<TaskState> terminals() {
        EnumSet<TaskState> terminals = EnumSet.noneOf(TaskState.class);
        for (TaskState state : TaskState.values()) {
            if (state.isTerminal()) {
                terminals.add(state);
            }
        }
        return terminals;
    }

    private static EnumSet<TaskState> expected(TaskState from) {
        switch (from) {
            case SUBMITTED:
                return EnumSet.of(TaskState.WORKING, TaskState.CANCELED, TaskState.REJECTED);
            case WORKING:
                return EnumSet.of(TaskState.SUBMITTED, TaskState.INPUT_REQUIRED, TaskState.COMPLETED,
                        TaskState.FAILED, TaskState.CANCELED);
            case INPUT_REQUIRED:
                return EnumSet.of(TaskState.SUBMITTED, TaskState.FAILED, TaskState.CANCELED);
            default:
                return EnumSet.noneOf(TaskState.class);
        }
    }
}
