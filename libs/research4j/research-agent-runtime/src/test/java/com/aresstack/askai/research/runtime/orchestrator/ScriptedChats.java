package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.research.runtime.role.ResearchRole;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** Role chats for tests: the orchestrator answers from a script; every call books {@code tokensPerCall}. */
final class ScriptedChats implements RoleChats {

    final ConcurrentLinkedQueue<String> answers = new ConcurrentLinkedQueue<String>();
    final List<String> schemas = new CopyOnWriteArrayList<String>();
    final List<List<ChatMessage>> requests = new CopyOnWriteArrayList<List<ChatMessage>>();
    final long tokensPerCall;
    /** Runs before each answer, e.g. a user action that overlaps the model call. */
    volatile Runnable beforeAnswer;

    ScriptedChats(long tokensPerCall) {
        this.tokensPerCall = tokensPerCall;
    }

    ScriptedChats answer(String json) {
        answers.add(json);
        return this;
    }

    @Override
    public MainModelChat chatFor(ResearchRole role, final AtomicLong tokens) {
        return new MainModelChat() {
            public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int max) {
                return completeJson(messages, temperature, max, null);
            }

            public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature, int max,
                                                    String schema) {
                requests.add(new ArrayList<ChatMessage>(messages));
                schemas.add(schema == null ? "" : schema);
                tokens.addAndGet(tokensPerCall);
                Runnable hook = beforeAnswer;
                if (hook != null) {
                    beforeAnswer = null;
                    hook.run();
                }
                String next = answers.poll();
                return next == null
                        ? MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE, "script empty")
                        : MainModelChatResult.ok(next);
            }

            public String modelName() {
                return "scripted";
            }
        };
    }
}
