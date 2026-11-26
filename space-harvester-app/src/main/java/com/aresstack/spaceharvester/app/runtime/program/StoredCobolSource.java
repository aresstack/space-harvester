package com.aresstack.spaceharvester.app.runtime.program;

import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.mainframe.cobol.CobolSource;
import java.util.Optional;
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
        return StoredFtpText.read(snapshot).map(text -> CobolSource.fixed(text.path(), text.content()));
    }

    private static boolean containsProgramId(String code) {
        return code.lines().filter(line -> FIXED_PREFIX.matcher(line).matches())
                .map(line -> line.substring(7, Math.min(72, line.length())).trim())
                .anyMatch(line -> PROGRAM_ID.matcher(line).matches());
    }
}
