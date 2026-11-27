package com.aresstack.enterpriseai.browser.api.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A picture as the engine took it (JPEG or PNG) and the control refs visible in it. */
public final class RawScreenshot {

    private final String mediaType;
    private final byte[] image;
    private final List<String> visibleRefs;

    public RawScreenshot(String mediaType, byte[] image, List<String> visibleRefs) {
        if (image == null || image.length == 0) {
            throw new IllegalArgumentException("image must not be empty");
        }
        this.mediaType = mediaType == null ? "image/png" : mediaType;
        this.image = image.clone();
        this.visibleRefs = Collections.unmodifiableList(new ArrayList<String>(
                visibleRefs == null ? Collections.<String>emptyList() : visibleRefs));
    }

    public String mediaType() {
        return mediaType;
    }

    public byte[] image() {
        return image.clone();
    }

    public List<String> visibleRefs() {
        return visibleRefs;
    }
}
