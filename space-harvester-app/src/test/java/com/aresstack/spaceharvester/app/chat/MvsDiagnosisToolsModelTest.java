package com.aresstack.spaceharvester.app.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.app.ui.deck.MvsDiagnosisSettings;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Zeile „Diagnose“ (Angelo 26.11.2026): Schalter „Diagnose per KI“ und Modell statt fest gpt-oss-120b. */
class MvsDiagnosisToolsModelTest {

    @Test
    void toolsFollowTheConfiguredDiagnosisModelAndSwitch() {
        AtomicReference<AiServiceSettings.Diagnosis> diagnosis = new AtomicReference<>(
                new AiServiceSettings.Diagnosis(null, new AiServiceSettings.ModelUse("gemma4:12b", null, null)));
        MvsDiagnosisTools tools = new MvsDiagnosisTools(new NoTools(), new MvsDiagnosisTools.Grant(null, null,
                MvsDiagnosisSettings.shared()), () -> true, diagnosis::get);

        tools.modelSelected("gpt-oss-120b");
        assertThat(names(tools)).as("die frühere feste Freigabe gilt nicht mehr").isEmpty();
        tools.modelSelected("ollama/gemma4:12b");
        assertThat(names(tools)).contains(MvsDiagnosisTools.INSPECT, MvsDiagnosisTools.READ, MvsDiagnosisTools.RECORDS);

        diagnosis.set(new AiServiceSettings.Diagnosis(false, diagnosis.get().use()));
        assertThat(names(tools)).as("ohne „Diagnose per KI“ keine Werkzeuge").isEmpty();
    }

    private static List<String> names(ChatToolset tools) {
        return tools.definitions().stream().map(AiChat.Tool::name).toList();
    }

    private static final class NoTools implements ChatToolset {
        @Override
        public List<AiChat.Tool> definitions() {
            return List.of();
        }

        @Override
        public String run(AiChat.ToolCall call) {
            return "";
        }

        @Override
        public String describe(AiChat.ToolCall call) {
            return "";
        }
    }
}
