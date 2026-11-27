package com.aresstack.research4j.acquire;

import com.aresstack.knowledge4j.access.SourceSearchPort;
import com.aresstack.knowledge4j.policy.TransferTarget;

import java.util.Objects;

/**
 * One search the host offers the research: its own index, a web search or another knowledge base. The research
 * wraps it behind the run's transfer guard, so every query is checked against the host's policy before it leaves.
 *
 * @param target       the service the queries go to, with its exposure (internal or external)
 * @param port         the search
 * @param hitsPerQuery how many hits one query asks for; a setting of the host
 */
public record SearchChannel(TransferTarget target, SourceSearchPort port, int hitsPerQuery) {
    public SearchChannel {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(port, "port");
        if (hitsPerQuery < 1) {
            throw new IllegalArgumentException("hitsPerQuery must be positive");
        }
    }
}
