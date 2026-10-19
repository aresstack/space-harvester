package com.aresstack.spaceharvester.source.ai;

import com.aresstack.spaceharvester.ai.AiServiceException;
import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.passages.NavigationLabelModel;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import com.aresstack.spaceharvester.source.TrustBundles;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Benennung der Themenabschnitte der Dokumentnavigation über das Chat-Modell des KI-Dienstes (#181, ADR 0020) mit der
 * unveränderten {@code ChatSectionLabeler} des Enterprise AI Client. Folgt der wirksamen Konfiguration bei jedem
 * Aufruf: ein anderes {@code ai.chat-model} macht vorhandene Navigationen alt, ohne Modell benennt er nichts. Text geht
 * nur aus Quellen in {@code external-processing-sources} an den Dienst, höchstens 700 Zeichen je Abschnitt. Eigene
 * Frist ({@link RemoteAi#LABEL_TIMEOUT}) und höchstens {@value #PARALLELISM} Anfragen zugleich, damit die Benennung
 * weder Suchvarianten noch Bildbeschreibung verdrängt. Nach einem Fehler benennt er {@link #PAUSE} lang nichts und
 * meldet sofort {@code UNREACHABLE}: Ein nicht erreichbares Modell hält die Hintergrund-Warteschlange, die Navigation
 * und Embeddings teilen, so höchstens einmal je Pause um eine Frist auf.
 */
public final class ChatNavigationLabeler implements NavigationLabelModel {

    /**
     * Kennung samt Verfahrensversion: Anweisung und Auswertung von {@code ChatSectionLabeler} in der Fassung von
     * Commit 9fb91c3. Ändert sich die Kopie, steigt die Version, und alle Navigationen werden neu berechnet.
     */
    public static final String ID = "chat-section-labeler-v1";
    /** Höchstens so viele Benennungen zugleich. */
    static final int PARALLELISM = 2;
    /** So lange nach einem Fehler keine Anfrage, für dieselbe {@link #version()}. */
    static final Duration PAUSE = Duration.ofMinutes(5);

    private final Supplier<AiServiceSettings> settings;
    private final Function<AiServiceSettings, RemoteAi> remote;
    private final Clock clock;
    private final Semaphore slots = new Semaphore(PARALLELISM, true);
    private volatile Pause pause;

    private record Pause(String version, Instant until) {
    }

    public ChatNavigationLabeler(Supplier<AiServiceSettings> settings, ConfiguredSourceCatalog catalog,
                                 TrustBundles trustBundles, String userAgent) {
        this(settings, current -> RemoteAi.of(current, catalog, trustBundles, userAgent), Clock.systemUTC());
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(trustBundles, "trustBundles");
        Objects.requireNonNull(userAgent, "userAgent");
    }

    ChatNavigationLabeler(Supplier<AiServiceSettings> settings, Function<AiServiceSettings, RemoteAi> remote,
                          Clock clock) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.remote = Objects.requireNonNull(remote, "remote");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Modell und ein Fingerabdruck der Dienstadresse: Ein anderer Dienst mit gleichem Modellnamen benennt womöglich
     * anders und macht vorhandene Navigationen ebenso alt. Die Adresse selbst steht nicht in der Version.
     */
    @Override
    public String version() {
        return version(settings.get());
    }

    private static String version(AiServiceSettings current) {
        if (current == null || current.chatModel() == null) {
            return ID + ":none";
        }
        return ID + ":" + current.chatModel() + "@" + fingerprint(String.valueOf(current.baseUrl()));
    }

    private static String fingerprint(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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
        String version = version(current);
        Pause paused = pause;
        if (paused != null && paused.version().equals(version) && clock.instant().isBefore(paused.until())) {
            throw new AiServiceException(AiServiceException.Reason.UNREACHABLE, "Chat-Modell kürzlich nicht erreichbar");
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
            Labels labels = remote.apply(current).sectionLabels(documentTitle, sectionTexts);
            if (!labels.complete()) {
                pause = new Pause(version, clock.instant().plus(PAUSE));
            }
            return labels;
        } catch (RuntimeException e) {
            pause = new Pause(version, clock.instant().plus(PAUSE));
            throw e;
        } finally {
            slots.release();
        }
    }
}
