package com.aresstack.spaceharvester.app.ui.content.viewer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Remux one MP4 audio track into a virtual audio-only MP4.
 * Read only MP4 metadata and indexed audio chunks from the remote source.
 * Reject unsupported and fragmented containers instead of silently downloading video.
 */
final class Mp4AudioOnlySource implements Mp4AudioStream {

    private static final int MAX_BOX_BYTES = 32 * 1024 * 1024;
    private static final int MAX_CHUNKS = 2_000_000;
    private static final int REMOTE_READ_BYTES = 256 * 1024;

    private final MediaStreamBridge.ChunkSource remote;
    private final byte[] header;
    private final long[] inputOffsets;
    private final long[] outputOffsets;
    private final int[] chunkLengths;
    private final long length;
    /**
     * Recently read runs of audio chunks that are contiguous in the original file. Every remote read costs a full
     * round trip (about 0.65 s from BSCW), so reading each 20-KB chunk on its own stalled audio playback for
     * seconds. A run never spans a byte that does not belong to an audio chunk, so no video data is read.
     */
    private final java.util.Deque<Run> runs = new java.util.ArrayDeque<>();
    private static final int CACHED_RUNS = 16;

    private Mp4AudioOnlySource(MediaStreamBridge.ChunkSource remote, byte[] header,
                               long[] inputOffsets, long[] outputOffsets,
                               int[] chunkLengths, long length) {
        this.remote = remote;
        this.header = header;
        this.inputOffsets = inputOffsets;
        this.outputOffsets = outputOffsets;
        this.chunkLengths = chunkLengths;
        this.length = length;
    }

    static Mp4AudioStream open(MediaStreamBridge.ChunkSource source, long sourceLength)
            throws IOException {
        if (source == null || sourceLength < 16) {
            throw unsupported("Invalid MP4 source");
        }
        byte[] ftyp = null;
        byte[] moov = null;
        long offset = 0;
        int inspected = 0;
        while (offset < sourceLength && inspected++ < 128) {
            byte[] head = fetch(source, offset, (int) Math.min(16, sourceLength - offset));
            Box top = box(head, 0, head.length, sourceLength - offset);
            if ("ftyp".equals(top.type)) {
                ftyp = readBox(source, offset, top.size);
            } else if ("moov".equals(top.type)) {
                moov = readBox(source, offset, top.size);
            }
            offset = add(offset, top.size);
            if (ftyp != null && moov != null) {
                break;
            }
        }
        if (ftyp == null || moov == null) {
            throw unsupported("MP4 ftyp/moov metadata unavailable");
        }
        Box moovBox = box(moov, 0, moov.length, moov.length);
        List<Box> children = children(moov, moovBox);
        for (Box child : children) {
            if ("mvex".equals(child.type)) {
                return FragmentedMp4AudioOnlySource.open(source, sourceLength, ftyp, moov);
            }
        }

        Box audioTrack = null;
        for (Box child : children) {
            if ("trak".equals(child.type) && isAudioTrack(moov, child)) {
                audioTrack = child;
                break;
            }
        }
        if (audioTrack == null) {
            throw unsupported("MP4 has no indexed audio track");
        }
        TrackChunks chunks = indexAudio(moov, audioTrack, sourceLength);
        long audioBytes = 0;
        long[] outputOffsets = new long[chunks.inputOffsets.length];
        for (int i = 0; i < chunks.inputOffsets.length; i++) {
            outputOffsets[i] = audioBytes;
            audioBytes = add(audioBytes, chunks.lengths[i]);
        }
        long moovSize = 8;
        for (Box child : children) {
            if (!"trak".equals(child.type) || child.start == audioTrack.start) {
                moovSize = add(moovSize, child.size);
            }
        }
        if (moovSize > Integer.MAX_VALUE || moovSize > MAX_BOX_BYTES) {
            throw unsupported("Audio-only MP4 metadata is too large");
        }
        long payloadStart = add(add(ftyp.length, moovSize), 16);
        byte[] audioMoov = buildMoov(moov, children, audioTrack, chunks, outputOffsets, payloadStart);
        long resultSize = add(add(ftyp.length, audioMoov.length), add(16, audioBytes));
        if (resultSize <= 0) {
            throw unsupported("Invalid remuxed audio size");
        }
        byte[] outputHeader = new byte[(int) payloadStart];
        System.arraycopy(ftyp, 0, outputHeader, 0, ftyp.length);
        System.arraycopy(audioMoov, 0, outputHeader, ftyp.length, audioMoov.length);
        int mdatStart = ftyp.length + audioMoov.length;
        write32(outputHeader, mdatStart, 1);
        writeType(outputHeader, mdatStart + 4, "mdat");
        write64(outputHeader, mdatStart + 8, add(audioBytes, 16));

        long[] absoluteOffsets = new long[outputOffsets.length];
        for (int i = 0; i < outputOffsets.length; i++) {
            absoluteOffsets[i] = add(payloadStart, outputOffsets[i]);
        }
        return new Mp4AudioOnlySource(source, outputHeader, chunks.inputOffsets,
                absoluteOffsets, chunks.lengths, resultSize);
    }

    @Override
    public long length() {
        return length;
    }

    @Override
    public byte[] read(long offset, int maximumBytes) throws Exception {
        if (offset < 0 || maximumBytes <= 0 || offset >= length
                || maximumBytes > REMOTE_READ_BYTES) {
            throw new IOException("Invalid audio-only byte range");
        }
        int count = (int) Math.min(maximumBytes, length - offset);
        byte[] output = new byte[count];
        int copied = 0;
        while (copied < count) {
            long position = offset + copied;
            if (position < header.length) {
                int bytes = (int) Math.min(count - copied, header.length - position);
                System.arraycopy(header, (int) position, output, copied, bytes);
                copied += bytes;
            } else {
                int chunk = findChunk(position);
                long inside = position - outputOffsets[chunk];
                int bytes = (int) Math.min(count - copied, chunkLengths[chunk] - inside);
                if (chunkLengths[chunk] > REMOTE_READ_BYTES) {
                    byte[] data = fetch(remote, inputOffsets[chunk] + inside, bytes);
                    System.arraycopy(data, 0, output, copied, bytes);
                } else {
                    Run run = run(chunk);
                    System.arraycopy(run.bytes, (int) (inputOffsets[chunk] - run.inputStart + inside),
                            output, copied, bytes);
                }
                copied += bytes;
            }
        }
        return output;
    }

    /** The cached run containing {@code chunk}, or a newly read run of contiguous audio chunks starting at it. */
    private Run run(int chunk) throws IOException {
        synchronized (runs) {
            for (Run cached : runs) {
                if (chunk >= cached.firstChunk && chunk <= cached.lastChunk) {
                    return cached;
                }
            }
        }
        int last = chunk;
        long total = chunkLengths[chunk];
        while (last + 1 < chunkLengths.length
                && inputOffsets[last + 1] == inputOffsets[last] + chunkLengths[last]
                && total + chunkLengths[last + 1] <= REMOTE_READ_BYTES) {
            last++;
            total += chunkLengths[last];
        }
        Run run = new Run(chunk, last, inputOffsets[chunk], fetch(remote, inputOffsets[chunk], (int) total));
        synchronized (runs) {
            runs.addFirst(run);
            while (runs.size() > CACHED_RUNS) {
                runs.removeLast();
            }
        }
        return run;
    }

    /** Audio chunks {@code firstChunk..lastChunk}, stored back to back in the original file. */
    private static final class Run {
        final int firstChunk;
        final int lastChunk;
        final long inputStart;
        final byte[] bytes;

        Run(int firstChunk, int lastChunk, long inputStart, byte[] bytes) {
            this.firstChunk = firstChunk;
            this.lastChunk = lastChunk;
            this.inputStart = inputStart;
            this.bytes = bytes;
        }
    }

    private int findChunk(long position) throws IOException {
        int low = 0;
        int high = outputOffsets.length - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            long begin = outputOffsets[mid];
            long end = begin + chunkLengths[mid];
            if (position < begin) {
                high = mid - 1;
            } else if (position >= end) {
                low = mid + 1;
            } else {
                return mid;
            }
        }
        throw new IOException("Audio chunk unavailable");
    }

    private static byte[] buildMoov(byte[] original, List<Box> children, Box audio,
                                    TrackChunks chunks, long[] outputOffsets, long payloadStart)
            throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write(new byte[8], 0, 8);
        for (Box child : children) {
            if ("trak".equals(child.type) && child.start != audio.start) {
                continue;
            }
            byte[] copied = new byte[(int) child.size];
            System.arraycopy(original, child.start, copied, 0, copied.length);
            if (child.start == audio.start) {
                int entryStart = chunks.offsetEntriesPosition - audio.start;
                for (int i = 0; i < outputOffsets.length; i++) {
                    long value = add(payloadStart, outputOffsets[i]);
                    if (chunks.longOffsets) {
                        write64(copied, entryStart + i * 8, value);
                    } else {
                        if (value > 0xffffffffL) {
                            throw unsupported("Audio offsets exceed 32-bit MP4 sample table");
                        }
                        write32(copied, entryStart + i * 4, value);
                    }
                }
            }
            result.write(copied, 0, copied.length);
        }
        byte[] moov = result.toByteArray();
        write32(moov, 0, moov.length);
        writeType(moov, 4, "moov");
        return moov;
    }

    static boolean isAudioTrack(byte[] data, Box track) throws IOException {
        Box media = requiredChild(data, track, "mdia");
        Box handler = requiredChild(data, media, "hdlr");
        if (handler.size < handler.header + 12) {
            throw unsupported("Invalid MP4 handler");
        }
        return "soun".equals(type(data, handler.start + handler.header + 8));
    }

    private static TrackChunks indexAudio(byte[] data, Box track, long sourceLength)
            throws IOException {
        Box media = requiredChild(data, track, "mdia");
        Box minf = requiredChild(data, media, "minf");
        Box stbl = requiredChild(data, minf, "stbl");
        Box stsc = requiredChild(data, stbl, "stsc");
        Box stsz = requiredChild(data, stbl, "stsz");
        Box stco = optionalChild(data, stbl, "stco");
        Box co64 = optionalChild(data, stbl, "co64");
        if ((stco == null) == (co64 == null)) {
            throw unsupported("Audio sample offsets are missing or ambiguous");
        }
        Box offsets = stco != null ? stco : co64;
        boolean longOffsets = co64 != null;
        int entrySize = longOffsets ? 8 : 4;
        int positions = offsets.start + offsets.header;
        ensure(data, positions, 8);
        long countValue = uint32(data, positions + 4);
        if (countValue == 0 || countValue > MAX_CHUNKS) {
            throw unsupported("Invalid audio chunk count");
        }
        int chunkCount = (int) countValue;
        int entriesStart = positions + 8;
        ensure(data, entriesStart, (long) chunkCount * entrySize);
        long[] inputOffsets = new long[chunkCount];
        for (int i = 0; i < chunkCount; i++) {
            inputOffsets[i] = longOffsets ? int64(data, entriesStart + i * 8)
                    : uint32(data, entriesStart + i * 4);
        }

        int stscContent = stsc.start + stsc.header;
        ensure(data, stscContent, 8);
        int stscCount = positiveCount(uint32(data, stscContent + 4));
        ensure(data, stscContent + 8, (long) stscCount * 12);
        long[] firstChunk = new long[stscCount];
        long[] samplesPerChunk = new long[stscCount];
        for (int i = 0; i < stscCount; i++) {
            int entry = stscContent + 8 + i * 12;
            firstChunk[i] = uint32(data, entry);
            samplesPerChunk[i] = uint32(data, entry + 4);
            if (firstChunk[i] < 1 || firstChunk[i] > chunkCount || samplesPerChunk[i] < 1
                    || (i > 0 && firstChunk[i] <= firstChunk[i - 1])) {
                throw unsupported("Invalid MP4 samples-per-chunk index");
            }
        }
        if (firstChunk[0] != 1) {
            throw unsupported("Missing first MP4 audio chunk");
        }
        int sizesContent = stsz.start + stsz.header;
        ensure(data, sizesContent, 12);
        long fixedSize = uint32(data, sizesContent + 4);
        long sampleCount = uint32(data, sizesContent + 8);
        if (sampleCount < 1 || sampleCount > Integer.MAX_VALUE) {
            throw unsupported("Invalid MP4 audio sample count");
        }
        if (fixedSize == 0) {
            ensure(data, sizesContent + 12, sampleCount * 4);
        }
        int[] lengths = new int[chunkCount];
        long sample = 0;
        int rule = 0;
        for (int chunk = 0; chunk < chunkCount; chunk++) {
            while (rule + 1 < stscCount && firstChunk[rule + 1] <= chunk + 1L) {
                rule++;
            }
            long chunkSize = 0;
            for (long j = 0; j < samplesPerChunk[rule]; j++) {
                if (sample >= sampleCount) {
                    throw unsupported("MP4 chunk refers beyond audio samples");
                }
                long size = fixedSize == 0
                        ? uint32(data, sizesContent + 12 + (int) sample * 4) : fixedSize;
                chunkSize = add(chunkSize, size);
                sample++;
            }
            if (chunkSize < 1 || chunkSize > Integer.MAX_VALUE) {
                throw unsupported("Invalid MP4 audio chunk size");
            }
            if (inputOffsets[chunk] < 0 || inputOffsets[chunk] > sourceLength - chunkSize) {
                throw unsupported("Audio chunk points outside MP4");
            }
            lengths[chunk] = (int) chunkSize;
        }
        if (sample != sampleCount) {
            throw unsupported("Unmapped MP4 audio samples");
        }
        return new TrackChunks(inputOffsets, lengths, longOffsets, entriesStart);
    }

    private static int positiveCount(long number) throws IOException {
        if (number <= 0 || number > MAX_CHUNKS) {
            throw unsupported("Invalid MP4 table length");
        }
        return (int) number;
    }

    static Box requiredChild(byte[] data, Box parent, String name) throws IOException {
        Box child = optionalChild(data, parent, name);
        if (child == null) {
            throw unsupported("Missing MP4 metadata: " + name);
        }
        return child;
    }

    static Box optionalChild(byte[] data, Box parent, String name) throws IOException {
        for (Box child : children(data, parent)) {
            if (name.equals(child.type)) {
                return child;
            }
        }
        return null;
    }

    static List<Box> children(byte[] data, Box parent) throws IOException {
        int begin = parent.start + parent.header;
        int end = (int) (parent.start + parent.size);
        List<Box> result = new ArrayList<>();
        while (begin < end) {
            Box entry = box(data, begin, end, end - begin);
            result.add(entry);
            begin += (int) entry.size;
        }
        return result;
    }

    static Box box(byte[] data, int start, int limit, long available) throws IOException {
        ensure(data, start, 8);
        long size = uint32(data, start);
        String name = type(data, start + 4);
        int header = 8;
        if (size == 1) {
            ensure(data, start, 16);
            size = int64(data, start + 8);
            header = 16;
        } else if (size == 0) {
            size = available;
        }
        if (size < header || size > available || (long) start + header > limit) {
            throw unsupported("Invalid MP4 box size");
        }
        return new Box(start, size, header, name);
    }

    static byte[] readBox(MediaStreamBridge.ChunkSource source, long offset, long size)
            throws IOException {
        if (size < 8 || size > MAX_BOX_BYTES) {
            throw unsupported("MP4 metadata exceeds safe size limit");
        }
        byte[] target = new byte[(int) size];
        for (int position = 0; position < target.length;) {
            int count = Math.min(REMOTE_READ_BYTES, target.length - position);
            byte[] data = fetch(source, offset + position, count);
            System.arraycopy(data, 0, target, position, count);
            position += count;
        }
        return target;
    }

    static byte[] fetch(MediaStreamBridge.ChunkSource source, long offset, int count)
            throws IOException {
        try {
            byte[] bytes = source.read(offset, count);
            if (bytes == null || bytes.length != count) {
                throw unsupported("Incomplete MP4 metadata or audio range");
            }
            return bytes;
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException("MP4 range unavailable: " + failure.getClass().getSimpleName(), failure);
        }
    }

    static void ensure(byte[] data, long start, long count) throws IOException {
        if (start < 0 || count < 0 || start > data.length || count > data.length - start) {
            throw unsupported("Invalid MP4 sample table");
        }
    }

    static long add(long left, long right) throws IOException {
        if (left < 0 || right < 0 || left > Long.MAX_VALUE - right) {
            throw unsupported("MP4 audio size overflow");
        }
        return left + right;
    }

    static long uint32(byte[] bytes, int start) {
        return ByteBuffer.wrap(bytes, start, 4).order(ByteOrder.BIG_ENDIAN).getInt() & 0xffffffffL;
    }

    static long int64(byte[] bytes, int start) {
        return ByteBuffer.wrap(bytes, start, 8).order(ByteOrder.BIG_ENDIAN).getLong();
    }

    static String type(byte[] bytes, int start) {
        return new String(bytes, start, 4, StandardCharsets.US_ASCII);
    }

    static void write32(byte[] output, int offset, long number) {
        ByteBuffer.wrap(output, offset, 4).order(ByteOrder.BIG_ENDIAN).putInt((int) number);
    }

    static void write64(byte[] output, int offset, long number) {
        ByteBuffer.wrap(output, offset, 8).order(ByteOrder.BIG_ENDIAN).putLong(number);
    }

    static void writeType(byte[] output, int offset, String type) {
        byte[] bytes = type.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, output, offset, 4);
    }

    static IOException unsupported(String reason) {
        return new IOException("Audio-only MP4 unsupported: " + reason);
    }

    static final class Box {
        final int start;
        final long size;
        final int header;
        final String type;

        private Box(int start, long size, int header, String type) {
            this.start = start;
            this.size = size;
            this.header = header;
            this.type = type;
        }
    }

    private static final class TrackChunks {
        private final long[] inputOffsets;
        private final int[] lengths;
        private final boolean longOffsets;
        private final int offsetEntriesPosition;

        private TrackChunks(long[] inputOffsets, int[] lengths, boolean longOffsets, int offsetEntriesPosition) {
            this.inputOffsets = inputOffsets;
            this.lengths = lengths;
            this.longOffsets = longOffsets;
            this.offsetEntriesPosition = offsetEntriesPosition;
        }
    }
}
