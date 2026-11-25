package com.aresstack.spaceharvester.app.speech;

import com.aresstack.spaceharvester.app.ui.content.DictationControl;
import com.aresstack.windirectml.inference.whisper.WhisperIncrementalTranscription;

import javax.sound.sampled.LineUnavailableException;
import javax.swing.SwingUtilities;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handle chat dictation using the original Enterprise AI Client recording and callback workflow.
 * Replace only the speech recognition backend with the Whisper checkpoint chosen in the settings.
 *
 * <p>While recording, a background worker decodes every completed 30-second window and reports it
 * through {@link Result#partial}; after stop only the remaining audio is decoded. Recording never
 * waits for the decoder: if the CPU is slower than real time, the backlog is decoded after stop.
 * If live decoding fails, the whole recording is decoded after stop instead.
 */
public final class Dictation implements DictationControl {

    private static final Logger LOG = Logger.getLogger(Dictation.class.getName());
    private static final long LIVE_POLL_SECONDS = 2;

    private final List<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "space-harvester-dictation");
        thread.setDaemon(true);
        return thread;
    });
    private final WhisperDictationService service = new WhisperDictationService();
    private final String description = "Whisper + Ask AI DSP/VAD (lokal, CPU)";
    private JavaSoundRecorder recorder;
    private Result pending;
    /** The live transcription of the current recording; only touched on the worker thread. */
    private LiveSession live;
    private ScheduledFuture<?> livePolling;
    private boolean experimental;
    /** Die zuletzt fehlgeschlagene Aufnahme (für „Erneut“); weg nach Erfolg, Verwerfen oder neuer Aufnahme. */
    private RecordedSpeech failedSpeech;
    /** Jede Aufnahme und jedes Verwerfen zählt hoch; ein altes Ergebnis kommt nicht mehr an. */
    private volatile long generation;

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
            return;
        }
        final JavaSoundRecorder active = recorder;
        final long expected = generation;
        experimental = LiveDictationPreference.enabled();
        final boolean preview = experimental;
        livePolling = worker.scheduleWithFixedDelay(() -> decodeWhileRecording(active, result, expected, preview),
                LIVE_POLL_SECONDS, LIVE_POLL_SECONDS, TimeUnit.SECONDS);
    }

    /** Worker thread: decode every completed window of the running recording. */
    private void decodeWhileRecording(JavaSoundRecorder active, Result result, long expected, boolean preview) {
        if (expected != generation) {
            return;
        }
        try {
            if (live == null || live.generation != expected) {
                live = new LiveSession(expected, service.startLive(active.liveAudio(),
                        preview ? ignored -> { } : partial(result, expected)));
            }
            if (!live.failed) {
                live.transcription.decodeAvailable();
                if (preview && !live.previewFailed) {
                    preview(active, result, expected);
                }
            }
        } catch (Exception | OutOfMemoryError failed) {
            if (expected != generation) {
                return; // verworfen: die Aufnahme ist geschlossen, das Lesen bricht absichtlich ab
            }
            LOG.log(Level.WARNING, "Spracheingabe: Erkennung während der Aufnahme fehlgeschlagen, "
                    + "die Aufnahme wird nach dem Stopp vollständig erkannt", failed);
            if (live != null && live.generation == expected) {
                live.failed = true;
            } else {
                live = new LiveSession(expected, null);
                live.failed = true;
            }
        }
    }

    private void preview(JavaSoundRecorder active, Result result, long expected) {
        try {
            var source = active.liveAudio();
            long end = source.sampleCount();
            long committed = live.transcription.committedSamples();
            DictationPreviewWindow window = DictationPreviewWindow.capture(source, committed, live.previewEnd);
            if (window == null) {
                return;
            }
            live.previewEnd = end;
            String text = service.preview(window);
            SwingUtilities.invokeLater(() -> {
                if (expected == generation && recorder == active) {
                    result.preview(text);
                }
            });
        } catch (Exception | OutOfMemoryError failure) {
            live.previewFailed = true;
            LOG.log(Level.WARNING, "Experimental dictation preview disabled for this recording", failure);
            SwingUtilities.invokeLater(() -> {
                if (expected == generation && recorder == active) {
                    result.preview("");
                }
            });
        }
    }

    private java.util.function.Consumer<String> partial(Result result, long expected) {
        return piece -> SwingUtilities.invokeLater(() -> {
            if (expected == generation) {
                result.partial(piece);
            }
        });
    }

    @Override
    public void finish() {
        final JavaSoundRecorder active = recorder;
        final Result result = pending;
        final WhisperDictationService current = service;
        recorder = null;
        pending = null;
        stopLivePolling();
        if (active == null) {
            return;
        }
        if (result == null) {
            active.close();
            return;
        }
        if (current == null) {
            active.close();
            result.failed(description);
            return;
        }
        try {
            transcribe(active.stop(), result, current, true, experimental);
        } catch (IOException failed) {
            active.close();
            LOG.log(Level.WARNING, "Mikrofonaufnahme fehlgeschlagen", failed);
            result.failed(failed.getMessage() == null ? "Mikrofonaufnahme fehlgeschlagen" : failed.getMessage());
        }
    }

    @Override
    public boolean canRetry() {
        return failedSpeech != null && service != null;
    }

    @Override
    public void retry(Result result) {
        RecordedSpeech speech = failedSpeech;
        failedSpeech = null;
        WhisperDictationService current = service;
        cancel();
        if (speech == null || current == null) {
            result.failed(current == null ? description : "Keine Aufnahme zum Wiederholen");
            return;
        }
        transcribe(speech, result, current, false, false);
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

    /**
     * Erkennt im Hintergrund; Ergebnis auf dem EDT, nur wenn inzwischen nichts verworfen oder neu begonnen wurde.
     * Nach einer Aufnahme mit Live-Erkennung bleibt nur der Rest zu erkennen.
     */
    private void transcribe(final RecordedSpeech speech, final Result result, final WhisperDictationService current,
                            final boolean continueLive, final boolean preview) {
        failedSpeech = null;
        final long expected = generation;
        worker.execute(() -> {
            String text = null;
            String problem = null;
            try {
                long started = System.currentTimeMillis();
                LiveSession session = live;
                live = null;
                if (continueLive && session != null && session.generation == expected && !session.failed) {
                    text = current.finishLive(session.transcription, speech);
                } else {
                    text = current.transcribe(speech, preview ? ignored -> { } : partial(result, expected));
                }
                LOG.info("Spracheingabe: " + speech.durationMillis() + " ms Audio, nach dem Stopp "
                        + (System.currentTimeMillis() - started) + " ms bis zum Ergebnis");
            } catch (Exception failed) {
                LOG.log(Level.WARNING, "Spracheingabe fehlgeschlagen", failed);
                problem = failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage();
            } catch (OutOfMemoryError memory) {
                LOG.log(Level.SEVERE, "Spracheingabe: zu wenig Speicher", memory);
                problem = "zu wenig Arbeitsspeicher für das Modell";
            }
            final String recognized = text;
            final String message = problem;
            SwingUtilities.invokeLater(() -> {
                if (expected != generation) {
                    speech.close();
                    return;
                }
                if (message != null) {
                    discardFailedSpeech();
                    failedSpeech = speech;
                    result.failed(message);
                } else {
                    speech.close();
                    result.recognized(recognized);
                }
            });
        });
    }

    private void stopLivePolling() {
        if (livePolling != null) {
            livePolling.cancel(false);
            livePolling = null;
        }
    }

    private void discardFailedSpeech() {
        if (failedSpeech != null) {
            failedSpeech.close();
            failedSpeech = null;
        }
    }

    @Override
    public void cancel() {
        generation++;
        stopLivePolling();
        discardFailedSpeech();
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

    /** One recording's live transcription; confined to the worker thread. */
    private static final class LiveSession {
        final long generation;
        final WhisperIncrementalTranscription transcription;
        boolean failed;
        boolean previewFailed;
        long previewEnd;

        LiveSession(long generation, WhisperIncrementalTranscription transcription) {
            this.generation = generation;
            this.transcription = transcription;
        }
    }
}
