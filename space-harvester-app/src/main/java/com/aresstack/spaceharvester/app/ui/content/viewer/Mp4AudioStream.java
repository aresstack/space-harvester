package com.aresstack.spaceharvester.app.ui.content.viewer;

/** Expose the size and byte ranges of an MP4 containing only audio samples. */
interface Mp4AudioStream extends MediaStreamBridge.ChunkSource {
    long length();
}
