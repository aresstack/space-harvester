package com.aresstack.askai.research.runtime.acquire;

/**
 * Resolves a search engine's redirect wrapper (Google's opaque {@code /goto?url=…}) to the page it points at —
 * in the search browser, the ONE client that talks to the engine. The research browser never opens an engine
 * address itself: a hit whose wrapper cannot be resolved is dropped, and an engine that answers the hop with a
 * challenge is noted as blocked for the rest of the search instead of asking the user to solve anything (the
 * search browser has no window).
 */
public interface OpaqueRedirectResolver {

    /** What became of one wrapper URL. */
    final class Resolution {
        public enum Status {
            /** {@link #getTargetUrl()} is the page the wrapper points at. */
            RESOLVED,
            /** The engine answered with a challenge/consent/rate-limit page: nothing resolves until it lifts. */
            BLOCKED,
            /** No target could be read (timeout, unexpected page, closed browser). */
            UNRESOLVED
        }

        private final Status status;
        private final String targetUrl;
        private final String detail;

        private Resolution(Status status, String targetUrl, String detail) {
            this.status = status;
            this.targetUrl = targetUrl == null ? "" : targetUrl;
            this.detail = detail == null ? "" : detail;
        }

        public static Resolution resolved(String targetUrl) {
            return new Resolution(Status.RESOLVED, targetUrl, "");
        }

        public static Resolution blocked(String detail) {
            return new Resolution(Status.BLOCKED, "", detail);
        }

        public static Resolution unresolved(String detail) {
            return new Resolution(Status.UNRESOLVED, "", detail);
        }

        public Status getStatus() {
            return status;
        }

        public String getTargetUrl() {
            return targetUrl;
        }

        public String getDetail() {
            return detail;
        }
    }

    /** Resolve one wrapper URL; never throws, never asks the user. */
    Resolution resolve(String wrapperUrl);
}
