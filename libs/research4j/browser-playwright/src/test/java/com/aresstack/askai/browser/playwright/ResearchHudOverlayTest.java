package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.hud.ResearchHudState;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The HUD overlay JS: capture-isolated mount, wired controls, and safe state rendering. */
public class ResearchHudOverlayTest {

    @Test
    public void installMountsOnDocumentElementInAShadowRootAndWiresButtons() {
        String js = ResearchHudOverlay.installScript();
        // Capture isolation: mounted on documentElement (not body) inside an OPEN shadow root, so
        // page.innerText("body") and the body walk never see it.
        assertTrue("mounts on documentElement", js.contains("document.documentElement.appendChild(host)"));
        assertTrue("open shadow DOM", js.contains("attachShadow({mode:'open'})"));
        assertFalse("never appended to body", js.contains("document.body.appendChild(host)"));
        // Controls call the exposed command binding.
        assertTrue(js.contains("window.__askaiHudCommand"));
        assertTrue("pause toggles resume", js.contains("window.__askaiHudPaused ? 'RESUME' : 'PAUSE'"));
        assertTrue("skip command", js.contains("cmd('SKIP')"));
        assertTrue("idempotent", js.contains("if (document.getElementById(HOST_ID)) return 'exists'"));
    }

    @Test
    public void renderShowsTheCountdownOnlyWhileWaitingAndEscapesStatus() {
        String waiting = ResearchHudOverlay.renderScript(
                new ResearchHudState("WAITING_FOR_USER", "solve the challenge", true, 37, false));
        assertTrue(waiting.contains("wait.hidden = false"));
        assertTrue(waiting.contains("textContent = 37"));

        String readable = ResearchHudOverlay.renderScript(
                new ResearchHudState("READABLE", "ok", false, ResearchHudState.NO_COUNTDOWN, true));
        assertTrue("no countdown when not waiting", readable.contains("wait.hidden = true"));
        assertTrue("paused shows resume", readable.contains("'▶ Resume'"));
    }

    @Test
    public void bottomBarHasADelaySliderAndNextButton() {
        String js = ResearchHudOverlay.installScript();
        assertTrue("delay slider", js.contains("id='hud-delay'"));
        assertTrue("range input", js.contains("type='range'"));
        assertTrue("next button wired", js.contains("cmd('NEXT')"));
        assertTrue("slider emits SET_DELAY with its value", js.contains("cmd('SET_DELAY:' + slider.value)"));
    }

    @Test
    public void topBarHasARelevanceStarThatTogglesAndRenderReflectsIt() {
        String js = ResearchHudOverlay.installScript();
        assertTrue("a ⭐ control", js.contains("id='hud-star'"));
        assertTrue("toggles relevance from the current state", js.contains("'SET_RELEVANCE:'"));
        assertTrue("off when currently on", js.contains("window.__askaiHudRelevant ? 'off' : 'on'"));

        String on = ResearchHudOverlay.renderScript(new ResearchHudState(
                "READABLE", "ok", false, ResearchHudState.NO_COUNTDOWN, false, 0, true));
        assertTrue("filled star when relevant", on.contains("'★'"));
        String off = ResearchHudOverlay.renderScript(new ResearchHudState(
                "READABLE", "ok", false, ResearchHudState.NO_COUNTDOWN, false, 0, false));
        assertTrue("empty star when not", off.contains("'☆'"));
    }

    @Test
    public void theWaitBarHasAResolvedButtonThatProceeds() {
        String js = ResearchHudOverlay.installScript();
        assertTrue("a 'resolved' control in the wait bar", js.contains("id='hud-resolve'"));
        assertTrue("resolved proceeds (reads the page now)", js.contains("cmd('NEXT')"));
    }

    @Test
    public void renderReflectsTheDelayValueButNotWhileDragging() {
        String js = ResearchHudOverlay.renderScript(new ResearchHudState(
                "DELAY", "waiting", false, ResearchHudState.NO_COUNTDOWN, false, 9));
        assertTrue("slider value comes from state", js.contains("slider.value = 9"));
        assertTrue("but never while the user drags it", js.contains("document.activeElement !== slider"));
    }

    @Test
    public void nextExistsOnlyDuringTheDelayPhaseAndSaysWhatItDoes() {
        String install = ResearchHudOverlay.installScript();
        assertTrue("next starts hidden", install.contains("id='hud-next' class='btn' "
                + "title='Wartezeit überspringen' hidden"));

        String delay = ResearchHudOverlay.renderScript(new ResearchHudState(
                "DELAY", "waiting", false, ResearchHudState.NO_COUNTDOWN, false, 9));
        assertTrue("visible while the inter-page delay runs", delay.contains("$('hud-next').hidden = false"));

        String readable = ResearchHudOverlay.renderScript(new ResearchHudState(
                "READABLE", "ok", false, ResearchHudState.NO_COUNTDOWN, false));
        assertTrue("hidden outside the delay phase — it only skips that delay",
                readable.contains("$('hud-next').hidden = true"));
    }

    @Test
    public void aTerminalStateDisablesEveryControlAndTheCountdown() {
        String js = ResearchHudOverlay.renderScript(
                ResearchHudState.terminal("DONE", "Recherche beendet"));
        assertTrue("bottom bar (pause/delay/next/skip) goes away", js.contains("$('hud-bottom').hidden = true"));
        assertTrue("relevance star goes away", js.contains("$('hud-star').hidden = true"));
        assertTrue("no user-wait box", js.contains("wait.hidden = true"));
        assertTrue("the terminal phase is shown", js.contains("'DONE'"));

        String live = ResearchHudOverlay.renderScript(new ResearchHudState(
                "READABLE", "ok", false, ResearchHudState.NO_COUNTDOWN, false));
        assertTrue("a live state keeps the controls", live.contains("$('hud-bottom').hidden = false"));
    }

    @Test
    public void statusTextCannotInjectMarkupOrBreakTheString() {
        String js = ResearchHudOverlay.renderScript(
                new ResearchHudState("X", "</style><script>evil()</script>'; alert(1)//", false, -1, false));
        assertFalse("angle brackets are neutralised", js.contains("<script>"));
        assertTrue("single quotes are escaped", js.contains("\\'"));
    }
}
