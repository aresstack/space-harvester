package com.aresstack.writing4j.book;

import com.aresstack.knowledge4j.basket.BasketReader;
import com.aresstack.knowledge4j.basket.SourceBasket;
import com.aresstack.knowledge4j.passage.PassageView;
import com.aresstack.knowledge4j.scope.Fence;
import com.aresstack.knowledge4j.source.SourceKind;
import com.aresstack.knowledge4j.source.SourceRef;
import com.aresstack.writing4j.memory.EvidenceVerdict;
import com.aresstack.writing4j.planwriter.WrittenSection;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Test tooling: a basket from a file, read through an in-memory {@link BasketReader}, and what a run made of it
 * written to a folder. A test basket ({@code korb-kontrolliert.json}) has {@code weidezaun} and {@code passagen} with
 * {@code id}, {@code quelle}, {@code url}, {@code titel}, {@code ueberschrift}, {@code text} and the expected class
 * {@code art} (never sent to the model). A source with an address is a web page with the address as its id.
 */
final class BasketStock implements BasketReader {

    String title = "";
    String language = "de";
    int targetWords;
    Fence fence;
    SourceBasket basket;
    final Map<String, List<PassageView>> bySource = new LinkedHashMap<String, List<PassageView>>();
    /** The expected class of a passage by its id (only a test basket has them). */
    final Map<String, String> classes = new LinkedHashMap<String, String>();

    @Override
    public List<PassageView> passages(SourceRef source) {
        List<PassageView> list = bySource.get(source.id());
        return list == null ? Collections.<PassageView>emptyList() : list;
    }

    int passageCount() {
        int n = 0;
        for (List<PassageView> list : bySource.values()) {
            n += list.size();
        }
        return n;
    }

    static BasketStock korb(Reader in) {
        JsonObject root = JsonParser.parseReader(in).getAsJsonObject();
        BasketStock stock = new BasketStock();
        stock.title = text(root, "titel");
        stock.language = text(root, "sprache").isEmpty() ? "de" : text(root, "sprache");
        stock.targetWords = root.has("zielWoerter") ? root.get("zielWoerter").getAsInt() : 0;
        JsonObject fence = root.getAsJsonObject("weidezaun");
        stock.fence = new Fence(text(fence, "auftrag"), text(fence, "rand"), list(fence, "dazu"),
                list(fence, "ausgeschlossen"), text(fence, "raum"), text(fence, "zeit"));
        Map<String, SourceRef> sources = new LinkedHashMap<String, SourceRef>();
        for (JsonElement element : root.getAsJsonArray("passagen")) {
            JsonObject p = element.getAsJsonObject();
            String url = text(p, "url");
            String source = url.isEmpty() ? text(p, "quelle") : url;
            if (!sources.containsKey(source)) {
                sources.put(source, SourceRef.of(url.isEmpty() ? SourceKind.INTERNAL_DOCUMENT : SourceKind.WEB_PAGE,
                        source).withTitle(text(p, "titel")));
            }
            stock.add(source, new PassageView(text(p, "id"), sources.get(source), text(p, "text"),
                    text(p, "ueberschrift"), stock.language, null));
            if (!text(p, "art").isEmpty()) {
                stock.classes.put(text(p, "id"), text(p, "art"));
            }
        }
        stock.basket = new SourceBasket(stock.fence, new ArrayList<SourceRef>(sources.values()));
        return stock;
    }

    private void add(String source, PassageView passage) {
        List<PassageView> list = bySource.get(source);
        if (list == null) {
            list = new ArrayList<PassageView>();
            bySource.put(source, list);
        }
        list.add(passage);
    }

    /** The opening alone as {@code fragen.json} and {@code fragen.md}: groups, questions, verdicts, steps. */
    JsonObject writeOpening(BookRun.Opening opening, File out, JsonObject run) throws IOException {
        Files.createDirectories(out.toPath());
        JsonObject json = new JsonObject();
        json.addProperty("korb", title);
        json.add("lauf", run);
        json.addProperty("quellen", basket.sources().size());
        json.addProperty("passagen", opening.getPassages().size());
        steps(json, opening.getSteps());
        json.addProperty("modellwechsel", opening.getModelSwitches());
        Map<String, List<BookRun.Question>> byGroup = new LinkedHashMap<String, List<BookRun.Question>>();
        Map<String, Integer> relations = new LinkedHashMap<String, Integer>();
        for (BookRun.Question question : opening.getQuestions()) {
            byGroup.computeIfAbsent(question.getGroupId(), k -> new ArrayList<BookRun.Question>()).add(question);
            relations.merge(question.getVerdict().getRelation().name(), 1, Integer::sum);
        }
        JsonObject relationCounts = new JsonObject();
        for (Map.Entry<String, Integer> entry : relations.entrySet()) {
            relationCounts.addProperty(entry.getKey(), entry.getValue());
        }
        json.add("fragenNachUrteil", relationCounts);
        JsonArray groups = new JsonArray();
        StringBuilder md = new StringBuilder("# Fragen aus dem Korb: ").append(title).append("\n\n");
        md.append(opening.getPassages().size()).append(" Passagen aus ").append(basket.sources().size())
                .append(" Quellen, ").append(opening.getGroups().size()).append(" Gruppen, ")
                .append(opening.getQuestions().size()).append(" Fragen.\n\n");
        for (BookRun.Group group : opening.getGroups()) {
            JsonObject g = new JsonObject();
            g.addProperty("id", group.getId());
            g.addProperty("titel", group.getTitle());
            JsonArray ids = new JsonArray();
            JsonObject byClass = new JsonObject();
            Map<String, Integer> classCounts = new LinkedHashMap<String, Integer>();
            for (String id : group.getPassageIds()) {
                ids.add(id);
                if (classes.containsKey(id)) {
                    classCounts.merge(classes.get(id), 1, Integer::sum);
                }
            }
            for (Map.Entry<String, Integer> entry : classCounts.entrySet()) {
                byClass.addProperty(entry.getKey(), entry.getValue());
            }
            g.add("passagen", ids);
            if (!classCounts.isEmpty()) {
                g.add("klassen", byClass);
            }
            g.addProperty("problem", group.getProblem());
            JsonArray questions = new JsonArray();
            md.append("## ").append(group.getId()).append(": ").append(group.getTitle()).append(" (")
                    .append(group.getPassageIds().size()).append(" Passagen)\n\n");
            if (!group.getProblem().isEmpty()) {
                md.append("Problem: ").append(group.getProblem()).append("\n\n");
            }
            List<BookRun.Question> list = byGroup.get(group.getId());
            for (BookRun.Question question : list == null ? Collections.<BookRun.Question>emptyList() : list) {
                JsonObject q = new JsonObject();
                q.addProperty("id", question.getId());
                q.addProperty("frage", question.getText());
                q.addProperty("urteil", question.getVerdict().getRelation().name());
                q.addProperty("grund", question.getVerdict().getReason());
                questions.add(q);
                md.append("- ").append(question.getId()).append(" [").append(question.getVerdict().getRelation())
                        .append("] ").append(question.getText());
                if (question.getVerdict().getRelation() != StockQuestions.Relation.INNEN) {
                    md.append(" (").append(question.getVerdict().getReason()).append(')');
                }
                md.append('\n');
            }
            md.append('\n');
            g.add("fragen", questions);
            groups.add(g);
        }
        json.add("gruppen", groups);
        String pretty = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json);
        Files.write(new File(out, "fragen.json").toPath(), pretty.getBytes(StandardCharsets.UTF_8));
        Files.write(new File(out, "fragen.md").toPath(), md.toString().getBytes(StandardCharsets.UTF_8));
        return json;
    }

    /** The whole run as {@code fragen.*}, {@code ergebnis.json}, {@code buch.md} and {@code bericht.txt}. */
    JsonObject write(BookRun.Result result, File out, JsonObject run) throws IOException {
        writeOpening(result.getOpening(), out, run);
        JsonObject json = new JsonObject();
        json.addProperty("korb", title);
        json.add("lauf", run);
        steps(json, result.getSteps());
        json.addProperty("modellwechsel", result.getModelSwitches());
        json.addProperty("zitiertAusserhalbWeidezaun", result.getCitedOutsideFence());
        JsonArray questionLines = new JsonArray();
        Map<String, Integer> states = new LinkedHashMap<String, Integer>();
        for (BookRun.QuestionLine line : result.getQuestionLines()) {
            JsonObject q = new JsonObject();
            q.addProperty("id", line.getQuestion().getId());
            q.addProperty("frage", line.getQuestion().getText());
            q.addProperty("gruppe", line.getQuestion().getGroupId());
            q.addProperty("stand", line.getState().name());
            q.addProperty("abschnitt", line.getSection());
            q.addProperty("grund", line.getReason());
            q.addProperty("belegt", line.getDirect());
            q.addProperty("hinweise", line.getLeads());
            questionLines.add(q);
            states.merge(line.getState().name(), 1, Integer::sum);
        }
        json.add("fragen", questionLines);
        JsonObject coverage = new JsonObject();
        for (Map.Entry<String, Integer> state : states.entrySet()) {
            coverage.addProperty(state.getKey(), state.getValue());
        }
        json.add("fragenAbdeckung", coverage);
        Map<String, Integer> sectionCounts = new LinkedHashMap<String, Integer>();
        for (WrittenSection section : result.getSections()) {
            for (Map.Entry<String, Integer> count : section.getCounts().entrySet()) {
                sectionCounts.merge(count.getKey(), count.getValue(), Integer::sum);
            }
        }
        JsonObject counts = new JsonObject();
        for (Map.Entry<String, Integer> count : sectionCounts.entrySet()) {
            counts.addProperty(count.getKey(), count.getValue());
        }
        json.add("abschnittsZaehler", counts);
        if (!classes.isEmpty()) {
            json.add("klassen", classes(result));
        }
        json.add("bericht", result.getReport().toJson());
        String pretty = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json);
        Files.write(new File(out, "ergebnis.json").toPath(), pretty.getBytes(StandardCharsets.UTF_8));
        Files.write(new File(out, "buch.md").toPath(), result.book().getBytes(StandardCharsets.UTF_8));
        Files.write(new File(out, "bericht.txt").toPath(),
                result.report(ZoneId.of("Europe/Berlin")).getBytes(StandardCharsets.UTF_8));
        return json;
    }

    private static void steps(JsonObject json, List<BookRun.Step> list) {
        JsonArray steps = new JsonArray();
        long calls = 0;
        long tokens = 0;
        long millis = 0;
        for (BookRun.Step step : list) {
            JsonObject s = new JsonObject();
            s.addProperty("schritt", step.getName());
            s.addProperty("aufrufe", step.getCalls());
            s.addProperty("fehlgeschlagen", step.getFailures());
            s.addProperty("promptTokens", step.getPromptTokens());
            s.addProperty("antwortTokens", step.getAnswerTokens());
            s.addProperty("sekunden", step.getMillis() / 1000.0);
            steps.add(s);
            calls += step.getCalls();
            tokens += step.getPromptTokens() + step.getAnswerTokens();
            millis += step.getMillis();
        }
        json.add("schritte", steps);
        json.addProperty("aufrufe", calls);
        json.addProperty("tokens", tokens);
        json.addProperty("sekunden", millis / 1000.0);
    }

    /** Per expected class: passages, judged BELEGT for any question, judged HINWEIS, cited in the book. */
    JsonObject classes(BookRun.Result result) {
        Set<String> direct = new LinkedHashSet<String>();
        Set<String> leads = new LinkedHashSet<String>();
        for (EvidenceVerdict verdict : result.getVerdicts()) {
            if (verdict.getKind() == EvidenceVerdict.Kind.BELEGT) {
                direct.add(verdict.getPassageId());
            } else if (verdict.getKind() == EvidenceVerdict.Kind.HINWEIS) {
                leads.add(verdict.getPassageId());
            }
        }
        Set<String> cited = new LinkedHashSet<String>();
        for (WrittenSection section : result.getSections()) {
            for (WrittenSection.Citation citation : section.getCitations().values()) {
                cited.add(citation.getPassageId());
            }
        }
        Map<String, int[]> byClass = new LinkedHashMap<String, int[]>();
        for (Map.Entry<String, String> entry : classes.entrySet()) {
            int[] c = byClass.computeIfAbsent(entry.getValue(), k -> new int[4]);
            c[0]++;
            c[1] += direct.contains(entry.getKey()) ? 1 : 0;
            c[2] += leads.contains(entry.getKey()) && !direct.contains(entry.getKey()) ? 1 : 0;
            c[3] += cited.contains(entry.getKey()) ? 1 : 0;
        }
        JsonObject out = new JsonObject();
        for (Map.Entry<String, int[]> entry : byClass.entrySet()) {
            JsonObject c = new JsonObject();
            c.addProperty("passagen", entry.getValue()[0]);
            c.addProperty("belegt", entry.getValue()[1]);
            c.addProperty("nurHinweis", entry.getValue()[2]);
            c.addProperty("zitiert", entry.getValue()[3]);
            out.add(entry.getKey(), c);
        }
        return out;
    }

    static Reader resource(String name) {
        InputStream in = BasketStock.class.getResourceAsStream("/book-run/" + name);
        if (in == null) {
            throw new IllegalStateException("missing test resource book-run/" + name);
        }
        return new InputStreamReader(in, StandardCharsets.UTF_8);
    }

    private static String text(JsonObject json, String field) {
        JsonElement value = json == null ? null : json.get(field);
        return value != null && value.isJsonPrimitive() ? value.getAsString().trim() : "";
    }

    private static List<String> list(JsonObject json, String field) {
        List<String> out = new ArrayList<String>();
        JsonElement value = json == null ? null : json.get(field);
        if (value != null && value.isJsonArray()) {
            for (JsonElement entry : value.getAsJsonArray()) {
                out.add(entry.getAsString());
            }
        }
        return out;
    }
}
