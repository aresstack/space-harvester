package com.aresstack.enterpriseai.astu.acropolis.speech;

/**
 * Changes the speed of 16-bit PCM speech without changing its pitch (WSOLA: waveform similarity overlap-add).
 *
 * <p>Streaming: {@link #process} takes the next block of interleaved samples and returns what is ready at the given
 * rate; the rate may change from block to block, so a speed change applies in the middle of a chunk. {@link #flush}
 * returns the rest and resets the state for the next chunk. At rate 1 every frame continues exactly where the previous
 * one ended, so the output equals the input.
 *
 * <p>Each output hop cross-fades the tail of the previous frame with the input frame near the nominal position
 * (input advances by hop x rate) that best matches the natural continuation of the previous frame; matching uses the
 * first channel. Not thread-safe; one instance per playback.
 */
public final class PcmTimeStretch {

    /** Output hop: one sixtieth of a second, about 17 ms (frames of 33 ms, half overlapping). */
    private static final int HOPS_PER_SECOND = 60;
    /** Search tolerance around the nominal position: one hundredth of a second. */
    private static final int TOLERANCES_PER_SECOND = 100;

    private final int channels;
    private final int hop;
    private final int tolerance;
    private final float[] fadeIn;
    private final float[] fadeOut;

    /** Buffered input per channel; {@code in[c][i]} is the frame at absolute position {@code base + i}. */
    private float[][] in;
    private int base;
    private int length;
    private double nominal;
    private int previous = -1;
    private final float[][] tail;

    public PcmTimeStretch(int sampleRate, int channels) {
        if (sampleRate <= 0 || channels <= 0) {
            throw new IllegalArgumentException("sampleRate and channels must be positive");
        }
        this.channels = channels;
        this.hop = Math.max(8, sampleRate / HOPS_PER_SECOND);
        this.tolerance = Math.max(1, sampleRate / TOLERANCES_PER_SECOND);
        this.fadeIn = new float[hop];
        this.fadeOut = new float[hop];
        for (int i = 0; i < hop; i++) {
            double rising = 0.5 - 0.5 * Math.cos(Math.PI * (i + 0.5) / hop);
            fadeIn[i] = (float) rising;
            fadeOut[i] = (float) (1.0 - rising);
        }
        this.in = new float[channels][hop * 8];
        this.tail = new float[channels][hop];
    }

    /**
     * Appends {@code frames} interleaved frames of {@code samples} and returns the interleaved output that is ready
     * at {@code rate} (see {@link SpeechRate}).
     */
    public short[] process(short[] samples, int frames, double rate) {
        append(samples, frames);
        return run(SpeechRate.clamp(rate), base + length);
    }

    /** Returns the remaining output of the current chunk and resets for the next one. */
    public short[] flush(double rate) {
        int end = base + length;
        if (previous < 0 && length == 0) {
            return new short[0];
        }
        // Pad with silence so the last frames find their candidates, but stop at the real end.
        append(new short[(2 * hop + 2 * tolerance) * channels], 2 * hop + 2 * tolerance);
        Output out = new Output(channels);
        run(SpeechRate.clamp(rate), end, out);
        if (previous >= 0) {
            out.add(tail, 0, hop);
        }
        reset();
        return out.toShorts();
    }

    private void reset() {
        base = 0;
        length = 0;
        nominal = 0;
        previous = -1;
    }

    private short[] run(double rate, int end) {
        Output out = new Output(channels);
        run(rate, end, out);
        return out.toShorts();
    }

    /** Emits hops while all candidates are buffered and the nominal position lies before {@code end}. */
    private void run(double rate, int end, Output out) {
        while (true) {
            int nominalFrame = (int) Math.round(nominal);
            if (nominalFrame >= end && previous >= 0) {
                return;
            }
            if (nominalFrame + tolerance + 2 * hop > base + length) {
                return;
            }
            if (previous < 0) {
                int start = Math.max(base, nominalFrame);
                out.add(in, start - base, hop);
                copyTail(start + hop);
                previous = start;
            } else {
                int chosen = choose(rate, nominalFrame);
                float[] mixed = new float[hop];
                for (int c = 0; c < channels; c++) {
                    float[] channel = in[c];
                    int offset = chosen - base;
                    for (int i = 0; i < hop; i++) {
                        mixed[i] = tail[c][i] * fadeOut[i] + channel[offset + i] * fadeIn[i];
                    }
                    out.addChannel(c, mixed, hop);
                }
                out.commit(hop);
                copyTail(chosen + hop);
                previous = chosen;
            }
            nominal += hop * rate;
            discard();
        }
    }

    /** The candidate start that best continues the previous frame; at rate 1 the natural continuation itself. */
    private int choose(double rate, int nominalFrame) {
        int natural = previous + hop;
        if (rate == SpeechRate.NORMAL) {
            return natural;
        }
        int from = Math.max(base, nominalFrame - tolerance);
        int to = Math.min(base + length - 2 * hop, nominalFrame + tolerance);
        float[] signal = in[0];
        int naturalOffset = natural - base;
        double naturalEnergy = 0;
        for (int i = 0; i < hop; i++) {
            naturalEnergy += signal[naturalOffset + i] * signal[naturalOffset + i];
        }
        int best = Math.max(from, Math.min(to, nominalFrame));
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int k = from; k <= to; k++) {
            int offset = k - base;
            double dot = 0;
            double energy = 0;
            for (int i = 0; i < hop; i++) {
                float value = signal[offset + i];
                dot += value * signal[naturalOffset + i];
                energy += value * value;
            }
            double score = dot / Math.sqrt(energy * naturalEnergy + 1e-9);
            if (score > bestScore) {
                bestScore = score;
                best = k;
            }
        }
        return best;
    }

    private void copyTail(int start) {
        for (int c = 0; c < channels; c++) {
            System.arraycopy(in[c], start - base, tail[c], 0, hop);
        }
    }

    /** Drops input no future candidate or continuation can reach. */
    private void discard() {
        int keepFrom = Math.min(previous + hop, (int) Math.floor(nominal) - tolerance);
        int drop = keepFrom - base;
        if (drop > hop * 4) {
            for (int c = 0; c < channels; c++) {
                System.arraycopy(in[c], drop, in[c], 0, length - drop);
            }
            base += drop;
            length -= drop;
        }
    }

    private void append(short[] samples, int frames) {
        if (length + frames > in[0].length) {
            int capacity = Math.max(in[0].length * 2, length + frames);
            for (int c = 0; c < channels; c++) {
                float[] grown = new float[capacity];
                System.arraycopy(in[c], 0, grown, 0, length);
                in[c] = grown;
            }
        }
        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < channels; c++) {
                in[c][length + f] = samples[f * channels + c];
            }
        }
        length += frames;
    }

    /** Collects output frames per channel and interleaves them as 16-bit samples. */
    private static final class Output {
        private final int channels;
        private float[][] frames;
        private int count;

        Output(int channels) {
            this.channels = channels;
            this.frames = new float[channels][256];
        }

        void add(float[][] source, int offset, int n) {
            ensure(n);
            for (int c = 0; c < channels; c++) {
                System.arraycopy(source[c], offset, frames[c], count, n);
            }
            count += n;
        }

        void addChannel(int channel, float[] source, int n) {
            ensure(n);
            System.arraycopy(source, 0, frames[channel], count, n);
        }

        void commit(int n) {
            count += n;
        }

        private void ensure(int n) {
            if (count + n > frames[0].length) {
                int capacity = Math.max(frames[0].length * 2, count + n);
                for (int c = 0; c < channels; c++) {
                    float[] grown = new float[capacity];
                    System.arraycopy(frames[c], 0, grown, 0, count);
                    frames[c] = grown;
                }
            }
        }

        short[] toShorts() {
            short[] samples = new short[count * channels];
            for (int f = 0; f < count; f++) {
                for (int c = 0; c < channels; c++) {
                    float value = Math.round(frames[c][f]);
                    samples[f * channels + c] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
                }
            }
            return samples;
        }
    }
}
