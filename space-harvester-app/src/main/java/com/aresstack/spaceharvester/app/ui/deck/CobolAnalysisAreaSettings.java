package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.deck.ftp.CobolAnalysisArea;
import com.aresstack.spaceharvester.deck.ftp.ConnectionState;
import com.aresstack.spaceharvester.deck.ftp.FtpSession;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Persist the analysis home separately from the FTP transport's navigation preferences. */
final class CobolAnalysisAreaSettings {
    private final Function<String, String> read;
    private final BiConsumer<String, String> write;

    CobolAnalysisAreaSettings() {
        this(AppState.shared()::get, AppState.shared()::put);
    }

    CobolAnalysisAreaSettings(Function<String, String> read, BiConsumer<String, String> write) {
        this.read = read;
        this.write = write;
    }

    Optional<CobolAnalysisArea> load(FtpSession session) {
        if (!available(session)) {
            return Optional.empty();
        }
        return load(session.profile().host(), session.profile().port(), session.user(), session.profile().syslib());
    }

    Optional<CobolAnalysisArea> load(String host, int port, String user, List<String> syslib) {
        return CobolAnalysisArea.configured(read.apply(key(host, port, user)), syslib);
    }

    void save(FtpSession session, String home) {
        if (!available(session)) {
            throw new IllegalStateException("Keine aktive MVS-Verbindung");
        }
        save(session.profile().host(), session.profile().port(), session.user(), session.profile().syslib(), home);
    }

    void save(String host, int port, String user, List<String> syslib, String home) {
        var area = CobolAnalysisArea.configured(home, syslib);
        write.accept(key(host, port, user), area.map(CobolAnalysisArea::home).orElse(null));
    }

    static boolean available(FtpSession session) {
        return session.state() == ConnectionState.CONNECTED && session.mvs() && !session.user().isBlank();
    }

    private static String key(String host, int port, String user) {
        if (host.isBlank() || user.isBlank() || port < 1 || port > 65535) {
            throw new IllegalArgumentException("FTP-Verbindung nicht eindeutig identifiziert");
        }
        return "deck.ftp.cobol.home." + encode(host.strip().toLowerCase(Locale.ROOT)) + "." + port
                + "." + encode(user.strip());
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
