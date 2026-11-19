package com.aresstack.spaceharvester.app.ui.content.viewer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Find the slide changes of a screen-recorded presentation from the MP4 index alone, without video data.
 *
 * <p>While a slide is shown the picture is still, so the encoder needs only a few hundred bytes per frame. A new
 * slide produces one frame tens of kilobytes large. Key frames are not used as change points because encoders
 * place them at a fixed interval (about every 30 s in the BSCW recordings); a burst of large frames within three
 * seconds, as produced by a slide animation or a moving window, counts as one change.</p>
 */
final class SlideChanges {
    /** A frame at least this many times the median frame size marks a change. */
    private static final int SIZE_FACTOR = 20;
    /** Frames smaller than this are never changes, even if the median is tiny. */
    private static final int MIN_CHANGE_BYTES = 2048;
    /** Large frames closer than this belong to the same change. */
    private static final long BURST_MILLIS = 3000;
    /** The picture at the very beginning is the first slide, not a change. */
    private static final long START_MILLIS = 1500;
    private static final int MAX_SAMPLES = 5_000_000;

    private SlideChanges() {
    }

    /** Read the MP4 index of a remote file and return its slide changes in milliseconds. */
    static List<Long> load(MediaStreamBridge.ChunkSource source, long length) throws IOException {
        long offset = 0;
        for (int inspected = 0; offset + 8 <= length && inspected < 128; inspected++) {
            byte[] head = Mp4AudioOnlySource.fetch(source, offset, (int) Math.min(16, length - offset));
            ByteBuffer header = ByteBuffer.wrap(head);
            long size = header.getInt() & 0xffffffffL;
            String type = new String(head, 4, 4, StandardCharsets.ISO_8859_1);
            if (size == 1 && head.length >= 16) {
                size = header.getLong(8);
            } else if (size == 0) {
                size = length - offset;
            }
            if (size < 8) {
                break;
            }
            if ("moov".equals(type)) {
                return detect(Mp4AudioOnlySource.readBox(source, offset, size));
            }
            offset += size;
        }
        return Collections.emptyList();
    }

    /** Detect slide changes in a {@code moov} box (with or without its own header). */
    static List<Long> detect(byte[] moov) {
        int start = moov.length >= 8 && "moov".equals(new String(moov, 4, 4, StandardCharsets.ISO_8859_1)) ? 8 : 0;
        int[] track = videoTrack(moov, start, moov.length);
        if (track == null) {
            return Collections.emptyList();
        }
        int[] mdhd = find(moov, track[0], track[1], "mdia", "mdhd");
        int[] stts = find(moov, track[0], track[1], "mdia", "minf", "stbl", "stts");
        int[] stsz = find(moov, track[0], track[1], "mdia", "minf", "stbl", "stsz");
        int[] stss = find(moov, track[0], track[1], "mdia", "minf", "stbl", "stss");
        if (mdhd == null || stts == null || stsz == null) {
            return Collections.emptyList();
        }
        ByteBuffer data = ByteBuffer.wrap(moov);
        long timescale = (data.get(mdhd[0]) == 1 ? data.getInt(mdhd[0] + 20) : data.getInt(mdhd[0] + 12)) & 0xffffffffL;
        int fixedSize = data.getInt(stsz[0] + 4);
        int samples = data.getInt(stsz[0] + 8);
        if (timescale == 0 || samples <= 0 || samples > MAX_SAMPLES || fixedSize != 0) {
            return Collections.emptyList();
        }
        int[] sizes = new int[samples];
        for (int i = 0; i < samples; i++) {
            sizes[i] = data.getInt(stsz[0] + 12 + 4 * i);
        }
        long[] times = new long[samples];
        int entries = data.getInt(stts[0] + 4);
        long time = 0;
        int sample = 0;
        for (int entry = 0; entry < entries && sample < samples; entry++) {
            long count = data.getInt(stts[0] + 8 + 8 * entry) & 0xffffffffL;
            long delta = data.getInt(stts[0] + 12 + 8 * entry) & 0xffffffffL;
            for (long k = 0; k < count && sample < samples; k++) {
                times[sample++] = time * 1000 / timescale;
                time += delta;
            }
        }
        boolean[] key = new boolean[samples];
        if (stss != null) {
            int keys = data.getInt(stss[0] + 4);
            for (int i = 0; i < keys; i++) {
                int index = data.getInt(stss[0] + 8 + 4 * i) - 1;
                if (index >= 0 && index < samples) {
                    key[index] = true;
                }
            }
        }
        int[] sorted = sizes.clone();
        Arrays.sort(sorted);
        long threshold = Math.max(MIN_CHANGE_BYTES, (long) sorted[samples / 2] * SIZE_FACTOR);
        List<Long> changes = new ArrayList<>();
        long burstEnd = -BURST_MILLIS;
        for (int i = 0; i < samples; i++) {
            if (key[i] || sizes[i] < threshold || times[i] < START_MILLIS) {
                continue;
            }
            if (times[i] - burstEnd >= BURST_MILLIS) {
                changes.add(times[i]);
            }
            burstEnd = times[i];
        }
        return Collections.unmodifiableList(changes);
    }

    /** The first change after {@code positionMillis}, or -1. */
    static long next(List<Long> changes, long positionMillis) {
        for (long change : changes) {
            if (change > positionMillis + 500) {
                return change;
            }
        }
        return -1;
    }

    /**
     * The change before the current slide's start, or 0 for the first slide. Shortly after a change, "previous"
     * goes to the slide before, like the previous-track button of a player.
     */
    static long previous(List<Long> changes, long positionMillis) {
        long previous = 0;
        for (long change : changes) {
            if (change >= positionMillis - 2000) {
                break;
            }
            previous = change;
        }
        return previous;
    }

    private static int[] videoTrack(byte[] moov, int start, int end) {
        for (int position = start; position + 8 <= end; ) {
            int size = boxSize(moov, position, end);
            if (size < 8) {
                return null;
            }
            if ("trak".equals(type(moov, position))) {
                int[] hdlr = find(moov, position + 8, position + size, "mdia", "hdlr");
                if (hdlr != null && hdlr[0] + 12 <= hdlr[1] && "vide".equals(type(moov, hdlr[0] + 4))) {
                    return new int[]{position + 8, position + size};
                }
            }
            position += size;
        }
        return null;
    }

    /** The content range of the box at the given path inside {@code [start, end)}, or {@code null}. */
    private static int[] find(byte[] moov, int start, int end, String... path) {
        int from = start;
        int to = end;
        for (String name : path) {
            int[] found = null;
            for (int position = from; position + 8 <= to; ) {
                int size = boxSize(moov, position, to);
                if (size < 8) {
                    return null;
                }
                if (name.equals(type(moov, position))) {
                    found = new int[]{position + 8, position + size};
                    break;
                }
                position += size;
            }
            if (found == null) {
                return null;
            }
            from = found[0];
            to = found[1];
        }
        return new int[]{from, to};
    }

    private static int boxSize(byte[] moov, int position, int end) {
        long size = ByteBuffer.wrap(moov).getInt(position) & 0xffffffffL;
        return size < 8 || position + size > end ? -1 : (int) size;
    }

    private static String type(byte[] moov, int position) {
        return new String(moov, position + 4, 4, StandardCharsets.ISO_8859_1);
    }
}
