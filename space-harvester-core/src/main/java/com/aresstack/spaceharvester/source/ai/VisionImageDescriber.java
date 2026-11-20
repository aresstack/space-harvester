package com.aresstack.spaceharvester.source.ai;

import com.aresstack.spaceharvester.ai.AiServiceException;
import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.assets.ImageDescriber;
import com.aresstack.spaceharvester.application.assets.ImageDescriptionException;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import com.aresstack.spaceharvester.source.TrustBundles;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Bildbeschreibung über das Vision-Modell des KI-Dienstes (ADR 0021), wie {@code model.vision} im Enterprise AI Client.
 * Folgt der wirksamen Konfiguration bei jedem Aufruf: ein anderes {@code ai.vision-model} macht vorhandene
 * Beschreibungen alt, ohne Modell beschreibt er nichts ({@link #available()}). Bilder gehen nur aus Quellen in
 * {@code external-processing-sources} an den Dienst.
 */
public final class VisionImageDescriber implements ImageDescriber {

    /**
     * Kennung samt Analyseversion: der Prompt {@code DESCRIPTION_PROMPT} der {@code SourceImageAnalysisUseCase} des
     * Enterprise AI Client in der Fassung von Commit 9fb91c3. Ändert sich der Prompt, steigt die Version, und alle
     * Beschreibungen gelten als veraltet.
     */
    public static final String ID = "openai-compatible-vision-v1";

    private final Supplier<AiServiceSettings> settings;
    private final ConfiguredSourceCatalog catalog;
    private final TrustBundles trustBundles;
    private final String userAgent;

    public VisionImageDescriber(Supplier<AiServiceSettings> settings, ConfiguredSourceCatalog catalog,
                                TrustBundles trustBundles, String userAgent) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.trustBundles = Objects.requireNonNull(trustBundles, "trustBundles");
        this.userAgent = Objects.requireNonNull(userAgent, "userAgent");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String modelVersion() {
        AiServiceSettings current = settings.get();
        return current == null || current.visionModel() == null ? "none" : current.visionModel();
    }

    @Override
    public boolean available() {
        AiServiceSettings current = settings.get();
        return current != null && current.vision();
    }

    @Override
    public boolean mayDescribe(String sourceId) {
        AiServiceSettings current = settings.get();
        return current != null && current.mayProcessExternally(sourceId);
    }

    @Override
    public String describe(byte[] image, String mediaType) {
        return describe(image, mediaType, null);
    }

    @Override
    public boolean loaded() {
        AiServiceSettings current = settings.get();
        return current == null || !current.vision()
                || RemoteAi.of(current, catalog, trustBundles, userAgent).visionLoaded();
    }

    /**
     * Wie AskAI: stößt das Laden einmal an ({@code POST /api/generate} läuft im Hintergrund weiter, auch wenn mehrere
     * Läufe zugleich warten) und fragt dann im eingestellten Abstand ({@code load-poll-interval}) mit
     * {@code GET /api/ps}, bis das Modell läuft; erst danach gehen die Prompts hinaus. Höchstens die Ladefrist
     * ({@code load-timeout}).
     *
     * @throws AiServiceException wenn das Laden scheitert oder die Ladefrist abläuft
     */
    @Override
    public void load() {
        AiServiceSettings current = settings.get();
        if (current == null || !current.vision()) {
            return;
        }
        RemoteAi remote = RemoteAi.of(current, catalog, trustBundles, userAgent);
        if (remote.visionLoaded()) {
            return;
        }
        CompletableFuture<Void> request;
        synchronized (this) {
            if (loading == null || loading.isDone()) {
                loading = CompletableFuture.runAsync(remote::loadVision, loader);
            }
            request = loading;
        }
        long deadline = System.nanoTime() + current.loadTimeout().toNanos();
        while (!remote.visionLoaded()) {
            if (request.isDone()) {
                try {
                    request.join();
                } catch (CompletionException failed) {
                    throw failed.getCause() instanceof AiServiceException cause ? cause
                            : new AiServiceException(AiServiceException.Reason.UNREACHABLE, "Vision-Modell nicht geladen");
                }
                // Geladen, auch wenn der Server es unter anderem Namen führt.
                return;
            }
            if (System.nanoTime() - deadline > 0) {
                throw new AiServiceException(AiServiceException.Reason.TIMEOUT, "Vision-Modell nicht innerhalb der "
                        + "Ladefrist geladen");
            }
            try {
                Thread.sleep(current.loadPollInterval().toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AiServiceException(AiServiceException.Reason.TIMEOUT, "Warten auf das Vision-Modell "
                        + "abgebrochen");
            }
        }
    }

    /** Das laufende Laden des Modells, damit es auch bei mehreren wartenden Läufen nur einmal angestoßen wird. */
    private CompletableFuture<Void> loading;
    private final ExecutorService loader = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "vision-model-load");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public int retries() {
        AiServiceSettings current = settings.get();
        return current == null ? 0 : current.retries();
    }

    @Override
    public String describe(byte[] image, String mediaType, String instruction) {
        AiServiceSettings current = settings.get();
        if (current == null || !current.vision()) {
            throw new ImageDescriptionException(ImageDescriptionException.Reason.NOT_CONFIGURED,
                    "Kein Vision-Modell eingestellt", null);
        }
        return RemoteAi.of(current, catalog, trustBundles, userAgent).describeImage(image, mediaType, instruction);
    }
}
