package com.aresstack.spaceharvester.app.speech;

import com.aresstack.spaceharvester.app.ui.content.DictationControl;

import javax.sound.sampled.LineUnavailableException;
import javax.swing.SwingUtilities;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handle chat dictation using the original Enterprise AI Client recording and callback workflow.
 * Replace only the speech recognition backend with the bundled Whisper Tiny checkpoint.
 */
public final class Dictation implements DictationControl {

    private static final Logger LOG = Logger.getLogger(Dictation.class.getName());

    private final List<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "space-harvester-dictation");
            thread.setDaemon(true);
            return thread;
        }
    });
    private final WhisperDictationService service = new WhisperDictationService();
    private final String description = "Whisper Tiny (lokal, CPU)";
    private JavaSoundRecorder recorder;
    private Result pending;
    /** Die zuletzt fehlgeschlagene Aufnahme (für „Erneut“); weg nach Erfolg, Verwerfen oder neuer Aufnahme. */
    private RecordedSpeech failedSpeech;
    /** Jede Aufnahme und jedes Verwerfen zählt hoch; ein altes Ergebnis kommt nicht mehr an. */
    private long generation;

    public String currentDescription() {
        return description;
    }

    @Override
    public boolean isAvailable() {
        return service != null;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public void start(Result result) {
        cancel();
        failedSpeech = null;
        generation++;
        if (service == null) {
            result.failed(description);
            return;
        }
        try {
            recorder = JavaSoundRecorder.start();
            pending = result;
        } catch (LineUnavailableException | RuntimeException failed) {
            LOG.log(Level.WARNING, "Mikrofon nicht verfügbar", failed);
            result.failed("Mikrofon nicht verfügbar: " + failed.getMessage());
        }
    }

    @Override
    public void finish() {
        final JavaSoundRecorder active = recorder;
        final Result result = pending;
        final WhisperDictationService current = service;
        recorder = null;
        pending = null;
        if (active == null || result == null) {
            return;
        }
        if (current == null) {
            active.close();
            result.failed(description);
            return;
        }
        transcribe(active.stop(), result, current);
    }

    @Override
    public boolean canRetry() {
        return failedSpeech != null && service != null;
    }

    @Override
    public void retry(Result result) {
        RecordedSpeech speech = failedSpeech;
        WhisperDictationService current = service;
        cancel();
        if (speech == null || current == null) {
            result.failed(current == null ? description : "Keine Aufnahme zum Wiederholen");
            return;
        }
        transcribe(speech, result, current);
    }

    @Override
    public int level() {
        JavaSoundRecorder active = recorder;
        return active == null ? 0 : DecibelLevelScale.percentFromPeak(active.consumeRecentPeak());
    }

    @Override
    public boolean clipping() {
        JavaSoundRecorder active = recorder;
        return active != null && active.clipped();
    }

    /** Erkennt im Hintergrund; Ergebnis auf dem EDT, nur wenn inzwischen nichts verworfen oder neu begonnen wurde. */
    private void transcribe(final RecordedSpeech speech, final Result result, final WhisperDictationService current) {
        failedSpeech = null;
        final long expected = generation;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                String text = null;
                String problem = null;
                try {
                    long started = System.currentTimeMillis();
                    text = current.transcribe(speech);
                    LOG.info("Spracheingabe: " + speech.durationMillis() + " ms Audio in "
                            + (System.currentTimeMillis() - started) + " ms erkannt");
                } catch (Exception failed) {
                    LOG.log(Level.WARNING, "Spracheingabe fehlgeschlagen", failed);
                    problem = failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage();
                } catch (OutOfMemoryError memory) {
                    LOG.log(Level.SEVERE, "Spracheingabe: zu wenig Speicher", memory);
                    problem = "zu wenig Arbeitsspeicher für das Modell";
                }
                final String recognized = text;
                final String message = problem;
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        if (expected != generation) {
                            return;
                        }
                        if (message != null) {
                            failedSpeech = speech;
                            result.failed(message);
                        } else {
                            result.recognized(recognized);
                        }
                    }
                });
            }
        });
    }

    @Override
    public void cancel() {
        generation++;
        if (recorder != null) {
            recorder.close();
            recorder = null;
        }
        pending = null;
    }

    @Override
    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    /** Beendet Aufnahme und Erkennung beim Herunterfahren; idempotent. */
    public void close() {
        cancel();
        worker.shutdownNow();
    }
}
