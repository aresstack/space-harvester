package com.aresstack.askai.browser.playwright;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The CONTROL-PLANE buffer between the HUD overlay and the runtime: pure Java, thread-safe, and deliberately
 * OUTSIDE the {@link BrowserSessionActor} command queue. The {@code exposeBinding} callback (owner thread,
 * during the event pump) appends; {@code web_hud_poll} drains DIRECTLY on the MCP HTTP worker thread. A
 * Skip/Pause must reach the runtime even while a data command (probe/read/open) is blocking the actor —
 * routing the drain through the actor would park the emergency control behind exactly the call it is meant
 * to interrupt.
 */
final class HudCommandInbox {

    private final Queue<String> commands = new ConcurrentLinkedQueue<String>();

    void add(String command) {
        if (command != null && !command.isEmpty()) {
            commands.add(command);
        }
    }

    /** Drain everything buffered so far as one newline-separated batch (or "" when none). */
    String drain() {
        StringBuilder sb = new StringBuilder();
        String command;
        while ((command = commands.poll()) != null) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(command);
        }
        return sb.toString();
    }
}
