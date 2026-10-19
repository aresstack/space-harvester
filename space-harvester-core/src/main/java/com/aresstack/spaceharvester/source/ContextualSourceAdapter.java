package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.source.api.KnowledgeSourceProvider;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Ein Quellentyp aus einem {@link KnowledgeSourceProvider} des Enterprise AI Client, der zum Öffnen etwas aus dem
 * {@link SourceAdapter.Context Kontext des Katalogs} braucht, etwa Zugangsdaten oder die Proxy-Route (#187). Geprüft und
 * geöffnet wird wie bei {@link KnowledgeSourceProviderAdapter}; die Prüfung bekommt einen Provider ohne Kontext, weil
 * {@code validate} nichts verbindet und keine Zugangsdaten auflöst.
 */
public final class ContextualSourceAdapter implements SourceAdapter {

    private final String type;
    private final String displayName;
    private final boolean needsCredentials;
    private final Function<Context, KnowledgeSourceProvider> provider;

    /**
     * @param type             Wert von {@code type} in der Quellenkonfiguration, gleich dem Typ des Providers
     * @param displayName      Anzeigename für Auswahllisten
     * @param needsCredentials ob der Typ {@code options.credentialRef} verlangt
     * @param provider         baut den Provider zum Kontext; mit {@code null} für die Prüfung
     */
    public ContextualSourceAdapter(String type, String displayName, boolean needsCredentials,
                                   Function<Context, KnowledgeSourceProvider> provider) {
        this.type = Objects.requireNonNull(type, "type");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.needsCredentials = needsCredentials;
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    @Override
    public String type() {
        return type;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public boolean needsCredentials() {
        return needsCredentials;
    }

    @Override
    public String addressOption() {
        return new KnowledgeSourceProviderAdapter(provider.apply(null)).addressOption();
    }

    @Override
    public List<String> check(SourceSettingsConfig config) {
        return new KnowledgeSourceProviderAdapter(provider.apply(null)).check(config);
    }

    @Override
    public OpenedSource open(SourceSettingsConfig config, Context context) {
        return new KnowledgeSourceProviderAdapter(provider.apply(context)).open(config, context);
    }
}
