package com.aresstack.spaceharvester.source.ai;

import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import com.aresstack.spaceharvester.ai.AiProvider;
import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.changelog.SummaryModel;
import com.aresstack.spaceharvester.source.TrustBundles;
import java.net.URI;
import java.util.Set;

/** Für Echttests: das Zusammenfassen über den echten Weg (RemoteAi, gestreamt, ai.parallel) gegen ein Ollama. */
public final class LiveSummaryModel {

    private LiveSummaryModel() {
    }

    public static SummaryModel ollama(String base, String model) {
        AiServiceSettings settings = new AiServiceSettings(URI.create(base), null, model, null, null, null, null,
                Set.of()).withProvider(AiProvider.OLLAMA).withContextTokens(16384);
        return new ChatUpdateSummaryModel(() -> settings,
                current -> new RemoteAi(current, HttpRoutePort.direct(), TrustBundles.NONE, "space-harvester-live"));
    }
}
