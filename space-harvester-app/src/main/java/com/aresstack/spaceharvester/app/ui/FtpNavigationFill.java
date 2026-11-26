package com.aresstack.spaceharvester.app.ui;

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

    static boolean matches(SourceSettingsConfig config, FtpProfile profile, String account, String user, boolean mvs) {
        if (!"ftp".equals(config.type()) || !Boolean.parseBoolean(config.options().get("navigationFill"))) {
            return false;
        }
        String host = config.options().getOrDefault("host", "");
        if (!host.trim().equalsIgnoreCase(profile.host()) || port(config) != profile.port()
                || user == null || user.isBlank()) {
            return false;
        }
        if (config.auth().type() == SourceSettingsConfig.AuthType.BASIC) {
            String principal = config.auth().username();
            return principal != null && (mvs ? principal.equalsIgnoreCase(user) : principal.equals(user));
        }
        return config.auth().type() == SourceSettingsConfig.AuthType.NONE && account != null
                && ("keepass:" + account).equals(config.options().get("credentialRef"));
    }

    private static int port(SourceSettingsConfig config) {
        try {
            String defaultPort = "implicit".equalsIgnoreCase(config.options().get("tls")) ? "990" : "21";
            return Integer.parseInt(config.options().getOrDefault("port", defaultPort).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static List<String> directFiles(boolean mvs, List<RemoteEntry> entries) {
        return entries.stream()
                .filter(entry -> !entry.directory() || mvs && "PS".equalsIgnoreCase(entry.details().organization()))
                .map(entry -> mvs ? MvsPath.unquote(entry.path()) : entry.path())
                .distinct().toList();
    }
}