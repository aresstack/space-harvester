package com.aresstack.askai.research.runtime.inference;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashSet;
import java.util.Set;

/**
 * A thin {@link WatchService} wrapper over the session config directory. On any create/modify of one of the
 * watched descriptor files ({@code inference-config.json}, {@code reranker-config.json}, ...) it fires the
 * {@code onChange} callback — which merely SIGNALS a pending reload on the
 * {@link ModelDescriptorReloadController}; it never re-reads or swaps here (that happens between turns). A
 * daemon thread drains events; {@link #close()} stops it and is safe to call once at session end.
 */
public final class ModelDescriptorWatcher implements Closeable {

    private final WatchService watchService;
    private final Thread thread;
    private volatile boolean running = true;

    private ModelDescriptorWatcher(WatchService watchService, final Set<String> fileNames,
                                   final Runnable onChange) {
        this.watchService = watchService;
        this.thread = new Thread(new Runnable() {
            public void run() {
                drain(fileNames, onChange);
            }
        }, "model-descriptor-watcher");
        this.thread.setDaemon(true);
    }

    /** Register on {@code directory} for create/modify of {@code fileNames} and start draining events. */
    public static ModelDescriptorWatcher start(Path directory, Set<String> fileNames, Runnable onChange)
            throws IOException {
        WatchService service = directory.getFileSystem().newWatchService();
        directory.register(service, StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY);
        ModelDescriptorWatcher watcher =
                new ModelDescriptorWatcher(service, new HashSet<String>(fileNames), onChange);
        watcher.thread.start();
        return watcher;
    }

    private void drain(Set<String> fileNames, Runnable onChange) {
        while (running) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (ClosedWatchServiceException | InterruptedException stop) {
                return;
            }
            boolean matched = false;
            for (java.nio.file.WatchEvent<?> event : key.pollEvents()) {
                Object context = event.context();
                if (context instanceof Path && fileNames.contains(((Path) context).getFileName().toString())) {
                    matched = true;
                }
            }
            key.reset();
            if (matched) {
                try {
                    onChange.run();
                } catch (RuntimeException ignored) {
                    // A signal failure must never kill the watch thread.
                }
            }
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            watchService.close();
        } catch (IOException ignored) {
            // best-effort
        }
        thread.interrupt();
    }
}
