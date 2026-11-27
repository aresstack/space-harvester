package com.aresstack.askai.research.runtime.loop;

import java.util.Map;

/**
 * The loop's only way to act: a tool call on one endpoint (browser, research control or the internal service
 * lane). The production implementation dispatches IN-PROCESS to the host's tool contributions
 * ({@link ContributionToolInvoker}); unit tests use deterministic fakes. A tool-level failure is thrown as
 * ToolFailure; an endpoint that is gone (closed with its session) as EndpointUnavailable.
 */
public interface ToolInvoker {

    String call(String toolName, Map<String, Object> args) throws ToolFailure, EndpointUnavailable;

    /** The tool names the endpoint currently offers (capability detection); fakes may answer empty. */
    default java.util.List<String> toolNames() {
        return java.util.Collections.emptyList();
    }

    class ToolFailure extends Exception {
        public ToolFailure(String message) {
            super(message);
        }
    }

    class EndpointUnavailable extends Exception {
        public EndpointUnavailable(String message) {
            super(message);
        }
    }
}
