package com.aresstack.writing4j.book;

import com.aresstack.knowledge4j.model.chat.ChatMessage;
import com.aresstack.knowledge4j.model.chat.MainModelChat;
import com.aresstack.knowledge4j.model.chat.MainModelChatResult;
import com.aresstack.knowledge4j.basket.SourceBasket;
import com.aresstack.knowledge4j.passage.PassageView;
import com.aresstack.knowledge4j.scope.Fence;
import com.aresstack.knowledge4j.source.SourceKind;
import com.aresstack.knowledge4j.source.SourceRef;
import com.aresstack.writing4j.cornerstone.CornerstoneSurvey;
import com.aresstack.writing4j.cornerstone.TermCandidates;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A live run of Phase 4 against a local Ollama, skipped unless the environment names a basket and a model: the whole
 * way from the basket to the book, with the reader played by "everything the sieve did not throw out" (safe in and the
 * uncertain middle). For checking a change with a real model before it goes to a host; writes the opening, the book
 * and the report to a folder. Nothing here runs in a normal build.
 *
 * <p>Environment: {@code BOOK_PROBE_BASKET} (a test basket as {@link BasketStock#korb} reads it, or a basket with
 * {@code mission} and {@code passages} of {@code id}, {@code sourceId}, {@code text}), {@code BOOK_PROBE_OLLAMA} (the
 * base address of Ollama), {@code BOOK_PROBE_MODEL}, {@code BOOK_PROBE_OUT} (folder), optional
 * {@code BOOK_PROBE_CONTEXT} (num_ctx, default 16384), {@code BOOK_PROBE_DEPTH} (candidates judged, default the
 * library's), {@code BOOK_PROBE_WORDS} (target length, default 0).</p>
 */
public class LiveBookProbe {

    @Test
    public void fromABasketToABookWithALocalModel() throws Exception {
        String basketFile = System.getenv("BOOK_PROBE_BASKET");
        String ollama = System.getenv("BOOK_PROBE_OLLAMA");
        String model = System.getenv("BOOK_PROBE_MODEL");
        Assume.assumeTrue("live probe only with BOOK_PROBE_BASKET, BOOK_PROBE_OLLAMA and BOOK_PROBE_MODEL",
                basketFile != null && ollama != null && model != null);
        int context = number("BOOK_PROBE_CONTEXT", 16384);
        File out = new File(System.getenv("BOOK_PROBE_OUT") == null ? "book-probe" : System.getenv("BOOK_PROBE_OUT"));
        out.mkdirs();
        BasketStock stock = load(new File(basketFile));
        Ollama chat = new Ollama(ollama, model, context);
        chat.transcript = new File(out, "aufrufe.txt");
        chat.transcript.delete();
        TermCandidates.Options defaults = TermCandidates.Options.defaults();
        BookRun.Settings settings = new BookRun.Settings(stock.language, number("BOOK_PROBE_WORDS", 0), 1, null,
                null, null, null).withCandidates(new TermCandidates.Options(defaults.getMinPassages(),
                defaults.getMinSources(), number("BOOK_PROBE_DEPTH", defaults.getDepth()),
                defaults.getUnknownFloor()));
        final StringBuilder log = new StringBuilder();
        final long began = System.currentTimeMillis();
        BookRun.Progress progress = new BookRun.Progress() {
            @Override
            public void step(String step, int done, int total) {
                if (done == 0 || done == total) {
                    line(log, began, step + " " + done + "/" + total);
                }
            }
        };
        BookRun run = new BookRun(chat, chat, chat, null, settings, Clock.systemUTC(),
                new java.util.function.Consumer<String>() {
                    @Override
                    public void accept(String text) {
                        line(log, began, "  " + text);
                    }
                });
        line(log, began, "basket: " + stock.basket.sources().size() + " sources, " + stock.passageCount()
                + " passages");
        BookRun.Preparation prepared = run.prepare(stock.basket, stock, progress);
        CornerstoneSurvey.Survey survey = prepared.getSurvey();
        line(log, began, "prepared: " + survey.numbers());
        Set<String> chosen = new LinkedHashSet<String>();
        for (TermCandidates.Candidate candidate : survey.safeIn()) {
            chosen.add(candidate.getKey());
        }
        for (TermCandidates.Candidate candidate : survey.uncertain()) {
            chosen.add(candidate.getKey());
        }
        StringBuilder terms = new StringBuilder();
        for (TermCandidates.Candidate candidate : survey.getCandidates().getCandidates()) {
            terms.append(survey.verdict(candidate.getKey())).append('\t').append(candidate.getTerm()).append('\n');
        }
        Files.write(new File(out, "begriffe.txt").toPath(), terms.toString().getBytes(StandardCharsets.UTF_8));
        BookRun.Opening opening = run.open(prepared, CornerstoneSurvey.selected(survey, chosen, null,
                prepared.basketHints()), progress);
        line(log, began, "opened: " + opening.getGroups().size() + " chapters, " + opening.getQuestions().size()
                + " questions");
        stock.writeOpening(opening, out, new JsonObject());
        BookRun.Result result = run.write(opening, progress);
        line(log, began, "written: " + result.getSections().size() + " sections, " + chat.calls + " model calls ("
                + chat.failures + " failed)");
        stock.write(result, out, new JsonObject());
        Files.write(new File(out, "lauf.txt").toPath(), log.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void line(StringBuilder log, long began, String text) {
        String line = ((System.currentTimeMillis() - began) / 1000) + " s  " + text;
        System.out.println(line);
        synchronized (log) {
            log.append(line).append('\n');
        }
    }

    private static int number(String name, int fallback) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? fallback : Integer.parseInt(value.trim());
    }

    /** A test basket, or a basket with mission and passages of source and text. */
    static BasketStock load(File file) throws Exception {
        JsonObject root;
        Reader in = new InputStreamReader(new java.io.FileInputStream(file), StandardCharsets.UTF_8);
        try {
            root = JsonParser.parseReader(in).getAsJsonObject();
        } finally {
            in.close();
        }
        if (root.has("passagen")) {
            Reader again = new InputStreamReader(new java.io.FileInputStream(file), StandardCharsets.UTF_8);
            try {
                return BasketStock.korb(again);
            } finally {
                again.close();
            }
        }
        BasketStock stock = new BasketStock();
        stock.fence = Fence.of(root.get("mission").getAsString());
        stock.title = root.get("mission").getAsString();
        Map<String, SourceRef> sources = new LinkedHashMap<String, SourceRef>();
        for (JsonElement element : root.getAsJsonArray("passages")) {
            JsonObject p = element.getAsJsonObject();
            String source = p.get("sourceId").getAsString();
            if (!sources.containsKey(source)) {
                sources.put(source, SourceRef.of(SourceKind.INTERNAL_DOCUMENT, source).withTitle(source));
                stock.bySource.put(source, new ArrayList<PassageView>());
            }
            stock.bySource.get(source).add(new PassageView(p.get("id").getAsString(), sources.get(source),
                    p.get("text").getAsString(), p.has("heading") ? p.get("heading").getAsString() : "", "de", null));
        }
        stock.basket = new SourceBasket(stock.fence, new ArrayList<SourceRef>(sources.values()));
        return stock;
    }

    /** Ollama's own chat call, no thinking, the given context; failures come back as results. */
    static final class Ollama implements MainModelChat {
        private final String base;
        private final String model;
        private final int context;
        volatile int calls;
        volatile int failures;
        File transcript;

        Ollama(String base, String model, int context) {
            this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
            this.model = model;
            this.context = context;
        }

        @Override
        public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
            return call(messages, temperature, maxOutputTokens, null);
        }

        @Override
        public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature, int maxOutputTokens,
                                                String schemaJson) {
            return call(messages, temperature, maxOutputTokens,
                    schemaJson == null ? new com.google.gson.JsonPrimitive("json") : JsonParser.parseString(schemaJson));
        }

        private synchronized MainModelChatResult call(List<ChatMessage> messages, double temperature,
                                                      int maxOutputTokens, JsonElement format) {
            calls++;
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("stream", false);
            body.addProperty("think", false);
            JsonArray list = new JsonArray();
            for (ChatMessage message : messages) {
                JsonObject m = new JsonObject();
                m.addProperty("role", message.getRole().name().toLowerCase(java.util.Locale.ROOT));
                m.addProperty("content", message.getContent());
                list.add(m);
            }
            body.add("messages", list);
            JsonObject options = new JsonObject();
            options.addProperty("num_ctx", context);
            options.addProperty("temperature", temperature);
            if (maxOutputTokens > 0) {
                options.addProperty("num_predict", maxOutputTokens);
            }
            body.add("options", options);
            if (format != null) {
                body.add("format", format);
            }
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(base + "/api/chat").openConnection();
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(600000);
                connection.setRequestProperty("Content-Type", "application/json");
                OutputStream sent = connection.getOutputStream();
                sent.write(body.toString().getBytes(StandardCharsets.UTF_8));
                sent.close();
                int status = connection.getResponseCode();
                InputStream answer = status < 400 ? connection.getInputStream() : connection.getErrorStream();
                Reader reader = new InputStreamReader(answer, StandardCharsets.UTF_8);
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                reader.close();
                if (status >= 400 || !json.has("message")) {
                    failures++;
                    return MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE, "http " + status);
                }
                String content = json.getAsJsonObject("message").get("content").getAsString();
                note(messages, content);
                return MainModelChatResult.ok(content);
            } catch (Exception failed) {
                failures++;
                return MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE,
                        failed.getClass().getSimpleName());
            }
        }

        /** The end of the prompt and the start of the answer of every call, for reading a run afterwards. */
        private void note(List<ChatMessage> messages, String content) {
            if (transcript == null) {
                return;
            }
            String prompt = messages.get(messages.size() - 1).getContent();
            String text = "=== " + calls + "\n... " + prompt.substring(Math.max(0, prompt.length() - 400)) + "\n--- "
                    + content.substring(0, Math.min(content.length(), 3000)) + "\n";
            try {
                Files.write(transcript.toPath(), text.getBytes(StandardCharsets.UTF_8),
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (java.io.IOException ignored) {
                // the transcript is a help, not part of the run
            }
        }

        @Override
        public int contextTokens() {
            return context;
        }

        @Override
        public String modelName() {
            return model;
        }
    }

}
