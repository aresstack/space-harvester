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
import java.util.concurrent.TimeUnit;
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
    static final String SYSTEM_PROMPT = "Du bist ein Code-Summarizer. Antworte ausschließlich in DEUTSCH, "
            + "extrem kurz und sachlich. Keine Einleitung, keine Erklärung, "
            + "keine Code-Wiederholung. Wenn ein bestimmter Stil verlangt wird "
            + "(LABEL / SENTENCE / BULLETS), halte dich exakt daran. "
            + "LABEL = eine Substantiv-Phrase (z.B. \"Validierung der Eingabe\"). "
            + "SENTENCE = ein Satz. "
            + "BULLETS = kurze Bullet-Liste mit \"- \" Präfix.";
    static final long TIMEOUT_SECONDS = 30;
    private static final int CACHE_SIZE = 1000;

    /**
     * Wie das Modell angesprochen wird.
     *
     * @param chat     streamender Chat-Port, {@code null} ohne KI-Dienst
     * @param model    Modellname, {@code null} ohne Modell
     * @param thinking Nachdenken ({@code none}, {@code low}, …) oder {@code null} = Einstellung des Dienstes
     * @param budget   Tokenbudget je Anfrage, {@code null} ohne bekannte Kontextlänge (dann eine Anfrage)
     */
    public record Target(AiChat chat, String model, String thinking, ModelBudget budget) {
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
        if (!AppState.shared().getBoolean(ENABLED_KEY, false)) {
            return false;
        }
        Target current = target.get();
        return current != null && current.chat() != null && current.model() != null && !current.model().isBlank();
    }

    @Override
    public String summarize(String code, int maxLength, String purpose) {
        if (code == null || code.isBlank()) {
            return null;
        }
        Target current = target.get();
        if (current == null || current.chat() == null || current.model() == null) {
            return null;
        }
        String key = current.model() + "|" + maxLength + "|" + Integer.toHexString(code.hashCode());
        String cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        List<String> parts;
        try {
            parts = parts(code, maxLength, purpose, current.budget());
        } catch (IllegalStateException tooSmall) {
            lastProblem = tooSmall.getMessage();
            return null;
        }
        String result;
        if (parts.size() == 1) {
            result = ask(current, prompt(code, maxLength, purpose), maxLength);
        } else {
            // Erst jeden Teil beschriften, dann aus den Teilbeschriftungen den ganzen Block.
            StringBuilder labels = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                String label = ask(current, prompt(parts.get(i), maxLength, part(purpose, i + 1, parts.size())),
                        maxLength);
                if (label == null) {
                    return null;
                }
                labels.append("- ").append(label).append('\n');
            }
            result = summarize(labels.toString(), maxLength, combined(purpose));
        }
        if (result == null || result.isEmpty()) {
            return null;
        }
        cache.put(key, result);
        lastProblem = null;
        return result;
    }

    private String ask(Target current, String prompt, int maxLength) {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> answer = new AtomicReference<>();
        AiChat.Task task = current.chat().stream(new AiChat.Request(List.of(AiChat.Message.system(SYSTEM_PROMPT),
                AiChat.Message.user(prompt)), current.model(), current.thinking()), new AiChat.Listener() {
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
            if (!done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                task.cancel();
                lastProblem = "Keine Antwort in " + TIMEOUT_SECONDS + " s";
                return null;
            }
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
        if (budget == null) {
            return List.of(code);
        }
        int fixed = ModelBudget.tokens(SYSTEM_PROMPT) + ModelBudget.tokens(prompt("", maxLength,
                part(purpose, Integer.MAX_VALUE, Integer.MAX_VALUE)));
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
        StringBuilder sb = new StringBuilder();
        sb.append("STIL: LABEL\n");
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
