package com.aresstack.spaceharvester.application.enrichment.statements;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Chat-Modell für Tests (wie {@code FakeChatCompletionPort} im Client): Antworten der Reihe nach, Anfragen gemerkt. */
final class FakeStatementChat implements StatementChat {

    private final String model;
    private final Deque<String> answers = new ArrayDeque<>();
    private final List<List<Message>> requests = new ArrayList<>();

    FakeStatementChat(String model) {
        this.model = model;
    }

    FakeStatementChat() {
        this("test-model");
    }

    void enqueueAnswer(String answer) {
        answers.add(answer);
    }

    List<List<Message>> requests() {
        return requests;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public synchronized String complete(List<Message> messages) {
        requests.add(List.copyOf(messages));
        if (answers.isEmpty()) {
            throw new IllegalStateException("keine Antwort vorbereitet");
        }
        return answers.poll();
    }
}
