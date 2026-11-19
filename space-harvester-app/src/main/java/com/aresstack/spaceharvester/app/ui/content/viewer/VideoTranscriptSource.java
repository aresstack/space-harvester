package com.aresstack.spaceharvester.app.ui.content.viewer;

import com.aresstack.spaceharvester.application.sources.MediaByteRange;

import java.io.IOException;
import java.net.URI;
import java.util.Objects;

/**
 * Open a temporary MP4 containing only indexed audio chunks.
 * Avoid holding UI-thread locks while resolving potentially slow remote MP4 metadata.
 */
public final class VideoTranscriptSource implements AutoCloseable {

    private final long fileSize;
    private final MediaByteRange remote;
    private MediaStreamBridge audioStream;
    private boolean closed;

    public VideoTranscriptSource(long fileSize, MediaByteRange remote) {
        if (fileSize < 1) {
            throw new IllegalArgumentException("An MP4 content length is required");
        }
        this.fileSize = fileSize;
        this.remote = Objects.requireNonNull(remote, "remote");
    }

    public URI openAudioOnly() throws IOException {
        synchronized (this) {
            if (closed) {
                throw new IOException("The selected video is no longer available");
            }
            if (audioStream != null) {
                return audioStream.address();
            }
        }

        Mp4AudioStream audio = Mp4AudioOnlySource.open(remote::read, fileSize);
        MediaStreamBridge prepared = new MediaStreamBridge(audio, audio.length(), "video/mp4");
        synchronized (this) {
            if (closed) {
                prepared.close();
                throw new IOException("The selected video was closed during audio indexing");
            }
            if (audioStream == null) {
                audioStream = prepared;
            } else {
                prepared.close();
            }
            return audioStream.address();
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (audioStream != null) {
            audioStream.close();
            audioStream = null;
        }
    }
}
