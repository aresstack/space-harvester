package com.aresstack.spaceharvester.source.ai;

import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.assets.ImageDescriber;
import com.aresstack.spaceharvester.application.assets.ImageDescriptionException;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import com.aresstack.spaceharvester.source.TrustBundles;
import java.util.Objects;
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
        AiServiceSettings current = settings.get();
        if (current == null || !current.vision()) {
            throw new ImageDescriptionException(ImageDescriptionException.Reason.NOT_CONFIGURED,
                    "Kein Vision-Modell eingestellt", null);
        }
        return RemoteAi.of(current, catalog, trustBundles, userAgent).describeImage(image, mediaType);
    }
}
