package com.aresstack.askai.browser.hud;

/**
 * The state the Research Browser HUD renders over a visited target page: a short phase badge, a one-line human
 * status (the VISIBLE reason — never chain-of-thought), and, while the agent is waiting for the user, a live
 * countdown. Immutable + a line-based {@link #render()}/{@link #parse(String)} so it crosses the sidecar tool
 * boundary as a single string. Slice 1 carries only what the skip/pause/countdown HUD needs.
 */
public final class ResearchHudState {

    /** Sentinel: no countdown is shown. */
    public static final int NO_COUNTDOWN = -1;

    public final String phase;            // short badge, e.g. READABLE / CONSENT_RESOLVED / WAITING_FOR_USER
    public final String statusText;       // one-line human status
    public final boolean waitingForUser;  // the agent is blocked waiting for a user action
    public final int countdownSeconds;    // remaining seconds while waiting, or NO_COUNTDOWN
    public final boolean paused;          // the user paused autonomous navigation
    public final int delaySeconds;        // the user-set inter-page delay (slider position), 0 = off
    public final boolean relevant;        // the user marked the CURRENT page relevant (⭐ toggle)
    /** The run is OVER (completed/failed/cancelled): the overlay must stop offering controls. */
    public final boolean terminal;

    /** Backward-compatible ctor (no delay). */
    public ResearchHudState(String phase, String statusText, boolean waitingForUser, int countdownSeconds,
                            boolean paused) {
        this(phase, statusText, waitingForUser, countdownSeconds, paused, 0);
    }

    public ResearchHudState(String phase, String statusText, boolean waitingForUser, int countdownSeconds,
                            boolean paused, int delaySeconds) {
        this(phase, statusText, waitingForUser, countdownSeconds, paused, delaySeconds, false);
    }

    public ResearchHudState(String phase, String statusText, boolean waitingForUser, int countdownSeconds,
                            boolean paused, int delaySeconds, boolean relevant) {
        this(phase, statusText, waitingForUser, countdownSeconds, paused, delaySeconds, relevant, false);
    }

    public ResearchHudState(String phase, String statusText, boolean waitingForUser, int countdownSeconds,
                            boolean paused, int delaySeconds, boolean relevant, boolean terminal) {
        this.phase = phase == null ? "" : phase;
        this.statusText = statusText == null ? "" : statusText;
        this.waitingForUser = waitingForUser;
        this.countdownSeconds = countdownSeconds < 0 ? NO_COUNTDOWN : countdownSeconds;
        this.paused = paused;
        this.delaySeconds = Math.max(0, delaySeconds);
        this.relevant = relevant;
        this.terminal = terminal;
    }

    /**
     * The state after a terminal run outcome (completed/failed/cancelled): the browser may stay open, but
     * the HUD must stop pretending a page is being visited — no waiting, no countdown, no active controls.
     */
    public static ResearchHudState terminal(String phase, String statusText) {
        return new ResearchHudState(phase, statusText, false, NO_COUNTDOWN, false, 0, false, true);
    }

    public ResearchHudState withCountdown(int seconds) {
        return new ResearchHudState(phase, statusText, waitingForUser, seconds, paused, delaySeconds,
                relevant, terminal);
    }

    /** Serialize to one escaped line-block for the {@code web_hud_render} tool argument. */
    public String render() {
        return "phase=" + esc(phase) + "\n"
                + "status=" + esc(statusText) + "\n"
                + "waiting=" + waitingForUser + "\n"
                + "countdown=" + countdownSeconds + "\n"
                + "paused=" + paused + "\n"
                + "delay=" + delaySeconds + "\n"
                + "relevant=" + relevant + "\n"
                + "terminal=" + terminal;
    }

    public static ResearchHudState parse(String raw) {
        String phase = "";
        String status = "";
        boolean waiting = false;
        int countdown = NO_COUNTDOWN;
        boolean paused = false;
        int delay = 0;
        boolean relevant = false;
        boolean terminal = false;
        if (raw != null) {
            for (String line : raw.split("\n", -1)) {
                int eq = line.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String key = line.substring(0, eq);
                String value = unesc(line.substring(eq + 1));
                if (key.equals("phase")) {
                    phase = value;
                } else if (key.equals("status")) {
                    status = value;
                } else if (key.equals("waiting")) {
                    waiting = Boolean.parseBoolean(value);
                } else if (key.equals("countdown")) {
                    countdown = parseInt(value);
                } else if (key.equals("paused")) {
                    paused = Boolean.parseBoolean(value);
                } else if (key.equals("delay")) {
                    delay = Math.max(0, parseInt(value));
                } else if (key.equals("relevant")) {
                    relevant = Boolean.parseBoolean(value);
                } else if (key.equals("terminal")) {
                    terminal = Boolean.parseBoolean(value);
                }
            }
        }
        return new ResearchHudState(phase, status, waiting, countdown, paused, delay, relevant, terminal);
    }

    private static int parseInt(String v) {
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException ex) {
            return NO_COUNTDOWN;
        }
    }

    private static String esc(String v) {
        return v.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unesc(String v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\' && i + 1 < v.length()) {
                char next = v.charAt(++i);
                sb.append(next == 'n' ? '\n' : next == 'r' ? '\r' : next);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
