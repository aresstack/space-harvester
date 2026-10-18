package com.aresstack.enterpriseai.astu.chat;

import java.util.Arrays;

/**
 * Ein Bild in einer Nutzernachricht (Bildverständnis, OCR): Medientyp und Bytes. Der Adapter überträgt es so, wie
 * das Modell es erwartet (bei OpenAI-kompatiblen Diensten als {@code image_url} mit Data-URL).
 */
public final class ChatImage {

    private final String mediaType;
    private final byte[] bytes;

    public ChatImage(String mediaType, byte[] bytes) {
        if (mediaType == null || !mediaType.startsWith("image/")) {
            throw new IllegalArgumentException("mediaType must be an image type: " + mediaType);
        }
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("bytes must not be empty");
        }
        this.mediaType = mediaType;
        this.bytes = bytes.clone();
    }

    public String mediaType() {
        return mediaType;
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public int size() {
        return bytes.length;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ChatImage && mediaType.equals(((ChatImage) other).mediaType)
                && Arrays.equals(bytes, ((ChatImage) other).bytes);
    }

    @Override
    public int hashCode() {
        return 31 * mediaType.hashCode() + Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "ChatImage[" + mediaType + ", " + bytes.length + " bytes]";
    }
}
