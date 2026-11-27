package com.aresstack.spaceharvester.app.ui.content;

import javax.swing.Timer;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Element;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;
import javax.swing.text.Utilities;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hebt beim Vorlesen den gerade erklingenden Satz hervor (Angelo 23.11.2026 im Chat, 27.11.2026 auch in der
 * Leseansicht: „das müsste eigentlich nicht neu entwickelt werden, sondern das können wir da wiederverwenden“).
 * Ungefähr und nie Wort für Wort, damit nichts flackert; ein Aufzählungspunkt zählt als Abschnitt.
 *
 * <p>Die Sprachausgabe meldet je Stück, welche Zeichen ungefähr erklingen und wie lange
 * ({@link com.aresstack.spaceharvester.app.speech.ReadAloudBinding.Progress}); die Sätze des Stücks werden anteilig
 * zu ihrer Länge nacheinander markiert, in den angezeigten Textblöcken wiedergefunden (Leerraum zählt nicht) und im
 * Blick gehalten. Gemeinsam für Chat ({@link ChatReadAloud}) und Leseansicht ({@link PageReadAloud}). Alles auf dem
 * EDT.
 */
final class ReadAloudMarker {

    /** Satzgrenzen im gesprochenen Text: hinter Satzzeichen mit Leerraum und an jedem Zeilenwechsel. */
    private static final Pattern BOUNDARY = Pattern.compile("(?<=[.!?…:;])[ \\t]+|\\n+");
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[.!?…:;,]+$");
    /** So viele Zeichen vom Satzanfang genügen, wenn der ganze Satz in der Anzeige anders steht. */
    private static final int PROBE = 32;

    /** Die angezeigten Textblöcke in Lesereihenfolge, in denen der Satz gesucht wird. */
    private final Supplier<List<JTextComponent>> blocks;
    private final Highlighter.HighlightPainter painter;
    /** Die Sätze des gerade erklingenden Stücks; der Takt schaltet sie anteilig zur Dauer weiter. */
    private final Timer steps = new Timer(0, event -> nextStep());
    private List<int[]> pending = List.of();
    private long pendingMillis;
    private int pendingCharacters;
    /** Der ganze vorlesbare Text, ab wo vorgelesen wird und die Längen vor und nach der Bereinigung. */
    private String spoken = "";
    private int base;
    private int sourceLength;
    private int cleanedLength;
    /** Anfang des gerade markierten Satzes im vorlesbaren Text (für das Springen). */
    private int current;
    /** Die Hervorhebung: Textkomponente und Kennung; {@code null} ohne. */
    private JTextComponent marked;
    private Object mark;
    private int markEnd;
    private int markStart;

    /**
     * @param blocks  liefert die angezeigten Textblöcke des Vorgelesenen in Lesereihenfolge
     * @param painter wie markiert wird (gelbe Fläche wie im Podcast oder gelber Strich auf farbigem Grund)
     */
    ReadAloudMarker(Supplier<List<JTextComponent>> blocks, Highlighter.HighlightPainter painter) {
        this.blocks = Objects.requireNonNull(blocks, "blocks");
        this.painter = Objects.requireNonNull(painter, "painter");
        steps.setRepeats(false);
    }

    /** Gelbe Fläche wie die Marke des Podcasts (auf hellem Grund). */
    static Highlighter.HighlightPainter fill() {
        return new DefaultHighlighter.DefaultHighlightPainter(PagePodcastPictures.MARK);
    }

    /** Gelber Strich unter der Zeile (auf farbigem Grund, etwa der Chat-Blase). */
    static Highlighter.HighlightPainter underline() {
        return new Underline(PagePodcastPictures.MARK);
    }

    /**
     * Ein neues Vorlesen beginnt: vorgelesen wird {@code spoken} ab {@code start}; an die Sprachausgabe ging dieser
     * Ausschnitt mit {@code cleanedLength} Zeichen (nach einer Bereinigung, sonst seine eigene Länge).
     */
    void start(String spoken, int start, int cleanedLength) {
        reset();
        this.spoken = spoken == null ? "" : spoken;
        this.base = Math.max(0, Math.min(start, this.spoken.length()));
        this.sourceLength = this.spoken.length() - base;
        this.cleanedLength = Math.max(0, cleanedLength);
        this.current = base;
    }

    /** Der Textblock der Marke, {@code null} ohne (Ausgangspunkt der Suche nach einem Sprung). */
    JTextComponent shownBlock() {
        return marked;
    }

    /**
     * Ab wo in der Anzeige nach einem Sprung zu {@code target} gesucht wird: vorwärts hinter der Marke, rückwärts
     * ungefähr so weit vor ihr, wie zurückgesprungen wird (mit Spielraum, die Anzeige ist nicht zeichengleich). So
     * landet die Marke nicht in einem gleichlautenden Inhaltsverzeichnis.
     */
    int shownAnchor(int target) {
        if (marked == null || mark == null) {
            return 0;
        }
        if (target >= current) {
            return markEnd;
        }
        int back = current - target;
        return Math.max(0, markStart - back - back / 4 - PROBE);
    }

    /** Der ganze vorlesbare Text des laufenden Vorlesens. */
    String spoken() {
        return spoken;
    }

    /**
     * Wo das Vorlesen nach einem Sprung um {@code sentences} Sätze (negativ = zurück) beginnt: ab dem Anfang des gerade
     * markierten Satzes gezählt, begrenzt auf den Text.
     */
    int jumpTarget(int sentences) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        Matcher matcher = BOUNDARY.matcher(spoken);
        while (matcher.find()) {
            if (matcher.end() < spoken.length()) {
                starts.add(matcher.end());
            }
        }
        int at = 0;
        for (int i = 0; i < starts.size(); i++) {
            if (starts.get(i) <= current) {
                at = i;
            }
        }
        return starts.get(Math.max(0, Math.min(starts.size() - 1, at + sentences)));
    }

    /**
     * Wie {@link #start(String, int, int)}; der erste Satz wird ab {@code offset} in {@code block} gesucht, nicht vom
     * Anfang. Nach einem Doppelklick auf eine Überschrift, die auch im Inhaltsverzeichnis steht, markiert die
     * Hervorhebung so die angeklickte Stelle und rollt nicht zum Verzeichnis zurück.
     */
    void start(String spoken, int start, int cleanedLength, JTextComponent block, int offset) {
        start(spoken, start, cleanedLength);
        if (block != null) {
            marked = block; // ohne Marke: nur der Ausgangspunkt der Suche
            markEnd = Math.max(0, offset);
        }
    }

    /**
     * Ein Stück erklingt: ungefähr die Zeichen {@code [from, to)} des an die Sprachausgabe gegangenen Texts, etwa
     * {@code millis} lang. Seine Sätze werden nacheinander hervorgehoben, jeder anteilig zu seiner Länge.
     */
    void playing(int from, int to, long millis) {
        double ratio = cleanedLength == 0 ? 1 : (double) sourceLength / cleanedLength;
        int start = base + (int) Math.round(from * ratio);
        int end = base + (int) Math.round(to * ratio);
        List<int[]> sentences = sentences(spoken, start, end);
        steps.stop();
        pending = new ArrayList<>(sentences);
        pendingMillis = Math.max(0, millis);
        pendingCharacters = sentences.stream().mapToInt(range -> range[1] - range[0]).sum();
        nextStep();
    }

    /** Angehalten: die Marke wartet mit. */
    void pause() {
        steps.stop();
    }

    /** Fortgesetzt: die Marke schaltet weiter. */
    void resume() {
        if (!pending.isEmpty()) {
            steps.restart();
        }
    }

    /** Keine Marke und kein Weiterschalten mehr (Vorlesen vorbei oder neu begonnen). */
    void reset() {
        steps.stop();
        pending = List.of();
        unmark();
    }

    private void nextStep() {
        if (pending.isEmpty()) {
            return;
        }
        int[] sentence = pending.remove(0);
        current = sentence[0];
        markSentence(spoken.substring(sentence[0], sentence[1]));
        if (!pending.isEmpty() && pendingCharacters > 0) {
            int delay = (int) Math.min(Integer.MAX_VALUE,
                    pendingMillis * (sentence[1] - sentence[0]) / pendingCharacters);
            steps.setInitialDelay(Math.max(1, delay));
            steps.restart();
        }
    }

    /**
     * Die Sätze zwischen {@code start} und {@code end}: beide Enden rücken an die nächste Satzgrenze, denn die
     * Stände sind nur ungefähr.
     */
    static List<int[]> sentences(String text, int start, int end) {
        List<Integer> bounds = new ArrayList<>();
        bounds.add(0);
        Matcher matcher = BOUNDARY.matcher(text);
        while (matcher.find()) {
            bounds.add(matcher.end());
        }
        bounds.add(text.length());
        int from = nearest(bounds, Math.max(0, Math.min(start, text.length())));
        int to = nearest(bounds, Math.max(0, Math.min(end, text.length())));
        if (to <= from) {
            to = bounds.stream().filter(bound -> bound > from).findFirst().orElse(text.length());
        }
        List<int[]> sentences = new ArrayList<>();
        int previous = from;
        for (int bound : bounds) {
            if (bound > from && bound <= to) {
                if (!text.substring(previous, bound).isBlank()) {
                    sentences.add(new int[] {previous, bound});
                }
                previous = bound;
            }
        }
        return sentences;
    }

    private static int nearest(List<Integer> bounds, int position) {
        int best = 0;
        for (int bound : bounds) {
            if (Math.abs(bound - position) < Math.abs(best - position)) {
                best = bound;
            }
        }
        return best;
    }

    /** Hebt den Satz in den Textblöcken hervor; steht er dort nicht wiederzufinden, bleibt die alte Marke. */
    private void markSentence(String sentence) {
        String needle = TRAILING_PUNCTUATION.matcher(ReadAloudStart.Collapsed.of(sentence).text.trim()).replaceAll("")
                .trim();
        if (needle.isEmpty()) {
            return;
        }
        List<JTextComponent> texts = blocks.get();
        if (texts == null || texts.isEmpty()) {
            return;
        }
        int first = marked == null ? 0 : Math.max(0, texts.indexOf(marked));
        for (boolean probe : new boolean[] {false, true}) {
            String wanted = probe ? probe(needle) : needle;
            if (wanted.isEmpty()) {
                continue;
            }
            for (int i = 0; i < texts.size(); i++) {
                JTextComponent block = texts.get((first + i) % texts.size());
                int from = block == marked && i == 0 ? markEnd : 0;
                if (mark(block, wanted, from, probe, needle.length())
                        || (from > 0 && mark(block, wanted, 0, probe, needle.length()))) {
                    return;
                }
            }
        }
    }

    /** Der Anfang eines Satzes bis zur letzten Wortgrenze in {@link #PROBE} Zeichen; leer, wenn er kürzer ist. */
    private static String probe(String needle) {
        if (needle.length() <= PROBE) {
            return "";
        }
        int cut = needle.lastIndexOf(' ', PROBE);
        return (cut > 8 ? needle.substring(0, cut) : needle.substring(0, PROBE)).trim();
    }

    private boolean mark(JTextComponent block, String wanted, int from, boolean probe, int length) {
        String content = slice(block, 0, block.getDocument().getLength());
        if (content == null) {
            return false;
        }
        ReadAloudStart.Collapsed shown = ReadAloudStart.Collapsed.of(content);
        int at = shown.text.indexOf(wanted, shown.collapsedIndex(from));
        if (at < 0) {
            return false;
        }
        int start = shown.original(at);
        int end = shown.original(at + (probe ? length : wanted.length()));
        if (probe) {
            // Nur der Anfang passte: bis höchstens zum Ende seines Absatzes.
            Element paragraph = Utilities.getParagraphElement(block, start);
            end = Math.min(Math.max(end, start + wanted.length()), paragraph.getEndOffset() - 1);
        }
        end = Math.max(start + 1, Math.min(end, content.length()));
        unmark();
        try {
            mark = block.getHighlighter().addHighlight(start, end, painter);
        } catch (BadLocationException e) {
            return false;
        }
        marked = block;
        markStart = start;
        markEnd = end;
        follow(block, start, end);
        return true;
    }

    /** Die Marke bleibt im Blick, ohne die Ansicht zu verreißen: gerollt wird nur, wenn sie draußen liegt. */
    private static void follow(JTextComponent block, int start, int end) {
        try {
            Rectangle first = block.modelToView2D(start).getBounds();
            Rectangle last = block.modelToView2D(Math.max(start, end - 1)).getBounds();
            Rectangle area = first.union(last);
            Rectangle visible = block.getVisibleRect();
            if (!visible.contains(area)) {
                block.scrollRectToVisible(area);
            }
        } catch (BadLocationException | RuntimeException e) {
            // ohne Lage keine Bewegung
        }
    }

    private void unmark() {
        if (marked != null && mark != null) {
            marked.getHighlighter().removeHighlight(mark);
        }
        marked = null;
        mark = null;
        markEnd = 0;
        markStart = 0;
    }

    /** Der hervorgehobene Text; leer ohne (für Tests). */
    String marked() {
        if (marked == null || mark == null) {
            return "";
        }
        for (Highlighter.Highlight highlight : marked.getHighlighter().getHighlights()) {
            if (highlight == mark) {
                String text = slice(marked, highlight.getStartOffset(), highlight.getEndOffset());
                return text == null ? "" : text;
            }
        }
        return "";
    }

    static String slice(JTextComponent text, int start, int end) {
        try {
            int length = text.getDocument().getLength();
            int from = Math.max(0, Math.min(start, length));
            int to = Math.max(from, Math.min(end, length));
            return text.getDocument().getText(from, to - from);
        } catch (BadLocationException e) {
            return null;
        }
    }

    /**
     * Unterstreicht den Bereich mit einem dicken gelben Strich (Angelo 23.11.2026: „meinetwegen mit einer
     * Unterkringelung … oder notfalls einfach wieder diese gelbe Hervorhebung“). Eine gelbe Fläche ließe weiße Schrift
     * auf der grünen Blase verschwinden.
     */
    static final class Underline extends DefaultHighlighter.DefaultHighlightPainter {

        static final int THICKNESS = 3;

        Underline(java.awt.Color color) {
            super(color);
        }

        @Override
        public java.awt.Shape paintLayer(java.awt.Graphics g, int start, int end, java.awt.Shape bounds,
                                         JTextComponent text, javax.swing.text.View view) {
            Rectangle area;
            if (start == view.getStartOffset() && end == view.getEndOffset()) {
                area = bounds instanceof Rectangle rectangle ? rectangle : bounds.getBounds();
            } else {
                try {
                    java.awt.Shape shape = view.modelToView(start, javax.swing.text.Position.Bias.Forward, end,
                            javax.swing.text.Position.Bias.Backward, bounds);
                    area = shape instanceof Rectangle rectangle ? rectangle : shape.getBounds();
                } catch (BadLocationException e) {
                    return null;
                }
            }
            g.setColor(getColor());
            g.fillRect(area.x, area.y + area.height - THICKNESS, Math.max(1, area.width), THICKNESS);
            return area;
        }
    }
}
