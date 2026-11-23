package com.aresstack.enterpriseai.browser.firefox;

import de.bund.zrb.command.request.parameters.input.sourceActions.KeySourceAction;
import de.bund.zrb.command.request.parameters.input.sourceActions.PauseAction;
import de.bund.zrb.command.request.parameters.input.sourceActions.PointerSourceAction;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HumanInputTest {

    @Test
    public void offMovesStraightAndTypesWithoutPauses() {
        HumanInput off = HumanInput.off();
        List<PointerSourceAction> path = off.path(0, 0, 300, 200);
        assertEquals(1, path.size());
        assertEquals(0, off.keyPause());
        assertEquals(0, off.readingPause(5000));
        assertTrue(off.scrollSteps(5000, 800).isEmpty());
        assertTrue(off.wander(10, 10).isEmpty());
    }

    @Test
    public void mousePathTakesACurvedDetourAndEndsOnTheTarget() {
        HumanInput human = new HumanInput(new Random(42L), false, true);
        List<PointerSourceAction> path = human.path(0, 0, 400, 0);
        assertTrue(path.size() > 10);
        PointerSourceAction.PointerMoveAction last = null;
        double farthest = 0;
        for (PointerSourceAction action : path) {
            if (action instanceof PointerSourceAction.PointerMoveAction) {
                last = (PointerSourceAction.PointerMoveAction) action;
                farthest = Math.max(farthest, Math.abs(last.getY()));
            }
        }
        assertEquals(400.0, last.getX(), 0.0);
        assertEquals(0.0, last.getY(), 0.0);
        assertTrue("path should leave the straight line", farthest >= 3);
    }

    @Test
    public void keysGetUnevenPausesScrollingAndReading() {
        HumanInput human = new HumanInput(new Random(7L), true, false);
        List<KeySourceAction> keys = human.withKeyPauses(Arrays.<KeySourceAction>asList(
                new KeySourceAction.KeyDownAction("a"), new KeySourceAction.KeyUpAction("a"),
                new KeySourceAction.KeyDownAction("b"), new KeySourceAction.KeyUpAction("b")));
        assertEquals(6, keys.size());
        assertTrue(keys.get(2) instanceof PauseAction);
        assertTrue(human.keyPause() >= 50);
        assertTrue(!human.scrollSteps(5000, 800).isEmpty());
        int reading = human.readingPause(4000);
        assertTrue(reading >= 300 && reading <= 2500);
    }
}
