package com.aresstack.research4j.run;

import com.aresstack.knowledge4j.model.chat.ChatMessage;
import com.aresstack.knowledge4j.model.chat.MainModelChat;
import com.aresstack.knowledge4j.model.chat.MainModelChatResult;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One JSON question to the model with a strict reader and repair attempts: an answer the reader rejects goes back with
 * the reason, as often as the caller's setting allows. It counts calls and failed calls for the run's report and never
 * throws for a model problem; the caller gets the reason instead.
 */
public final class ModelAsk {

    /** Reads one answer; throws {@link Rejected} with the reason when it is unusable. */
    @FunctionalInterface
    public interface Reader<T> {
        T read(JsonObject answer) throws Rejected;
    }

    /** An answer the reader cannot use; the message goes back to the model. */
    public static final class Rejected extends Exception {
        public Rejected(String reason) {
            super(reason);
        }
    }

    /** The outcome: the value, or the reason there is none. */
    public record Outcome<T>(T value, String problem) {
        public boolean ok() {
            return value != null;
        }
    }

    private final MainModelChat chat;
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    public ModelAsk(MainModelChat chat) {
        this.chat = chat;
    }

    public <T> Outcome<T> ask(String system, String prompt, String schema, double temperature, int answerTokens,
                              int repairAttempts, Reader<T> reader) {
        if (chat == null) {
            return new Outcome<>(null, "no model");
        }
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(system));
        messages.add(ChatMessage.user(prompt));
        for (int attempt = 0; ; attempt++) {
            calls.incrementAndGet();
            MainModelChatResult result;
            try {
                result = chat.completeJson(messages, temperature, answerTokens, schema);
            } catch (RuntimeException failure) {
                failures.incrementAndGet();
                return new Outcome<>(null, "model call failed: " + failure.getClass().getSimpleName());
            }
            if (result == null || !result.isOk()) {
                failures.incrementAndGet();
                return new Outcome<>(null, result == null ? "model gave no result"
                        : "model call failed (" + result.getStatus() + "): " + result.getDetail());
            }
            String text = result.getText() == null ? "" : result.getText();
            try {
                return new Outcome<>(reader.read(object(text)), "");
            } catch (Rejected rejected) {
                if (attempt >= repairAttempts) {
                    failures.incrementAndGet();
                    return new Outcome<>(null, "invalid answer: " + rejected.getMessage());
                }
                messages.add(ChatMessage.assistant(text));
                messages.add(ChatMessage.user("Your answer was rejected: " + rejected.getMessage()
                        + "\nAnswer again with the corrected JSON object only."));
            }
        }
    }

    public long calls() {
        return calls.get();
    }

    public long failures() {
        return failures.get();
    }

    static JsonObject object(String text) throws Rejected {
        String trimmed = text.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new Rejected("the answer is no JSON object");
        }
        try {
            JsonElement element = JsonParser.parseString(trimmed.substring(start, end + 1));
            if (!element.isJsonObject()) {
                throw new Rejected("the answer is no JSON object");
            }
            return element.getAsJsonObject();
        } catch (RuntimeException invalid) {
            throw new Rejected("the answer is no valid JSON");
        }
    }

    /** A string field, trimmed; empty when missing. */
    public static String text(JsonObject object, String field) {
        JsonElement element = object.get(field);
        return element != null && element.isJsonPrimitive() ? element.getAsString().trim() : "";
    }
}
