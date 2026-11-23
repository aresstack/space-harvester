package com.aresstack.spaceharvester.deck.files.sharepoint;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Anwendungsfall „SharePoint öffnen“: Web-Adresse oder Site in den UNC-Pfad übersetzen und anmelden; je Rechner nur
 * einmal je Sitzung (wie MainframeMate). Das Ergebnis kommt an {@code done}, auf dem Hintergrund-Thread.
 */
public final class SharePointAccess {

    /** Ergebnis: der UNC-Pfad, der geöffnet werden kann, oder warum nicht. */
    public record Result(String uncPath, WebDavLogin.Outcome outcome) {

        public boolean connected() {
            return outcome.connected();
        }
    }

    private final WebDavLogin login;
    private final Executor background;
    private final Set<String> connectedHosts = ConcurrentHashMap.newKeySet();

    public SharePointAccess(WebDavLogin login, Executor background) {
        this.login = Objects.requireNonNull(login, "login");
        this.background = Objects.requireNonNull(background, "background");
    }

    /** Der UNC-Pfad zu einer Web-Adresse oder einem Pfad; Pfade bleiben, wie sie sind. */
    public static Optional<String> resolve(String urlOrPath) {
        if (SharePointPaths.isWebUrl(urlOrPath)) {
            return SharePointPaths.toUncPath(urlOrPath);
        }
        return Optional.ofNullable(urlOrPath).map(String::strip).filter(s -> !s.isEmpty());
    }

    /** Ob für diesen Pfad eine Anmeldung nötig sein kann (WebDAV-Pfad eines noch nicht verbundenen Rechners). */
    public boolean needsLogin(String path) {
        return SharePointPaths.isWebDav(path)
                && SharePointPaths.host(path).map(h -> !connectedHosts.contains(h)).orElse(false);
    }

    /** Verbindet ohne Kennwort. */
    public void connect(String uncPath, Consumer<Result> done) {
        run(uncPath, () -> login.connect(uncPath), done);
    }

    /** Verbindet mit Benutzer und Kennwort (aus dem Anmeldedialog oder KeePass). */
    public void connect(String uncPath, String user, char[] password, Consumer<Result> done) {
        char[] copy = password == null ? new char[0] : password.clone();
        run(uncPath, () -> {
            try {
                return login.connect(uncPath, user, copy);
            } finally {
                java.util.Arrays.fill(copy, '\0');
            }
        }, done);
    }

    private void run(String uncPath, java.util.function.Supplier<WebDavLogin.Outcome> attempt,
                     Consumer<Result> done) {
        Objects.requireNonNull(uncPath, "uncPath");
        Objects.requireNonNull(done, "done");
        Optional<String> host = SharePointPaths.host(uncPath);
        if (host.isPresent() && connectedHosts.contains(host.get())) {
            done.accept(new Result(uncPath, WebDavLogin.Outcome.ok()));
            return;
        }
        background.execute(() -> {
            WebDavLogin.Outcome outcome;
            try {
                outcome = attempt.get();
            } catch (RuntimeException e) {
                outcome = WebDavLogin.Outcome.failed("Anmeldung gescheitert (" + e.getClass().getSimpleName() + ")");
            }
            if (outcome.connected()) {
                host.ifPresent(connectedHosts::add);
            }
            done.accept(new Result(uncPath, outcome));
        });
    }
}
