package com.aresstack.spaceharvester.mainframe.diagram;

import com.aresstack.spaceharvester.mainframe.calltree.SourceResolver;
import com.aresstack.spaceharvester.mainframe.model.JclElement;
import com.aresstack.spaceharvester.mainframe.model.JclElementType;
import com.aresstack.spaceharvester.mainframe.parser.NaturalParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Beschriftungen des Flussdiagramms, die ein Mensch ohne Kenntnis der Sprache versteht (Angelo 28.11.2026: Diagramm
 * „pseudocode-artig“ für Neulinge, Manager und den Alltag).
 *
 * <p>Ohne KI (oder wenn sie scheitert) bleibt die bisherige Darstellung und wird nur um Fakten aus dem Quelltext
 * ergänzt: die Bedingung einer Verzweigung und der Zweig (etwa {@code = 'SW'}), unter dem ein Aufruf steht. Mit KI
 * ({@link BlockLabelSummarizer}) beschreibt das Modell
 * <ul>
 *   <li>jeden zusammengefassten Block anhand seiner vollständigen Quelltextzeilen (auch MOVE, COMPUTE …, die die
 *       Gliederung nicht kennt),</li>
 *   <li>jede Verzweigung als einfache Frage,</li>
 *   <li>jedes aufgerufene Programm (CALLNAT, CALL, FETCH): dessen Quelltext wird über den {@link SourceResolver}
 *       geladen; das Modell sagt, was es tut und wozu es an dieser Stelle aufgerufen wird. Seine eigenen Aufrufe
 *       stehen darunter („ruft: …“), damit man sieht, was danach passiert.</li>
 * </ul>
 * Darf blockieren (Auflösen, KI); nie aus dem EDT aufrufen.
 */
public final class FlowLabels {

    /**
     * Länge und Zeilenbreite der Beschriftungen (Einstellungen › Diagramme).
     *
     * @param length       Höchstlänge der Beschriftung im Knoten in Zeichen (Überschrift eines Blocks, Frage, Zweig,
     *                     Zweck eines Aufrufs)
     * @param detailLength Höchstlänge einer Erklärung in der Erklärbox neben dem Diagramm (wenige Sätze)
     * @param lineWidth    so viele Zeichen höchstens je Zeile im Knoten, danach wird am Wortende umbrochen
     */
    public record Layout(int length, int detailLength, int lineWidth) {

        /** Vorgabe, solange nichts eingestellt ist. */
        public static final Layout DEFAULT = new Layout(60, 300, 30);

        public Layout {
            if (length < 1 || detailLength < 1 || lineWidth < 1) {
                throw new IllegalArgumentException("Länge und Zeilenbreite müssen positiv sein");
            }
        }
    }

    /**
     * Gemeinsame Regel aller Erklärungen (Angelo 28.11.2026): den Code erklären, nicht nacherzählen. Eine
     * Nacherzählung Anweisung für Anweisung („kopiert X nach Y, setzt Z auf FALSE“) ist nur neuer Programmcode in
     * Worten.
     */
    static final String EXPLAIN_RULE = "Erkläre den fachlichen Zweck: was dieser Code für das Geschäft oder den Ablauf "
            + "bewirkt und warum. Erzähle den Code NICHT Anweisung für Anweisung nach (nicht \"kopiert X nach Y\", "
            + "nicht \"setzt Z auf FALSE\"), nenne keine Befehle, keine Variablen- oder Feldnamen und keine Werte, "
            + "außer sie tragen die fachliche Bedeutung.";
    /** Überschrift eines Blocks im Knoten. */
    static final String BLOCK_TITLE_PURPOSE = "Abschnitt eines Großrechner-Programms. Gib eine kurze Überschrift in "
            + "einfachem Deutsch, wozu dieser Abschnitt da ist (z. B. \"Zugang prüfen\" oder \"Sendung "
            + "vorbereiten\"). " + EXPLAIN_RULE;
    /** Erklärung eines Blocks (Erklärbox neben dem Diagramm). */
    static final String BLOCK_PURPOSE = "Abschnitt eines Großrechner-Programms. Erkläre einem Einsteiger, der diese "
            + "Sprache nicht kennt, in wenigen einfachen Sätzen, wozu dieser Abschnitt da ist und was er im Ablauf "
            + "erreicht. " + EXPLAIN_RULE;
    static final String DECISION_PURPOSE = "Verzweigung eines Großrechner-Programms. Formuliere die Entscheidung als "
            + "kurze, verständliche Frage für Einsteiger (z. B. \"Welche Satzart liegt vor?\" oder \"Darf der "
            + "Benutzer das Programm nutzen?\"). Nur die Frage, ohne Feldnamen und Werte, außer sie tragen die "
            + "fachliche Bedeutung.";
    /** Überschrift eines JCL-Schritts im Knoten. */
    static final String STEP_TITLE_PURPOSE = "Schritt eines JCL-Jobs mit seinen Dateien (DD). Gib eine kurze "
            + "Überschrift in einfachem Deutsch, wozu dieser Schritt da ist (z. B. \"Kundendaten sortieren\"). "
            + EXPLAIN_RULE;
    static final String STEP_PURPOSE = "Schritt eines JCL-Jobs mit seinen Dateien (DD). Erkläre einem Einsteiger, "
            + "der JCL nicht kennt, in wenigen einfachen Sätzen, wozu dieser Schritt da ist: welche Daten er "
            + "verarbeitet, was dabei fachlich herauskommt und wofür das Ergebnis gebraucht wird. " + EXPLAIN_RULE;
    /** Zweck eines aufgerufenen Programms im Knoten. */
    static final String CALLEE_TITLE_PURPOSE = "Quelltext des aufgerufenen Programms %s. Aufruf an dieser Stelle: %s%s. "
            + "Gib in wenigen Worten an, wozu es hier aufgerufen wird (z. B. \"Satzart SW verbuchen\"). "
            + EXPLAIN_RULE;
    static final String CALLEE_PURPOSE = "Quelltext des aufgerufenen Programms %s. Aufruf an dieser Stelle: %s%s. "
            + "Erkläre einem Einsteiger in wenigen einfachen Sätzen, wozu dieses Programm da ist, warum es an dieser "
            + "Stelle aufgerufen wird und was dadurch im Ablauf erreicht wird. " + EXPLAIN_RULE;

    /**
     * Was zu einem Knoten gehört: eine Überschrift, die Quelltextzeilen und mit KI die Erklärung, die auch in der
     * Erklärbox steht. Beim Klick auf den Knoten erscheint das im Detailbereich.
     *
     * @param heading     Überschrift (etwa „Zeilen 12–30“ oder „SATZSW – aufgerufen mit …“)
     * @param code        Quelltext, den der Knoten zusammenfasst
     * @param purpose     Zweck der Erklärung (für eine Nachfrage im Chat)
     * @param number      Nummer der Erklärung in der Erklärbox, 0 ohne Erklärung
     * @param explanation Erklärung der KI, {@code null} ohne
     */
    public record Detail(String heading, String code, String purpose, int number, String explanation) {
    }

    private static final Pattern BRANCH = Pattern.compile(
            "^\\s*(VALUE|WHEN|NONE|ANY|ALL|ELSE)\\b\\s*(?:VALUE\\b\\s*)?(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPEN = Pattern.compile(
            "^\\s*(IF|DECIDE|FOR|REPEAT|READ|FIND|HISTOGRAM|AT|ON)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOSE = Pattern.compile(
            "^\\s*END-(IF|DECIDE|FOR|REPEAT|READ|FIND|HISTOGRAM|WORK|ENDDATA|BREAK|START|ERROR)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DECIDE_ON = Pattern.compile(
            "^\\s*DECIDE\\s+ON\\s+(?:FIRST\\s+|EVERY\\s+)?(?:VALUE\\s+)?(?:OF\\s+)?(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern IF = Pattern.compile("^\\s*IF\\s+(.+?)\\s*(?:THEN)?\\s*$",
            Pattern.CASE_INSENSITIVE);

    private final String[] lines;
    private final SourceResolver resolver;
    private final BlockLabelSummarizer ai;
    private final Layout layout;
    private final Map<String, String> sources = new java.util.LinkedHashMap<>();
    private final Map<String, Detail> details = new java.util.LinkedHashMap<>();

    private FlowLabels(String content, SourceResolver resolver, BlockLabelSummarizer ai, Layout layout) {
        this.lines = content == null ? new String[0] : content.split("\\r?\\n", -1);
        this.resolver = resolver;
        this.ai = ai != null && enabled(ai) ? ai : null;
        this.layout = layout == null ? Layout.DEFAULT : layout;
    }

    /**
     * @param content  Quelltext des Diagramms; {@code null} = nur die Gliederung (bisheriges Verhalten)
     * @param resolver lädt aufgerufene Programme; {@code null} = keine Auflösung
     * @param ai       KI-Beschreibung; {@code null} oder ausgeschaltet = nur Fakten aus dem Quelltext
     * @param layout   Länge und Zeilenbreite; {@code null} = {@link Layout#DEFAULT}
     */
    public static FlowLabels of(String content, SourceResolver resolver, BlockLabelSummarizer ai, Layout layout) {
        return new FlowLabels(content, resolver, ai, layout);
    }

    /** Ohne Quelltext, Auflösung und KI: die Darstellung, wie sie die Gliederung allein hergibt. */
    static FlowLabels outlineOnly(BlockLabelSummarizer ai, Layout layout) {
        return new FlowLabels(null, null, ai, layout);
    }

    /** Ob die KI an diesem Diagramm mitschreibt. */
    public boolean ai() {
        return ai != null && enabled(ai);
    }

    Layout layout() {
        return layout;
    }

    // ---- Blöcke ----------------------------------------------------------------------------------------------------

    /**
     * Code eines Blocks: die Quelltextzeilen {@code from}…{@code to} (1-basiert, einschließlich), ohne Quelltext die
     * Zeilen der Elemente.
     */
    String blockCode(List<JclElement> details, int from, int to) {
        if (lines.length > 0 && from >= 1 && to >= from) {
            String code = range(from, Math.min(to, lines.length));
            if (!code.isBlank()) {
                return code;
            }
        }
        StringBuilder raw = new StringBuilder();
        for (JclElement e : details) {
            if (e != null && e.getRawText() != null && !e.getRawText().isBlank()) {
                raw.append(e.getRawText().replace("\r", "").trim()).append('\n');
            }
        }
        return raw.toString();
    }

    /** Ob die Zeilen ausführbaren Code enthalten (nicht nur Leerzeilen, Kommentare und END-…). */
    boolean hasStatements(int from, int to) {
        for (int line = Math.max(1, from); line <= Math.min(to, lines.length); line++) {
            String text = lines[line - 1].trim();
            if (!text.isEmpty() && !comment(text) && !text.toUpperCase(Locale.ROOT).startsWith("END-")
                    && !text.equalsIgnoreCase("END") && !BRANCH.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Merkt sich, was beim Klick auf den Knoten {@code id} im Detailbereich steht (Zeilen {@code from}…{@code to}),
     * und gibt mit KI die kurze Überschrift für den Knoten, sonst {@code null}.
     */
    String titleLines(String id, String heading, int from, int to, String titlePurpose, String detailPurpose) {
        if (lines.length == 0 || from < 1 || to < from) {
            return null;
        }
        return title(id, heading, range(from, Math.min(to, lines.length)), titlePurpose, detailPurpose);
    }

    /** Wie {@link #titleLines} mit dem Code selbst. */
    String title(String id, String heading, String code, String titlePurpose, String detailPurpose) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String title = ask(code, titlePurpose, false);
        // Beide Fragen immer stellen, auch wenn die Überschrift ausbleibt: In der Sammelrunde für parallele Anfragen
        // (ParallelLabels) antwortet noch keine, gestellt werden müssen trotzdem beide.
        String explanation = detailPurpose == null ? null : explain(code, detailPurpose);
        int number = 0;
        if (title != null && explanation != null) {
            Detail known = id == null ? null : details.get(id);
            number = known != null && known.number() > 0 ? known.number()
                    : details.values().stream().mapToInt(Detail::number).max().orElse(0) + 1;
            title = mark(number) + " " + title;
        }
        if (id != null) {
            details.put(id, new Detail(heading, code.strip(), detailPurpose, number,
                    number == 0 ? null : explanation));
        }
        return title;
    }

    /** Die Erklärung der KI zum Zweck {@code purpose} in höchstens {@link Layout#detailLength()} Zeichen. */
    private String explain(String code, String purpose) {
        if (!ai()) {
            return null;
        }
        try {
            String answer = ai.describe(code, layout.detailLength(), purpose);
            return answer == null || answer.isBlank() ? null : answer.trim();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Nummer einer Erklärung: ① bis ⑳, danach (21), (22) … */
    public static String mark(int number) {
        return number >= 1 && number <= 20 ? String.valueOf((char) ('\u2460' + number - 1)) : "(" + number + ")";
    }

    /**
     * Die Erklärbox (Angelo 28.11.2026): eine freistehende gelbe Box neben dem Diagramm, ohne Linien zu den Knoten.
     * Jede Erklärung trägt die Nummer, die auch vor dem Titel ihres Knotens steht. Leer, wenn es keine Erklärung gibt
     * (ohne KI oder wenn die KI nicht antwortete).
     */
    public String notes() {
        List<Detail> explained = details.values().stream().filter(d -> d.number() > 0)
                .sorted(java.util.Comparator.comparingInt(Detail::number)).toList();
        if (explained.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        out.append("  classDef erklaerung fill:#fff8c4,stroke:#c9b458,color:#333\n");
        out.append("  subgraph erklaerungen [\"Erklärungen\"]\n");
        out.append("    direction TB\n");
        for (Detail d : explained) {
            out.append("    erklaerung_").append(d.number()).append("[\"")
                    .append(noteText(mark(d.number()) + " " + d.explanation())).append("\"]:::erklaerung\n");
        }
        // Unsichtbare Verbindungen nur innerhalb der Box: hält die Erklärungen in ihrer Reihenfolge untereinander
        for (int i = 1; i < explained.size(); i++) {
            out.append("    erklaerung_").append(explained.get(i - 1).number()).append(" ~~~ erklaerung_")
                    .append(explained.get(i).number()).append('\n');
        }
        out.append("  end\n");
        return out.toString();
    }

    private static String noteText(String text) {
        return text.replace("#", "＃").replace("\"", "＂").replace("&", "＆").replace("<", "＜").replace(">", "＞")
                .replace("|", "｜").replace("`", "｀").replace("\\", "＼").replace("[", "［").replace("]", "］")
                .replaceAll("\\s+", " ").trim();
    }

    /** Was beim Klick auf einen Knoten im Detailbereich steht, je Mermaid-ID des Knotens. */
    public Map<String, Detail> details() {
        return java.util.Collections.unmodifiableMap(details);
    }

    /** Die Verzweigung der Zeilen {@code from}…{@code to} als Frage (KI), sonst {@code fact}. */
    String question(String fact, int from, int to) {
        if (!ai() || lines.length == 0 || from < 1) {
            return fact;
        }
        String question = ask(range(from, Math.min(Math.max(from, to), lines.length)), DECISION_PURPOSE, false);
        return question == null ? fact : question;
    }


    // ---- Verzweigungen ---------------------------------------------------------------------------------------------

    /** Beschriftung einer Verzweigung (IF, DECIDE): mit KI eine Frage, sonst die Bedingung aus dem Quelltext. */
    String decision(JclElement e) {
        String fallback = decisionFact(e);
        if (!ai()) {
            return fallback;
        }
        int line = e.getLineNumber();
        String code = lines.length >= line && line >= 1 ? range(line, closing(line)) : e.getRawText();
        String question = ask(code, DECISION_PURPOSE, false);
        return question == null ? fallback : question;
    }

    /** Die Bedingung, wie sie im Quelltext steht, kurz und als Frage. */
    String decisionFact(JclElement e) {
        String name = e.getName() != null ? e.getName() : e.getType().getDisplayName();
        String raw = e.getRawText() == null ? "" : e.getRawText().trim();
        if (e.getType() == JclElementType.NAT_IF_BLOCK || e.getType() == JclElementType.IF) {
            Matcher m = IF.matcher(raw);
            if (m.find()) {
                return cut(m.group(1).trim()) + "?";
            }
        }
        if (e.getType() == JclElementType.NAT_DECIDE) {
            Matcher m = DECIDE_ON.matcher(raw);
            if (m.find()) {
                return "Wert von " + m.group(1) + "?";
            }
            if (raw.toUpperCase(Locale.ROOT).startsWith("DECIDE FOR")) {
                return "Welche Bedingung trifft zu?";
            }
        }
        return name;
    }

    /**
     * Der Zweig, unter dem die Zeile {@code line} steht, als Pfeilbeschriftung in Worten: {@code #SATZART = 'SW'}
     * (VALUE im DECIDE ON), die Bedingung (WHEN), „sonst“ (NONE), „wenn …“ / „wenn nicht …“ (im IF / ELSE). Die
     * Bedingung steht immer dabei, auch wenn der Pfeil nicht direkt an der Raute beginnt. {@code null}, wenn die
     * Zeile in keinem Zweig steht oder der Quelltext fehlt.
     */
    String branch(int line) {
        int depth = 0;
        String clause = null;
        String rest = "";
        for (int i = Math.min(line, lines.length) - 1; i >= 1; i--) {
            String text = lines[i - 1];
            String trimmed = text.trim();
            if (comment(trimmed)) {
                continue;
            }
            if (CLOSE.matcher(text).find()) {
                depth++;
                continue;
            }
            if (OPEN.matcher(text).find()) {
                if (depth > 0) {
                    depth--;
                    continue;
                }
                String upper = trimmed.toUpperCase(Locale.ROOT);
                if (upper.startsWith("IF") || upper.startsWith("DECIDE")) {
                    return describeBranch(clause, rest, trimmed);
                }
                // Schleifen und Lesebefehle sind kein Zweig: weiter nach außen zur umgebenden Verzweigung
                clause = null;
                rest = "";
                continue;
            }
            if (depth == 0 && clause == null) {
                Matcher m = BRANCH.matcher(text);
                if (m.find()) {
                    clause = m.group(1).toUpperCase(Locale.ROOT);
                    rest = m.group(2).trim();
                    if (clause.equals("WHEN") && rest.matches("(?i)(NONE|ANY|ALL)\\b.*")) {
                        clause = rest.split("\\s+")[0].toUpperCase(Locale.ROOT);
                        rest = "";
                    }
                }
            }
        }
        return null;
    }

    /** Ein Zweig in Worten aus Schlüsselwort ({@code null} = keines), dessen Rest und der öffnenden Zeile. */
    private String describeBranch(String clause, String rest, String opener) {
        String upper = opener.toUpperCase(Locale.ROOT);
        if (upper.startsWith("IF")) {
            Matcher m = IF.matcher(opener);
            String condition = m.find() ? m.group(1).trim() : opener.substring(2).trim();
            return cut(("ELSE".equals(clause) ? "wenn nicht " : "wenn ") + condition);
        }
        if (!upper.startsWith("DECIDE") || clause == null) {
            return null;
        }
        return switch (clause) {
            case "VALUE" -> {
                if (rest.isEmpty()) {
                    yield null;
                }
                Matcher on = DECIDE_ON.matcher(opener);
                yield cut(on.find() ? on.group(1) + " = " + rest : "= " + rest);
            }
            case "WHEN" -> rest.isEmpty() ? null : cut(rest);
            case "NONE", "ELSE" -> "sonst";
            case "ANY" -> "bei jedem Treffer";
            case "ALL" -> "wenn alle zutreffen";
            default -> null;
        };
    }

    // ---- Aufgerufene Programme -------------------------------------------------------------------------------------

    /**
     * Was unter dem Namen eines aufgerufenen Programms steht: mit KI in wenigen Worten, wozu es hier aufgerufen wird;
     * mit geladenem Quelltext seine eigenen Aufrufe („ruft: …“). Der Quelltext des Programms kommt in den
     * Detailbereich des Knotens {@code id}. {@code null}, wenn nichts bekannt ist.
     */
    String callee(String id, String target, JclElement call, String branch) {
        return callee(id, target, call == null || call.getRawText() == null ? null : call.getRawText().trim(),
                branch);
    }

    /** Wie oben mit dem Text der Aufrufstelle ({@code null} = nur der Name). */
    String callee(String id, String target, String callText, String branch) {
        String source = source(target);
        if (source == null) {
            return null;
        }
        String where = callText == null || callText.isBlank() ? target : callText;
        String condition = branch == null ? "" : " (Zweig: " + branch + ")";
        List<String> parts = new ArrayList<>();
        String text = title(id, target + " – aufgerufen mit " + where + condition, source,
                String.format(Locale.ROOT, CALLEE_TITLE_PURPOSE, target, where, condition),
                String.format(Locale.ROOT, CALLEE_PURPOSE, target, where, condition));
        if (text != null) {
            parts.add(text);
        }
        List<String> next = calls(source, target);
        if (!next.isEmpty()) {
            parts.add(cut("ruft: " + String.join(", ", next)));
        }
        return parts.isEmpty() ? null : String.join("\n", parts);
    }

    private String source(String target) {
        if (resolver == null || target == null || target.isBlank()) {
            return null;
        }
        String key = target.toUpperCase(Locale.ROOT);
        if (sources.containsKey(key)) {
            return sources.get(key);
        }
        String source;
        try {
            source = resolver.resolve(target);
        } catch (RuntimeException e) {
            source = null;
        }
        if (source != null && source.isBlank()) {
            source = null;
        }
        sources.put(key, source);
        return source;
    }

    /** Externe Aufrufe eines Natural-Quelltexts, jeder Name einmal, in Reihenfolge. */
    static List<String> calls(String source, String name) {
        Set<String> found = new LinkedHashSet<>();
        try {
            for (JclElement e : new NaturalParser().parse(source, name).getElements()) {
                JclElementType t = e.getType();
                if (t == JclElementType.NAT_CALLNAT || t == JclElementType.NAT_CALL
                        || t == JclElementType.NAT_FETCH) {
                    String target = e.getParameter("TARGET") != null ? e.getParameter("TARGET") : e.getName();
                    if (target != null && !target.isBlank() && !target.equalsIgnoreCase(name)) {
                        found.add(target.toUpperCase(Locale.ROOT));
                    }
                }
            }
        } catch (RuntimeException e) {
            return List.of();
        }
        return List.copyOf(found);
    }

    // ---- Hilfen ----------------------------------------------------------------------------------------------------

    private String ask(String code, String purpose, boolean sentence) {
        if (!ai() || code == null || code.isBlank()) {
            return null;
        }
        try {
            String answer = sentence ? ai.explain(code, layout.length(), purpose)
                    : ai.summarize(code, layout.length(), purpose);
            return answer == null || answer.isBlank() ? null : answer.trim();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean enabled(BlockLabelSummarizer ai) {
        try {
            return ai.enabled();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Zeile der passenden END-…-Zeile zur öffnenden Zeile {@code line}, sonst das Dateiende. */
    private int closing(int line) {
        int depth = 0;
        for (int i = line; i <= lines.length; i++) {
            String text = lines[i - 1];
            if (comment(text.trim())) {
                continue;
            }
            if (i > line && OPEN.matcher(text).find()) {
                depth++;
            } else if (CLOSE.matcher(text).find()) {
                if (depth == 0) {
                    return i;
                }
                depth--;
            }
        }
        return lines.length;
    }

    private String range(int from, int to) {
        StringBuilder out = new StringBuilder();
        for (int i = from; i <= to && i <= lines.length; i++) {
            out.append(lines[i - 1]).append('\n');
        }
        return out.toString();
    }

    private static boolean comment(String trimmed) {
        return trimmed.startsWith("*") || trimmed.startsWith("/*");
    }

    private String cut(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() > layout.length() ? flat.substring(0, layout.length() - 1) + "…" : flat;
    }

    /** Umbricht einen Text am Wortende auf {@link Layout#lineWidth()} Zeichen je Zeile (Zeilen mit {@code \n}). */
    String wrap(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String paragraph : text.split("\n")) {
            if (out.length() > 0) {
                out.append('\n');
            }
            int lineLength = 0;
            for (String word : paragraph.trim().split("\\s+")) {
                if (word.isEmpty()) {
                    continue;
                }
                if (lineLength > 0 && lineLength + 1 + word.length() > layout.lineWidth()) {
                    out.append('\n');
                    lineLength = 0;
                } else if (lineLength > 0) {
                    out.append(' ');
                    lineLength++;
                }
                out.append(word);
                lineLength += word.length();
            }
        }
        return out.toString();
    }
}
