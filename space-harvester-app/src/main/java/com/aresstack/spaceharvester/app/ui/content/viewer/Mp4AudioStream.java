package com.aresstack.spaceharvester.app.ui.content.viewer;

/** Expose the size and byte ranges of an MP4 containing only audio samples. */
interface Mp4AudioStream extends MediaStreamBridge.ChunkSource {
    long length();

    /** Where the audio of this stream begins in the whole video; {@code 0} unless it was cut to resume later. */
    default long startMillis() {
        return 0;
    }
}
