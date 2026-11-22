package com.aresstack.spaceharvester.source.ai;

import com.aresstack.spaceharvester.ai.AiServiceException;
import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.stepmodel.StepModel;
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
 * Ein Analyseschritt über das Chat-Modell des KI-Dienstes (ADR 0032): die Vorstufe „Sätze vereinfachen“ (FL3) und der
 * Prüfschritt (FL4) teilen diesen einen Adapter, je mit eigener Kennung. Temperatur 0, eine am Ausgabelimit
 * abgeschnittene Antwort wirft ({@link RemoteAi#termCompletions()}). Folgt der wirksamen Konfiguration bei jedem Aufruf
 * wie {@link ChatUpdateSummaryModel}; ist für den Schritt ein eigenes Modell eingestellt, gilt es statt
 * {@code ai.chat-model} (Dienst und Schlüssel bleiben dieselben). Welche Seiten an den Dienst dürfen, entscheidet der
 * Schritt über die KI-Freigabe je Seite (ADR 0028).
 */
public final class ChatStepModel implements StepModel {

    /** Kennung der Vorstufe „Sätze vereinfachen“. */
    public static final String SIMPLIFICATION = "sentence-simplification";
    /** Kennung des Prüfschritts. */
    public static final String CHECK = "statement-check";

    private final String id;
    private final Supplier<AiServiceSettings> settings;
    private final Supplier<String> stepModel;
    private final Supplier<Integer> maxTokens;
    private final Supplier<String> thinking;
    private final Function<AiServiceSettings, RemoteAi> remote;

    /**
     * @param id        Kennung des Schritts, etwa {@link #SIMPLIFICATION} oder {@link #CHECK}
     * @param stepModel eigenes Modell für diesen Schritt oder {@code null}/leer für {@code ai.chat-model}
     */
    public ChatStepModel(String id, Supplier<AiServiceSettings> settings, Supplier<String> stepModel,
                         ConfiguredSourceCatalog catalog, TrustBundles trustBundles, String userAgent) {
        this(id, settings, stepModel, () -> null, catalog, trustBundles, userAgent);
    }

    /**
     * Mit eigener Tokenlänge des Schritts: {@code maxTokens} geht bei jeder Anfrage als Ausgabelimit mit
     * ({@code max_tokens}, bei Ollama {@code num_predict}); {@code null} heißt {@code ai.chat-max-tokens}.
     */
    public ChatStepModel(String id, Supplier<AiServiceSettings> settings, Supplier<String> stepModel,
                         Supplier<Integer> maxTokens, ConfiguredSourceCatalog catalog, TrustBundles trustBundles,
                         String userAgent) {
        this(id, settings, stepModel, maxTokens, () -> null, catalog, trustBundles, userAgent);
    }

    /**
     * Mit eigener Tokenlänge und eigenem Nachdenken des Schritts: {@code thinking} ({@code none}, {@code low},
     * {@code medium}, {@code high}) geht bei jeder Anfrage mit; {@code null} heißt wie das Text-Modell
     * ({@code ai.step-thinking}).
     */
    public ChatStepModel(String id, Supplier<AiServiceSettings> settings, Supplier<String> stepModel,
                         Supplier<Integer> maxTokens, Supplier<String> thinking, ConfiguredSourceCatalog catalog,
                         TrustBundles trustBundles, String userAgent) {
        this(id, settings, stepModel, maxTokens, thinking,
                current -> RemoteAi.of(current, catalog, trustBundles, userAgent));
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(trustBundles, "trustBundles");
        Objects.requireNonNull(userAgent, "userAgent");
    }

    ChatStepModel(String id, Supplier<AiServiceSettings> settings, Supplier<String> stepModel,
                  Function<AiServiceSettings, RemoteAi> remote) {
        this(id, settings, stepModel, () -> null, remote);
    }

    ChatStepModel(String id, Supplier<AiServiceSettings> settings, Supplier<String> stepModel,
                  Supplier<Integer> maxTokens, Function<AiServiceSettings, RemoteAi> remote) {
        this(id, settings, stepModel, maxTokens, () -> null, remote);
    }

    ChatStepModel(String id, Supplier<AiServiceSettings> settings, Supplier<String> stepModel,
                  Supplier<Integer> maxTokens, Supplier<String> thinking, Function<AiServiceSettings, RemoteAi> remote) {
        this.thinking = Objects.requireNonNull(thinking, "thinking");
        this.maxTokens = Objects.requireNonNull(maxTokens, "maxTokens");
        this.id = Objects.requireNonNull(id, "id");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.stepModel = Objects.requireNonNull(stepModel, "stepModel");
        this.remote = Objects.requireNonNull(remote, "remote");
    }

    /**
     * Die wirksamen Einstellungen mit dem Modell und der Tokenlänge des Schritts als Chat-Modell; {@code null} ohne
     * Dienst.
     */
    AiServiceSettings effective() {
        AiServiceSettings current = settings.get();
        if (current == null) {
            return null;
        }
        String step = stepModel.get();
        Integer limit = maxTokens.get();
        String think = thinking.get();
        if ((step == null || step.isBlank()) && limit == null && (think == null || think.isBlank())) {
            return current;
        }
        return current.forChat(step, limit, think);
    }

    /** Verfahren, Modell und ein Fingerabdruck der Dienstadresse; nie die Adresse selbst. */
    @Override
    public String version() {
        AiServiceSettings current = effective();
        if (current == null || current.chatModel() == null) {
            return id + ":none";
        }
        return id + ":" + current.chatModel() + "@" + fingerprint(String.valueOf(current.baseUrl()));
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
        AiServiceSettings current = effective();
        return current != null && current.chat();
    }

    @Override
    public String complete(String instructions, String input) {
        AiServiceSettings current = effective();
        if (current == null || !current.chat()) {
            throw new AiServiceException(AiServiceException.Reason.NOT_CONFIGURED, "Kein Chat-Modell eingestellt");
        }
        return remote.apply(current).termCompletions().apply(instructions, input);
    }
}
