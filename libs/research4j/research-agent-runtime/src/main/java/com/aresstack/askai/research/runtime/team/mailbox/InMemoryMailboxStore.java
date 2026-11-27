package com.aresstack.askai.research.runtime.team.mailbox;

import java.util.ArrayList;
import java.util.List;

/** A {@link MailboxStore} in memory: for tests and for runs without a project folder. */
public final class InMemoryMailboxStore implements MailboxStore {

    private final List<String> lines = new ArrayList<String>();

    public synchronized void append(String line) {
        lines.add(line);
    }

    public synchronized List<String> readLines() {
        return new ArrayList<String>(lines);
    }
}
