package com.aresstack.enterpriseai.astu.acropolis.speech;

import com.aresstack.enterpriseai.speech.api.TextAnalysisEngine;
import com.aresstack.enterpriseai.speech.api.TextAnalysisEngineLoader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Die Textanalyse beim Vorlesen, übernommen aus askai-java8 arch ({@code ChatTextAnalysisService}): teilt eine Antwort
 * in Absätze (Leerzeilen), Sätze (Satzmodell je Sprache) und Abschnitte gleicher Sprache (Spracherkennung je Satz).
 * Sie läuft nicht dauerhaft: wer sie braucht, meldet sich mit {@link #acquire(Object)} an (der erste lädt die Modelle
 * über den {@link TextAnalysisEngineLoader}), nach dem letzten {@link #release(Object)} wird die Engine nach einer
 * kurzen Schonfrist wieder entladen, damit aufeinanderfolgende Antworten die Modelle nicht jedes Mal neu laden.
 *
 * <p>Ohne geladene Engine (Laden gescheitert) liefert {@link #segment} den ganzen Text als ein Stück.</p>
 */
public final class TextAnalysisService {

    /** Schonfrist, bevor die Engine nach der letzten Antwort entladen wird (wie arch). */
    public static final long DEFAULT_STOP_GRACE_MILLIS = 60000L;

    /** Ein zusammenhängender Abschnitt gleicher Sprache, in Lesereihenfolge. */
    public static final class Segment {
        private final String languageCode;
        private final List<String> chunks;

        public Segment(String languageCode, List<String> chunks) {
            this.languageCode = languageCode;
            this.chunks = Collections.unmodifiableList(new ArrayList<String>(chunks));
        }

        public String languageCode() {
            return languageCode;
        }

        /** Die zu sprechenden Stücke in Reihenfolge (je Satz, je Absatz oder eins). */
        public List<String> chunks() {
            return chunks;
        }
    }

    private final TextAnalysisEngineLoader loader;
    private final long stopGraceMillis;
    private final Map<Object, Boolean> listeners = new IdentityHashMap<Object, Boolean>();
    private TextAnalysisEngine engine;
    private String loadProblem;
    private long stopGeneration;

    public TextAnalysisService(TextAnalysisEngineLoader loader, long stopGraceMillis) {
        if (loader == null) {
            throw new IllegalArgumentException("loader must not be null");
        }
        this.loader = loader;
        this.stopGraceMillis = Math.max(0, stopGraceMillis);
    }

    /** Meldet einen Nutzer an; der ERSTE lädt die Engine aus den installierten Modellen. */
    public synchronized void acquire(Object listener) {
        listeners.put(listener, Boolean.TRUE);
        stopGeneration++; // eine laufende Schonfrist ist hinfällig, die Analyse wird wieder gebraucht
        if (engine == null) {
            try {
                engine = loader.load();
                loadProblem = null;
            } catch (RuntimeException brokenModels) {
                engine = null; // segment() fällt auf ein Stück zurück
                loadProblem = brokenModels.getMessage() == null ? brokenModels.getClass().getSimpleName()
                        : brokenModels.getMessage();
            }
        }
    }

    /** Meldet ab; nach dem LETZTEN Nutzer wird die Engine nach der Schonfrist entladen. */
    public synchronized void release(Object listener) {
        listeners.remove(listener);
        if (!listeners.isEmpty()) {
            return;
        }
        final long myGeneration = ++stopGeneration;
        if (stopGraceMillis == 0) {
            stopNow(myGeneration);
            return;
        }
        Thread stopper = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(stopGraceMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                stopNow(myGeneration);
            }
        }, "enterprise-ai-text-analysis-stop");
        stopper.setDaemon(true);
        stopper.start();
    }

    private synchronized void stopNow(long myGeneration) {
        if (myGeneration != stopGeneration || !listeners.isEmpty()) {
            return; // während der Schonfrist wieder angemeldet
        }
        if (engine != null) {
            engine.close();
            engine = null;
        }
    }

    public synchronized boolean isRunning() {
        return engine != null;
    }

    /**
     * Die Sätze eines Absatzes mit dem Satzmodell der Sprache (z. B. für die Passagenbildung beim Indexieren); ohne
     * geladene Engine {@code null}. Vorher {@link #acquire(Object)}.
     */
    public synchronized List<String> sentences(String paragraph, String languageCode) {
        return engine == null ? null : engine.sentences(paragraph, languageCode);
    }

    /**
     * The language of a text ({@code de}, {@code en}), or {@code ""} when unknown or without a loaded engine. Call
     * {@link #acquire(Object)} first.
     */
    public synchronized String detectLanguage(String text) {
        return engine == null || !engine.detectsLanguages() ? "" : engine.detectLanguage(text);
    }

    /**
     * The tokens of a sentence by the tokenizer model, or an empty list without a loaded engine or tokenizer model
     * for the language. Call {@link #acquire(Object)} first.
     */
    public synchronized List<String> tokens(String sentence, String languageCode) {
        if (engine == null || !engine.tokenizes(languageCode)) {
            return Collections.emptyList();
        }
        return engine.tokens(sentence, languageCode);
    }

    /**
     * The tagged tokens, or an empty list without a loaded engine or part-of-speech model for the language. Call
     * {@link #acquire(Object)} first.
     */
    public synchronized List<com.aresstack.enterpriseai.speech.api.TaggedToken> tag(List<String> tokens,
                                                                                 String languageCode) {
        if (engine == null || !engine.tagsWordClasses(languageCode)) {
            return Collections.emptyList();
        }
        return engine.tag(tokens, languageCode);
    }

    /** Warum die Engine zuletzt nicht geladen werden konnte, sonst {@code null}. */
    public synchronized String loadProblem() {
        return loadProblem;
    }

    /** Ein Absatz: die Sätze einer Sprache, in Reihenfolge. */
    private static final class Run {
        final String language;
        final List<String> sentences = new ArrayList<String>();

        Run(String language) {
            this.language = language;
        }

        String joined() {
            StringBuilder text = new StringBuilder();
            for (String sentence : sentences) {
                if (text.length() > 0) {
                    text.append(' ');
                }
                text.append(sentence);
            }
            return text.toString();
        }
    }

    /**
     * Teilt {@code text} in Abschnitte gleicher Sprache, deren Stücke der {@link SpeechChunking} folgen: ganzer
     * Abschnitt, je Absatz (Leerzeilen) oder je Satz. Die Sätze kommen immer vom Satzmodell der Rückfallsprache; die
     * Spracherkennung stimmt je Satz ab, ein Sprachwechsel innerhalb eines Absatzes trennt sauber.
     *
     * @param detectLanguages {@code false}: keine Spracherkennung, alles bleibt in der Rückfallsprache
     */
    public synchronized List<Segment> segment(String text, String fallbackLanguage, boolean detectLanguages,
                                              SpeechChunking chunking) {
        String value = text == null ? "" : text.trim();
        List<Segment> single = Collections.singletonList(
                new Segment(fallbackLanguage, Collections.singletonList(value)));
        if (value.isEmpty()) {
            return single;
        }
        if (engine == null || (!detectLanguages && chunking == SpeechChunking.ANSWER)) {
            return single;
        }
        boolean detect = detectLanguages && engine.detectsLanguages();
        List<Run> runs = new ArrayList<Run>();
        for (String paragraph : value.split("\\n{2,}")) {
            String trimmed = paragraph.trim().replaceAll("\\s+", " ");
            if (trimmed.isEmpty()) {
                continue;
            }
            List<String> sentences = engine.sentences(trimmed, fallbackLanguage);
            if (sentences == null || sentences.isEmpty()) {
                sentences = Collections.singletonList(trimmed);
            }
            Run run = null;
            for (String sentence : sentences) {
                String detected = detect ? engine.detectLanguage(sentence) : "";
                String language = detected == null || detected.isEmpty() ? fallbackLanguage : detected;
                if (run == null || !language.equals(run.language)) {
                    if (run != null) {
                        runs.add(run);
                    }
                    run = new Run(language);
                }
                run.sentences.add(sentence);
            }
            if (run != null) {
                runs.add(run);
            }
        }
        if (runs.isEmpty()) {
            return single;
        }
        // Aufeinanderfolgende Läufe gleicher Sprache bilden einen Abschnitt; die Einheit bestimmt die Stücke.
        List<Segment> segments = new ArrayList<Segment>();
        String currentLanguage = null;
        List<Run> currentRuns = new ArrayList<Run>();
        for (Run run : runs) {
            if (currentLanguage != null && !run.language.equals(currentLanguage)) {
                segments.add(new Segment(currentLanguage, chunks(currentRuns, chunking)));
                currentRuns = new ArrayList<Run>();
            }
            currentLanguage = run.language;
            currentRuns.add(run);
        }
        segments.add(new Segment(currentLanguage, chunks(currentRuns, chunking)));
        return segments;
    }

    private static List<String> chunks(List<Run> runs, SpeechChunking chunking) {
        List<String> chunks = new ArrayList<String>();
        if (chunking == SpeechChunking.SENTENCES) {
            for (Run run : runs) {
                chunks.addAll(run.sentences);
            }
            return chunks;
        }
        if (chunking == SpeechChunking.PARAGRAPHS) {
            for (Run run : runs) {
                chunks.add(run.joined());
            }
            return chunks;
        }
        StringBuilder joined = new StringBuilder();
        for (Run run : runs) {
            if (joined.length() > 0) {
                joined.append(' ');
            }
            joined.append(run.joined());
        }
        chunks.add(joined.toString());
        return chunks;
    }
}
