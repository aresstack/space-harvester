package com.aresstack.writing4j.cornerstone;

import com.aresstack.knowledge4j.model.chat.ChatMessage;
import com.aresstack.knowledge4j.model.chat.MainModelChat;
import com.aresstack.knowledge4j.model.chat.MainModelChatResult;
import com.aresstack.knowledge4j.scope.Fence;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The fields the search found, as cards for the reader (measurements M16, M20, Angelo's decision of 27.11.2026): the
 * terms with a cornerstone or neighbour vote are sorted by the model into a few named fields; each card shows a few
 * example terms. The reader drags up the fields the book shall cover; a field left lying is not wanted. Both widen the
 * fence ({@link Fence#widened}). Never a list to sort out term by term.
 */
public final class FieldCards {

    /** The settings. */
    public static final class Options {
        public static final int DEFAULT_MAX_FIELDS = 8;
        public static final int DEFAULT_EXAMPLES = 4;
        public static final int DEFAULT_NAME_WORDS = 4;
        public static final int DEFAULT_ANSWER_TOKENS = 4000;
        public static final int DEFAULT_PASSAGE_TERMS = 2;

        private final int maxFields;
        private final int examples;
        private final int nameWords;
        private final int answerTokens;
        private final int passageTerms;

        /**
         * @param passageTerms a passage belongs to the field with the most of its terms in it, when that is at least
         *                     this many (and no other field has as many); else to none
         */
        public Options(int maxFields, int examples, int nameWords, int answerTokens, int passageTerms) {
            if (maxFields < 1 || examples < 1 || nameWords < 1 || answerTokens < 1 || passageTerms < 1) {
                throw new IllegalArgumentException("maxFields, examples, nameWords, answerTokens and passageTerms >= 1 "
                        + "expected");
            }
            this.maxFields = maxFields;
            this.examples = examples;
            this.nameWords = nameWords;
            this.answerTokens = answerTokens;
            this.passageTerms = passageTerms;
        }

        public static Options defaults() {
            return new Options(DEFAULT_MAX_FIELDS, DEFAULT_EXAMPLES, DEFAULT_NAME_WORDS, DEFAULT_ANSWER_TOKENS,
                    DEFAULT_PASSAGE_TERMS);
        }

        public int getPassageTerms() {
            return passageTerms;
        }
    }

    /** A field: its name, its terms (keys) in rank order and the examples a card shows. */
    public static final class Field {
        private final String name;
        private final List<String> keys;
        private final List<String> examples;

        public Field(String name, List<String> keys, List<String> examples) {
            this.name = name;
            this.keys = Collections.unmodifiableList(new ArrayList<String>(keys));
            this.examples = Collections.unmodifiableList(new ArrayList<String>(examples));
        }

        public String getName() {
            return name;
        }

        public List<String> getKeys() {
            return keys;
        }

        public List<String> getExamples() {
            return examples;
        }

        @Override
        public String toString() {
            return name + " " + examples;
        }
    }

    private final MainModelChat chat;
    private final Options options;
    private final boolean german;
    private final Consumer<String> log;

    public int passageTerms() {
        return options.passageTerms;
    }

    public FieldCards(MainModelChat chat, String language, Options options, Consumer<String> log) {
        this.chat = chat;
        this.options = options == null ? Options.defaults() : options;
        this.german = !"en".equalsIgnoreCase(language == null ? "" : language.trim());
        this.log = log == null ? line -> {
        } : log;
    }

    /**
     * The fields of the terms (in rank order); a term the answer leaves out belongs to no field. Empty when the call
     * fails twice: the reader then gets no cards, and nothing else changes.
     */
    public List<Field> fields(List<TermCandidates.Candidate> terms, Fence fence) {
        if (terms.isEmpty()) {
            return Collections.emptyList();
        }
        String problem = "";
        for (int attempt = 0; attempt < 2; attempt++) {
            List<ChatMessage> messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system(german ? "Du ordnest die Begriffe einer Quellensammlung für ein Sachbuch "
                    + "in Themenfelder. Antworte nur mit JSON." : "You sort the terms of a collection of sources for a "
                    + "non-fiction book into fields. Answer only with JSON."));
            messages.add(ChatMessage.user(prompt(terms, fence)));
            MainModelChatResult result;
            try {
                result = chat.completeJson(messages, 0.0, options.answerTokens, schema());
            } catch (RuntimeException failed) {
                problem = failed.getClass().getSimpleName();
                continue;
            }
            if (result == null || !result.isOk()) {
                problem = result == null ? "no answer" : result.getStatus() + ": " + result.getDetail();
                continue;
            }
            List<Field> fields = parse(result.getText(), terms);
            if (!fields.isEmpty()) {
                log.accept("fields: " + fields.size() + " of " + terms.size() + " terms");
                return fields;
            }
            problem = "no fields in the answer";
        }
        log.accept("fields failed: " + problem);
        return Collections.emptyList();
    }

    String prompt(List<TermCandidates.Candidate> terms, Fence fence) {
        StringBuilder text = new StringBuilder(fence.describe(german, Integer.MAX_VALUE));
        text.append(german ? "Begriffe aus der Quellensammlung:\n" : "Terms of the collection of sources:\n");
        for (int i = 0; i < terms.size(); i++) {
            text.append(i + 1).append(". ").append(terms.get(i).getTerm()).append('\n');
        }
        if (german) {
            text.append("\nFasse die Begriffe zu höchstens ").append(options.maxFields).append(" Themenfeldern "
                    + "zusammen. Jedes Feld bekommt einen Namen aus höchstens ").append(options.nameWords)
                    .append(" Wörtern, der sagt, wovon es handelt. Nachbargebiete des Buchauftrags bilden eigene "
                            + "Felder. Jeder Begriff gehört zu höchstens einem Feld.\n"
                            + "Antworte nur mit JSON: \"felder\", je Feld \"name\" und \"begriffe\" (die Nummern).");
        } else {
            text.append("\nGroup the terms into at most ").append(options.maxFields).append(" fields. Each field "
                    + "gets a name of at most ").append(options.nameWords).append(" words that says what it is about. "
                    + "Neighbouring fields of the book's mission form fields of their own. Each term belongs to at "
                    + "most one field.\nAnswer only with JSON: \"felder\", per field \"name\" and \"begriffe\" (the "
                    + "numbers).");
        }
        return text.toString();
    }

    List<Field> parse(String answer, List<TermCandidates.Candidate> terms) {
        JsonElement root;
        try {
            root = JsonParser.parseString(answer == null ? "" : answer.trim());
        } catch (RuntimeException notJson) {
            return Collections.emptyList();
        }
        if (!root.isJsonObject() || !root.getAsJsonObject().has("felder")
                || !root.getAsJsonObject().get("felder").isJsonArray()) {
            return Collections.emptyList();
        }
        Set<Integer> used = new LinkedHashSet<Integer>();
        Map<String, Set<Integer>> byName = new LinkedHashMap<String, Set<Integer>>();
        for (JsonElement entry : root.getAsJsonObject().getAsJsonArray("felder")) {
            if (!entry.isJsonObject()) {
                continue;
            }
            JsonObject field = entry.getAsJsonObject();
            String name = field.has("name") && field.get("name").isJsonPrimitive()
                    ? field.get("name").getAsString().replaceAll("\\s+", " ").trim() : "";
            if (name.isEmpty() || !field.has("begriffe") || !field.get("begriffe").isJsonArray()) {
                continue;
            }
            Set<Integer> members = byName.computeIfAbsent(name, n -> new LinkedHashSet<Integer>());
            for (JsonElement number : field.getAsJsonArray("begriffe")) {
                int n = -1;
                try {
                    n = number.getAsInt();
                } catch (RuntimeException notNumber) {
                    // a name instead of a number: not used
                }
                if (n >= 1 && n <= terms.size() && used.add(n)) {
                    members.add(n);
                }
            }
        }
        List<Field> out = new ArrayList<Field>();
        for (Map.Entry<String, Set<Integer>> entry : byName.entrySet()) {
            if (entry.getValue().isEmpty() || out.size() >= options.maxFields) {
                continue;
            }
            List<Integer> numbers = new ArrayList<Integer>(entry.getValue());
            Collections.sort(numbers);
            List<String> keys = new ArrayList<String>();
            List<String> examples = new ArrayList<String>();
            for (int n : numbers) {
                keys.add(terms.get(n - 1).getKey());
                if (examples.size() < options.examples) {
                    examples.add(terms.get(n - 1).getTerm());
                }
            }
            out.add(new Field(entry.getKey(), keys, examples));
        }
        return out;
    }

    static String schema() {
        JsonObject string = new JsonObject();
        string.addProperty("type", "string");
        JsonObject integer = new JsonObject();
        integer.addProperty("type", "integer");
        JsonObject numbers = new JsonObject();
        numbers.addProperty("type", "array");
        numbers.add("items", integer);
        JsonObject properties = new JsonObject();
        properties.add("name", string);
        properties.add("begriffe", numbers);
        JsonObject field = object(properties, "name", "begriffe");
        JsonObject list = new JsonObject();
        list.addProperty("type", "array");
        list.add("items", field);
        JsonObject top = new JsonObject();
        top.add("felder", list);
        return object(top, "felder").toString();
    }

    private static JsonObject object(JsonObject properties, String... required) {
        JsonArray names = new JsonArray();
        for (String name : required) {
            names.add(name);
        }
        JsonObject o = new JsonObject();
        o.addProperty("type", "object");
        o.add("properties", properties);
        o.add("required", names);
        o.addProperty("additionalProperties", false);
        return o;
    }
}
