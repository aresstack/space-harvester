package com.aresstack.spaceharvester.mainframe.diagram;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Stellt die KI-Fragen eines Diagramms gleichzeitig statt nacheinander (Angelo 28.11.2026): In der ersten Runde
 * sammelt er jede Frage und antwortet nichts ({@code null}), {@link #askAll()} stellt sie dann parallel, höchstens
 * {@link BlockLabelSummarizer#parallel()} auf einmal, und in der zweiten Runde antwortet er aus den gesammelten
 * Antworten. Eine Frage, die erst in der zweiten Runde auftaucht, geht direkt an den Summarizer.
 */
final class ParallelLabels implements BlockLabelSummarizer {

    private enum Style { LABEL, SENTENCE, DETAILS }

    private record Question(Style style, String code, int maxLength, String purpose) {
    }

    private final BlockLabelSummarizer target;
    private final Set<Question> asked = new LinkedHashSet<>();
    private final Map<Question, String> answers = new ConcurrentHashMap<>();
    private volatile boolean collecting = true;

    ParallelLabels(BlockLabelSummarizer target) {
        this.target = target;
    }

    @Override
    public boolean enabled() {
        return collecting || on();
    }

    @Override
    public int parallel() {
        return target.parallel();
    }

    @Override
    public String summarize(String code, int maxLength, String purpose) {
        return answer(new Question(Style.LABEL, code, maxLength, purpose));
    }

    @Override
    public String explain(String code, int maxLength, String purpose) {
        return answer(new Question(Style.SENTENCE, code, maxLength, purpose));
    }

    @Override
    public String describe(String code, int maxLength, String purpose) {
        return answer(new Question(Style.DETAILS, code, maxLength, purpose));
    }

    private String answer(Question question) {
        if (collecting) {
            synchronized (asked) {
                asked.add(question);
            }
            return null;
        }
        if (answers.containsKey(question)) {
            String answer = answers.get(question);
            return answer.isEmpty() ? null : answer;
        }
        return on() ? ask(question) : null;
    }

    /** Stellt alle gesammelten Fragen gleichzeitig und wartet auf alle; danach antwortet er aus den Antworten. */
    void askAll() {
        List<Question> questions;
        synchronized (asked) {
            questions = new ArrayList<>(asked);
        }
        collecting = false;
        if (questions.isEmpty()) {
            return;
        }
        int threads = Math.max(1, Math.min(parallel(), questions.size()));
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "diagramm-ki");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Callable<Void>> jobs = new ArrayList<>();
            for (Question question : questions) {
                jobs.add(() -> {
                    // Ausgeschaltet während der Arbeit: keine weiteren Fragen
                    if (on()) {
                        String answer = ask(question);
                        answers.put(question, answer == null ? "" : answer);
                    }
                    return null;
                });
            }
            for (Future<Void> done : pool.invokeAll(jobs)) {
                try {
                    done.get();
                } catch (java.util.concurrent.ExecutionException ignored) {
                    // eine gescheiterte Frage lässt die einfache Beschriftung stehen
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
    }

    private boolean on() {
        try {
            return target.enabled();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private String ask(Question question) {
        try {
            return switch (question.style()) {
                case LABEL -> target.summarize(question.code(), question.maxLength(), question.purpose());
                case SENTENCE -> target.explain(question.code(), question.maxLength(), question.purpose());
                case DETAILS -> target.describe(question.code(), question.maxLength(), question.purpose());
            };
        } catch (RuntimeException e) {
            return null;
        }
    }
}
