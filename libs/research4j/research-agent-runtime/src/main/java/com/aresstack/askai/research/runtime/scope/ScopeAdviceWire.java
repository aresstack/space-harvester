package com.aresstack.askai.research.runtime.scope;

import com.aresstack.askai.agent.model.reranker.MiniJson;
import com.aresstack.askai.research.domain.scope.ScopeAdviceCandidate;
import com.aresstack.askai.research.domain.scope.ScopeAdviceChooser;
import com.aresstack.askai.research.domain.scope.ScopeAdviceChooser.AdviceDecision;
import com.aresstack.askai.research.domain.scope.ScopeAdviceChooser.CandidateOffer;
import com.aresstack.askai.research.domain.scope.ScopeAdviceChooser.ChoiceRequest;
import com.aresstack.askai.research.domain.scope.ScopeAdviceChooser.ChoiceResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runtime side of the Z4b advice-chooser wire: parse the host's request JSON (the finished offers
 * — never the raw sweep) and render the typed decision back for the {@code #RSX1# advice} line.
 * The host's Gson codec of the same payload is the ONLY peer; a cross-process round-trip test
 * pins the format. Like the probe wire this is an INTERNAL host↔runtime seam — no MCP tool, no
 * user surface, and the runtime never mutates scope through it.
 * <pre>
 * request: {"mission":"…","candidates":[{"id":"…","reason":"RESOLVE_PENDING",
 *           "topic":"…","context":"…"}],"driftGuards":["…"],
 *           "temperature":0.4,"maxOutputTokens":1024}
 * result:  {"status":"OK","message":"…","decision":"ASK","candidateId":"…",
 *           "assistantMessage":"…"}   (candidateId absent on NONE)
 * </pre>
 */
public final class ScopeAdviceWire {

    /** The decoded request: the offers plus the host-owned chooser knobs. */
    public static final class ParsedRequest {
        public final ChoiceRequest request;
        public final MainModelScopeAdviceChooser.ChooserSettings settings;

        ParsedRequest(ChoiceRequest request,
                      MainModelScopeAdviceChooser.ChooserSettings settings) {
            this.request = request;
            this.settings = settings;
        }
    }

    private ScopeAdviceWire() {
    }

    /** Parse the host's request JSON; malformed input throws {@link IllegalArgumentException}. */
    public static ParsedRequest parseRequest(String json) {
        Object parsed;
        try {
            parsed = MiniJson.parse(json);
        } catch (MiniJson.JsonParseException malformed) {
            throw new IllegalArgumentException("request is not JSON: " + malformed.getMessage());
        }
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException("request top level is not an object");
        }
        Map<?, ?> root = (Map<?, ?>) parsed;
        List<CandidateOffer> candidates = new ArrayList<CandidateOffer>();
        Object candidatesRaw = root.get("candidates");
        if (candidatesRaw instanceof List) {
            for (Object entry : (List<?>) candidatesRaw) {
                if (!(entry instanceof Map)) {
                    throw new IllegalArgumentException("candidate entry is not an object");
                }
                Map<?, ?> candidate = (Map<?, ?>) entry;
                candidates.add(new CandidateOffer(
                        text(candidate, "id"),
                        ScopeAdviceCandidate.Reason.valueOf(text(candidate, "reason")),
                        text(candidate, "topic"),
                        optionalText(candidate, "context")));
            }
        }
        List<String> guards = new ArrayList<String>();
        Object guardsRaw = root.get("driftGuards");
        if (guardsRaw instanceof List) {
            for (Object entry : (List<?>) guardsRaw) {
                if (entry instanceof String) {
                    guards.add((String) entry);
                }
            }
        }
        ChoiceRequest request = new ChoiceRequest(text(root, "mission"), candidates, guards);
        MainModelScopeAdviceChooser.ChooserSettings settings =
                new MainModelScopeAdviceChooser.ChooserSettings(
                        numberOf(root, "temperature"), (int) numberOf(root, "maxOutputTokens"));
        return new ParsedRequest(request, settings);
    }

    /** Render the typed result — success carries the decision, failure carries status + message. */
    public static String renderResult(ChoiceResult result) {
        StringBuilder json = new StringBuilder("{\"status\":\"")
                .append(result.getStatus().name()).append('"')
                .append(",\"message\":").append(quote(result.getMessage()));
        if (result.isOk()) {
            AdviceDecision decision = result.getDecision();
            json.append(",\"decision\":\"").append(decision.getDecision().name()).append('"');
            if (decision.getDecision() == AdviceDecision.Decision.ASK) {
                json.append(",\"candidateId\":").append(quote(decision.getCandidateId()));
            }
            json.append(",\"assistantMessage\":").append(quote(decision.getAssistantMessage()));
        }
        return json.append('}').toString();
    }

    private static String text(Map<?, ?> object, String key) {
        Object value = object.get(key);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new IllegalArgumentException("missing string field: " + key);
        }
        return (String) value;
    }

    private static String optionalText(Map<?, ?> object, String key) {
        Object value = object.get(key);
        return value instanceof String ? (String) value : "";
    }

    private static double numberOf(Map<?, ?> object, String key) {
        Object value = object.get(key);
        if (!(value instanceof Double)) {
            throw new IllegalArgumentException("missing numeric field: " + key);
        }
        return (Double) value;
    }

    private static String quote(String value) {
        StringBuilder quoted = new StringBuilder("\"");
        String text = value == null ? "" : value;
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            switch (c) {
                case '"': quoted.append("\\\""); break;
                case '\\': quoted.append("\\\\"); break;
                case '\n': quoted.append("\\n"); break;
                case '\r': quoted.append("\\r"); break;
                case '\t': quoted.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        quoted.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        quoted.append(c);
                    }
            }
        }
        return quoted.append('"').toString();
    }
}
