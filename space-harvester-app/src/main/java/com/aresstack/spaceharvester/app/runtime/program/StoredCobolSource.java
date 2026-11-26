package com.aresstack.spaceharvester.app.runtime.program;

import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.mainframe.cobol.CobolSource;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Optional;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/** Recover exact fixed-format code from a verified FTP adapter payload. */
public final class StoredCobolSource {
    private static final Pattern PROGRAM_ID = Pattern.compile("^PROGRAM-ID\\s*\\..*", Pattern.CASE_INSENSITIVE);
    private static final Pattern FIXED_PREFIX = Pattern.compile("^[0-9 ]{6} .*");

    private StoredCobolSource() { }

    public static Optional<CobolSource> read(PageSnapshot snapshot) {
        return verified(snapshot).filter(StoredCobolSource::isProgram);
    }

    static boolean isProgram(CobolSource source) {
        return containsProgramId(source.content());
    }

    /** Recover verified source text for a program or copybook. */
    public static Optional<CobolSource> verified(PageSnapshot snapshot) {
        String path = snapshot.metadata().attributes().get("ftp.path");
        if (path == null || path.isBlank() || snapshot.revision() == null
                || !NativePayload.DOCUMENT_TEXT.equals(snapshot.payload().nativeFormat())) {
            return Optional.empty();
        }
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
        if (!hash(code).equalsIgnoreCase(snapshot.revision())) {
            return Optional.empty();
        }
        return Optional.of(CobolSource.fixed(path, code));
    }

    private static boolean containsProgramId(String code) {
        return code.lines().filter(line -> FIXED_PREFIX.matcher(line).matches())
                .map(line -> line.substring(7, Math.min(72, line.length())).trim())
                .anyMatch(line -> PROGRAM_ID.matcher(line).matches());
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
