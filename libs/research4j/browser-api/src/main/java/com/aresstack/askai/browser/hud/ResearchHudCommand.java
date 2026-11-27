package com.aresstack.askai.browser.hud;

import java.util.ArrayList;
import java.util.List;

/**
 * A command the HUD overlay sends back to the agent (buffered in the sidecar, drained by the runtime via the
 * {@code web_hud_poll} tool). Slice 1 uses {@link Type#PAUSE}/{@link Type#RESUME}/{@link Type#SKIP}/
 * {@link Type#NEXT}; later slices add {@code SET_DELAY}/{@code SET_RELEVANCE} (kept in the enum so the wire format
 * never has to change). Line format: {@code TYPE} or {@code TYPE:arg}.
 */
public final class ResearchHudCommand {

    public enum Type {
        PAUSE, RESUME, NEXT, SKIP, SET_DELAY, SET_RELEVANCE, UNKNOWN
    }

    public final Type type;
    public final String arg;

    public ResearchHudCommand(Type type, String arg) {
        this.type = type == null ? Type.UNKNOWN : type;
        this.arg = arg == null ? "" : arg;
    }

    public static ResearchHudCommand parseLine(String line) {
        if (line == null) {
            return new ResearchHudCommand(Type.UNKNOWN, "");
        }
        String trimmed = line.trim();
        int colon = trimmed.indexOf(':');
        String name = colon < 0 ? trimmed : trimmed.substring(0, colon);
        String arg = colon < 0 ? "" : trimmed.substring(colon + 1);
        Type type;
        try {
            type = Type.valueOf(name.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            type = Type.UNKNOWN;
        }
        return new ResearchHudCommand(type, arg);
    }

    /** Parse a newline-separated batch (the {@code web_hud_poll} return); UNKNOWN/empty lines are dropped. */
    public static List<ResearchHudCommand> parseBatch(String raw) {
        List<ResearchHudCommand> commands = new ArrayList<ResearchHudCommand>();
        if (raw == null) {
            return commands;
        }
        for (String line : raw.split("\n", -1)) {
            if (line.trim().isEmpty()) {
                continue;
            }
            ResearchHudCommand command = parseLine(line);
            if (command.type != Type.UNKNOWN) {
                commands.add(command);
            }
        }
        return commands;
    }

    public String render() {
        return arg.isEmpty() ? type.name() : type.name() + ":" + arg;
    }
}
