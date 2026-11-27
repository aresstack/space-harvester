package com.aresstack.askai.research.runtime.team.mailbox;

import java.io.IOException;
import java.util.List;

/**
 * Append-only persistence of one run's mailbox journal (plan T3): one JSON record per line. The plugin stores it
 * as {@code team/<runId>/messages.jsonl} with the UTF-8 helpers; tests use {@link InMemoryMailboxStore}.
 */
public interface MailboxStore {

    /** Appends one complete line (without the newline) and returns only when it is durable. */
    void append(String line) throws IOException;

    /** All complete lines in file order; a missing journal is empty, a torn last line is skipped. */
    List<String> readLines() throws IOException;
}
