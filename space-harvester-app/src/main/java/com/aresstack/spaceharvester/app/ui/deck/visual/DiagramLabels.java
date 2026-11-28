package com.aresstack.spaceharvester.app.ui.deck.visual;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.ai.AiChatException;
import com.aresstack.spaceharvester.app.runtime.ModelBudget;
import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.mainframe.diagram.BlockLabelSummarizer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * KI-Kurzbeschriftung zusammengefasster Diagramm-Blöcke, wie {@code SummarizerServiceImpl} mit Stil LABEL in
 * MainframeMate: gleicher System-Prompt, gleiches Anfrageformat (STIL/MAX_ZEICHEN/KONTEXT/TEXT), gleiche Bereinigung,
 * LRU-Cache mit 1000 Einträgen, Frist 30 s je Block (MainframeMate 15 s; das erste Laden eines Modells dauert länger). Anders als dort immer gestreamt (Proxy bricht lange Antworten
 * ohne Strom ab) und über den KI-Dienst der Einstellungen (Zeile „Zusammenfassung“: Modell und Nachdenken, sonst das
 * Chat-Modell). Eingeschaltet in Einstellungen › Diagramme › Diagramm-Ansicht; aus = keine Aufrufe.
 *
 * <p>Der Code eines Blocks wird nie abgeschnitten (F41-7, docs/grenzen.md; früher fehlte alles nach 4000 Zeichen):
 * Passt er nicht in das Tokenbudget des Modells ({@link ModelBudget}: Kontextlänge abzüglich Antwortreserve), wird er
 * an Zeilengrenzen in Teile zerlegt, jeder Teil beschriftet und aus den Teilbeschriftungen die Beschriftung des Blocks
 * gebildet. Ohne bekannte Kontextlänge geht der ganze Code in eine Anfrage.
 */
public final class DiagramLabels implements BlockLabelSummarizer {

    /** Schalter in den Einstellungen (MainframeMate: Summarizer aktiv und „UML-Zusammenfassung“). */
    public static final String ENABLED_KEY = "deck.visual.ai-labels";
    /**
     * Vorgabe des Schalters: an. Die Kacheln mit „📦 5 Anweisungen“ waren als Rückfall gedacht, wenn keine KI da ist
     * (Angelo 28.11.2026); ohne eingerichtetes Modell entstehen trotzdem keine Aufrufe ({@link #enabled()}).
     */
    public static final boolean ENABLED_DEFAULT = true;
    /** Länge kurzer KI-Beschriftungen in Zeichen (Einstellungen › Diagramme). */
    public static final String LENGTH_KEY = "deck.visual.label-length";
    /** Länge der ausführlichen Beschreibung eines Blocks in Zeichen (Einstellungen › Diagramme). */
    public static final String DETAIL_LENGTH_KEY = "deck.visual.block-length";
    /** Zeichen je Zeile im Diagrammknoten (Einstellungen › Diagramme). */
    public static final String LINE_WIDTH_KEY = "deck.visual.label-line-width";
    static final String SYSTEM_PROMPT = "Du bist ein Code-Summarizer. Antworte ausschließlich in DEUTSCH, "
            + "extrem kurz und sachlich. Keine Einleitung, keine Erklärung, "
            + "keine Code-Wiederholung. Wenn ein bestimmter Stil verlangt wird "
            + "(LABEL / SENTENCE / BULLETS), halte dich exakt daran. "
            + "LABEL = eine Substantiv-Phrase (z.B. \"Validierung der Eingabe\"). "
            + "SENTENCE = ein Satz. "
            + "DETAILS = mehrere kurze, einfache Sätze, Schritt für Schritt. "
            + "BULLETS = kurze Bullet-Liste mit \"- \" Präfix.";
    private static final int CACHE_SIZE = 1000;

    /**
     * Wie das Modell angesprochen wird.
     *
     * @param chat     streamender Chat-Port, {@code null} ohne KI-Dienst
     * @param model    Modellname, {@code null} ohne Modell
     * @param thinking Nachdenken ({@code none}, {@code low}, …) oder {@code null} = Einstellung des Dienstes
     * @param budget    Tokenbudget je Anfrage, {@code null} ohne bekannte Kontextlänge (dann eine Anfrage)
     * @param maxTokens Ausgabelimit je Anfrage (dasselbe wie die Antwortreserve im Budget), {@code null} =
     *                  Einstellung des Dienstes
     */
    public record Target(AiChat chat, String model, String thinking, ModelBudget budget, Integer maxTokens,
            Integer parallel) {

        public Target(AiChat chat, String model, String thinking, ModelBudget budget, Integer maxTokens) {
            this(chat, model, thinking, budget, maxTokens, null);
        }

        public Target(AiChat chat, String model, String thinking, ModelBudget budget) {
            this(chat, model, thinking, budget, budget == null ? null : budget.answerTokens());
        }
    }

    private final Supplier<Target> target;
    private final Map<String, String> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > CACHE_SIZE;
                }
            });
    private volatile String lastProblem;

    public DiagramLabels(Supplier<Target> target) {
        this.target = target;
    }

    @Override
    public boolean enabled() {
        if (!switchedOn()) {
            return false;
        }
        Target current = target.get();
        return current != null && current.chat() != null && current.model() != null && !current.model().isBlank();
    }

    /**
     * So viele Fragen eines Diagramms gleichzeitig, wie der KI-Dienst unter „ai.parallel“ erlaubt; nicht eingestellt
     * gilt dessen Vorgabe.
     */
    @Override
    public int parallel() {
        Target current = target.get();
        Integer parallel = current == null ? null : current.parallel();
        return parallel == null || parallel < 1 ? com.aresstack.spaceharvester.ai.AiServiceSettings.DEFAULT_PARALLEL
                : parallel;
    }

    /** Ob der Schalter in Einstellungen › Diagramme an ist. */
    public static boolean switchedOn() {
        return AppState.shared().getBoolean(ENABLED_KEY, ENABLED_DEFAULT);
    }

    /** Länge und Zeilenbreite aus den Einstellungen; Ungültiges zählt als Vorgabe. */
    public static com.aresstack.spaceharvester.mainframe.diagram.FlowLabels.Layout layout() {
        var standard = com.aresstack.spaceharvester.mainframe.diagram.FlowLabels.Layout.DEFAULT;
        int length = AppState.shared().getInt(LENGTH_KEY, standard.length());
        int detail = AppState.shared().getInt(DETAIL_LENGTH_KEY, standard.detailLength());
        int width = AppState.shared().getInt(LINE_WIDTH_KEY, standard.lineWidth());
        return new com.aresstack.spaceharvester.mainframe.diagram.FlowLabels.Layout(
                length > 0 ? length : standard.length(), detail > 0 ? detail : standard.detailLength(),
                width > 0 ? width : standard.lineWidth());
    }

    @Override
    public String summarize(String code, int maxLength, String purpose) {
        return label(code, maxLength, purpose, "LABEL");
    }

    @Override
    public String explain(String code, int maxLength, String purpose) {
        return label(code, maxLength, purpose, "SENTENCE");
    }

    @Override
    public String describe(String code, int maxLength, String purpose) {
        return label(code, maxLength, purpose, "DETAILS");
    }

    private String label(String code, int maxLength, String purpose, String style) {
        if (code == null || code.isBlank()) {
            return null;
        }
        Target current = target.get();
        if (current == null || current.chat() == null || current.model() == null) {
            return null;
        }
        String key = current.model() + "|" + style + "|" + maxLength + "|" + Integer.toHexString(code.hashCode())
                + "|" + Integer.toHexString(String.valueOf(purpose).hashCode());
        String cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        List<String> parts;
        try {
            parts = parts(code, maxLength, purpose, current.budget(), style);
        } catch (IllegalStateException tooSmall) {
            lastProblem = tooSmall.getMessage();
            return null;
        }
        String result;
        if (parts.size() == 1) {
            result = ask(current, prompt(code, maxLength, purpose, style), maxLength);
        } else {
            // Erst jeden Teil beschriften, dann aus den Teilbeschriftungen den ganzen Block.
            StringBuilder labels = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                String label = ask(current, prompt(parts.get(i), maxLength, part(purpose, i + 1, parts.size()),
                        style), maxLength);
                if (label == null) {
                    return null;
                }
                labels.append("- ").append(label).append('\n');
            }
            result = label(labels.toString(), maxLength, combined(purpose), style);
        }
        if (result == null || result.isEmpty()) {
            return null;
        }
        cache.put(key, result);
        // Den Grund nicht löschen: Bei parallelen Fragen darf eine gelungene Antwort den Fehler einer anderen nicht
        // verdecken; DiagramView holt ihn je Diagramm einmal ab (takeProblem).
        return result;
    }

    private String ask(Target current, String prompt, int maxLength) {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> answer = new AtomicReference<>();
        AiChat.Task task = current.chat().stream(new AiChat.Request(List.of(AiChat.Message.system(SYSTEM_PROMPT),
                AiChat.Message.user(prompt)), current.model(), current.thinking(), List.of(), null,
                current.maxTokens()), new AiChat.Listener() {
            @Override
            public void delta(String text) {
            }

            @Override
            public void completed(String text) {
                answer.set(text);
                done.countDown();
            }

            @Override
            public void failed(AiChatException error) {
                lastProblem = error.getMessage();
                done.countDown();
            }

            @Override
            public void cancelled() {
                done.countDown();
            }
        });
        try {
            // Wie im Chat keine feste Frist: Die Antwort kommt gestreamt, und die Verbindung bricht selbst ab, wenn
            // länger als die eingestellte KI-Frist kein Zeichen mehr kommt (Lesefrist der Verbindung). Das meldet
            // failed(), der Grund steht dann in der gelben Meldung, und der Block behält die Beschriftung ohne KI.
            done.await();
        } catch (InterruptedException e) {
            task.cancel();
            Thread.currentThread().interrupt();
            return null;
        }
        return sanitize(answer.get(), maxLength);
    }

    /**
     * Der Code in Teilen, die mit Systemanweisung und Rahmen ins Budget passen, an Zeilengrenzen; eine einzelne zu
     * lange Zeile wird an Zeichengrenzen geteilt. Ohne Budget oder wenn alles passt: ein Teil.
     */
    static List<String> parts(String code, int maxLength, String purpose, ModelBudget budget) {
        return parts(code, maxLength, purpose, budget, "LABEL");
    }

    static List<String> parts(String code, int maxLength, String purpose, ModelBudget budget, String style) {
        if (budget == null) {
            return List.of(code);
        }
        int fixed = ModelBudget.tokens(SYSTEM_PROMPT) + ModelBudget.tokens(prompt("", maxLength,
                part(purpose, Integer.MAX_VALUE, Integer.MAX_VALUE), style));
        int room = budget.left(fixed);
        if (room < 1) {
            throw new IllegalStateException("Kontextlänge des Modells (" + budget.contextTokens()
                    + " Tokens) reicht nicht für Systemanweisung und Antwortreserve (" + budget.answerTokens()
                    + " Tokens)");
        }
        if (ModelBudget.tokens(code) <= room) {
            return List.of(code);
        }
        List<String> parts = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : code.split("(?<=\n)")) {
            for (String piece : pieces(line, room)) {
                if (current.length() > 0 && ModelBudget.tokens(current + piece) > room) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
                current.append(piece);
            }
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }

    /** Eine Zeile, die allein nicht ins Budget passt, in Stücke, die passen. */
    private static List<String> pieces(String line, int room) {
        if (ModelBudget.tokens(line) <= room) {
            return List.of(line);
        }
        List<String> pieces = new java.util.ArrayList<>();
        int chars = Math.max(1, room * ModelBudget.CHARACTERS_PER_TOKEN / 2);
        for (int start = 0; start < line.length(); start += chars) {
            pieces.add(line.substring(start, Math.min(line.length(), start + chars)));
        }
        return pieces;
    }

    private static String part(String purpose, int number, int count) {
        String part = "Teil " + number + " von " + count + " eines Blocks";
        return purpose == null || purpose.isEmpty() ? part : purpose + "; " + part;
    }

    private static String combined(String purpose) {
        String combined = "Beschriftungen der Teile eines Blocks; eine Beschriftung für den ganzen Block";
        return purpose == null || purpose.isEmpty() ? combined : purpose + "; " + combined;
    }

    /** Warum die letzte Beschriftung nicht kam (für den Status), sonst {@code null}. */
    public String lastProblem() {
        return lastProblem;
    }

    /** Wie {@link #lastProblem()}, vergisst den Grund danach (je Diagramm einmal melden). */
    public String takeProblem() {
        String problem = lastProblem;
        lastProblem = null;
        return problem;
    }

    static String prompt(String code, int maxLength, String purpose) {
        return prompt(code, maxLength, purpose, "LABEL");
    }

    static String prompt(String code, int maxLength, String purpose, String style) {
        StringBuilder sb = new StringBuilder();
        sb.append("STIL: ").append(style).append('\n');
        sb.append("MAX_ZEICHEN: ").append(maxLength).append('\n');
        if (purpose != null && !purpose.isEmpty()) {
            sb.append("KONTEXT: ").append(purpose).append('\n');
        }
        sb.append("\nTEXT:\n");
        sb.append(code);
        return sb.toString();
    }

    /** Bereinigung wie MainframeMate: Codezäune, Anführungszeichen, Zeilenumbrüche, Länge am Wortende. */
    static String sanitize(String s, int maxLength) {
        if (s == null) {
            return null;
        }
        String out = s.trim();
        if (out.startsWith("```")) {
            int nl = out.indexOf('\n');
            if (nl > 0) {
                out = out.substring(nl + 1);
            }
            if (out.endsWith("```")) {
                out = out.substring(0, out.length() - 3).trim();
            }
        }
        if (out.length() > 1 && (out.startsWith("\"") && out.endsWith("\"")
                || out.startsWith("„") && out.endsWith("“"))) {
            out = out.substring(1, out.length() - 1);
        }
        out = out.replace("\n", " ").replace("\r", " ").replaceAll("\\s+", " ").trim();
        if (maxLength > 0 && out.length() > maxLength) {
            out = out.substring(0, maxLength);
            int sp = out.lastIndexOf(' ');
            if (sp > maxLength / 2) {
                out = out.substring(0, sp);
            }
            out = out + "…";
        }
        return out.trim();
    }
}
