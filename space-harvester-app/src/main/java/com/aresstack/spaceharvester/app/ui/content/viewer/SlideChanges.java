package com.aresstack.spaceharvester.app.ui.content.viewer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import com.aresstack.spaceharvester.app.settings.VideoSettings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Find the slide changes of a screen-recorded presentation from the MP4 index alone, without video data.
 *
 * <p>While a slide is shown the picture is still, so the encoder needs only a few hundred bytes per frame. A new
 * slide produces one frame tens of kilobytes large. Key frames are not used as change points because encoders
 * place them at a fixed interval (about every 30 s in the BSCW recordings); a burst of large frames within a short
 * time, as produced by a slide animation or a moving window, counts as one change.</p>
 *
 * <p>A video that is no presentation (moving pictures, a live demo) has large frames all the time. As a safeguard it
 * gets at most so many marks per minute and per video ({@link VideoSettings.Value#MAX_MARKS_PER_MINUTE},
 * {@link VideoSettings.Value#MAX_MARKS}): then only the strongest changes stay, which are its scene changes. All values
 * come from {@link VideoSettings}.</p>
 */
final class SlideChanges {
    /** More samples than any video track has; a larger count means a damaged index. */
    private static final int MAX_SAMPLES = 5_000_000;

    private SlideChanges() {
    }

    /** Read the MP4 index of a remote file and return its slide changes in milliseconds. */
    static List<Long> load(MediaStreamBridge.ChunkSource source, long length, VideoSettings settings)
            throws IOException {
        Mp4AudioOnlySource.indexReaders(settings);
        byte[] moov = moov(source, length);
        return moov == null ? Collections.emptyList() : detect(moov, settings);
    }

    /** Like {@link #load(MediaStreamBridge.ChunkSource, long, VideoSettings)} with the defaults. */
    static List<Long> load(MediaStreamBridge.ChunkSource source, long length) throws IOException {
        return load(source, length, VideoSettings.DEFAULT);
    }

    /** The length of a remote MP4 file from its movie header ({@code mvhd}), or 0 if unreadable. */
    static long durationMillis(MediaStreamBridge.ChunkSource source, long length) throws IOException {
        long[] box = moovBox(source, length);
        if (box == null) {
            return 0;
        }
        // The movie header comes first in the moov box: its head suffices, not the whole index.
        byte[] moov = Mp4AudioOnlySource.fetch(source, box[0], (int) Math.min(box[1], MOVIE_HEADER_BYTES));
        int start = moov.length >= 8 && "moov".equals(new String(moov, 4, 4, StandardCharsets.ISO_8859_1)) ? 8 : 0;
        int[] mvhd = find(moov, start, moov.length, "mvhd");
        if (mvhd == null) {
            return 0;
        }
        ByteBuffer data = ByteBuffer.wrap(moov);
        boolean wide = data.get(mvhd[0]) == 1;
        long timescale = data.getInt(mvhd[0] + (wide ? 20 : 12)) & 0xffffffffL;
        long duration = wide ? data.getLong(mvhd[0] + 24) : data.getInt(mvhd[0] + 16) & 0xffffffffL;
        return timescale == 0 ? 0 : duration * 1000 / timescale;
    }

    /** Enough of the moov box for its movie header ({@code mvhd}), which comes first. */
    private static final int MOVIE_HEADER_BYTES = 512;

    /** The {@code moov} box of a remote MP4 file, or {@code null} if there is none near the top level. */
    private static byte[] moov(MediaStreamBridge.ChunkSource source, long length) throws IOException {
        long[] box = moovBox(source, length);
        return box == null ? null : Mp4AudioOnlySource.readBox(source, box[0], box[1]);
    }

    /** Offset and size of the {@code moov} box of a remote MP4 file, or {@code null}. */
    private static long[] moovBox(MediaStreamBridge.ChunkSource source, long length) throws IOException {
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
                return new long[] {offset, size};
            }
            offset += size;
        }
        return null;
    }

    /** Like {@link #detect(byte[], VideoSettings)} with the defaults. */
    static List<Long> detect(byte[] moov) {
        return detect(moov, VideoSettings.DEFAULT);
    }

    /** Detect slide changes in a {@code moov} box (with or without its own header). */
    static List<Long> detect(byte[] moov, VideoSettings settings) {
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
        long threshold = Math.max(settings.get(VideoSettings.Value.MIN_CHANGE_BYTES),
                (long) sorted[samples / 2] * settings.get(VideoSettings.Value.SIZE_FACTOR));
        long burst = settings.get(VideoSettings.Value.BURST_MILLIS);
        long firstSlide = settings.get(VideoSettings.Value.START_MILLIS);
        // Each change with the largest frame of its burst, the measure of how strong it is.
        List<long[]> changes = new ArrayList<>();
        long burstEnd = -burst;
        for (int i = 0; i < samples; i++) {
            if (key[i] || sizes[i] < threshold || times[i] < firstSlide) {
                continue;
            }
            if (times[i] - burstEnd >= burst) {
                changes.add(new long[]{times[i], sizes[i]});
            } else if (!changes.isEmpty()) {
                long[] last = changes.get(changes.size() - 1);
                last[1] = Math.max(last[1], sizes[i]);
            }
            burstEnd = times[i];
        }
        return strongest(changes, times[samples - 1], settings);
    }

    /**
     * The safeguard for videos that are no presentation: at most so many changes per minute of video and per video;
     * if there are more, the strongest (largest frame) stay, in time order.
     *
     * @param changes time and strength of each change, in time order
     */
    static List<Long> strongest(List<long[]> changes, long durationMillis, VideoSettings settings) {
        long perMinute = settings.get(VideoSettings.Value.MAX_MARKS_PER_MINUTE);
        long minutes = Math.max(1, (durationMillis + 59_999) / 60_000);
        long allowed = Math.min(settings.get(VideoSettings.Value.MAX_MARKS), minutes * perMinute);
        List<long[]> kept = changes;
        if (changes.size() > allowed) {
            kept = new ArrayList<>(changes);
            kept.sort(Comparator.comparingLong((long[] change) -> change[1]).reversed());
            kept = new ArrayList<>(kept.subList(0, (int) allowed));
            kept.sort(Comparator.comparingLong(change -> change[0]));
        }
        List<Long> times = new ArrayList<>(kept.size());
        kept.forEach(change -> times.add(change[0]));
        return Collections.unmodifiableList(times);
    }

    /** Like {@link #next(List, long, VideoSettings)} with the defaults. */
    static long next(List<Long> changes, long positionMillis) {
        return next(changes, positionMillis, VideoSettings.DEFAULT);
    }

    /** The first change after {@code positionMillis} (not within the skip distance), or -1. */
    static long next(List<Long> changes, long positionMillis, VideoSettings settings) {
        long skip = settings.get(VideoSettings.Value.NEXT_SKIP_MILLIS);
        for (long change : changes) {
            if (change > positionMillis + skip) {
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
        return previous(changes, positionMillis, VideoSettings.DEFAULT);
    }

    /** Like {@link #previous(List, long)} with the grace time from {@code settings}. */
    static long previous(List<Long> changes, long positionMillis, VideoSettings settings) {
        long grace = settings.get(VideoSettings.Value.PREVIOUS_GRACE_MILLIS);
        long previous = 0;
        for (long change : changes) {
            if (change >= positionMillis - grace) {
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
