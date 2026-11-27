package com.aresstack.askai.browser.hud;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The HUD wire types round-trip losslessly and parse the overlay's command batch. */
public class ResearchHudTest {

    @Test
    public void stateRoundTripsIncludingAwkwardStatusText() {
        ResearchHudState s = new ResearchHudState("WAITING_FOR_USER",
                "Consent (REJECT_ALL) resolved = readable\nline2", true, 37, true);
        ResearchHudState back = ResearchHudState.parse(s.render());
        assertEquals("WAITING_FOR_USER", back.phase);
        assertEquals("Consent (REJECT_ALL) resolved = readable\nline2", back.statusText);
        assertTrue(back.waitingForUser);
        assertEquals(37, back.countdownSeconds);
        assertTrue(back.paused);
    }

    @Test
    public void noCountdownIsPreserved() {
        ResearchHudState back = ResearchHudState.parse(
                new ResearchHudState("READABLE", "ok", false, ResearchHudState.NO_COUNTDOWN, false).render());
        assertEquals(ResearchHudState.NO_COUNTDOWN, back.countdownSeconds);
        assertFalse(back.waitingForUser);
    }

    @Test
    public void parsesACommandBatchAndDropsUnknownLines() {
        List<ResearchHudCommand> commands = ResearchHudCommand.parseBatch("PAUSE\nSET_DELAY:3000\n\nWAT?\nSKIP");
        assertEquals(3, commands.size());
        assertEquals(ResearchHudCommand.Type.PAUSE, commands.get(0).type);
        assertEquals(ResearchHudCommand.Type.SET_DELAY, commands.get(1).type);
        assertEquals("3000", commands.get(1).arg);
        assertEquals(ResearchHudCommand.Type.SKIP, commands.get(2).type);
    }

    @Test
    public void delaySliderValueAndNextSurviveTheWire() {
        ResearchHudState back = ResearchHudState.parse(new ResearchHudState(
                "DELAY", "waiting", false, ResearchHudState.NO_COUNTDOWN, false, 12).render());
        assertEquals(12, back.delaySeconds);

        List<ResearchHudCommand> commands = ResearchHudCommand.parseBatch("NEXT\nSET_DELAY:8");
        assertEquals(ResearchHudCommand.Type.NEXT, commands.get(0).type);
        assertEquals(ResearchHudCommand.Type.SET_DELAY, commands.get(1).type);
        assertEquals("8", commands.get(1).arg);
    }

    @Test
    public void terminalSurvivesTheWireAndDefaultsToFalse() {
        ResearchHudState done = ResearchHudState.parse(
                ResearchHudState.terminal("DONE", "Recherche beendet").render());
        assertTrue(done.terminal);
        assertEquals("DONE", done.phase);
        assertFalse("a terminal state never waits", done.waitingForUser);
        assertEquals(ResearchHudState.NO_COUNTDOWN, done.countdownSeconds);

        ResearchHudState live = ResearchHudState.parse(new ResearchHudState(
                "READABLE", "ok", false, ResearchHudState.NO_COUNTDOWN, false).render());
        assertFalse("a live state is not terminal", live.terminal);
        // Backward compatibility: a line-block WITHOUT the terminal key parses as live.
        assertFalse(ResearchHudState.parse("phase=READABLE\nstatus=ok").terminal);
    }

    @Test
    public void relevanceSurvivesTheWire() {
        ResearchHudState back = ResearchHudState.parse(new ResearchHudState(
                "READABLE", "ok", false, ResearchHudState.NO_COUNTDOWN, false, 0, true).render());
        assertTrue(back.relevant);
        assertEquals(ResearchHudCommand.Type.SET_RELEVANCE, ResearchHudCommand.parseLine("SET_RELEVANCE:on").type);
        assertEquals("on", ResearchHudCommand.parseLine("SET_RELEVANCE:on").arg);
        assertFalse(ResearchHudState.parse(new ResearchHudState("X", "y", false, -1, false).render()).relevant);
    }

    @Test
    public void anOldStateWithoutADelayLineDefaultsToZero() {
        ResearchHudState back = ResearchHudState.parse(
                "phase=READABLE\nstatus=ok\nwaiting=false\ncountdown=-1\npaused=false");
        assertEquals(0, back.delaySeconds);
    }

    @Test
    public void commandRenderRoundTrips() {
        assertEquals("PAUSE", new ResearchHudCommand(ResearchHudCommand.Type.PAUSE, "").render());
        assertEquals(ResearchHudCommand.Type.SET_DELAY,
                ResearchHudCommand.parseLine("SET_DELAY:5000").type);
        assertEquals("5000", ResearchHudCommand.parseLine("SET_DELAY:5000").arg);
    }
}
