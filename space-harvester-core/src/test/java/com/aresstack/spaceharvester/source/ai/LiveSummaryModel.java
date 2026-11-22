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
        return of(new AiServiceSettings(URI.create(base), null, model, null, null, null, null,
                Set.of()).withProvider(AiProvider.OLLAMA).withContextTokens(16384));
    }

    /**
     * Derselbe Ollama über seine OpenAI-kompatible Schnittstelle ({@code /v1}): So geht „Nachdenken aus“ als
     * {@code reasoning_effort} mit, wie beim Dienst im Betrieb.
     */
    public static SummaryModel openAiCompatible(String base, String model) {
        return of(new AiServiceSettings(URI.create(base.replaceAll("/+$", "") + "/v1"), null, model, null, null, null,
                null, Set.of()).withProvider(AiProvider.OPENAI_COMPATIBLE));
    }

    /** Ausgabelimit der Zusammenfassung für einen Echttest absichtlich klein (gezielt hervorgerufenes Abschneiden). */
    public static void summaryMaxTokens(int maxTokens) {
        RemoteAi.summaryMaxTokensForTest = maxTokens;
    }

    /** Ausgabelimit wieder wie im Betrieb. */
    public static void resetSummaryMaxTokens() {
        RemoteAi.summaryMaxTokensForTest = 0;
    }

    private static SummaryModel of(AiServiceSettings configured) {
        AiServiceSettings settings = configured;
        return new ChatUpdateSummaryModel(() -> settings,
                current -> new RemoteAi(current, HttpRoutePort.direct(), TrustBundles.NONE, "space-harvester-live"));
    }
}
