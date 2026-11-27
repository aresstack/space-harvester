package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.hud.ResearchHudState;

/**
 * Builds the JavaScript for the Research Browser HUD — a fixed overlay drawn OVER a visited target page (Slice 1:
 * a Pause/Resume control, a Skip control, a one-line status and a user-wait countdown). It is isolated so the
 * website's CSS cannot break it and — crucially — it is NEVER captured as page content: the host is mounted on
 * {@code document.documentElement} inside an OPEN Shadow DOM, while the browser runtime extracts text with
 * {@code page.innerText("body")} and walks {@code document.body}, so the HUD is outside every capture and every
 * consent/challenge/block text scan.
 *
 * <p>Since the AskAI Research add-on (docs/research-browser-addon.md) this overlay is only the FALLBACK: a page can
 * see the host element and the window globals, the add-on's toolbar button and sidebar it cannot. Camoufox shows the
 * HUD through the add-on; the overlay is used for Chrome/Edge and when the add-on does not connect.</p>
 *
 * <p>Buttons call {@code window.__askaiHudCommand('PAUSE'|'RESUME'|'SKIP'|'NEXT')}, which the browser runtime wires to an
 * exposed binding that buffers the command for the runtime to poll. Pure string building — unit-testable without
 * a browser.</p>
 */
final class ResearchHudOverlay {

    /** The host element id; the capture text extraction never sees it (mounted on documentElement + shadow DOM). */
    static final String HOST_ID = "__askai-research-hud";

    private ResearchHudOverlay() {
    }

    /** Idempotent install: create the shadow-DOM host + controls + button→binding wiring. Returns 'installed'/'exists'. */
    static String installScript() {
        return "() => {\n"
                + "  const HOST_ID = '" + HOST_ID + "';\n"
                + "  if (document.getElementById(HOST_ID)) return 'exists';\n"
                + "  const host = document.createElement('div');\n"
                + "  host.id = HOST_ID;\n"
                + "  host.style.cssText = 'all:initial; position:fixed; inset:0; pointer-events:none; "
                + "z-index:2147483647;';\n"
                + "  const root = host.attachShadow({mode:'open'});\n"
                + "  root.innerHTML = `"
                + "<style>"
                + ":host{all:initial;}"
                + ".bar{position:fixed;left:0;right:0;display:flex;align-items:center;gap:8px;padding:6px 10px;"
                + "font:13px/1.4 system-ui,Segoe UI,Arial,sans-serif;pointer-events:auto;}"
                + ".top{top:0;background:rgba(18,20,24,.82);color:#e8eaed;}"
                + ".bottom{bottom:0;background:rgba(18,20,24,.82);color:#e8eaed;}"
                + ".badge{font-weight:600;padding:2px 8px;border-radius:10px;background:#2b6cb0;color:#fff;"
                + "white-space:nowrap;}"
                + ".star{pointer-events:auto;cursor:pointer;border:0;background:transparent;color:#f5c518;"
                + "font-size:18px;line-height:1;padding:0 4px;}"
                + ".status{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}"
                + ".spacer{flex:1;}"
                + ".btn{pointer-events:auto;cursor:pointer;border:0;border-radius:6px;padding:6px 12px;"
                + "font:inherit;color:#fff;background:#3a3f46;}"
                + ".btn:hover{background:#4a5058;}"
                + ".danger{background:#7a2530;}.danger:hover{background:#94303c;}"
                + ".delaylbl{white-space:nowrap;opacity:.9;}"
                + ".slider{pointer-events:auto;cursor:pointer;width:110px;vertical-align:middle;}"
                + ".wait{position:fixed;top:44px;left:50%;transform:translateX(-50%);pointer-events:auto;"
                + "background:rgba(122,37,48,.92);color:#fff;padding:6px 12px;border-radius:8px;}"
                + "[hidden]{display:none!important;}"
                + "</style>"
                + "<div class='bar top'>"
                + "<button id='hud-star' class='star' title='Als relevant markieren'>☆</button>"
                + "<span id='hud-phase' class='badge'></span>"
                + "<span id='hud-status' class='status'></span></div>"
                + "<div id='hud-wait' class='wait' hidden>Waiting for user — <b id='hud-count'></b>s"
                + "<button id='hud-resolve' class='btn' style='margin-left:10px;'>✓ Gelöst</button></div>"
                + "<div id='hud-bottom' class='bar bottom'><button id='hud-pause' class='btn'>⏸ Pause</button>"
                + "<div class='spacer'></div>"
                + "<label id='hud-delaylbl' class='delaylbl'>Delay <b id='hud-delayval'>0</b>s</label>"
                + "<input id='hud-delay' class='slider' type='range' min='0' max='30' step='1' value='0'>"
                // NEXT only skips the inter-page delay — it is rendered ONLY during that delay phase
                // (renderScript), so it can never suggest a "next page" action it does not perform.
                + "<button id='hud-next' class='btn' title='Wartezeit überspringen' hidden>"
                + "⏭ Weiter</button>"
                + "<button id='hud-skip' class='btn danger'>Skip ✕</button></div>`;\n"
                + "  const cmd = (t) => { try { if (window.__askaiHudCommand) window.__askaiHudCommand(t); } "
                + "catch(e){} };\n"
                + "  root.getElementById('hud-pause').addEventListener('click', () => "
                + "cmd(window.__askaiHudPaused ? 'RESUME' : 'PAUSE'));\n"
                + "  root.getElementById('hud-skip').addEventListener('click', () => cmd('SKIP'));\n"
                + "  root.getElementById('hud-next').addEventListener('click', () => cmd('NEXT'));\n"
                + "  root.getElementById('hud-resolve').addEventListener('click', () => cmd('NEXT'));\n"
                + "  root.getElementById('hud-star').addEventListener('click', () => "
                + "cmd('SET_RELEVANCE:' + (window.__askaiHudRelevant ? 'off' : 'on')));\n"
                + "  const slider = root.getElementById('hud-delay');\n"
                + "  slider.addEventListener('input', () => { root.getElementById('hud-delayval')"
                + ".textContent = slider.value; cmd('SET_DELAY:' + slider.value); });\n"
                + "  document.documentElement.appendChild(host);\n"
                + "  window.__askaiResearchHudRoot = root;\n"
                + "  return 'installed';\n"
                + "}";
    }

    /** Update the (already installed) overlay from a state. Returns 'rendered' or 'no-hud'. */
    static String renderScript(ResearchHudState state) {
        boolean showCountdown = !state.terminal && state.waitingForUser && state.countdownSeconds >= 0;
        // NEXT only ever skips the inter-page delay, so it exists only while that delay is running.
        boolean showNext = !state.terminal && "DELAY".equals(state.phase);
        return "() => {\n"
                + "  const root = window.__askaiResearchHudRoot;\n"
                + "  if (!root) return 'no-hud';\n"
                + "  const $ = (id) => root.getElementById(id);\n"
                + "  $('hud-phase').textContent = " + js(state.phase) + ";\n"
                + "  $('hud-status').textContent = " + js(state.statusText) + ";\n"
                // Terminal: the run is over. The browser may stay open, but the HUD must not keep
                // offering controls for a page visit that no longer exists.
                + "  $('hud-bottom').hidden = " + state.terminal + ";\n"
                + "  $('hud-star').hidden = " + state.terminal + ";\n"
                + "  $('hud-next').hidden = " + !showNext + ";\n"
                + "  window.__askaiHudPaused = " + state.paused + ";\n"
                + "  $('hud-pause').textContent = " + (state.paused ? "'▶ Resume'" : "'⏸ Pause'") + ";\n"
                + "  window.__askaiHudRelevant = " + state.relevant + ";\n"
                + "  $('hud-star').textContent = " + (state.relevant ? "'★'" : "'☆'") + ";\n"
                // Reflect the current delay on the slider, but NOT while the user is dragging it (that would
                // fight the drag): only overwrite when the slider is not the active element.
                + "  const slider = $('hud-delay');\n"
                + "  if (slider && document.activeElement !== slider && root.activeElement !== slider) {\n"
                + "    slider.value = " + state.delaySeconds + "; $('hud-delayval').textContent = "
                + state.delaySeconds + ";\n"
                + "  }\n"
                + "  const wait = $('hud-wait');\n"
                + (showCountdown
                        ? "  wait.hidden = false; $('hud-count').textContent = " + state.countdownSeconds + ";\n"
                        : "  wait.hidden = true;\n")
                + "  return 'rendered';\n"
                + "}";
    }

    /** Take the fallback overlay out of the page again (the research add-on took over). Returns 'removed'/'none'. */
    static String removeScript() {
        return "() => {\n"
                + "  const host = document.getElementById('" + HOST_ID + "');\n"
                + "  delete window.__askaiResearchHudRoot;\n"
                + "  if (!host) return 'none';\n"
                + "  host.remove();\n"
                + "  return 'removed';\n"
                + "}";
    }

    /** A JS string literal (single-quoted) with the awkward characters escaped. */
    private static String js(String value) {
        String v = value == null ? "" : value;
        StringBuilder sb = new StringBuilder("'");
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\'':
                    sb.append("\\'");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '<':
                    sb.append("\\x3c"); // never let status text open a tag
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.append('\'').toString();
    }
}
