package com.aresstack.askai.research.runtime.scope;

import com.aresstack.askai.agent.model.reranker.MiniJson;
import com.aresstack.askai.research.domain.scope.ScopeAnchor;
import com.aresstack.askai.research.domain.scope.ScopeCalibrationProbe;
import com.aresstack.askai.research.domain.scope.ScopeProbe;
import com.aresstack.askai.research.domain.scope.ScopeProbeGenerator.ProbeGeneration;
import com.aresstack.askai.research.domain.scope.ScopeProbeGenerator.ProbeGenerationRequest;
import com.aresstack.askai.research.domain.scope.ScopeProbeGenerator.ProbeGenerationResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runtime side of the Z3b-3 probe-generation wire: parse the host's request JSON (strict — a
 * malformed request is answered as a typed failure, never guessed at) and render the typed result
 * back as JSON for the {@code #RSX1# probes} line. The host's Gson codec of the same payload is
 * the ONLY peer; a cross-process round-trip test pins the format. This wire is an INTERNAL
 * host↔runtime seam — no MCP tool, no user surface, and the runtime never mutates scope through it.
 * <pre>
 * request:  {"mission":"…","domains":[…],"contexts":[…],
 *            "knownFacetLabels":[…],"anchors":[{"anchorId":"…","membership":"IN",
 *            "semanticText":"…"}],"targetCount":50,
 *            "temperature":0.7,"maxOutputTokens":4096,"controlsPerAnchor":2}
 * result:   {"status":"OK","message":"…","requestedBroadCount":50,
 *            "broadProbes":[{"id":"probe-0001","text":"…"}],
 *            "calibrationProbes":[{"id":"control-0001","parentAnchorId":"…","text":"…"}]}
 * </pre>
 * The probe ids travel on the wire so BOTH processes hold the identical identity the runtime
 * assigned — the host never re-mints ids.
 */
public final class ProbeGenerationWire {

    /** The decoded request: what to generate plus the host-owned generator knobs. */
    public static final class ParsedRequest {
        public final ProbeGenerationRequest request;
        public final MainModelScopeProbeGenerator.GeneratorSettings settings;

        ParsedRequest(ProbeGenerationRequest request,
                      MainModelScopeProbeGenerator.GeneratorSettings settings) {
            this.request = request;
            this.settings = settings;
        }
    }

    private ProbeGenerationWire() {
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
        List<ScopeAnchor> anchors = new ArrayList<ScopeAnchor>();
        Object anchorsRaw = root.get("anchors");
        if (anchorsRaw instanceof List) {
            int index = 0;
            for (Object entry : (List<?>) anchorsRaw) {
                if (!(entry instanceof Map)) {
                    throw new IllegalArgumentException("anchor entry is not an object");
                }
                Map<?, ?> anchor = (Map<?, ?>) entry;
                index++;
                anchors.add(new ScopeAnchor(
                        text(anchor, "anchorId"),
                        // The wire deliberately does NOT carry facet ids — the runtime has no
                        // business with facet structure; a synthetic one satisfies the invariant.
                        "wire-" + index,
                        text(anchor, "semanticText"),
                        ScopeAnchor.Membership.valueOf(text(anchor, "membership"))));
            }
        }
        ProbeGenerationRequest request = new ProbeGenerationRequest(
                text(root, "mission"), texts(root, "domains"), texts(root, "contexts"),
                texts(root, "knownFacetLabels"), anchors, intOf(root, "targetCount"));
        MainModelScopeProbeGenerator.GeneratorSettings settings =
                new MainModelScopeProbeGenerator.GeneratorSettings(
                        doubleOf(root, "temperature"), intOf(root, "maxOutputTokens"),
                        intOf(root, "controlsPerAnchor"));
        return new ParsedRequest(request, settings);
    }

    /** Render the typed result — success carries the material, failure carries status + message. */
    public static String renderResult(ProbeGenerationResult result) {
        StringBuilder json = new StringBuilder("{\"status\":\"")
                .append(result.getStatus().name()).append('"')
                .append(",\"message\":").append(quote(result.getMessage()));
        if (result.isOk()) {
            ProbeGeneration generation = result.getGeneration();
            json.append(",\"requestedBroadCount\":").append(generation.getRequestedBroadCount());
            json.append(",\"broadProbes\":[");
            List<ScopeProbe> broad = generation.getBroadProbes();
            for (int index = 0; index < broad.size(); index++) {
                if (index > 0) {
                    json.append(',');
                }
                json.append("{\"id\":").append(quote(broad.get(index).getProbeId()))
                        .append(",\"text\":").append(quote(broad.get(index).getSemanticText()))
                        .append('}');
            }
            json.append("],\"calibrationProbes\":[");
            List<ScopeCalibrationProbe> controls = generation.getCalibrationProbes();
            for (int index = 0; index < controls.size(); index++) {
                if (index > 0) {
                    json.append(',');
                }
                json.append("{\"id\":").append(quote(controls.get(index).getProbeId()))
                        .append(",\"parentAnchorId\":")
                        .append(quote(controls.get(index).getParentAnchorId()))
                        .append(",\"text\":").append(quote(controls.get(index).getSemanticText()))
                        .append('}');
            }
            json.append(']');
        }
        return json.append('}').toString();
    }

    private static String text(Map<?, ?> object, String key) {
        Object value = object.get(key);
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("missing string field: " + key);
        }
        return (String) value;
    }

    private static List<String> texts(Map<?, ?> object, String key) {
        List<String> values = new ArrayList<String>();
        Object raw = object.get(key);
        if (raw instanceof List) {
            for (Object entry : (List<?>) raw) {
                if (entry instanceof String) {
                    values.add((String) entry);
                }
            }
        }
        return values;
    }

    private static int intOf(Map<?, ?> object, String key) {
        Object value = object.get(key);
        if (!(value instanceof Double)) {
            throw new IllegalArgumentException("missing numeric field: " + key);
        }
        return (int) Math.round((Double) value);
    }

    private static double doubleOf(Map<?, ?> object, String key) {
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
