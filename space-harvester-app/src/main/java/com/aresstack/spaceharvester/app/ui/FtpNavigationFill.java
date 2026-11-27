package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.adyton.SecretUnavailableException;
import com.aresstack.enterpriseai.astu.source.SourceLocators;
import com.aresstack.enterpriseai.astu.source.SourceSettings;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp.FtpSourceProvider;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.ui.deck.FtpNavigationObserver;
import com.aresstack.spaceharvester.deck.ftp.FtpProfile;
import com.aresstack.spaceharvester.deck.ftp.FtpSession;
import com.aresstack.spaceharvester.deck.ftp.MvsPath;
import com.aresstack.spaceharvester.deck.ftp.RemoteEntry;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** Transfer directly visited FTP entries to the configured knowledge source. */
final class FtpNavigationFill implements FtpNavigationObserver {

    private static final Logger LOG = LoggerFactory.getLogger(FtpNavigationFill.class);
    private final AppContext context;

    FtpNavigationFill(AppContext context) {
        this.context = context;
    }

    @Override
    public boolean enabled(FtpSession session) {
        return !sources(session).isEmpty();
    }

    @Override
    public void listed(FtpSession session, List<RemoteEntry> entries) {
        List<String> paths = directFiles(session.mvs(), entries);
        List<String> sources = sources(session);
        AppRuntime runtime = context.runtime();
        if (runtime == null || paths.isEmpty() || sources.isEmpty()) {
            return;
        }
        Thread.startVirtualThread(() -> {
            for (String sourceId : sources) {
                if (context.runtime() != runtime) {
                    return;
                }
                try {
                    runtime.workspace().acquireVisitedResources(sourceId, paths);
                } catch (RuntimeException failure) {
                    LOG.warn("Navigation acquisition failed for source {}", sourceId, failure);
                }
            }
        });
    }

    private List<String> sources(FtpSession session) {
        AppSettings settings = context.settings();
        if (settings == null || session.user().isBlank()) {
            return List.of();
        }
        return settings.sources().stream()
                .filter(entry -> !entry.secretMissing() && !settings.disabledSources().contains(entry.id()))
                .filter(entry -> matches(entry.config(), session.profile(), session.label(), session.user(), session.mvs()))
                .map(AppSettings.SourceEntry::id).toList();
    }

    /** Nur für {@link FtpSourceProvider#locator}: liest keine Zugangsdaten. */
    private static final FtpSourceProvider FTP = new FtpSourceProvider(ref -> {
        throw new SecretUnavailableException(SecretUnavailableException.Reason.NOT_AVAILABLE, ref,
                "Nur Adressvergleich");
    });

    static boolean matches(SourceSettingsConfig config, FtpProfile profile, String account, String user, boolean mvs) {
        return Boolean.parseBoolean(config.options().get("navigationFill")) && serves(config, profile, account, user, mvs);
    }

    /** Ob die Quelle denselben FTP-Server hat (Host und Port, wie die Quelle selbst sie liest). */
    static boolean sameEndpoint(SourceSettingsConfig config, FtpProfile profile) {
        if (!"ftp".equals(config.type())) {
            return false;
        }
        // Host und Port wie die Quelle selbst sie liest (Standardports des Anbieters, etwa bei implizitem TLS).
        String endpoint = FTP.locator(SourceSettings.of(config.options()));
        return !endpoint.isEmpty() && endpoint.equals(SourceLocators.hostPort(profile.host(), profile.port()));
    }

    /** Ob die Quelle denselben Server mit demselben Konto liest wie die Sitzung (Benutzer oder KeePass-Eintrag). */
    static boolean serves(SourceSettingsConfig config, FtpProfile profile, String account, String user, boolean mvs) {
        if (!sameEndpoint(config, profile) || user == null || user.isBlank()) {
            return false;
        }
        if (config.auth().type() == SourceSettingsConfig.AuthType.BASIC) {
            String principal = config.auth().username();
            return principal != null && (mvs ? principal.equalsIgnoreCase(user) : principal.equals(user));
        }
        return config.auth().type() == SourceSettingsConfig.AuthType.NONE && account != null
                && ("keepass:" + account).equals(config.options().get("credentialRef"));
    }

    static List<String> directFiles(boolean mvs, List<RemoteEntry> entries) {
        return entries.stream()
                .filter(entry -> !entry.directory() || mvs && "PS".equalsIgnoreCase(entry.details().organization()))
                .map(entry -> mvs ? MvsPath.unquote(entry.path()) : entry.path())
                .distinct().toList();
    }
}