package com.aresstack.research4j;

import com.aresstack.knowledge4j.model.chat.ChatMessage;
import com.aresstack.knowledge4j.model.chat.MainModelChat;
import com.aresstack.knowledge4j.model.chat.MainModelChatResult;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A scripted model, deterministic and thread-safe. The planner gets two variants per node and cycle; the judge reads
 * the marker in the source's beginning. Sources named in {@link #failOnce} fail their first judgement.
 */
public final class ScriptedModel implements MainModelChat {

    public final AtomicInteger calls = new AtomicInteger();
    public final Set<String> failOnce = ConcurrentHashMap.newKeySet();
    private final Set<String> failed = ConcurrentHashMap.newKeySet();
    private static final Pattern NODE = Pattern.compile("NODE\\n(.+)\\n");
    private static final Pattern CYCLE = Pattern.compile("RESEARCH CYCLE\\n(\\d+)");
    private static final Pattern ADDRESS = Pattern.compile("(?:Address|Adresse): (.+)\\n");
    private static final Pattern MARK = Pattern.compile("\\[(INNEN|GEMISCHT|AUSSEN|UNKLAR)]");

    @Override
    public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
        calls.incrementAndGet();
        String system = messages.get(0).getContent();
        String prompt = messages.get(1).getContent();
        if (system.contains("search planner")) {
            Matcher node = NODE.matcher(prompt);
            Matcher cycle = CYCLE.matcher(prompt);
            String label = node.find() ? node.group(1) : "?";
            String round = cycle.find() ? " Runde " + cycle.group(1) : "";
            return MainModelChatResult.ok("{\"queries\":[\"" + label + " Leitfaden" + round + "\",\"" + label
                    + " Praxis" + round + "\"]}");
        }
        Matcher address = ADDRESS.matcher(prompt);
        String id = address.find() ? address.group(1).trim() : "";
        if (failOnce.contains(id) && failed.add(id)) {
            return MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE, "scripted failure");
        }
        Matcher mark = MARK.matcher(prompt);
        String fit = mark.find() ? mark.group(1) : "UNKLAR";
        return MainModelChatResult.ok("{\"subject\":\"" + id + "\",\"fence\":\"" + fit
                + "\",\"reason\":\"scripted\"}");
    }

    @Override
    public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature, int maxOutputTokens,
                                            String schemaJson) {
        return complete(messages, temperature, maxOutputTokens);
    }

    @Override
    public String modelName() {
        return "scripted";
    }
}
