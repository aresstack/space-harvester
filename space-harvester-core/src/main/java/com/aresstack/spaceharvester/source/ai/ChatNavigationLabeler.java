package com.aresstack.spaceharvester.source.ai;

import com.aresstack.spaceharvester.ai.AiServiceException;
import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.passages.NavigationLabelModel;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import com.aresstack.spaceharvester.source.TrustBundles;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Benennung der Themenabschnitte der Dokumentnavigation über das Chat-Modell des KI-Dienstes (#181, ADR 0020) mit der
 * unveränderten {@code ChatSectionLabeler} des Enterprise AI Client. Folgt der wirksamen Konfiguration bei jedem
 * Aufruf: ein anderes {@code ai.chat-model} macht vorhandene Navigationen alt, ohne Modell benennt er nichts. Text geht
 * nur aus Quellen in {@code external-processing-sources} an den Dienst, höchstens 700 Zeichen je Abschnitt. Eigene
 * Frist ({@link RemoteAi#LABEL_TIMEOUT}) und höchstens {@value #PARALLELISM} Anfragen zugleich, damit die Benennung
 * weder Suchvarianten noch Bildbeschreibung verdrängt.
 */
public final class ChatNavigationLabeler implements NavigationLabelModel {

    /**
     * Kennung samt Verfahrensversion: Anweisung und Auswertung von {@code ChatSectionLabeler} in der Fassung von
     * Commit 9fb91c3. Ändert sich die Kopie, steigt die Version, und alle Navigationen werden neu berechnet.
     */
    public static final String ID = "chat-section-labeler-v1";
    /** Höchstens so viele Benennungen zugleich. */
    static final int PARALLELISM = 2;

    private final Supplier<AiServiceSettings> settings;
    private final ConfiguredSourceCatalog catalog;
    private final TrustBundles trustBundles;
    private final String userAgent;
    private final Semaphore slots = new Semaphore(PARALLELISM, true);

    public ChatNavigationLabeler(Supplier<AiServiceSettings> settings, ConfiguredSourceCatalog catalog,
                                 TrustBundles trustBundles, String userAgent) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.trustBundles = Objects.requireNonNull(trustBundles, "trustBundles");
        this.userAgent = Objects.requireNonNull(userAgent, "userAgent");
    }

    @Override
    public String version() {
        AiServiceSettings current = settings.get();
        return ID + ":" + (current == null || current.chatModel() == null ? "none" : current.chatModel());
    }

    @Override
    public boolean available() {
        AiServiceSettings current = settings.get();
        return current != null && current.chat();
    }

    @Override
    public boolean mayLabel(String sourceId) {
        AiServiceSettings current = settings.get();
        return current != null && current.mayProcessExternally(sourceId);
    }

    @Override
    public Labels labels(String documentTitle, List<String> sectionTexts) {
        AiServiceSettings current = settings.get();
        if (current == null || !current.chat()) {
            throw new AiServiceException(AiServiceException.Reason.NOT_CONFIGURED, "Kein Chat-Modell eingestellt");
        }
        boolean acquired;
        try {
            acquired = slots.tryAcquire(RemoteAi.LABEL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiServiceException(AiServiceException.Reason.TIMEOUT, "Abschnittsbenennung unterbrochen");
        }
        if (!acquired) {
            throw new AiServiceException(AiServiceException.Reason.TIMEOUT, "Abschnittsbenennung ausgelastet");
        }
        try {
            return RemoteAi.of(current, catalog, trustBundles, userAgent).sectionLabels(documentTitle, sectionTexts);
        } finally {
            slots.release();
        }
    }
}
