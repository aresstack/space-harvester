package com.aresstack.spaceharvester.source.ai;

import com.aresstack.spaceharvester.ai.AiServiceException;
import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.terms.AcronymExpansionModel;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import com.aresstack.spaceharvester.source.TrustBundles;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Akronymauflösung über das Chat-Modell des KI-Dienstes (#122). Folgt der wirksamen Konfiguration bei jedem Aufruf:
 * ein anderes {@code ai.chat-model} wirkt ohne Neustart, ohne Modell löst er nichts auf. Text geht nur aus Quellen in
 * {@code external-processing-sources} an den Dienst.
 */
public final class ChatAcronymExpansionModel implements AcronymExpansionModel {

    /** Kennung des Verfahrens; ändern sich Anweisung oder Auswertung, steigt die Version. */
    public static final String ID = "chat-acronym-expansions-v1";

    private final Supplier<AiServiceSettings> settings;
    private final Function<AiServiceSettings, RemoteAi> remote;

    public ChatAcronymExpansionModel(Supplier<AiServiceSettings> settings, ConfiguredSourceCatalog catalog,
                                     TrustBundles trustBundles, String userAgent) {
        this(settings, current -> RemoteAi.of(current, catalog, trustBundles, userAgent));
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(trustBundles, "trustBundles");
        Objects.requireNonNull(userAgent, "userAgent");
    }

    ChatAcronymExpansionModel(Supplier<AiServiceSettings> settings, Function<AiServiceSettings, RemoteAi> remote) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.remote = Objects.requireNonNull(remote, "remote");
    }

    /** Modell und ein Fingerabdruck der Dienstadresse, wie {@link ChatNavigationLabeler}; nie die Adresse selbst. */
    @Override
    public String version() {
        AiServiceSettings current = settings.get();
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
    public boolean mayProcess(String sourceId) {
        AiServiceSettings current = settings.get();
        return current != null && current.mayProcessExternally(sourceId);
    }

    @Override
    public String complete(String instructions, String input) {
        AiServiceSettings current = settings.get();
        if (current == null || !current.chat()) {
            throw new AiServiceException(AiServiceException.Reason.NOT_CONFIGURED, "Kein Chat-Modell eingestellt");
        }
        return remote.apply(current).acronymExpansions().apply(instructions, input);
    }
}
