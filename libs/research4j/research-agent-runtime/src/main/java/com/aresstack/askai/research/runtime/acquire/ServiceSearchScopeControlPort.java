package com.aresstack.askai.research.runtime.acquire;

import com.aresstack.askai.research.runtime.loop.ToolInvoker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The productive {@link SearchScopeControlPort}: the internal research-service endpoint's
 * {@code search_scope_begin/evaluate/end} tools (the SAME trust lane as
 * {@code manual_source_accept} — never an agent tool). Replies are line-based machine text;
 * an {@code UNAVAILABLE}/{@code UNKNOWN_HANDLE} reply is a {@link ToolInvoker.ToolFailure} so
 * the caller ends the run typed and fail-closed.
 */
public final class ServiceSearchScopeControlPort implements SearchScopeControlPort {

    private final ToolInvoker service;

    public ServiceSearchScopeControlPort(ToolInvoker service) {
        this.service = service;
    }

    @Override
    public Session begin() throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
        String reply = service.call("search_scope_begin", new HashMap<String, Object>());
        String all = reply == null ? "" : reply.trim();
        // PR 12: an optional second line "BLACKLIST [json]" carries the typed entries.
        String line = all;
        com.aresstack.askai.research.domain.scope.TypedBlacklist typed =
                com.aresstack.askai.research.domain.scope.TypedBlacklist.empty();
        int newline = all.indexOf('\n');
        if (newline >= 0) {
            line = all.substring(0, newline).trim();
            for (String extra : all.substring(newline + 1).split("\r?\n")) {
                String trimmed = extra.trim();
                if (trimmed.startsWith(BLACKLIST_PREFIX)) {
                    typed = parseBlacklist(trimmed.substring(BLACKLIST_PREFIX.length()));
                }
            }
        }
        return beginSession(line).withBlacklist(typed);
    }

    /** The second line of a begin reply that carries the typed blacklist as a JSON array. */
    public static final String BLACKLIST_PREFIX = "BLACKLIST ";

    static com.aresstack.askai.research.domain.scope.TypedBlacklist parseBlacklist(String json)
            throws ToolInvoker.ToolFailure {
        try {
            com.google.gson.JsonArray array = com.google.gson.JsonParser.parseString(json)
                    .getAsJsonArray();
            List<String> entries = new ArrayList<String>();
            for (com.google.gson.JsonElement element : array) {
                entries.add(element.getAsString());
            }
            return com.aresstack.askai.research.domain.scope.TypedBlacklist.of(entries);
        } catch (RuntimeException broken) {
            // the blacklist must never silently vanish: a broken line ends the run fail-closed
            throw new ToolInvoker.ToolFailure("search_scope_begin: unreadable blacklist " + json);
        }
    }

    private Session beginSession(String line) throws ToolInvoker.ToolFailure {
        if (line.startsWith("ACTIVE handle=")) {
            int space = line.indexOf(' ', "ACTIVE handle=".length());
            String handle = space < 0 ? line.substring("ACTIVE handle=".length())
                    : line.substring("ACTIVE handle=".length(), space);
            // SC2a capability split; an older host without the flags reads as SC1 (out only).
            boolean outFilter = !line.contains(" outFilter=false");
            boolean inAffinity = line.contains(" inAffinity=true");
            return new Session(true, handle, line.substring("ACTIVE ".length()),
                    outFilter, inAffinity);
        }
        if (line.startsWith("INACTIVE")) {
            return new Session(false, null, line);
        }
        throw new ToolInvoker.ToolFailure("search_scope_begin: " + line);
    }

    @Override
    public List<Decision> evaluate(String handle, String lane, List<Item> items)
            throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < items.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append("{\"id\":");
            appendJsonString(json, items.get(index).id);
            json.append(",\"text\":");
            appendJsonString(json, items.get(index).text);
            json.append('}');
        }
        json.append(']');
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("handle", handle);
        args.put("lane", lane);
        args.put("items_json", json.toString());
        String reply = service.call("search_scope_evaluate", args);
        List<Decision> decisions = new ArrayList<Decision>();
        for (String line : (reply == null ? "" : reply).split("\r?\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("EVALUATED")) {
                continue;
            }
            if (trimmed.startsWith("UNAVAILABLE") || trimmed.startsWith("UNKNOWN_HANDLE")) {
                throw new ToolInvoker.ToolFailure("search_scope_evaluate: " + trimmed);
            }
            int idMark = trimmed.indexOf(" id=");
            if (idMark < 0) {
                continue; // unknown diagnostic line — never a verdict
            }
            String id = trimmed.substring(idMark + " id=".length());
            String head = trimmed.substring(0, idMark);
            boolean out = head.startsWith("OUT");
            boolean unclassified = head.startsWith("UNCLASSIFIED");
            String nearest = "";
            int nearestMark = head.indexOf("nearest=\"");
            if (nearestMark >= 0) {
                int close = head.indexOf('"', nearestMark + "nearest=\"".length());
                if (close > 0) {
                    nearest = head.substring(nearestMark + "nearest=\"".length(), close);
                }
            }
            boolean nearIn = head.contains(" in=NEAR");
            String nearestIn = "";
            int inMark = head.indexOf("nearest_in=\"");
            if (inMark >= 0) {
                int close = head.indexOf('"', inMark + "nearest_in=\"".length());
                if (close > 0) {
                    nearestIn = head.substring(inMark + "nearest_in=\"".length(), close);
                }
            }
            decisions.add(new Decision(id, out, unclassified, nearest, nearIn, nearestIn));
        }
        return decisions;
    }

    @Override
    public void end(String handle) {
        try {
            Map<String, Object> args = new HashMap<String, Object>();
            args.put("handle", handle);
            service.call("search_scope_end", args);
        } catch (ToolInvoker.ToolFailure ignored) {
            // best effort — the host clears leftover handles with the session anyway
        } catch (ToolInvoker.EndpointUnavailable ignored) {
            // best effort
        }
    }

    private static void appendJsonString(StringBuilder sb, String value) {
        sb.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\') {
                sb.append('\\').append(character);
            } else if (character == '\n') {
                sb.append("\\n");
            } else if (character == '\r') {
                sb.append("\\r");
            } else {
                sb.append(character);
            }
        }
        sb.append('"');
    }
}
