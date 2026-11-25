package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.speech.ReadAloudBinding;
import com.aresstack.spaceharvester.app.ui.controls.PodcastOrb;
import com.aresstack.spaceharvester.app.ui.markdown.AssistantMarkdownMessage;
import com.aresstack.spaceharvester.app.ui.markdown.MarkdownSpeech;
import com.aresstack.spaceharvester.application.podcast.SpeakableText;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;
import javax.swing.text.Utilities;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vorlesen im Chat (Angelo 23.11.2026: „Im Chat fehlt auch noch die Vorlesefunktion … das wollen wir nicht neu
 * machen“). Der Ablauf ist der des Verlaufs im Enterprise AI Client ({@code ChatTranscriptPanel}): Play liest die
 * letzte Antwort vor und bleibt aktiv, jede neue Antwort wird dann vorgelesen, bis der Knopf es beendet. Der Knopf und
 * das Weiterlesen ab einem Wort sind die des Lesebereichs ({@link PageReadAloud}):
 *
 * <ul>
 *   <li>Derselbe runde Knopf ({@link PodcastOrb}); beim Vorlesen hält er an und setzt fort.</li>
 *   <li>Ein Doppelklick auf ein Wort einer Antwort, während vorgelesen wird, liest sofort ab diesem Wort weiter
 *   ({@link ReadAloudStart}); daneben ändert sich nichts.</li>
 *   <li>Vorgelesen wird nur die Antwort des Assistenten, gesprochen wie im EAC ({@link MarkdownSpeech}: nur was als
 *   Text zu sehen ist) und bereinigt wie der Podcast ({@link SpeakableText}).</li>
 *   <li>Der gerade gelesene Satz oder Abschnitt (ein Aufzählungspunkt zählt als Abschnitt) ist gelb hinterlegt wie im
 *   Podcast, ungefähr und nie Wort für Wort, damit nichts flackert.</li>
 *   <li>Ein anderer Chat, ein anderer Bereich oder eine zugeklappte Seitenleiste beenden das Vorlesen.</li>
 * </ul>
 *
 * Ohne Sprachausgabe bleibt der Knopf sichtbar, aber gesperrt, und sein Tooltip nennt den Grund (wie im EAC; Angelo
 * 24.11.2026 fand ihn sonst nicht). Alles auf dem EDT.
 */
final class ChatReadAloud {

    static final String PLAY_TIP = "Letzte Antwort vorlesen · danach wird jede neue Antwort vorgelesen; beim Vorlesen "
            + "liest ein Doppelklick auf ein Wort ab dort weiter";
    static final String PAUSE_TIP = "Vorlesen anhalten";
    static final String RESUME_TIP = "Vorlesen fortsetzen";
    static final String ACTIVE_TIP = "Vorlesen beenden · neue Antworten werden sonst vorgelesen";
    static final String NO_TEXT_TIP = "Noch keine Antwort zum Vorlesen";
    static final String NO_SPEECH_TIP = "Vorlesen nicht möglich: kein KI-Dienst mit Sprachausgabe eingerichtet "
            + "(Einstellungen › KI-Dienst; Ollama hat keine Sprachausgabe)";
    static final String NO_RUNTIME_TIP = "Vorlesen nicht möglich: der Space Harvester läuft noch nicht";

    /** Satzgrenzen im gesprochenen Text: hinter Satzzeichen mit Leerraum und an jedem Zeilenwechsel. */
    private static final Pattern BOUNDARY = Pattern.compile("(?<=[.!?…:;])[ \\t]+|\\n+");
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[.!?…:;,]+$");
    /** So viele Zeichen vom Satzanfang genügen, wenn der ganze Satz in der Anzeige anders steht. */
    private static final int PROBE = 32;

    private final JComponent transcript;
    private final PodcastOrb orb = new PodcastOrb(state -> switch (state) {
        case PLAY -> "Antwort vorlesen";
        case PAUSE -> PAUSE_TIP;
        case WAITING -> "Vorlesen wird vorbereitet";
        case FAILED -> "Vorlesen erneut versuchen";
    });
    /** Gelb wie die Marke des Podcasts, aber als Strich unter der Zeile: auf der farbigen Blase bleibt Text lesbar. */
    private final DefaultHighlighter.DefaultHighlightPainter painter = new Underline(PagePodcastPictures.MARK);
    /** Die fertigen Antworten des gezeigten Chats in Reihenfolge. */
    private final List<AssistantMarkdownMessage> answers = new ArrayList<>();
    private final AWTEventListener doubleClicks = this::mouseEvent;
    private ReadAloudBinding binding;
    /** Warum ohne Sprachausgabe nicht vorgelesen wird (Tooltip des gesperrten Knopfs). */
    private String unavailable = NO_SPEECH_TIP;
    /** Play gedrückt und nicht beendet: neue Antworten werden vorgelesen (wie im EAC). */
    private boolean active;
    /** Ob gerade vorgelesen wird (auch angehalten). */
    private boolean speaking;
    /** Ob das Vorlesen angehalten ist (Stelle bleibt, der Knopf setzt dort fort). */
    private boolean paused;
    /** Kennung des laufenden Vorlesens; das Ende eines abgelösten Laufs setzt den Knopf nicht zurück. */
    private Object run;
    /** Die vorgelesene Antwort, ihr gesprochener Text und ab wo vorgelesen wird. */
    private AssistantMarkdownMessage reading;
    /** Fertig geworden, während angehalten war: wird nach der laufenden Antwort vorgelesen. */
    private AssistantMarkdownMessage deferred;
    private String spoken = "";
    private int base;
    /** Länge des vorgelesenen Ausschnitts vor und nach der Bereinigung (die Stände rechnen anteilig zurück). */
    private int sourceLength;
    private int cleanedLength;
    /** Die Sätze des gerade erklingenden Stücks; der Takt schaltet sie anteilig zur Dauer weiter. */
    private final Timer steps = new Timer(0, event -> nextStep());
    private List<int[]> pending = List.of();
    private long pendingMillis;
    private int pendingCharacters;
    /** Die Hervorhebung: Textkomponente und Kennung; {@code null} ohne. */
    private JTextComponent marked;
    private Object mark;
    private int markEnd;

    ChatReadAloud(JComponent transcript) {
        this.transcript = Objects.requireNonNull(transcript, "transcript");
        orb.setName("chat.readAloudOrb");
        orb.setVisible(false);
        orb.addActionListener(event -> toggle());
        steps.setRepeats(false);
        // Doppelklicks auf die Antworten: die Textblöcke entstehen beim Rendern neu, daher ein Hörer für den Verlauf.
        transcript.addHierarchyListener(event -> {
            long flags = event.getChangeFlags();
            if ((flags & HierarchyEvent.DISPLAYABILITY_CHANGED) != 0) {
                Toolkit toolkit = Toolkit.getDefaultToolkit();
                toolkit.removeAWTEventListener(doubleClicks);
                if (transcript.isDisplayable()) {
                    toolkit.addAWTEventListener(doubleClicks, AWTEvent.MOUSE_EVENT_MASK);
                }
            }
            // Seitenleiste zu oder anderes Fenster: der Chat liest nicht weiter (wie die Leseansicht).
            if ((flags & HierarchyEvent.SHOWING_CHANGED) != 0 && !transcript.isShowing()) {
                stop();
                active = false; // eine später fertige Antwort spricht nicht ungesehen los
                deferred = null;
                style();
            }
        });
        style();
    }

    /** Der Knopf für oben rechts über dem Verlauf. */
    PodcastOrb orb() {
        return orb;
    }

    /** Schließt die Sprachausgabe an; {@code null} = keine, dann ist der Knopf weg. */
    void bind(ReadAloudBinding binding) {
        bind(binding, NO_SPEECH_TIP);
    }

    /** Wie {@link #bind(ReadAloudBinding)}; ohne Sprachausgabe nennt der gesperrte Knopf {@code reason}. */
    void bind(ReadAloudBinding binding, String reason) {
        if (this.binding != null && this.binding != binding) {
            stop();
            active = false;
            deferred = null;
        }
        this.binding = binding;
        unavailable = reason == null || reason.isBlank() ? NO_SPEECH_TIP : reason;
        orb.setVisible(true);
        style();
    }

    /** Eine fertige Antwort des gezeigten Chats (geladen oder gerade fertig geworden). */
    void answer(AssistantMarkdownMessage bubble) {
        answers.add(Objects.requireNonNull(bubble, "bubble"));
        style();
    }

    /** Eine live beantwortete Frage ist fertig: bei aktivem Vorlesen wird sie gleich vorgelesen (wie im EAC). */
    void answered(AssistantMarkdownMessage bubble) {
        answer(bubble);
        if (binding == null || !active) {
            return;
        }
        if (speaking && paused) {
            deferred = bubble; // angehalten: nach dem Fortsetzen und dem Ende der laufenden Antwort
            return;
        }
        speak(bubble, MarkdownSpeech.spokenText(bubble.view().getMarkdown()), 0);
    }

    /** Ein anderer Chat wird gezeigt: Vorlesen beenden, die Antworten vergessen. */
    void clear() {
        stop();
        active = false;
        deferred = null;
        answers.clear();
        style();
    }

    /** Play: letzte Antwort vorlesen und aktiv bleiben; beim Vorlesen anhalten und fortsetzen; sonst beenden. */
    void toggle() {
        if (binding == null) {
            return;
        }
        if (speaking) {
            paused = !paused;
            if (paused) {
                binding.pause();
                steps.stop(); // die Marke wartet mit
            } else {
                binding.resume();
                active = true;
                if (!pending.isEmpty()) {
                    steps.restart();
                }
            }
            style();
            return;
        }
        if (active) {
            active = false;
            deferred = null;
            style();
            return;
        }
        AssistantMarkdownMessage latest = latest();
        if (latest == null) {
            return;
        }
        active = true;
        speak(latest, MarkdownSpeech.spokenText(latest.view().getMarkdown()), 0);
    }

    private AssistantMarkdownMessage latest() {
        for (int i = answers.size() - 1; i >= 0; i--) {
            AssistantMarkdownMessage bubble = answers.get(i);
            if (!bubble.view().getMarkdown().isBlank()) {
                return bubble;
            }
        }
        return null;
    }

    private void mouseEvent(AWTEvent event) {
        if (event.getID() != MouseEvent.MOUSE_CLICKED || !(event instanceof MouseEvent click)) {
            return;
        }
        if (!SwingUtilities.isLeftMouseButton(click) || click.getClickCount() != 2
                || !(click.getComponent() instanceof JTextComponent text)
                || !SwingUtilities.isDescendingFrom(text, transcript)) {
            return;
        }
        readFrom(text, click.getPoint());
    }

    /**
     * Doppelklick während des Vorlesens: trifft er eine Textstelle einer Antwort, beginnt das Vorlesen sofort ab dem
     * angeklickten Wort neu; daneben (Frage, Leerraum, Diagramm) ändert sich nichts.
     *
     * @return ob neu begonnen wurde
     */
    boolean readFrom(JTextComponent text, Point point) {
        if (binding == null || !(speaking || active)) {
            return false;
        }
        Object owner = SwingUtilities.getAncestorOfClass(AssistantMarkdownMessage.class, text);
        if (!(owner instanceof AssistantMarkdownMessage bubble) || !answers.contains(bubble)) {
            return false;
        }
        int position = text.viewToModel2D(point);
        if (position < 0) {
            return false;
        }
        Element element = Utilities.getParagraphElement(text, position);
        String paragraph = slice(text, element.getStartOffset(), element.getEndOffset());
        if (paragraph == null) {
            return false;
        }
        String speech = MarkdownSpeech.spokenText(bubble.view().getMarkdown());
        int start = ReadAloudStart.find(speech, paragraph, position - element.getStartOffset(),
                place(bubble, text, element.getStartOffset(), paragraph));
        if (start < 0) {
            return false;
        }
        active = true;
        speak(bubble, speech, start);
        return true;
    }

    /** Wo der angeklickte Absatz in der ganzen Antwort steht (alle Textblöcke hintereinander). */
    private static ReadAloudStart.Place place(AssistantMarkdownMessage bubble, JTextComponent clicked,
                                              int paragraphStart, String paragraph) {
        StringBuilder shown = new StringBuilder();
        int offset = -1;
        for (JTextComponent block : texts(bubble)) {
            if (block == clicked) {
                offset = shown.length();
            }
            String content = slice(block, 0, block.getDocument().getLength());
            shown.append(content == null ? "" : content).append("\n\n");
        }
        return offset < 0 ? null : ReadAloudStart.place(shown.toString(), offset + paragraphStart, paragraph);
    }

    /** Liest {@code speech} ab {@code start} vor; ein laufendes Vorlesen endet vorher (dieselbe Sprachausgabe). */
    private void speak(AssistantMarkdownMessage bubble, String speech, int start) {
        String source = speech.substring(Math.max(0, Math.min(start, speech.length())));
        String cleaned = SpeakableText.of(source);
        if (cleaned.isBlank()) {
            return;
        }
        Object mine = new Object();
        run = mine;
        speaking = true;
        paused = false;
        reading = bubble;
        if (deferred == bubble) {
            deferred = null;
        }
        spoken = speech;
        base = speech.length() - source.length();
        sourceLength = source.length();
        cleanedLength = cleaned.length();
        reset();
        style();
        binding.speak(cleaned, () -> true, () -> {
            if (run == mine) {
                run = null;
                speaking = false;
                paused = false;
                reset();
                style();
                AssistantMarkdownMessage next = deferred;
                deferred = null;
                if (next != null && active && binding != null && answers.contains(next)) {
                    speak(next, MarkdownSpeech.spokenText(next.view().getMarkdown()), 0);
                }
            }
        }, (from, to, millis) -> {
            if (run == mine) {
                playing(from, to, millis);
            }
        });
    }

    /**
     * Ein Stück erklingt: ungefähr die Zeichen {@code [from, to)} des bereinigten Texts, etwa {@code millis} lang. Seine
     * Sätze werden nacheinander hervorgehoben, jeder anteilig zu seiner Länge.
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

    private void nextStep() {
        if (pending.isEmpty() || !speaking) {
            return;
        }
        int[] sentence = pending.remove(0);
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

    /** Hebt den Satz in der vorgelesenen Antwort hervor; steht er dort nicht wiederzufinden, bleibt die alte Marke. */
    private void markSentence(String sentence) {
        if (reading == null) {
            return;
        }
        String needle = TRAILING_PUNCTUATION.matcher(collapse(sentence).trim()).replaceAll("").trim();
        if (needle.isEmpty()) {
            return;
        }
        List<JTextComponent> blocks = texts(reading);
        int first = marked == null ? 0 : Math.max(0, blocks.indexOf(marked));
        for (boolean probe : new boolean[] {false, true}) {
            String wanted = probe ? probe(needle) : needle;
            if (wanted.isEmpty()) {
                continue;
            }
            for (int i = 0; i < blocks.size(); i++) {
                JTextComponent block = blocks.get((first + i) % blocks.size());
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
        Collapsed shown = Collapsed.of(content);
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
        markEnd = end;
        follow(block, start, end);
        return true;
    }

    /** Die Marke bleibt im Blick, ohne den Verlauf zu verreißen: gerollt wird nur, wenn sie draußen liegt. */
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
    }

    /** Keine Marke und kein Weiterschalten mehr (Vorlesen vorbei oder neu begonnen). */
    private void reset() {
        steps.stop();
        pending = List.of();
        unmark();
    }

    /** Beendet das Vorlesen; idempotent. Der Knopf bleibt aktiv, wenn er es war. */
    void stop() {
        if (!speaking) {
            reset();
            return;
        }
        run = null;
        speaking = false;
        paused = false;
        reset();
        if (binding != null) {
            binding.stop();
        }
        style();
    }

    /** Ob gerade vorgelesen wird (für Tests). */
    boolean speaking() {
        return speaking;
    }

    /** Ob angehalten ist (für Tests). */
    boolean paused() {
        return paused;
    }

    /** Ob neue Antworten vorgelesen werden (für Tests). */
    boolean active() {
        return active;
    }

    /** Der hervorgehobene Text; leer ohne (für Tests). */
    String marked() {
        if (marked == null || mark == null) {
            return "";
        }
        for (javax.swing.text.Highlighter.Highlight highlight : marked.getHighlighter().getHighlights()) {
            if (highlight == mark) {
                String text = slice(marked, highlight.getStartOffset(), highlight.getEndOffset());
                return text == null ? "" : text;
            }
        }
        return "";
    }

    private void style() {
        if (binding == null) {
            orb.setState(PodcastOrb.State.PLAY);
            orb.setEnabled(false);
            orb.setToolTipText(unavailable);
            return;
        }
        boolean hasText = latest() != null;
        boolean playing = speaking && !paused;
        boolean on = playing || (!speaking && active);
        orb.setState(on ? PodcastOrb.State.PAUSE : PodcastOrb.State.PLAY);
        orb.setEnabled(speaking || active || hasText);
        orb.setToolTipText(speaking ? (paused ? RESUME_TIP : PAUSE_TIP)
                : active ? ACTIVE_TIP : hasText ? PLAY_TIP : NO_TEXT_TIP);
    }

    /** Die Textblöcke einer Antwort in Lesereihenfolge. */
    static List<JTextComponent> texts(AssistantMarkdownMessage bubble) {
        List<JTextComponent> texts = new ArrayList<>();
        collect(bubble.view(), texts);
        return texts;
    }

    private static void collect(Container container, List<JTextComponent> texts) {
        for (Component child : container.getComponents()) {
            if (child instanceof JTextComponent text) {
                texts.add(text);
            } else if (child instanceof Container nested) {
                collect(nested, texts);
            }
        }
    }

    private static String slice(JTextComponent text, int start, int end) {
        try {
            int length = text.getDocument().getLength();
            int from = Math.max(0, Math.min(start, length));
            int to = Math.max(from, Math.min(end, length));
            return text.getDocument().getText(from, to - from);
        } catch (BadLocationException e) {
            return null;
        }
    }

    private static String collapse(String text) {
        return Collapsed.of(text).text;
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

    /** Ein Text mit jedem Leerraum-Lauf als einem Leerzeichen und der Rückabbildung auf die Originalstellen. */
    private static final class Collapsed {
        final String text;
        private final int[] originals;

        private Collapsed(String text, int[] originals) {
            this.text = text;
            this.originals = originals;
        }

        static Collapsed of(String source) {
            StringBuilder text = new StringBuilder(source.length());
            int[] originals = new int[source.length() + 1];
            boolean space = false;
            for (int i = 0; i < source.length(); i++) {
                char c = source.charAt(i);
                if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                    if (!space) {
                        originals[text.length()] = i;
                        text.append(' ');
                        space = true;
                    }
                } else {
                    originals[text.length()] = i;
                    text.append(c);
                    space = false;
                }
            }
            originals[text.length()] = source.length();
            return new Collapsed(text.toString(), originals);
        }

        int collapsedIndex(int index) {
            for (int i = 0; i < text.length(); i++) {
                if (originals[i] >= index) {
                    return i;
                }
            }
            return text.length();
        }

        int original(int collapsed) {
            return originals[Math.max(0, Math.min(collapsed, text.length()))];
        }
    }
}
