package com.aresstack.spaceharvester.app.runtime.program;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp.FtpSourceProvider;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/** Recover verified decoded FTP text independently of the programming language. */
public record StoredFtpText(String path, String content) {

    public static boolean nativeFormat(NativePayload payload) {
        return FtpSourceProvider.SOURCE_TEXT_FORMAT.equals(payload.nativeFormat());
    }

    public static Optional<StoredFtpText> read(PageSnapshot snapshot) {
        String path = snapshot.metadata().attributes().get("ftp.path");
        if (path == null || path.isBlank() || snapshot.revision() == null) return Optional.empty();
        if (nativeFormat(snapshot.payload())) return readNative(snapshot, path);
        if (!NativePayload.DOCUMENT_TEXT.equals(snapshot.payload().nativeFormat())) return Optional.empty();
        return readLegacy(snapshot, path);
    }

    private static Optional<StoredFtpText> readNative(PageSnapshot snapshot, String path) {
        if (!("sha256:" + snapshot.revision().toLowerCase(Locale.ROOT)).equals(snapshot.payload().rawSha256())) {
            return Optional.empty();
        }
        try {
            String code = StandardCharsets.UTF_8.newDecoder()
                    .decode(ByteBuffer.wrap(snapshot.payload().bytes())).toString();
            return Optional.of(new StoredFtpText(path, code));
        } catch (CharacterCodingException invalid) {
            return Optional.empty();
        }
    }

    private static Optional<StoredFtpText> readLegacy(PageSnapshot snapshot, String path) {
        String payload = new String(snapshot.payload().bytes(), StandardCharsets.UTF_8);
        String prefix = "# " + path + "\n\n```\n";
        String unixPrefix = "# /" + path + "\n\n```\n";
        if (payload.startsWith(unixPrefix)) prefix = unixPrefix;
        String suffix = "\n```";
        if (!payload.startsWith(prefix) || !payload.endsWith(suffix)
                || payload.length() < prefix.length() + suffix.length()) {
            return Optional.empty();
        }
        String code = payload.substring(prefix.length(), payload.length() - suffix.length());
        return hash(code).equalsIgnoreCase(snapshot.revision())
                ? Optional.of(new StoredFtpText(path, code)) : Optional.empty();
    }

    private static String hash(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
