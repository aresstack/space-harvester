package com.aresstack.spaceharvester.app.ui.content.viewer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.aresstack.spaceharvester.app.ui.content.viewer.Mp4AudioOnlySource.children;
import static com.aresstack.spaceharvester.app.ui.content.viewer.Mp4AudioOnlySource.ensure;
import static com.aresstack.spaceharvester.app.ui.content.viewer.Mp4AudioOnlySource.int64;
import static com.aresstack.spaceharvester.app.ui.content.viewer.Mp4AudioOnlySource.requiredChild;
import static com.aresstack.spaceharvester.app.ui.content.viewer.Mp4AudioOnlySource.uint32;
import static com.aresstack.spaceharvester.app.ui.content.viewer.Mp4AudioOnlySource.unsupported;
import static com.aresstack.spaceharvester.app.ui.content.viewer.Mp4AudioOnlySource.write32;
import static com.aresstack.spaceharvester.app.ui.content.viewer.Mp4AudioOnlySource.write64;

/**
 * Cut the audio track of an MP4 index so that it begins at the chunk that holds a given time: a stopped transcription
 * resumes there without fetching anything before it. The sample tables are rewritten from that chunk on (times, chunk
 * rules, sizes, offsets, per-sample flags and groups), the edit list is dropped and the durations shortened, so the
 * decoded audio begins exactly at the chunk's start time from the index. VLC's own seek ({@code :start-time}) landed
 * 0.5 to 1 s early depending on the chunk layout, which shifted every time and repeated audio at the seam.
 */
final class Mp4AudioTrim {

    /** Per-sample or per-chunk tables this class knows how to cut; any other table leaves the track uncut. */
    private static final Set<String> KNOWN = Set.of("stsd", "stts", "ctts", "stsc", "stsz", "stco", "co64", "stss",
            "sdtp", "sbgp", "sgpd");

    /**
     * The cut audio track.
     *
     * @param trak          the new {@code trak} box
     * @param offsetEntries where the chunk offsets begin inside {@code trak}; the caller writes them
     * @param longOffsets   whether the offsets are 64 bits wide ({@code co64})
     * @param firstChunk    the first chunk that remains, zero-based
     * @param startMillis   when that chunk begins in the whole track
     */
    record Result(byte[] trak, int offsetEntries, boolean longOffsets, int firstChunk, long startMillis) {
    }

    private Mp4AudioTrim() {
    }

    /**
     * The audio track from the chunk that holds {@code fromMillis} on; {@code null} if that is the first chunk or the
     * track has tables this class cannot cut (then the audio starts at the beginning).
     *
     * @param movieTimescale timescale of the movie header, for the track header's duration
     */
    static Result from(byte[] moov, Mp4AudioOnlySource.Box trak, long fromMillis, long movieTimescale)
            throws IOException {
        Mp4AudioOnlySource.Box mdia = requiredChild(moov, trak, "mdia");
        Mp4AudioOnlySource.Box mdhd = requiredChild(moov, mdia, "mdhd");
        Mp4AudioOnlySource.Box minf = requiredChild(moov, mdia, "minf");
        Mp4AudioOnlySource.Box stbl = requiredChild(moov, minf, "stbl");
        for (Mp4AudioOnlySource.Box table : children(moov, stbl)) {
            if (!KNOWN.contains(table.type)) {
                return null;
            }
        }
        int mdhdContent = mdhd.start + mdhd.header;
        ensure(moov, mdhdContent, 32);
        boolean wideMdhd = moov[mdhdContent] == 1;
        long timescale = uint32(moov, mdhdContent + (wideMdhd ? 20 : 12));
        if (timescale == 0) {
            return null;
        }
        long[][] stts = runs(moov, requiredChild(moov, stbl, "stts"), 0);
        long[][] stsc = triples(moov, requiredChild(moov, stbl, "stsc"));
        Mp4AudioOnlySource.Box offsets = child(moov, stbl, "stco");
        boolean longOffsets = offsets == null;
        if (longOffsets) {
            offsets = requiredChild(moov, stbl, "co64");
        }
        int chunkCount = (int) uint32(moov, offsets.start + offsets.header + 4);

        // The chunk that holds the time: walk the chunks with their sample counts and the sample durations.
        int run = 0;
        long leftInRun = stts.length == 0 ? 0 : stts[0][0];
        long time = 0;
        long sample = 0;
        int rule = 0;
        int cut = 0;
        long cutSample = 0;
        long cutTime = 0;
        for (int chunk = 0; chunk < chunkCount; chunk++) {
            while (rule + 1 < stsc.length && stsc[rule + 1][0] <= chunk + 1L) {
                rule++;
            }
            if (time * 1000 / timescale > fromMillis) {
                break;
            }
            cut = chunk;
            cutSample = sample;
            cutTime = time;
            for (long j = 0; j < stsc[rule][1]; j++) {
                while (leftInRun == 0 && run + 1 < stts.length) {
                    leftInRun = stts[++run][0];
                }
                if (leftInRun == 0) {
                    throw unsupported("MP4 sample times end before the samples");
                }
                time += stts[run][1];
                leftInRun--;
                sample++;
            }
        }
        if (cut == 0) {
            return null;
        }
        long removedSamples = cutSample;
        long startMillis = cutTime * 1000 / timescale;

        ByteArrayOutputStream tables = new ByteArrayOutputStream();
        for (Mp4AudioOnlySource.Box table : children(moov, stbl)) {
            byte[] content = switch (table.type) {
                case "stts" -> runsWithout(moov, table, 0, removedSamples);
                case "ctts" -> runsWithout(moov, table, 0, removedSamples);
                case "stsc" -> rulesFrom(stsc, cut, versionFlags(moov, table));
                case "stsz" -> sizesWithout(moov, table, removedSamples);
                case "stco" -> offsetsFrom(moov, table, cut, 4);
                case "co64" -> offsetsFrom(moov, table, cut, 8);
                case "stss" -> syncWithout(moov, table, removedSamples);
                case "sdtp" -> flagsWithout(moov, table, removedSamples);
                case "sbgp" -> groupsWithout(moov, table, removedSamples);
                default -> content(moov, table);
            };
            tables.write(box(table.type, content));
        }
        byte[] newStbl = box("stbl", tables.toByteArray());
        byte[] newMinf = replaced(moov, minf, "stbl", newStbl);
        long removedTime = cutTime;
        byte[] newMdhd = withDuration(content(moov, mdhd), wideMdhd ? 24 : 16, wideMdhd, removedTime);
        long mediaDuration = duration(newMdhd, wideMdhd ? 24 : 16, wideMdhd);
        byte[] newMdia = replaced(moov, mdia, "minf", newMinf, "mdhd", box("mdhd", newMdhd));

        ByteArrayOutputStream trakContent = new ByteArrayOutputStream();
        for (Mp4AudioOnlySource.Box part : children(moov, trak)) {
            switch (part.type) {
                // Without the edit list the decoded audio begins at the first remaining sample.
                case "edts" -> {
                }
                case "tkhd" -> {
                    byte[] header = content(moov, part);
                    boolean wide = header[0] == 1;
                    setDuration(header, wide ? 28 : 20, wide, mediaDuration * movieTimescale / timescale);
                    trakContent.write(box("tkhd", header));
                }
                case "mdia" -> trakContent.write(newMdia);
                default -> trakContent.write(box(part.type, content(moov, part)));
            }
        }
        byte[] newTrak = box("trak", trakContent.toByteArray());
        Mp4AudioOnlySource.Box root = Mp4AudioOnlySource.box(newTrak, 0, newTrak.length, newTrak.length);
        Mp4AudioOnlySource.Box newOffsets = requiredChild(newTrak, requiredChild(newTrak, requiredChild(newTrak,
                requiredChild(newTrak, root, "mdia"), "minf"), "stbl"), longOffsets ? "co64" : "stco");
        return new Result(newTrak, newOffsets.start + newOffsets.header + 8, longOffsets, cut, startMillis);
    }

    /** The duration a cut track keeps, for the movie header. */
    static long duration(byte[] header, int at, boolean wide) {
        return wide ? int64(header, at) : uint32(header, at);
    }

    private static byte[] withDuration(byte[] header, int at, boolean wide, long removed) {
        setDuration(header, at, wide, Math.max(0, duration(header, at, wide) - removed));
        return header;
    }

    private static void setDuration(byte[] header, int at, boolean wide, long value) {
        if (wide) {
            write64(header, at, value);
        } else {
            write32(header, at, Math.min(0xffffffffL, value));
        }
    }

    private static Mp4AudioOnlySource.Box child(byte[] data, Mp4AudioOnlySource.Box parent, String type)
            throws IOException {
        return Mp4AudioOnlySource.optionalChild(data, parent, type);
    }

    /** {@code parent} with the children named in {@code replacements} (type, box, type, box …) replaced. */
    private static byte[] replaced(byte[] data, Mp4AudioOnlySource.Box parent, Object... replacements)
            throws IOException {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        for (Mp4AudioOnlySource.Box part : children(data, parent)) {
            byte[] replacement = null;
            for (int i = 0; i < replacements.length; i += 2) {
                if (replacements[i].equals(part.type)) {
                    replacement = (byte[]) replacements[i + 1];
                }
            }
            content.write(replacement != null ? replacement : box(part.type, content(data, part)));
        }
        return box(parent.type, content.toByteArray());
    }

    private static byte[] content(byte[] data, Mp4AudioOnlySource.Box box) {
        byte[] content = new byte[(int) (box.size - box.header)];
        System.arraycopy(data, box.start + box.header, content, 0, content.length);
        return content;
    }

    private static byte[] box(String type, byte[] content) {
        byte[] box = new byte[content.length + 8];
        write32(box, 0, box.length);
        Mp4AudioOnlySource.writeType(box, 4, type);
        System.arraycopy(content, 0, box, 8, content.length);
        return box;
    }

    private static long versionFlags(byte[] data, Mp4AudioOnlySource.Box box) {
        return uint32(data, box.start + box.header);
    }

    /** The (count, value) runs of {@code stts} or {@code ctts}, after {@code skip} bytes of the box's fields. */
    private static long[][] runs(byte[] data, Mp4AudioOnlySource.Box box, int skip) throws IOException {
        int at = box.start + box.header + 4 + skip;
        ensure(data, at, 4);
        long count = uint32(data, at);
        ensure(data, at + 4, count * 8);
        long[][] runs = new long[(int) count][2];
        for (int i = 0; i < count; i++) {
            runs[i][0] = uint32(data, at + 4 + i * 8);
            runs[i][1] = uint32(data, at + 8 + i * 8);
        }
        return runs;
    }

    private static long[][] triples(byte[] data, Mp4AudioOnlySource.Box box) throws IOException {
        int at = box.start + box.header + 4;
        ensure(data, at, 4);
        long count = uint32(data, at);
        ensure(data, at + 4, count * 12);
        long[][] rules = new long[(int) count][3];
        for (int i = 0; i < count; i++) {
            for (int j = 0; j < 3; j++) {
                rules[i][j] = uint32(data, at + 4 + i * 12 + j * 4);
            }
        }
        return rules;
    }

    /** {@code stts}/{@code ctts} without the first {@code removed} samples. */
    private static byte[] runsWithout(byte[] data, Mp4AudioOnlySource.Box box, int skip, long removed)
            throws IOException {
        long[][] runs = runs(data, box, skip);
        List<long[]> kept = new ArrayList<>();
        long left = removed;
        for (long[] run : runs) {
            long count = run[0];
            long drop = Math.min(count, left);
            left -= drop;
            if (count - drop > 0) {
                kept.add(new long[]{count - drop, run[1]});
            }
        }
        byte[] content = new byte[8 + skip + kept.size() * 8];
        write32(content, 0, versionFlags(data, box));
        System.arraycopy(data, box.start + box.header + 4, content, 4, skip);
        write32(content, 4 + skip, kept.size());
        for (int i = 0; i < kept.size(); i++) {
            write32(content, 8 + skip + i * 8, kept.get(i)[0]);
            write32(content, 12 + skip + i * 8, kept.get(i)[1]);
        }
        return content;
    }

    /** {@code stsc} for the chunks from {@code cut} on, numbered from 1 again. */
    private static byte[] rulesFrom(long[][] rules, int cut, long versionFlags) {
        List<long[]> kept = new ArrayList<>();
        for (int i = 0; i < rules.length; i++) {
            long first = rules[i][0];
            long nextFirst = i + 1 < rules.length ? rules[i + 1][0] : Long.MAX_VALUE;
            if (nextFirst <= cut + 1L) {
                continue;
            }
            kept.add(new long[]{Math.max(1, first - cut), rules[i][1], rules[i][2]});
        }
        byte[] content = new byte[8 + kept.size() * 12];
        write32(content, 0, versionFlags);
        write32(content, 4, kept.size());
        for (int i = 0; i < kept.size(); i++) {
            for (int j = 0; j < 3; j++) {
                write32(content, 8 + i * 12 + j * 4, kept.get(i)[j]);
            }
        }
        return content;
    }

    private static byte[] sizesWithout(byte[] data, Mp4AudioOnlySource.Box box, long removed) throws IOException {
        int at = box.start + box.header;
        long fixed = uint32(data, at + 4);
        long count = uint32(data, at + 8);
        long remaining = count - removed;
        byte[] content = new byte[(int) (12 + (fixed == 0 ? remaining * 4 : 0))];
        write32(content, 0, uint32(data, at));
        write32(content, 4, fixed);
        write32(content, 8, remaining);
        if (fixed == 0) {
            ensure(data, at + 12, count * 4);
            System.arraycopy(data, (int) (at + 12 + removed * 4), content, 12, (int) (remaining * 4));
        }
        return content;
    }

    private static byte[] offsetsFrom(byte[] data, Mp4AudioOnlySource.Box box, int cut, int width) {
        int at = box.start + box.header;
        long count = uint32(data, at + 4);
        long remaining = count - cut;
        byte[] content = new byte[(int) (8 + remaining * width)];
        write32(content, 0, uint32(data, at));
        write32(content, 4, remaining);
        System.arraycopy(data, at + 8 + cut * width, content, 8, (int) (remaining * width));
        return content;
    }

    private static byte[] syncWithout(byte[] data, Mp4AudioOnlySource.Box box, long removed) throws IOException {
        int at = box.start + box.header;
        long count = uint32(data, at + 4);
        ensure(data, at + 8, count * 4);
        List<Long> kept = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long number = uint32(data, at + 8 + i * 4);
            if (number > removed) {
                kept.add(number - removed);
            }
        }
        if (kept.isEmpty() || kept.get(0) != 1) {
            // The first remaining sample starts the cut track; for audio every sample is one anyway.
            kept.add(0, 1L);
        }
        byte[] content = new byte[8 + kept.size() * 4];
        write32(content, 0, uint32(data, at));
        write32(content, 4, kept.size());
        for (int i = 0; i < kept.size(); i++) {
            write32(content, 8 + i * 4, kept.get(i));
        }
        return content;
    }

    private static byte[] flagsWithout(byte[] data, Mp4AudioOnlySource.Box box, long removed) {
        byte[] all = content(data, box);
        int drop = (int) Math.min(removed, all.length - 4L);
        byte[] content = new byte[all.length - drop];
        System.arraycopy(all, 0, content, 0, 4);
        System.arraycopy(all, 4 + drop, content, 4, content.length - 4);
        return content;
    }

    private static byte[] groupsWithout(byte[] data, Mp4AudioOnlySource.Box box, long removed) throws IOException {
        int version = data[box.start + box.header] & 0xff;
        return runsWithout(data, box, version == 1 ? 8 : 4, removed);
    }
}
