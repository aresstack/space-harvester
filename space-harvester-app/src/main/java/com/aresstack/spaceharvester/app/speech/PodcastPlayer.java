package com.aresstack.spaceharvester.app.speech;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Spielt die Abschnitte eines Podcasts nacheinander über Java Sound, während weitere noch entstehen (Angelo 21.11.2026:
 * das Abspielen beginnt, sobald der erste Abschnitt fertig ist). Die Zeitleiste rechnet über alle Abschnitte; ein noch
 * fehlender zählt mit der mittleren Länge der fertigen, deshalb dürfen sich Position und Marken verschieben, wenn
 * Abschnitte nachkommen.
 *
 * <p>Erreicht die Wiedergabe einen Abschnitt, der noch entsteht, wartet sie ({@link Listener#waiting}). Entsteht er nicht
 * mehr (der Auftrag ist zu Ende), überspringt sie ihn statt zu verstummen; fehlen am Ende Abschnitte oder bricht die
 * Ausgabe ab, meldet sie {@link Listener#failed} mit der Stelle, an der {@link #play(long)} fortsetzen kann.
 */
public final class PodcastPlayer {

    /** Rückmeldungen; laufen auf dem Wiedergabe-Thread. */
    public interface Listener {

        /** Die Position in Millisekunden und die (geschätzte) Gesamtlänge. */
        void position(long millis, long total);

        /** Die Wiedergabe wartet auf einen Abschnitt, der noch entsteht ({@code true}) oder spielt wieder. */
        void waiting(boolean waiting);

        /** Das Ende ist erreicht, alle Abschnitte waren da. */
        void ended();

        /** Abgebrochen oder Abschnitte fehlen; {@code resumeAt} ist die Stelle zum Fortsetzen. */
        void failed(String message, long resumeAt);
    }

    /** Ein Abschnitt: seine Datei oder {@code null}, solange sie fehlt. */
    public record Track(Path audio, boolean startsTopic, String title) {
    }

    private static final int BLOCK_MILLIS = 100;
    /** Geschätzte Länge eines fehlenden Abschnitts, solange keiner fertig ist. */
    static final long DEFAULT_TRACK_MILLIS = 30000;

    private final Listener listener;
    private final Object lock = new Object();
    private List<Track> tracks = List.of();
    private long[] durations = new long[0];
    private boolean producing;
    private long generation;
    private boolean paused;
    private boolean playing;
    private long position;
    private long seekTo = -1;

    public PodcastPlayer(Listener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Übernimmt den Stand des Podcasts; eine laufende Wiedergabe spielt mit den neuen Abschnitten weiter.
     *
     * @param producing ob noch ein Auftrag daran arbeitet (dann wartet die Wiedergabe auf fehlende Abschnitte)
     */
    public void tracks(List<Track> tracks, boolean producing) {
        long[] measured = new long[tracks.size()];
        for (int i = 0; i < tracks.size(); i++) {
            measured[i] = tracks.get(i).audio() == null ? -1 : duration(tracks.get(i).audio());
        }
        synchronized (lock) {
            this.tracks = List.copyOf(tracks);
            this.durations = measured;
            this.producing = producing;
            lock.notifyAll();
        }
    }

    /** Ob die Wiedergabe gerade läuft (auch wartend oder angehalten). */
    public boolean playing() {
        synchronized (lock) {
            return playing;
        }
    }

    public boolean paused() {
        synchronized (lock) {
            return paused;
        }
    }

    /** Startet ab {@code fromMillis}; eine laufende Wiedergabe endet vorher. */
    public void play(long fromMillis) {
        long mine;
        synchronized (lock) {
            mine = ++generation;
            paused = false;
            playing = true;
            position = Math.max(0, fromMillis);
            seekTo = -1;
            lock.notifyAll();
        }
        Thread thread = new Thread(() -> run(mine), "space-harvester-podcast-player");
        thread.setDaemon(true);
        thread.start();
    }

    public void pause() {
        synchronized (lock) {
            paused = true;
        }
    }

    public void resume() {
        synchronized (lock) {
            paused = false;
            lock.notifyAll();
        }
    }

    /** Springt an eine Stelle der Zeitleiste. */
    public void seek(long millis) {
        synchronized (lock) {
            seekTo = Math.max(0, millis);
            position = seekTo;
            lock.notifyAll();
        }
    }

    /** Beendet die Wiedergabe. */
    public void stop() {
        synchronized (lock) {
            generation++;
            playing = false;
            paused = false;
            lock.notifyAll();
        }
    }

    /**
     * Beendet die Wiedergabe und vergisst den Podcast: keine Abschnitte, Position 0. Für einen anderen Podcast, damit
     * dessen Zeitleiste nicht mit Länge und Endstand des vorigen beginnt (Angelo 23.11.2026).
     */
    public void reset() {
        synchronized (lock) {
            generation++;
            playing = false;
            paused = false;
            tracks = List.of();
            durations = new long[0];
            producing = false;
            position = 0;
            seekTo = -1;
            lock.notifyAll();
        }
    }

    /** Die aktuelle Position. */
    public long position() {
        synchronized (lock) {
            return position;
        }
    }

    /** Gesamtlänge: fertige Abschnitte gemessen, fehlende mit der mittleren Länge der fertigen. */
    public long total() {
        synchronized (lock) {
            return total(durations);
        }
    }

    /** Wo die Themen beginnen (für die gelben Marken). */
    public List<Long> topicStarts() {
        synchronized (lock) {
            List<Long> starts = new ArrayList<>();
            long at = 0;
            long estimate = estimate(durations);
            for (int i = 0; i < tracks.size(); i++) {
                if (tracks.get(i).startsTopic() && i > 0) {
                    starts.add(at);
                }
                at += durations[i] < 0 ? estimate : durations[i];
            }
            return starts;
        }
    }

    /** Der Titel des Abschnitts an einer Stelle. */
    /** Der Index des Abschnitts an {@code millis} in der Reihenfolge der Tracks, {@code -1} außerhalb. */
    public int trackAt(long millis) {
        synchronized (lock) {
            return indexAt(millis);
        }
    }

    /** Wo {@code millis} im eigenen Abschnitt liegt: {@code [Stelle, Länge]} in Millisekunden; {0, 0} außerhalb. */
    public long[] inTrack(long millis) {
        synchronized (lock) {
            int index = indexAt(millis);
            return index < 0 ? new long[] {0, 0}
                    : new long[] {Math.max(0, millis - startOf(index)), Math.max(0, durationAt(index))};
        }
    }

    public String titleAt(long millis) {
        synchronized (lock) {
            int index = indexAt(millis);
            return index < 0 ? null : tracks.get(index).title();
        }
    }

    // --- Wiedergabe ---------------------------------------------------------------------------------------------

    private void run(long mine) {
        int skipped = 0;
        long firstSkipped = -1;
        SourceDataLine line = null;
        AudioFormat lineFormat = null;
        try {
            while (true) {
                int index;
                long offset;
                Path audio;
                synchronized (lock) {
                    if (generation != mine) {
                        return;
                    }
                    if (seekTo >= 0) {
                        position = seekTo;
                        seekTo = -1;
                    }
                    index = indexAt(position);
                    if (index < 0) {
                        playing = false;
                        break;
                    }
                    offset = position - startOf(index);
                    audio = tracks.get(index).audio();
                    if (audio == null) {
                        if (producing) {
                            listener.waiting(true);
                            while (generation == mine && seekTo < 0 && producing
                                    && tracks.size() > index && tracks.get(index).audio() == null) {
                                lock.wait();
                            }
                            listener.waiting(false);
                            continue;
                        }
                        // Entsteht nicht mehr: überspringen statt verstummen.
                        skipped++;
                        if (firstSkipped < 0) {
                            firstSkipped = startOf(index);
                        }
                        position = startOf(index) + Math.max(0, durationAt(index));
                        continue;
                    }
                }
                try (AudioInputStream stream = AudioSystem.getAudioInputStream(audio.toFile())) {
                    AudioFormat format = stream.getFormat();
                    if (line == null || !format.matches(lineFormat)) {
                        if (line != null) {
                            line.drain();
                            line.close();
                        }
                        line = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, format));
                        line.open(format);
                        line.start();
                        lineFormat = format;
                    }
                    if (!playTrack(mine, stream, format, line, index, offset)) {
                        return;
                    }
                }
            }
            if (line != null) {
                line.drain();
            }
            if (skipped > 0) {
                listener.failed(skipped == 1 ? "Ein Abschnitt fehlt noch" : skipped + " Abschnitte fehlen noch", Math.max(0, firstSkipped));
            } else {
                listener.ended();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | UnsupportedAudioFileException | LineUnavailableException | RuntimeException e) {
            long at;
            synchronized (lock) {
                if (generation != mine) {
                    // Eine überholte Wiedergabe (gestoppt, zurückgesetzt): ihr Fehler betrifft den neuen Stand nicht.
                    return;
                }
                at = position;
                playing = false;
            }
            listener.failed("Wiedergabe abgebrochen (" + e.getClass().getSimpleName() + ")", at);
        } finally {
            if (line != null) {
                line.stop();
                line.close();
            }
        }
    }

    /** Spielt einen Abschnitt ab {@code offset}; {@code false}, wenn die Wiedergabe beendet wurde. */
    private boolean playTrack(long mine, AudioInputStream stream, AudioFormat format, SourceDataLine line, int index,
                              long offset) throws IOException, InterruptedException {
        int frameSize = Math.max(1, format.getFrameSize());
        long skip = (long) (offset / 1000.0 * format.getFrameRate()) * frameSize;
        while (skip > 0) {
            long skipped = stream.skip(skip);
            if (skipped <= 0) {
                break;
            }
            skip -= skipped;
        }
        int block = Math.max(frameSize, (int) (format.getFrameRate() * BLOCK_MILLIS / 1000) * frameSize);
        byte[] buffer = new byte[block];
        long start;
        synchronized (lock) {
            if (generation != mine) {
                return false;
            }
            start = startOf(index);
        }
        long played = offset;
        while (true) {
            synchronized (lock) {
                while (paused && generation == mine && seekTo < 0) {
                    line.stop();
                    lock.wait();
                    line.start();
                }
                if (generation != mine) {
                    line.flush();
                    return false;
                }
                if (seekTo >= 0) {
                    line.flush();
                    return true;
                }
            }
            int read = stream.read(buffer, 0, buffer.length - buffer.length % frameSize);
            if (read <= 0) {
                synchronized (lock) {
                    // Während des Lesens zurückgesetzt: Abschnitte und Position gehören schon zum neuen Stand.
                    if (generation != mine) {
                        return false;
                    }
                    position = start + Math.max(played, durationAt(index));
                }
                return true;
            }
            line.write(buffer, 0, read);
            played += Math.round(read / (double) frameSize / format.getFrameRate() * 1000);
            long total;
            synchronized (lock) {
                if (generation != mine) {
                    line.flush();
                    return false;
                }
                position = start + played;
                total = total(durations);
            }
            listener.position(start + played, total);
        }
    }

    private int indexAt(long millis) {
        long at = 0;
        long estimate = estimate(durations);
        for (int i = 0; i < tracks.size(); i++) {
            long length = durations[i] < 0 ? estimate : durations[i];
            if (millis < at + length) {
                return i;
            }
            at += length;
        }
        return -1;
    }

    private long startOf(int index) {
        long at = 0;
        long estimate = estimate(durations);
        for (int i = 0; i < index; i++) {
            at += durations[i] < 0 ? estimate : durations[i];
        }
        return at;
    }

    private long durationAt(int index) {
        return durations[index] < 0 ? estimate(durations) : durations[index];
    }

    static long estimate(long[] durations) {
        long sum = 0;
        int count = 0;
        for (long duration : durations) {
            if (duration >= 0) {
                sum += duration;
                count++;
            }
        }
        return count == 0 ? DEFAULT_TRACK_MILLIS : sum / count;
    }

    static long total(long[] durations) {
        long estimate = estimate(durations);
        long total = 0;
        for (long duration : durations) {
            total += duration < 0 ? estimate : duration;
        }
        return total;
    }

    /** Länge einer WAV-Datei in Millisekunden; unlesbar = 0. */
    public static long duration(Path file) {
        try {
            AudioFileFormat format = AudioSystem.getAudioFileFormat(file.toFile());
            long frames = format.getFrameLength();
            float rate = format.getFormat().getFrameRate();
            return frames <= 0 || rate <= 0 ? 0 : Math.round(frames / (double) rate * 1000);
        } catch (IOException | UnsupportedAudioFileException e) {
            return 0;
        }
    }
}
