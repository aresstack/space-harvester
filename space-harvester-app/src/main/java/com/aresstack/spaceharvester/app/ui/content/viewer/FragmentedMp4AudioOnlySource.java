package com.aresstack.spaceharvester.app.ui.content.viewer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static com.aresstack.spaceharvester.app.ui.content.viewer.Mp4AudioOnlySource.Box;
import static com.aresstack.spaceharvester.app.ui.content.viewer.Mp4AudioOnlySource.add;
import static com.aresstack.spaceharvester.app.ui.content.viewer.Mp4AudioOnlySource.box;
import static com.aresstack.spaceharvester.app.ui.content.viewer.Mp4AudioOnlySource.children;
import static com.aresstack.spaceharvester.app.ui.content.viewer.Mp4AudioOnlySource.ensure;
import static com.aresstack.spaceharvester.app.ui.content.viewer.Mp4AudioOnlySource.fetch;
import static com.aresstack.spaceharvester.app.ui.content.viewer.Mp4AudioOnlySource.int64;
import static com.aresstack.spaceharvester.app.ui.content.viewer.Mp4AudioOnlySource.isAudioTrack;
import static com.aresstack.spaceharvester.app.ui.content.viewer.Mp4AudioOnlySource.optionalChild;
import static com.aresstack.spaceharvester.app.ui.content.viewer.Mp4AudioOnlySource.readBox;
import static com.aresstack.spaceharvester.app.ui.content.viewer.Mp4AudioOnlySource.requiredChild;
import static com.aresstack.spaceharvester.app.ui.content.viewer.Mp4AudioOnlySource.uint32;
import static com.aresstack.spaceharvester.app.ui.content.viewer.Mp4AudioOnlySource.unsupported;
import static com.aresstack.spaceharvester.app.ui.content.viewer.Mp4AudioOnlySource.write32;
import static com.aresstack.spaceharvester.app.ui.content.viewer.Mp4AudioOnlySource.write64;
import static com.aresstack.spaceharvester.app.ui.content.viewer.Mp4AudioOnlySource.writeType;

/**
 * Serve a virtual fragmented MP4 with only one audio track.
 * Copy moof metadata and audio sample byte ranges; never fetch original video samples.
 */
final class FragmentedMp4AudioOnlySource implements Mp4AudioStream {

    private static final int MAX_FRAGMENTS = 100_000;
    private static final int MAX_AUDIO_RUNS = 1_000_000;
    private static final int MAX_READ = MediaStreamBridge.CHUNK_BYTES;
    private static final int BASE_DATA_OFFSET = 0x000001;
    private static final int DEFAULT_SAMPLE_SIZE = 0x000010;
    private static final int DEFAULT_BASE_IS_MOOF = 0x020000;
    private static final int TRUN_DATA_OFFSET = 0x000001;
    private static final int TRUN_FIRST_SAMPLE_FLAGS = 0x000004;
    private static final int TRUN_SAMPLE_DURATION = 0x000100;
    private static final int TRUN_SAMPLE_SIZE = 0x000200;
    private static final int TRUN_SAMPLE_FLAGS = 0x000400;
    private static final int TRUN_COMPOSITION_OFFSET = 0x000800;

    private final MediaStreamBridge.ChunkSource remote;
    private final List<Part> parts;
    private final long length;

    private FragmentedMp4AudioOnlySource(MediaStreamBridge.ChunkSource remote, List<Part> parts,
                                         long length) {
        this.remote = remote;
        this.parts = parts;
        this.length = length;
    }

    static FragmentedMp4AudioOnlySource open(MediaStreamBridge.ChunkSource remote,
                                            long sourceLength, byte[] ftyp, byte[] moov)
            throws IOException {
        Box root = box(moov, 0, moov.length, moov.length);
        Box audioTrack = null;
        for (Box child : children(moov, root)) {
            if ("trak".equals(child.type) && isAudioTrack(moov, child)) {
                audioTrack = child;
                break;
            }
        }
        if (audioTrack == null) {
            throw unsupported("Fragmented MP4 has no audio track");
        }
        int trackId = trackId(moov, audioTrack);
        Box mvex = requiredChild(moov, root, "mvex");
        Trex trex = findTrex(moov, mvex, trackId);
        byte[] selectedMoov = selectAudioMoov(moov, root, audioTrack, mvex, trackId);
        Parts output = new Parts();
        output.addLiteral(ftyp);
        output.addLiteral(selectedMoov);

        long sourceOffset = 0;
        int fragments = 0;
        int inspected = 0;
        while (sourceOffset < sourceLength) {
            if (inspected++ > MAX_FRAGMENTS * 4) {
                throw unsupported("Too many MP4 boxes");
            }
            int available = (int) Math.min(16, sourceLength - sourceOffset);
            if (available < 8) {
                throw unsupported("Trailing invalid MP4 box");
            }
            byte[] probe = fetch(remote, sourceOffset, 8);
            if (uint32(probe, 0) == 1) {
                probe = fetch(remote, sourceOffset, available);
            }
            Box top = box(probe, 0, probe.length, sourceLength - sourceOffset);
            if ("moof".equals(top.type)) {
                if (++fragments > MAX_FRAGMENTS) {
                    throw unsupported("Too many MP4 fragments");
                }
                byte[] originalMoof = readBox(remote, sourceOffset, top.size);
                Fragment fragment = remuxFragment(originalMoof, sourceOffset, sourceLength,
                        output.length, trackId, trex);
                if (fragment != null) {
                    output.addLiteral(fragment.moof);
                    byte[] mdatHeader = new byte[16];
                    write32(mdatHeader, 0, 1);
                    writeType(mdatHeader, 4, "mdat");
                    write64(mdatHeader, 8, add(fragment.audioBytes, 16));
                    output.addLiteral(mdatHeader);
                    for (AudioRun run : fragment.runs) {
                        output.addRemote(run.originalOffset, run.length);
                    }
                }
            }
            sourceOffset = add(sourceOffset, top.size);
        }
        if (fragments == 0 || output.parts.size() <= 2) {
            throw unsupported("Fragmented MP4 contains no audio samples");
        }
        return new FragmentedMp4AudioOnlySource(remote, output.parts, output.length);
    }

    @Override
    public long length() {
        return length;
    }

    @Override
    public byte[] read(long offset, int maximumBytes) throws Exception {
        if (offset < 0 || offset >= length || maximumBytes < 1 || maximumBytes > MAX_READ) {
            throw new IOException("Invalid fragmented-audio byte range");
        }
        int count = (int) Math.min(maximumBytes, length - offset);
        byte[] target = new byte[count];
        int written = 0;
        while (written < count) {
            long position = offset + written;
            Part part = find(position);
            long partOffset = position - part.begin;
            int next = (int) Math.min(count - written, part.length - partOffset);
            if (part.literal != null) {
                System.arraycopy(part.literal, (int) partOffset, target, written, next);
            } else {
                byte[] range = fetch(remote, part.originalOffset + partOffset, next);
                System.arraycopy(range, 0, target, written, next);
            }
            written += next;
        }
        return target;
    }

    private Part find(long position) throws IOException {
        int left = 0;
        int right = parts.size() - 1;
        while (left <= right) {
            int middle = (left + right) >>> 1;
            Part part = parts.get(middle);
            if (position < part.begin) {
                right = middle - 1;
            } else if (position >= part.begin + part.length) {
                left = middle + 1;
            } else {
                return part;
            }
        }
        throw new IOException("Audio fragment range is not indexed");
    }

    private static int trackId(byte[] moov, Box track) throws IOException {
        Box tkhd = requiredChild(moov, track, "tkhd");
        int base = tkhd.start + tkhd.header;
        ensure(moov, base, 24);
        int version = moov[base] & 0xff;
        int idOffset = base + (version == 1 ? 20 : 12);
        ensure(moov, idOffset, 4);
        return (int) uint32(moov, idOffset);
    }

    private static Trex findTrex(byte[] moov, Box mvex, int trackId) throws IOException {
        for (Box child : children(moov, mvex)) {
            if (!"trex".equals(child.type)) {
                continue;
            }
            int data = child.start + child.header;
            ensure(moov, data, 24);
            if (uint32(moov, data + 4) == trackId) {
                return new Trex(uint32(moov, data + 16));
            }
        }
        throw unsupported("Missing audio trex metadata");
    }

    private static byte[] selectAudioMoov(byte[] original, Box moov, Box audioTrack, Box mvex,
                                          int trackId) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[8], 0, 8);
        for (Box child : children(original, moov)) {
            if ("trak".equals(child.type) && child.start != audioTrack.start) {
                continue;
            }
            if (child.start == mvex.start) {
                ByteArrayOutputStream selected = new ByteArrayOutputStream();
                selected.write(new byte[8], 0, 8);
                for (Box mvexChild : children(original, mvex)) {
                    if ("trex".equals(mvexChild.type)
                            && uint32(original, mvexChild.start + mvexChild.header + 4) != trackId) {
                        continue;
                    }
                    appendBox(selected, original, mvexChild);
                }
                byte[] rewritten = selected.toByteArray();
                write32(rewritten, 0, rewritten.length);
                writeType(rewritten, 4, "mvex");
                out.write(rewritten, 0, rewritten.length);
            } else {
                appendBox(out, original, child);
            }
        }
        byte[] rewritten = out.toByteArray();
        write32(rewritten, 0, rewritten.length);
        writeType(rewritten, 4, "moov");
        return rewritten;
    }

    private static Fragment remuxFragment(byte[] original, long inputMoofOffset,
                                          long sourceLength, long outputMoofOffset, int trackId, Trex trex)
            throws IOException {
        Box root = box(original, 0, original.length, original.length);
        Box selectedTrack = null;
        for (Box entry : children(original, root)) {
            if (!"traf".equals(entry.type)) {
                continue;
            }
            Box tfhd = requiredChild(original, entry, "tfhd");
            if (uint32(original, tfhd.start + tfhd.header + 4) == trackId) {
                if (selectedTrack != null) {
                    throw unsupported("Multiple audio track fragments in one moof");
                }
                selectedTrack = entry;
            }
        }
        if (selectedTrack == null) {
            return null;
        }
        Box tfhd = requiredChild(original, selectedTrack, "tfhd");
        int tfhdBody = tfhd.start + tfhd.header;
        ensure(original, tfhdBody, 8);
        int tfhdFlags = (int) uint32(original, tfhdBody) & 0xffffff;
        long base;
        if ((tfhdFlags & BASE_DATA_OFFSET) != 0) {
            ensure(original, tfhdBody + 8, 8);
            base = int64(original, tfhdBody + 8);
        } else if ((tfhdFlags & DEFAULT_BASE_IS_MOOF) != 0) {
            base = inputMoofOffset;
        } else {
            throw unsupported("MP4 audio fragments lack an explicit data base");
        }
        long defaultSize = trex.defaultSampleSize;
        if ((tfhdFlags & DEFAULT_SAMPLE_SIZE) != 0) {
            int position = tfhdBody + 8
                    + ((tfhdFlags & BASE_DATA_OFFSET) != 0 ? 8 : 0)
                    + ((tfhdFlags & 0x000002) != 0 ? 4 : 0)
                    + ((tfhdFlags & 0x000008) != 0 ? 4 : 0);
            ensure(original, position, 4);
            defaultSize = uint32(original, position);
        }

        List<AudioRun> runs = new ArrayList<>();
        for (Box child : children(original, selectedTrack)) {
            if ("trun".equals(child.type)) {
                runs.add(readRun(original, child, base, sourceLength, defaultSize));
                if (runs.size() > MAX_AUDIO_RUNS) {
                    throw unsupported("Too many MP4 audio runs");
                }
            }
        }
        if (runs.isEmpty()) {
            return null;
        }
        long audioBytes = 0;
        for (AudioRun run : runs) {
            audioBytes = add(audioBytes, run.length);
        }
        long moofSize = 8;
        for (Box child : children(original, root)) {
            if (!"traf".equals(child.type) || child.start == selectedTrack.start) {
                moofSize = add(moofSize, child.size);
            }
        }
        if (moofSize > Integer.MAX_VALUE) {
            throw unsupported("Audio fragment metadata is too large");
        }
        byte[] audioTraf = new byte[(int) selectedTrack.size];
        System.arraycopy(original, selectedTrack.start, audioTraf, 0, audioTraf.length);

        int tfhdPosition = tfhdBody - selectedTrack.start;
        if ((tfhdFlags & BASE_DATA_OFFSET) != 0) {
            write64(audioTraf, tfhdPosition + 8, outputMoofOffset);
        } else {
            write32(audioTraf, tfhdPosition, (tfhdFlags | DEFAULT_BASE_IS_MOOF));
        }
        long nextDataOffset = add(moofSize, 16);
        for (AudioRun run : runs) {
            if (nextDataOffset > Integer.MAX_VALUE) {
                throw unsupported("Audio fragment exceeds signed MP4 data offsets");
            }
            int position = run.dataOffsetPosition - selectedTrack.start;
            write32(audioTraf, position, nextDataOffset);
            nextDataOffset = add(nextDataOffset, run.length);
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(new byte[8], 0, 8);
        for (Box child : children(original, root)) {
            if ("traf".equals(child.type)) {
                if (child.start == selectedTrack.start) {
                    output.write(audioTraf, 0, audioTraf.length);
                }
            } else {
                appendBox(output, original, child);
            }
        }
        byte[] moof = output.toByteArray();
        write32(moof, 0, moof.length);
        writeType(moof, 4, "moof");
        return new Fragment(moof, runs, audioBytes);
    }

    private static AudioRun readRun(byte[] data, Box trun, long base, long sourceLength,
                                    long defaultSampleSize) throws IOException {
        int start = trun.start + trun.header;
        ensure(data, start, 8);
        int flags = (int) uint32(data, start) & 0xffffff;
        int samples = (int) uint32(data, start + 4);
        if (samples <= 0 || samples > 10_000_000 || (flags & TRUN_DATA_OFFSET) == 0) {
            throw unsupported("MP4 audio trun has no supported data-offset/sample count");
        }
        int dataOffsetPosition = start + 8;
        ensure(data, dataOffsetPosition, 4);
        long audioStart = signedAdd(base, ByteBuffer.wrap(data, dataOffsetPosition, 4)
                .order(ByteOrder.BIG_ENDIAN).getInt());
        int at = dataOffsetPosition + 4;
        if ((flags & TRUN_FIRST_SAMPLE_FLAGS) != 0) {
            at += 4;
        }
        long sum = 0;
        for (int sample = 0; sample < samples; sample++) {
            if ((flags & TRUN_SAMPLE_DURATION) != 0) {
                at += 4;
            }
            long size;
            if ((flags & TRUN_SAMPLE_SIZE) != 0) {
                ensure(data, at, 4);
                size = uint32(data, at);
                at += 4;
            } else {
                size = defaultSampleSize;
            }
            if ((flags & TRUN_SAMPLE_FLAGS) != 0) {
                at += 4;
            }
            if ((flags & TRUN_COMPOSITION_OFFSET) != 0) {
                at += 4;
            }
            sum = add(sum, size);
        }
        if (at > trun.start + trun.size || sum == 0 || audioStart < 0
                || audioStart > sourceLength - sum) {
            throw unsupported("MP4 audio run exceeds valid byte range");
        }
        return new AudioRun(audioStart, sum, dataOffsetPosition);
    }

    private static long signedAdd(long base, int delta) throws IOException {
        if (delta < 0) {
            if (base < -(long) delta) {
                throw unsupported("Negative audio offset outside MP4");
            }
            return base + delta;
        }
        return add(base, delta);
    }

    private static void appendBox(ByteArrayOutputStream output, byte[] original, Box box) {
        output.write(original, box.start, (int) box.size);
    }

    private static final class Trex {
        private final long defaultSampleSize;

        private Trex(long defaultSampleSize) {
            this.defaultSampleSize = defaultSampleSize;
        }
    }

    private static final class AudioRun {
        private final long originalOffset;
        private final long length;
        private final int dataOffsetPosition;

        private AudioRun(long originalOffset, long length, int dataOffsetPosition) {
            this.originalOffset = originalOffset;
            this.length = length;
            this.dataOffsetPosition = dataOffsetPosition;
        }
    }

    private static final class Fragment {
        private final byte[] moof;
        private final List<AudioRun> runs;
        private final long audioBytes;

        private Fragment(byte[] moof, List<AudioRun> runs, long audioBytes) {
            this.moof = moof;
            this.runs = runs;
            this.audioBytes = audioBytes;
        }
    }

    private static final class Part {
        private final long begin;
        private final long length;
        private final byte[] literal;
        private final long originalOffset;

        private Part(long begin, long length, byte[] literal, long originalOffset) {
            this.begin = begin;
            this.length = length;
            this.literal = literal;
            this.originalOffset = originalOffset;
        }
    }

    private static final class Parts {
        private final List<Part> parts = new ArrayList<>();
        private long length;

        private void addLiteral(byte[] bytes) throws IOException {
            if (bytes.length == 0) {
                return;
            }
            parts.add(new Part(length, bytes.length, bytes, -1));
            length = add(length, bytes.length);
        }

        private void addRemote(long originalOffset, long count) throws IOException {
            if (count <= 0) {
                throw unsupported("Audio fragment has no data");
            }
            parts.add(new Part(length, count, null, originalOffset));
            length = add(length, count);
        }
    }
}
