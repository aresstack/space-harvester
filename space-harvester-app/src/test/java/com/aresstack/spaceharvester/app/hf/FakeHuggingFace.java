package com.aresstack.spaceharvester.app.hf;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ein Hugging Face im Speicher: Dateien je Pfad, mit {@code Range}, und auf Wunsch einem Abriss mitten im Inhalt. */
public final class FakeHuggingFace implements HuggingFaceClient.Transport {

    private final Map<String, byte[]> bodies = new LinkedHashMap<>();
    /** Jede Anfrage als {@code <Pfad>@<ab Byte>}. */
    public final List<String> requests = new ArrayList<>();
    /** So viele Antworten brechen nach der Hälfte ihres Inhalts ab. */
    public int breaks;

    public FakeHuggingFace put(String path, byte[] body) {
        bodies.put(path, body);
        return this;
    }

    public FakeHuggingFace put(String path, String body) {
        return put(path, body.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public HuggingFaceClient.Response get(URI target, long rangeFrom) {
        String path = target.getRawPath() + (target.getRawQuery() == null ? "" : "?" + target.getRawQuery());
        requests.add(target.getRawPath() + "@" + rangeFrom);
        byte[] body = bodies.get(target.getRawPath());
        if (body == null) {
            return new HuggingFaceClient.Response(404, InputStream.nullInputStream(), 0, null, "huggingface.co");
        }
        if (rangeFrom >= body.length && rangeFrom > 0) {
            return new HuggingFaceClient.Response(416, InputStream.nullInputStream(), 0, null, "huggingface.co");
        }
        byte[] rest = Arrays.copyOfRange(body, (int) rangeFrom, body.length);
        InputStream content = new ByteArrayInputStream(rest);
        if (breaks > 0 && rest.length > 1 && !path.contains("/api/")) {
            breaks--;
            content = new Broken(rest);
        }
        return new HuggingFaceClient.Response(rangeFrom > 0 ? 206 : 200, content, rest.length,
                rangeFrom > 0 ? "bytes " + rangeFrom + "-" + (body.length - 1) + "/" + body.length : null,
                "huggingface.co");
    }

    public static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    /** Die SHA-1, unter der Git den Inhalt als Blob führt. */
    public static String gitOid(byte[] content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(("blob " + content.length + "\0").getBytes(StandardCharsets.US_ASCII));
        return HexFormat.of().formatHex(digest.digest(content));
    }

    /** Liefert die erste Hälfte und reißt dann ab. */
    private static final class Broken extends InputStream {
        private final byte[] content;
        private int at;

        Broken(byte[] content) {
            this.content = content;
        }

        @Override
        public int read() throws IOException {
            if (at >= content.length / 2) {
                throw new IOException("Verbindung abgerissen");
            }
            return content[at++] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int value = read();
            buffer[offset] = (byte) value;
            return 1;
        }
    }
}
