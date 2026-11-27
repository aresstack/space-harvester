package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserException;
import com.aresstack.askai.browser.BrowserLink;
import com.aresstack.askai.browser.BrowserPageSnapshot;
import com.aresstack.askai.browser.BrowserSession;
import com.aresstack.askai.browser.LegacySearchEngineAttemptResult;
import com.aresstack.askai.browser.WebSearchItem;
import com.aresstack.askai.browser.WebSearchResult;
import com.aresstack.askai.browser.search.analysis.SearchLayoutRepairTools;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The flat {@code web_*} tool set over a {@link BrowserSession}, transport-free: exactly the tool names,
 * arguments and TEXT contracts the former MCP sidecar served (the research runtime and the host bridge
 * parse these formats), now dispatched by name in-process. The three A4 layout-repair tools are served
 * when the session is Playwright-backed; the browser stays model-free.
 */
final class BrowserToolSet {

    static final List<String> TOOL_NAMES = Collections.unmodifiableList(Arrays.asList(
            "web_search", "web_search_prepare", "web_search_apply_layout", "web_search_discard_repair",
            "web_open", "web_read", "web_probe", "web_reprobe", "web_dismiss_consent",
            "web_hud_render", "web_hud_poll", "web_links", "web_follow", "web_back",
            "web_challenge_status"));

    private final BrowserSession session;
    private final SearchLayoutRepairTools repairTools;

    BrowserToolSet(BrowserSession session, SearchLayoutRepairTools repairTools) {
        this.session = session;
        this.repairTools = repairTools;
    }

    String call(String tool, Map<String, Object> args) throws BrowserException {
        switch (tool == null ? "" : tool) {
            case "web_search":
                return search(str(args, "query"), engines(args));
            case "web_search_prepare":
                return prepare(str(args, "query"), engines(args));
            case "web_search_apply_layout":
                return repair().applyLayout(str(args, "submission"));
            case "web_search_discard_repair":
                return repair().discard(str(args, "repairTicketId"));
            case "web_open":
                return render(session.open(str(args, "url")));
            case "web_read":
                return render(session.currentPage());
            case "web_probe":
                return session.probe(str(args, "url")).render();
            case "web_reprobe":
                return session.probeCurrent().render();
            case "web_dismiss_consent":
                return session.dismissConsent();
            case "web_hud_render":
                return session.renderHud(str(args, "state"));
            case "web_hud_poll":
                // Control plane: the actor drains its HUD inbox directly on the calling thread.
                return session.pollHudCommands();
            case "web_links":
                return links();
            case "web_follow":
                return render(session.follow(str(args, "link_id")));
            case "web_back":
                return render(session.back());
            case "web_challenge_status":
                return String.join("\n", session.challengeStatus());
            default:
                throw new BrowserException("Unknown tool: " + tool);
        }
    }

    /**
     * web_search's optional {@code engines}: catalog ids as a JSON array or a comma-separated string; null when the
     * call names none (then the engines enabled in the settings search).
     */
    static List<String> engines(Map<String, Object> args) {
        Object value = args == null ? null : args.get("engines");
        if (value == null) {
            return null;
        }
        List<String> ids = new java.util.ArrayList<String>();
        if (value instanceof java.util.Collection) {
            for (Object id : (java.util.Collection<?>) value) {
                ids.add(id == null ? null : String.valueOf(id));
            }
            return ids;
        }
        String text = String.valueOf(value).trim();
        if (text.startsWith("[") && text.endsWith("]")) {
            text = text.substring(1, text.length() - 1);
        }
        for (String part : text.split(",")) {
            String id = part.trim().replace("\"", "");
            if (!id.isEmpty()) {
                // A search page's URL comes escaped (its commas would split it), as the engine table stores it.
                ids.add(com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection.unescapeId(id));
            }
        }
        return ids;
    }

    private String search(String query, List<String> engineIds) throws BrowserException {
        return renderSearch(session.search(query, engineIds), session.challengeStatus());
    }

    /**
     * The web_search text contract: PROVIDER lines, one block per hit, a pending CHALLENGE line and one ATTEMPT line
     * per engine. Also the answer of the chat tools' browser_search ({@link PlaywrightChatConnection#search}).
     */
    static String renderSearch(WebSearchResult result, List<String> challengeStatus) {
        StringBuilder sb = new StringBuilder();
        // Consumers treat every engine host as transit — never an evidence source.
        for (String providerHost : result.getProviderHosts()) {
            sb.append("PROVIDER: ").append(providerHost).append('\n');
        }
        for (WebSearchItem item : result.getItems()) {
            sb.append(item.getId()).append(": ").append(item.getTitle())
              .append(" — ").append(item.getUrl()).append('\n')
              .append("   ").append(item.getSnippet()).append('\n');
        }
        // A pending manual challenge travels typed WITH the results (same line format as
        // web_challenge_status), so the consumer can lock the domain family.
        for (String line : challengeStatus) {
            if (line.startsWith("CHALLENGE: ")) {
                sb.append(line).append('\n');
            }
        }
        // One typed line per attempted engine — diagnostics, never candidates.
        for (LegacySearchEngineAttemptResult attempt : result.getAttempts()) {
            sb.append("ATTEMPT: ").append(attempt.getSearchEngineHost())
              .append(' ').append(attempt.getOutcome()).append('\n');
        }
        return sb.length() == 0 ? "No results." : sb.toString();
    }

    private String links() throws BrowserException {
        StringBuilder sb = new StringBuilder();
        for (BrowserLink link : session.links()) {
            sb.append(link.getId()).append(": ").append(link.getText())
              .append(" — ").append(link.getUrl()).append('\n');
        }
        return sb.length() == 0 ? "No links." : sb.toString();
    }

    /**
     * web_search_prepare, optionally for one priority group of Search → Engines ({@code engines}): then exactly those
     * engines, all of them, merged together.
     */
    private String prepare(final String query, List<String> engineIds) throws BrowserException {
        if (engineIds == null) {
            return repair().prepare(query);
        }
        if (!(session instanceof EngineGroupSession)) {
            throw new BrowserException("This browser backend does not take engines for web_search_prepare.");
        }
        final SearchLayoutRepairTools tools = repair();
        // The research browser's session sits behind its owner-thread actor; both run the group (EngineGroupSession).
        return ((EngineGroupSession) session).withEngineGroup(engineIds,
                () -> tools.prepare(query, com.aresstack.askai.browser.search.engine.EngineAcquisitionMode.ALL_ENABLED));
    }

    private SearchLayoutRepairTools repair() throws BrowserException {
        if (repairTools == null) {
            throw new BrowserException("Playwright backend unavailable — the layout-repair tools need the "
                    + "Playwright-backed session.");
        }
        return repairTools;
    }

    /** The page render format the bridge and the runtime parse: URL / TITLE / optional (truncated) / blank / text. */
    static String render(BrowserPageSnapshot snap) {
        return "URL: " + snap.getUrl() + "\nTITLE: " + snap.getTitle()
                + (snap.isTruncated() ? "\n(truncated)" : "") + "\n\n" + snap.getText();
    }

    private static String str(Map<String, Object> args, String key) throws BrowserException {
        Object v = args == null ? null : args.get(key);
        if (v == null || String.valueOf(v).trim().isEmpty()) {
            throw new BrowserException("Missing required argument: " + key);
        }
        return String.valueOf(v);
    }
}
