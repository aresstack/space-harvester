package com.aresstack.spaceharvester.app.speech;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.io.IOException;

/**
 * Capture unlimited PCM16 microphone audio into delayed two-minute disk chunks.
 * Convert stereo to mono while recording; resample only the windows requested by Whisper.
 */
final class JavaSoundRecorder {
    private static final float[][] CANDIDATES = {
            {16000f, 1}, {48000f, 1}, {44100f, 1}, {32000f, 1},
            {48000f, 2}, {44100f, 2}
    };

    private final TargetDataLine line;
    private final AudioFormat format;
    private final ChunkedPcmAudioSource audio;
    private final Thread reader;
    private volatile boolean running = true;
    private volatile IOException captureFailure;
    private volatile int recentPeak;
    private volatile boolean clipped;

    private JavaSoundRecorder(TargetDataLine line, AudioFormat format) {
        this.line = line;
        this.format = format;
        this.audio = new ChunkedPcmAudioSource(Math.round(format.getSampleRate()));
        this.reader = new Thread(this::read, "space-harvester-microphone");
        this.reader.setDaemon(true);
    }

    static JavaSoundRecorder start() throws LineUnavailableException {
        LineUnavailableException last = null;
        for (float[] candidate : CANDIDATES) {
            AudioFormat format = new AudioFormat(candidate[0], 16, (int) candidate[1], true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            if (!AudioSystem.isLineSupported(info)) {
                continue;
            }
            try {
                TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
                line.open(format);
                JavaSoundRecorder recorder = new JavaSoundRecorder(line, format);
                line.start();
                recorder.reader.start();
                return recorder;
            } catch (LineUnavailableException | IllegalArgumentException | SecurityException error) {
                last = error instanceof LineUnavailableException ? (LineUnavailableException) error
                        : new LineUnavailableException(error.getMessage());
            }
        }
        throw last != null ? last : new LineUnavailableException("Kein Mikrofon gefunden");
    }

    private void read() {
        byte[] input = new byte[8192];
        try {
            while (running) {
                int count = line.read(input, 0, input.length);
                if (count <= 0) {
                    break;
                }
                measure(input, count);
                if (format.getChannels() == 1) {
                    audio.append(input, 0, count - (count & 1));
                } else {
                    byte[] mono = downmixStereo(input, count);
                    audio.append(mono, 0, mono.length);
                }
            }
        } catch (IOException | RuntimeException error) {
            if (running) {
                captureFailure = new IOException("Mikrofonaufnahme fehlgeschlagen", error);
            }
        } finally {
            running = false;
        }
    }

    private void measure(byte[] pcm, int count) {
        int peak = recentPeak;
        for (int position = 0; position + 1 < count; position += 2) {
            int value = sample(pcm, position);
            int magnitude = Math.abs(value);
            if (magnitude > peak) {
                peak = magnitude;
            }
            if (magnitude >= Short.MAX_VALUE) {
                clipped = true;
            }
        }
        recentPeak = peak;
    }

    static byte[] downmixStereo(byte[] input, int count) {
        if (count < 0 || count > input.length || (count & 3) != 0) {
            throw new IllegalArgumentException("Require complete stereo PCM16 frames");
        }
        byte[] mono = new byte[count / 2];
        for (int position = 0; position < count; position += 4) {
            int left = sample(input, position);
            int right = sample(input, position + 2);
            short mixed = (short) ((left + right) / 2);
            int target = position / 2;
            mono[target] = (byte) mixed;
            mono[target + 1] = (byte) (mixed >>> 8);
        }
        return mono;
    }

    private static short sample(byte[] input, int position) {
        return (short) ((input[position] & 0xff) | (input[position + 1] << 8));
    }

    int consumeRecentPeak() {
        int value = recentPeak;
        recentPeak = 0;
        return value;
    }

    boolean clipped() {
        return clipped;
    }

    RecordedSpeech stop() throws IOException {
        stopCapture();
        if (reader.isAlive()) {
            throw new IOException("Microphone capture thread did not stop");
        }
        audio.finish();
        if (captureFailure != null) {
            audio.close();
            throw captureFailure;
        }
        if (audio.isEmpty()) {
            audio.close();
            throw new IOException("Keine Audiodaten aufgenommen");
        }
        return new RecordedSpeech(audio);
    }

    void close() {
        stopCapture();
        if (!reader.isAlive()) {
            audio.close();
        }
    }

    private void stopCapture() {
        running = false;
        line.stop();
        line.close();
        if (Thread.currentThread() == reader) {
            return;
        }
        try {
            reader.join(3000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
