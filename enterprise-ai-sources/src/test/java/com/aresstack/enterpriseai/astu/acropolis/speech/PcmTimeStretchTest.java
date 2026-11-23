package com.aresstack.enterpriseai.astu.acropolis.speech;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Issue #93: the speaking rate changes duration, not pitch; rate 1 leaves the audio as it is. */
public class PcmTimeStretchTest {

    private static final int RATE = 24000;

    @Test
    public void normalRateReturnsTheInputUnchanged() {
        short[] input = speechLike(RATE * 2, 1);
        short[] output = stretch(input, 1, 1.0);
        assertTrue("alles kommt heraus: " + output.length, output.length >= input.length);
        short[] head = new short[input.length];
        System.arraycopy(output, 0, head, 0, input.length);
        assertArrayEquals(input, head);
    }

    @Test
    public void fasterAndSlowerChangeTheDurationButKeepThePitch() {
        short[] tone = sine(RATE * 2, 220.0);
        for (double rate : new double[]{0.5, 0.8, 1.25, 2.0}) {
            short[] output = stretch(tone, 1, rate);
            double expected = tone.length / rate;
            assertEquals("Dauer bei " + rate, expected, output.length, expected * 0.03);
            double frequency = zeroCrossings(output) / 2.0 / (output.length / (double) RATE);
            assertEquals("Tonhöhe bei " + rate, 220.0, frequency, 220.0 * 0.03);
        }
    }

    @Test
    public void rateMayChangeBetweenBlocksAndStereoStaysInterleaved() {
        short[] stereo = speechLike(RATE * 2, 2);
        PcmTimeStretch stretch = new PcmTimeStretch(RATE, 2);
        int block = RATE / 20;
        int total = 0;
        for (int start = 0, n = 0; start < stereo.length / 2; start += block, n++) {
            int frames = Math.min(block, stereo.length / 2 - start);
            short[] part = new short[frames * 2];
            System.arraycopy(stereo, start * 2, part, 0, part.length);
            short[] out = stretch.process(part, frames, start < RATE ? 1.0 : 2.0);
            assertEquals(0, out.length % 2);
            total += out.length / 2;
        }
        total += stretch.flush(2.0).length / 2;
        // First second at normal speed, second second twice as fast: about 1.5 seconds.
        assertEquals(RATE * 1.5, total, RATE * 0.05);
        assertEquals("nach flush beginnt das nächste Stück frisch", 0, stretch.flush(1.0).length);
    }

    private static short[] stretch(short[] input, int channels, double rate) {
        PcmTimeStretch stretch = new PcmTimeStretch(RATE, channels);
        int block = RATE / 20;
        short[][] parts = new short[input.length / channels / block + 2][];
        int count = 0;
        int frames = input.length / channels;
        for (int start = 0; start < frames; start += block) {
            int n = Math.min(block, frames - start);
            short[] part = new short[n * channels];
            System.arraycopy(input, start * channels, part, 0, part.length);
            parts[count++] = stretch.process(part, n, rate);
        }
        parts[count++] = stretch.flush(rate);
        int length = 0;
        for (int i = 0; i < count; i++) {
            length += parts[i].length;
        }
        short[] output = new short[length];
        int at = 0;
        for (int i = 0; i < count; i++) {
            System.arraycopy(parts[i], 0, output, at, parts[i].length);
            at += parts[i].length;
        }
        return trimTrailingSilence(output, channels);
    }

    private static short[] trimTrailingSilence(short[] samples, int channels) {
        int end = samples.length;
        while (end >= channels && samples[end - 1] == 0) {
            end--;
        }
        end -= end % channels;
        short[] trimmed = new short[end];
        System.arraycopy(samples, 0, trimmed, 0, end);
        return trimmed;
    }

    private static short[] sine(int frames, double frequency) {
        short[] samples = new short[frames];
        for (int i = 0; i < frames; i++) {
            samples[i] = (short) Math.round(8000 * Math.sin(2 * Math.PI * frequency * i / RATE));
        }
        samples[frames - 1] = 1; // keep the real end visible to the trimming
        return samples;
    }

    /** A voiced, gliding signal with an envelope, like a syllable; never exactly zero. */
    private static short[] speechLike(int frames, int channels) {
        short[] samples = new short[frames * channels];
        double phase = 0;
        for (int i = 0; i < frames; i++) {
            double t = i / (double) RATE;
            phase += 2 * Math.PI * (140 + 40 * Math.sin(2 * Math.PI * 3 * t)) / RATE;
            double envelope = 0.55 + 0.45 * Math.sin(2 * Math.PI * 4 * t);
            double value = envelope * (6000 * Math.sin(phase) + 2000 * Math.sin(3 * phase));
            for (int c = 0; c < channels; c++) {
                samples[i * channels + c] = (short) (Math.round(value * (c == 0 ? 1 : 0.5)) | 1);
            }
        }
        return samples;
    }

    private static int zeroCrossings(short[] samples) {
        int crossings = 0;
        for (int i = 1; i < samples.length; i++) {
            if ((samples[i - 1] < 0) != (samples[i] < 0)) {
                crossings++;
            }
        }
        return crossings;
    }
}
