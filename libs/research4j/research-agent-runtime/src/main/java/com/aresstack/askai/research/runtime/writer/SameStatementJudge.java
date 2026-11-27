package com.aresstack.askai.research.runtime.writer;

import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;
import com.aresstack.askai.research.runtime.role.ResearchRole;
import com.aresstack.askai.research.runtime.role.RoleOutput;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Component 12, second stage: one model call decides whether a later sentence of the book makes the same statement as
 * an earlier one. The sentence vectors only pick the candidate pairs ({@link BookRepeatCheck}); on the judged pairs
 * of the book of 17 Nov no similarity bound separated the same statement from the same topic with another actor or
 * another condition (component test of 21.11.2026), so the model decides.
 *
 * <p>The call sees the two sentences only, never their passages. The answer is a JSON object with the reason first
 * and then the verdict {@code GLEICH}, {@code TEILWEISE} or {@code VERSCHIEDEN}; the schema binds it at generation
 * time. Temperature 0, thinking off, a slot of its own ({@link ResearchModelSlots#PHASE_DRAFT_REPEAT}). Rules of the
 * build plan: a failed call or an answer that is no valid verdict gets one repair attempt, then the pair is
 * {@link Verdict#ERROR} with the reason, never "different". The judge keeps no state; it is as thread-safe as the
 * injected chat.</p>
 *
 * <p>First model run (21.11.2026, the 60 judged pairs of the book of 17 Nov): both models called nearly every small
 * detail new information and said {@code TEILWEISE} for most pairs the test data call {@code GLEICH}; and the
 * prompt's examples were close to two pairs of the test data. Since then the prompt asks for essential new
 * information, its examples come from another field (road traffic), and the definitions are those of the test data.
 * Second model run (fresh pairs): {@code TEILWEISE} is not reliable with either model (13 and 16 of 40 different pairs
 * called TEILWEISE; the models do not check whether the earlier statement is in the later sentence at all), while
 * {@code GLEICH} held with {@code gemma4:12b} (7 of 9, none of 40 different pairs). So only {@code GLEICH} reports a
 * repeat to the writer; {@code TEILWEISE} stays a hint in the result ({@link BookRepeatCheck.Result#getHints()}).</p>
 */
public final class SameStatementJudge {

    /** The model's verdict on one pair. */
    public enum Verdict {
        /** The later sentence states what the earlier one states, in whatever words. */
        SAME("GLEICH"),
        /** Part of the statement repeats, the later sentence adds something new. */
        PARTLY("TEILWEISE"),
        /** Another topic, or the same topic with another statement. */
        DIFFERENT("VERSCHIEDEN"),
        /** The call failed twice or never gave a valid verdict. */
        ERROR("FEHLER"),
        /** Not asked: the model had failed too often before (see {@link BookRepeatCheck}). */
        NOT_JUDGED("NICHT_BEURTEILT");

        private final String wireName;

        Verdict(String wireName) {
            this.wireName = wireName;
        }

        /** The word in the model's answer and in reports. */
        public String wireName() {
            return wireName;
        }

        static Verdict fromAnswer(String word) {
            String w = word == null ? "" : word.trim().toUpperCase(Locale.ROOT);
            for (Verdict v : new Verdict[]{SAME, PARTLY, DIFFERENT}) {
                if (v.wireName.equals(w)) {
                    return v;
                }
            }
            return null;
        }
    }

    /** The outcome of one pair; immutable. */
    public static final class Judgement {
        private final Verdict verdict;
        private final String reason;
        private final List<String> answers;
        private final String problem;

        Judgement(Verdict verdict, String reason, List<String> answers, String problem) {
            this.verdict = verdict;
            this.reason = reason == null ? "" : reason;
            this.answers = Collections.unmodifiableList(new ArrayList<String>(answers));
            this.problem = problem == null ? "" : problem;
        }

        static Judgement notJudged(String why) {
            return new Judgement(Verdict.NOT_JUDGED, "", Collections.<String>emptyList(), why);
        }

        public Verdict getVerdict() {
            return verdict;
        }

        /** The model's reason in one sentence; empty for an error. */
        public String getReason() {
            return reason;
        }

        /** The raw answer of every call, in order (a failed call adds {@code ""}). */
        public List<String> getAnswers() {
            return answers;
        }

        /** Calls made for this pair. */
        public int getAttempts() {
            return answers.size();
        }

        /** Why the pair has no verdict; empty when it has one. */
        public String getProblem() {
            return problem;
        }

        public boolean isJudged() {
            return verdict == Verdict.SAME || verdict == Verdict.PARTLY || verdict == Verdict.DIFFERENT;
        }

        @Override
        public String toString() {
            return verdict.wireName() + (problem.isEmpty() ? ": " + reason : " (" + problem + ")");
        }
    }

    /** The role: its own slot under Document, a fixed schema, thinking off. */
    public static final ResearchRole ROLE = ResearchRole.builder("repeat-judge", ResearchModelSlots.PHASE_DRAFT_REPEAT)
            .purpose("component 12: decides whether a later sentence of the book makes the same statement as an "
                    + "earlier one (candidate pairs from the sentence vectors)")
            .promptFromCaller()
            .output(RoleOutput.schema(schema(), "grund, urteil GLEICH/TEILWEISE/VERSCHIEDEN"))
            .thinking(ThinkingMode.OFF)
            .build();

    static final double TEMPERATURE = 0.0;
    /** Answer budget: one sentence of reason and one word. */
    static final int ANSWER_TOKENS = 300;
    /** Calls per pair: the first and one repair attempt (rules of the build plan). */
    static final int ATTEMPTS = 2;

    private final MainModelChat chat;
    private final boolean german;

    /**
     * @param chat     the transport of the slot ({@link #ROLE})
     * @param language the language of the book ({@code "en"} for English; anything else reads German)
     */
    public SameStatementJudge(MainModelChat chat, String language) {
        if (chat == null) {
            throw new IllegalArgumentException("the judge needs a chat");
        }
        this.chat = chat;
        this.german = !"en".equalsIgnoreCase(language == null ? "" : language.trim());
    }

    /** What the chat calls (the slot in the app, the model in a test). */
    public String modelName() {
        return chat.modelName();
    }

    /** One pair: at most {@link #ATTEMPTS} calls. */
    public Judgement judge(String earlier, String later) {
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(german ? SYSTEM_DE : SYSTEM_EN));
        messages.add(ChatMessage.user(prompt(earlier, later, german)));
        List<String> answers = new ArrayList<String>();
        String problem = "";
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            MainModelChatResult result;
            try {
                result = chat.completeJson(new ArrayList<ChatMessage>(messages), TEMPERATURE, ANSWER_TOKENS,
                        schema());
            } catch (RuntimeException failed) {
                result = MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE,
                        failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage());
            }
            if (!result.isOk()) {
                answers.add("");
                problem = "model call failed (" + result.getStatus() + "): " + result.getDetail();
                continue; // the same request once more
            }
            String text = result.getText() == null ? "" : result.getText();
            answers.add(text);
            Parsed parsed = parse(text);
            if (parsed.verdict != null) {
                return new Judgement(parsed.verdict, parsed.reason, answers, "");
            }
            problem = "invalid answer: " + parsed.problem;
            messages.add(ChatMessage.assistant(text));
            messages.add(ChatMessage.user((german ? "Deine Antwort war ungültig: " : "Your answer was invalid: ")
                    + parsed.problem + (german ? "\nAntworte noch einmal, nur mit dem JSON-Objekt."
                    : "\nAnswer again with the JSON object only.")));
        }
        return new Judgement(Verdict.ERROR, "", answers, problem);
    }

    static final String SYSTEM_DE = "Du prüfst ein Sachbuch auf Wiederholungen. Du bekommst zwei Sätze aus "
            + "verschiedenen Abschnitten desselben Buchs und entscheidest, ob der spätere Satz die Aussage des "
            + "früheren wiederholt, ganz oder mit einer wesentlichen neuen Information. Du urteilst nur über die "
            + "beiden Sätze, nicht über ihre Richtigkeit. Antworte nur mit JSON.";

    static final String SYSTEM_EN = "You check a non-fiction book for repetitions. You get two sentences from "
            + "different sections of the same book and decide whether the later sentence repeats the statement of the "
            + "earlier one, wholly or with essential new information. You judge only the two sentences, not whether "
            + "they are true. Answer only with JSON.";

    static String prompt(String earlier, String later, boolean german) {
        String e = earlier == null ? "" : earlier.trim();
        String l = later == null ? "" : later.trim();
        if (german) {
            return "Früherer Satz:\n" + e + "\n\nSpäterer Satz:\n" + l + "\n\n"
                    + "Urteile so:\n"
                    + "- GLEICH: Der spätere Satz macht dieselbe Aussage wie der frühere. Was er dazu bringt, ist nur "
                    + "ein Beispiel, eine Aufzählung, die dasselbe sagt, oder eine Umformulierung, nichts, was einen "
                    + "eigenen Satz verdienen würde. Er bringt keine wesentliche neue Information.\n"
                    + "- TEILWEISE: Im Kern macht der spätere Satz dieselbe Aussage wie der frühere, und dazu kommt "
                    + "eine eigene neue Aussage: etwa eine neue Bedingung, eine Folge, eine Rechtsgrundlage, ein "
                    + "weiterer Handelnder oder ein Zweck, der etwas Eigenes sagt.\n"
                    + "- VERSCHIEDEN: Der spätere Satz macht eine andere Aussage, auch wenn Thema oder Begründung "
                    + "gleich sind. Das gilt auch, wenn er dasselbe für andere Handelnde oder Betroffene sagt als der "
                    + "frühere Satz, unter einer anderen Bedingung, mit einer anderen Zahl, nach einer anderen "
                    + "Rechtsgrundlage, für einen anderen Zeitraum oder Ort.\n\n"
                    + "Beispiele aus einem anderen Gebiet:\n"
                    + "- „Radfahrer müssen bei Dunkelheit das Licht einschalten.“ und „Wer im Dunkeln Rad fährt, muss "
                    + "das Licht anmachen, etwa auf dem Heimweg.“: GLEICH.\n"
                    + "- „Radfahrer müssen bei Dunkelheit das Licht einschalten.“ und „Radfahrer müssen bei Dunkelheit "
                    + "das Licht einschalten, sonst droht ein Bußgeld.“: TEILWEISE.\n"
                    + "- „Radfahrer müssen bei Dunkelheit das Licht einschalten.“ und „Autofahrer müssen bei "
                    + "Dunkelheit das Licht einschalten.“: VERSCHIEDEN.\n\n"
                    + "Schreibe zuerst in einem Satz den Grund, dann das Urteil. Antworte als JSON mit den Feldern "
                    + "\"grund\" und \"urteil\".";
        }
        return "Earlier sentence:\n" + e + "\n\nLater sentence:\n" + l + "\n\n"
                + "Judge like this:\n"
                + "- GLEICH: the later sentence makes the same statement as the earlier one. What it adds is only an "
                + "example, a list that says the same, or a rewording, nothing that would deserve a sentence of its "
                + "own. It adds no essential new information.\n"
                + "- TEILWEISE: at its core the later sentence makes the same statement as the earlier one, and adds a "
                + "statement of its own: for instance a new condition, a consequence, a legal basis, a further actor "
                + "or a purpose that says something of its own.\n"
                + "- VERSCHIEDEN: the later sentence makes another statement, even when topic or justification are the "
                + "same. That holds too when it says the same for other actors or persons concerned than the earlier "
                + "sentence, under another condition, with another number, under another legal basis, for another "
                + "period or place.\n\n"
                + "Examples from another field:\n"
                + "- \"Cyclists must switch on their lights in the dark.\" and \"Whoever cycles in the dark has to "
                + "turn the light on, for instance on the way home.\": GLEICH.\n"
                + "- \"Cyclists must switch on their lights in the dark.\" and \"Cyclists must switch on their lights "
                + "in the dark, or they risk a fine.\": TEILWEISE.\n"
                + "- \"Cyclists must switch on their lights in the dark.\" and \"Drivers must switch on their lights "
                + "in the dark.\": VERSCHIEDEN.\n\n"
                + "First write the reason in one sentence, then the verdict. Answer as JSON with the fields \"grund\" "
                + "and \"urteil\".";
    }

    /** {@code {"grund": string, "urteil": GLEICH|TEILWEISE|VERSCHIEDEN}}, both required, the reason first. */
    static String schema() {
        JsonObject properties = new JsonObject();
        JsonObject reason = new JsonObject();
        reason.addProperty("type", "string");
        properties.add("grund", reason);
        JsonObject verdict = new JsonObject();
        verdict.addProperty("type", "string");
        JsonArray values = new JsonArray();
        values.add(Verdict.SAME.wireName());
        values.add(Verdict.PARTLY.wireName());
        values.add(Verdict.DIFFERENT.wireName());
        verdict.add("enum", values);
        properties.add("urteil", verdict);
        JsonArray required = new JsonArray();
        required.add("grund");
        required.add("urteil");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema.toString();
    }

    /** A verdict with its reason, or why the answer holds none. */
    static final class Parsed {
        final Verdict verdict;
        final String reason;
        final String problem;

        Parsed(Verdict verdict, String reason, String problem) {
            this.verdict = verdict;
            this.reason = reason;
            this.problem = problem;
        }
    }

    static Parsed parse(String answer) {
        JsonObject json;
        try {
            JsonElement element = JsonParser.parseString(answer == null ? "" : answer.trim());
            if (!element.isJsonObject()) {
                return new Parsed(null, "", "the answer is no JSON object");
            }
            json = element.getAsJsonObject();
        } catch (RuntimeException notJson) {
            return new Parsed(null, "", "the answer is no JSON");
        }
        JsonElement word = json.get("urteil");
        Verdict verdict = word != null && word.isJsonPrimitive() ? Verdict.fromAnswer(word.getAsString()) : null;
        if (verdict == null) {
            return new Parsed(null, "", "\"urteil\" must be GLEICH, TEILWEISE or VERSCHIEDEN");
        }
        JsonElement reason = json.get("grund");
        if (reason == null || !reason.isJsonPrimitive() || !reason.getAsJsonPrimitive().isString()
                || reason.getAsString().trim().isEmpty()) {
            // A transport may fall back to plain completion without the schema: a verdict needs its reason.
            return new Parsed(null, "", "\"grund\" must be a text");
        }
        return new Parsed(verdict, reason.getAsString().replaceAll("\\s+", " ").trim(), "");
    }
}
