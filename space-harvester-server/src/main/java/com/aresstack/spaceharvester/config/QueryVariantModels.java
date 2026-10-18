package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.query.progressive.QueryVariantModel;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import com.aresstack.spaceharvester.source.TrustBundles;
import com.aresstack.spaceharvester.source.ai.RemoteAi;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Das Chat-Modell der wirksamen Konfiguration für die Suchvarianten der progressiven Suche (ADR 0017), wie im
 * {@code Harvester} je Einstellungsstand einmal gebaut; leer ohne Dienst oder Chat-Modell. Dann formulieren die Regeln
 * die Varianten.
 */
final class QueryVariantModels implements Supplier<Optional<QueryVariantModel>> {

    private record Built(AiServiceSettings settings, Optional<QueryVariantModel> model) {
    }

    private final Supplier<AiServiceSettings> settings;
    private final ConfiguredSourceCatalog catalog;
    private final TrustBundles trust;
    private final String userAgent;
    private volatile Built built;

    QueryVariantModels(Supplier<AiServiceSettings> settings, ConfiguredSourceCatalog catalog, TrustBundles trust,
                       String userAgent) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.catalog = catalog;
        this.trust = trust;
        this.userAgent = userAgent;
    }

    @Override
    public Optional<QueryVariantModel> get() {
        AiServiceSettings current = settings.get();
        Built last = built;
        if (last != null && last.settings() == current) {
            return last.model();
        }
        Optional<QueryVariantModel> model;
        try {
            model = current != null && current.chat()
                    ? Optional.of(RemoteAi.of(current, catalog, trust, userAgent).queryVariants())
                    : Optional.empty();
        } catch (RuntimeException e) {
            // Etwa ein nicht eingerichtetes trust-bundle: dann Regeln, wie ohne Modell.
            model = Optional.empty();
        }
        built = new Built(current, model);
        return model;
    }
}
