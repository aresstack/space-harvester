package com.aresstack.askai.research.runtime.acquire;

import com.aresstack.askai.research.runtime.loop.ToolInvoker;

import java.util.List;

/**
 * SC1 Search-Control: the runtime-side port to the HOST's semantic acquisition filter — the
 * second, ORTHOGONAL decision in the funnel. Query relevance ("does this answer my query?")
 * stays with the reranker/relevance model; scope control answers "may this enter the
 * negotiated research space at all?". NEVER a model tool: implementations ride the internal
 * research-service endpoint, structurally absent from the agent tool catalog.
 *
 * <p>Currency rule (deliberately different from scope_probe): a RUNNING search never goes
 * stale — {@link #begin()} pins one immutable snapshot, every {@link #evaluate} of the run
 * judges against it, the NEXT run sees the new fence. Page 1 and page 8 are judged against the
 * same research space.</p>
 */
public interface SearchScopeControlPort {

    /**
     * The pinned run snapshot. SC2a splits the capabilities: {@code outFilter} carries SC1's
     * fail-closed boundary; {@code inAffinity} is the shadow measurement, which also works
     * with an EMPTY blacklist and degrades to baseline on failure. {@code active} stays the
     * union (any capability → evaluate calls happen).
     */
    final class Session {
        public final boolean active;
        public final String handle;
        public final String summary;
        public final boolean outFilter;
        public final boolean inAffinity;
        /**
         * PR 12: the user's TYPED blacklist entries of the same snapshot — the deterministic term
         * and domain filter the run applies itself, with or without the semantic gate.
         */
        public final com.aresstack.askai.research.domain.scope.TypedBlacklist blacklist;

        public Session(boolean active, String handle, String summary) {
            this(active, handle, summary, active, false);
        }

        /** The same snapshot with the typed blacklist attached. */
        public Session withBlacklist(
                com.aresstack.askai.research.domain.scope.TypedBlacklist typed) {
            return new Session(active, handle, summary, outFilter, inAffinity, typed);
        }

        public Session(boolean active, String handle, String summary, boolean outFilter,
                       boolean inAffinity) {
            this(active, handle, summary, outFilter, inAffinity,
                    com.aresstack.askai.research.domain.scope.TypedBlacklist.empty());
        }

        public Session(boolean active, String handle, String summary, boolean outFilter,
                       boolean inAffinity,
                       com.aresstack.askai.research.domain.scope.TypedBlacklist blacklist) {
            this.blacklist = blacklist == null
                    ? com.aresstack.askai.research.domain.scope.TypedBlacklist.empty() : blacklist;
            this.active = active;
            this.handle = handle;
            this.summary = summary == null ? "" : summary;
            this.outFilter = outFilter;
            this.inAffinity = inAffinity;
        }
    }

    /** One candidate: id (usually the URL) + its best available semantic text. */
    final class Item {
        public final String id;
        public final String text;

        public Item(String id, String text) {
            this.id = id == null ? "" : id;
            this.text = text == null ? "" : text;
        }
    }

    /**
     * One verdict. OUT = clear canonical out (hard reject); UNCLASSIFIED = no usable text.
     * SC2a: {@code nearIn}/{@code nearestInLabel} = the IN side of the same reading — shadow
     * observation, never an acquisition decision.
     */
    final class Decision {
        public final String id;
        public final boolean out;
        public final boolean unclassified;
        public final String nearestOutLabel;
        public final boolean nearIn;
        public final String nearestInLabel;

        public Decision(String id, boolean out, boolean unclassified, String nearestOutLabel) {
            this(id, out, unclassified, nearestOutLabel, false, "");
        }

        public Decision(String id, boolean out, boolean unclassified, String nearestOutLabel,
                        boolean nearIn, String nearestInLabel) {
            this.id = id;
            this.out = out;
            this.unclassified = unclassified;
            this.nearestOutLabel = nearestOutLabel == null ? "" : nearestOutLabel;
            this.nearIn = nearIn;
            this.nearestInLabel = nearestInLabel == null ? "" : nearestInLabel;
        }
    }

    /** Pin the run's immutable scope snapshot. Throws when scope control is needed but broken. */
    Session begin() throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable;

    /** Judge ONE batch against the pinned snapshot ({@code lane}: serp/links/page — log only). */
    List<Decision> evaluate(String handle, String lane, List<Item> items)
            throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable;

    /** Release the snapshot. Best effort — never throws. */
    void end(String handle);
}
