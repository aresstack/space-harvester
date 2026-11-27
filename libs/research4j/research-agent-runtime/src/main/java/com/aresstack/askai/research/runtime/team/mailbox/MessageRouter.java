package com.aresstack.askai.research.runtime.team.mailbox;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Display numbers of one run ↔ task ids, and which workers are still live (plan T3). A worker keeps its number
 * for the whole run, also when its task is re-queued after a restart. Not thread-safe on its own: the mailbox
 * guards it.
 */
final class MessageRouter {

    private final Map<Integer, String> taskByNumber = new LinkedHashMap<Integer, String>();
    private final Map<String, Integer> numberByTask = new LinkedHashMap<String, Integer>();
    private final Map<Integer, Boolean> live = new LinkedHashMap<Integer, Boolean>();
    private int next = 1;

    /** The number of {@code taskId}, newly assigned if the task is unknown; the worker is live afterwards. */
    int register(String taskId) {
        Integer known = numberByTask.get(taskId);
        int number = known != null ? known : next++;
        restore(number, taskId, true);
        return number;
    }

    /** Restores one registration from the journal. */
    void restore(int number, String taskId, boolean isLive) {
        taskByNumber.put(number, taskId);
        numberByTask.put(taskId, number);
        live.put(number, isLive);
        if (number >= next) {
            next = number + 1;
        }
    }

    void finish(String taskId) {
        Integer number = numberByTask.get(taskId);
        if (number != null) {
            live.put(number, Boolean.FALSE);
        }
    }

    Integer numberOf(String taskId) {
        return numberByTask.get(taskId);
    }

    String taskOf(int number) {
        return taskByNumber.get(number);
    }

    boolean isKnown(int number) {
        return taskByNumber.containsKey(number);
    }

    boolean isLive(int number) {
        return Boolean.TRUE.equals(live.get(number));
    }
}
