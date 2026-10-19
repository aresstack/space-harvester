package com.aresstack.spaceharvester.source.ai;

import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.enrichment.statements.StatementChat;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import com.aresstack.spaceharvester.source.TrustBundles;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Das Chat-Modell ({@code ai.chat-model}) der wirksamen Konfiguration für die Satzanalyse (Prozessor
 * {@code statements}, #108), je Einstellungsstand einmal gebaut wie die Suchvarianten (ADR 0017); {@code null} ohne
 * Dienst oder Chat-Modell. Die Freigabe je Quelle ({@link #mayProcessExternally}) stammt aus derselben Einstellung:
 * neu freigegebene Inhalte gehen nie an den Endpunkt eines früheren Stands.
 */
public final class StatementChatModels implements Supplier<StatementChat> {

    private record Built(AiServiceSettings settings, StatementChat model) {
    }

    private final Supplier<AiServiceSettings> settings;
    private final ConfiguredSourceCatalog catalog;
    private final TrustBundles trustBundles;
    private final String userAgent;
    private volatile Built built;

    public StatementChatModels(Supplier<AiServiceSettings> settings, ConfiguredSourceCatalog catalog,
                               TrustBundles trustBundles, String userAgent) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.trustBundles = Objects.requireNonNull(trustBundles, "trustBundles");
        this.userAgent = Objects.requireNonNull(userAgent, "userAgent");
    }

    @Override
    public StatementChat get() {
        AiServiceSettings current = settings.get();
        Built last = built;
        if (last != null && last.settings() == current) {
            return last.model();
        }
        StatementChat model;
        try {
            model = current != null && current.chat()
                    ? RemoteAi.of(current, catalog, trustBundles, userAgent).statementChat() : null;
        } catch (RuntimeException e) {
            // Etwa ein nicht eingerichtetes trust-bundle: dann keine Satzanalyse, wie ohne Modell.
            model = null;
        }
        built = new Built(current, model);
        return model;
    }

    /** Ob Inhalte der Quelle an den Dienst gehen dürfen ({@code ai.external-processing-sources}), je Aufruf gelesen. */
    public Predicate<String> mayProcessExternally() {
        return sourceId -> {
            AiServiceSettings current = settings.get();
            return current != null && current.mayProcessExternally(sourceId);
        };
    }
}
