package com.aresstack.askai.research.runtime;

/**
 * The agent's one output channel for a turn: every line the agent used to stream to the host as an ACP
 * message — assistant text or a typed {@code #RSX1#} wire line — now goes here, directly in-process. The
 * host maps the lines into its backend events exactly as before; nothing about the line contract changed.
 */
public interface AgentOutput {

    void message(String text);
}
