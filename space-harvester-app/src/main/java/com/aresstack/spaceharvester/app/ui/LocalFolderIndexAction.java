package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.settings.ConfigFileEditor;
import com.aresstack.spaceharvester.app.settings.SourceDraft;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import java.awt.Component;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/** Connect the Deck selection to the existing source configuration and index action. */
public final class LocalFolderIndexAction {

    private final AppContext context;
    private boolean running;

    public LocalFolderIndexAction(AppContext context) {
        this.context = context;
    }

    public void start(Component parent, String location, BiConsumer<String, Boolean> status) {
        if (running || context.runtime() == null || context.settings() == null) {
            status.accept("Indexierung gerade nicht verfügbar.", true);
            return;
        }
        running = true;
        AppRuntime runtime = context.runtime();
        AppSettings settings = context.settings();
        status.accept("Prüfe Ordner und vorhandene Wissensquellen …", false);
        Background.run(() -> {
            var target = LocalFolderIndexPlan.resolve(Path.of(location),
                    settings.sources().stream().map(AppSettings.SourceEntry::config).toList(),
                    runtime.workspace().sources().stream().map(HarvestSource::id).collect(Collectors.toSet()));
            protectStorage(target.folder(), settings);
            if (!target.newSource() && target.pageId() != null) {
                var page = runtime.workspace().liveMetadata(target.sourceId(), target.pageId()).entry().page();
                if (!PageMetadata.FOLDER_KIND.equals(page.attributes().get(PageMetadata.RESOURCE_KIND))) {
                    throw new IllegalStateException("Der Ordner ist in der vorhandenen Quelle nicht zugänglich.");
                }
            }
            return target;
        }, target -> confirm(parent, runtime, settings, target, status), error -> fail(status, error));
    }

    private void confirm(Component parent, AppRuntime runtime, AppSettings settings,
                         LocalFolderIndexPlan.Target target, BiConsumer<String, Boolean> status) {
        if (context.runtime() != runtime || context.settings() != settings) {
            fail(status, new IllegalStateException("Konfiguration geändert. Bitte erneut indexieren."));
            return;
        }
        if (!target.newSource()) {
            boolean marked = runtime.workspace().indexed(target.sourceId(), target.sourceId(), target.pageId());
            if (!Dialogs.confirm(parent, "Ordner indexieren", target.folder() + "\n\nVorhandene Wissensquelle: "
                    + target.sourceId() + (marked ? "\nBereits markiert; erneut abgleichen?" : "\nOrdner samt Unterordnern indexieren?")
                    + "\nEs gelten die Quellenfilter. Der Auftrag lädt markierte Bereiche; unabhängige Synchronisierungen bleiben unverändert.")) {
                running = false;
                return;
            }
            if (context.runtime() != runtime || context.settings() != settings) {
                fail(status, new IllegalStateException("Konfiguration geändert. Bitte erneut indexieren."));
                return;
            }
            index(target.sourceId(), target.pageId(), status);
            return;
        }
        Boolean directOnly = Dialogs.confirm(parent, "Ordner indexieren", target.folder()
                + "\n\nEine neue Wissensquelle für genau diesen Ordner einrichten und indexieren.",
                "Nur gezielte Suche (nicht in globaler Suche)", "Indexieren");
        if (directOnly == null || !Dialogs.restartAllowed(parent, context)) {
            running = false;
            return;
        }
        if (context.runtime() != runtime || context.settings() != settings) {
            fail(status, new IllegalStateException("Konfiguration geändert. Bitte erneut indexieren."));
            return;
        }
        create(target, !directOnly, status);
    }

    private void create(LocalFolderIndexPlan.Target target, boolean global, BiConsumer<String, Boolean> status) {
        String id = "ordner-" + UUID.randomUUID().toString();
        SourceDraft draft = new SourceDraft(id, SourceDraft.FILES, target.folder().getFileName().toString(),
                null, target.folder().toString(), null, SourceSettingsConfig.AuthType.NONE,
                null, null, null, null, null, null, null, null, Map.of()).withUsage(true, global);
        Background.run(() -> {
            ConfigFileEditor editor = new ConfigFileEditor(context.loader());
            // Recheck persisted configuration before adding a source.
            AppSettings current = context.loader().load(context.configFile());
            protectStorage(target.folder(), current);
            var checked = LocalFolderIndexPlan.resolve(target.folder(),
                    current.sources().stream().map(AppSettings.SourceEntry::config).toList(), java.util.Set.of());
            if (!checked.newSource()) {
                throw new IllegalStateException("Quellenkonfiguration geändert. Bitte erneut indexieren.");
            }
            editor.save(context.configFile(), editor.draft(context.configFile(), draft),
                    !editor.fileHasPlaintextSecret(context.configFile()));
            return id;
        }, source -> context.reload(() -> {
            if (context.runtime() == null || context.runtime().workspace().sources().stream()
                    .noneMatch(s -> s.id().equals(source))) {
                fail(status, new IllegalStateException("Quelle gespeichert, aber nicht aktiv. Bitte Konfiguration prüfen."));
                return;
            }
            index(source, null, status);
        }), error -> fail(status, error));
    }

    private void protectStorage(Path folder, AppSettings settings) throws java.io.IOException {
        for (Path path : java.util.Arrays.asList(settings.storage().dataDirectory(),
                settings.storage().assetDirectory(), settings.storage().exportDirectory(), context.configFile())) {
            if (path == null) {
                continue;
            }
            Path resolved = java.nio.file.Files.exists(path) ? path.toRealPath() : path.toAbsolutePath().normalize();
            if (folder.startsWith(resolved) || resolved.startsWith(folder)) {
                throw new IllegalArgumentException("Der gewählte Ordner überschneidet sich mit der Harvester-Ablage oder Konfiguration.");
            }
        }
    }

    private void index(String source, String pageId, BiConsumer<String, Boolean> status) {
        Indexer.start(context, source, source, pageId,
                com.aresstack.spaceharvester.application.sync.SyncMode.CATALOG_ONLY, result -> {
            running = false;
            if (result.failed().isEmpty()) {
                status.accept("Indexierungsauftrag gestartet: " + source, false);
            } else {
                status.accept(String.join("; ", result.failed()), true);
            }
        }, error -> fail(status, error));
    }

    private void fail(BiConsumer<String, Boolean> status, Throwable error) {
        running = false;
        Problems.Problem problem = Problems.describe(error);
        status.accept(problem.title() + ": " + problem.message(), true);
    }
}
