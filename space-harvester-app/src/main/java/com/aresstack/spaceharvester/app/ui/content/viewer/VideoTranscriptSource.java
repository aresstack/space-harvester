package com.aresstack.spaceharvester.app.ui.content.viewer;

import com.aresstack.spaceharvester.app.settings.VideoSettings;
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
    private final VideoSettings settings;
    private MediaStreamBridge audioStream;
    /** Requested and actual start of the open audio stream. */
    private long audioFrom;
    private long audioStart;
    private boolean closed;

    public VideoTranscriptSource(long fileSize, MediaByteRange remote) {
        this(fileSize, remote, VideoSettings.DEFAULT);
    }

    /** @param settings parallel reads of the index and the audio chunks, and of the media bridge */
    public VideoTranscriptSource(long fileSize, MediaByteRange remote, VideoSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        if (fileSize < 1) {
            throw new IllegalArgumentException("An MP4 content length is required");
        }
        this.fileSize = fileSize;
        this.remote = Objects.requireNonNull(remote, "remote");
    }

    /**
     * The audio-only MP4 and where its audio begins in the video.
     *
     * @param startMillis the start of the first chunk; a resumed transcription drops the audio up to its resume point
     */
    public record AudioOnly(URI address, long startMillis) {
    }

    public URI openAudioOnly() throws IOException {
        return openAudioOnly(0).address();
    }

    /**
     * The audio from the chunk that holds {@code fromMillis} on; nothing before it is fetched. The same start again
     * reuses the open stream.
     */
    public AudioOnly openAudioOnly(long fromMillis) throws IOException {
        synchronized (this) {
            if (closed) {
                throw new IOException("The selected video is no longer available");
            }
            if (audioStream != null && audioFrom == fromMillis) {
                return new AudioOnly(audioStream.address(), audioStart);
            }
        }

        Mp4AudioStream audio = Mp4AudioOnlySource.open(remote::read, fileSize, settings, fromMillis);
        MediaStreamBridge prepared = new MediaStreamBridge(audio, audio.length(), "video/mp4", settings);
        synchronized (this) {
            if (closed) {
                prepared.close();
                throw new IOException("The selected video was closed during audio indexing");
            }
            if (audioStream != null) {
                audioStream.close();
            }
            audioStream = prepared;
            audioFrom = fromMillis;
            audioStart = audio.startMillis();
            return new AudioOnly(audioStream.address(), audioStart);
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
