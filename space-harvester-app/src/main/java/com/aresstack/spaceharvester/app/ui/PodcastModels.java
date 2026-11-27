package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.ai.AiModel;
import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.app.settings.AiDraft;
import com.aresstack.spaceharvester.app.settings.AppSettings;

import javax.swing.JMenu;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Die Modelle der Podcast-Schritte im Menü am Dreieck der Lese- und der Podcast-Ansicht (Angelo 24.11.2026): dieselben
 * Zeilen und Schlüssel wie unter Einstellungen › KI-Dienst › Modelle, gelesen und geschrieben direkt dort
 * ({@link AppContext#changeAi}); „Standard“ ist der leere Eintrag wie bisher.
 */
public final class PodcastModels {

    /** Eine Zeile der Einstellungen: lesen und schreiben ihres Modells. */
    record Row(String label, String tip, String category, Function<AiDraft, String> read,
               BiFunction<AiDraft, String, AiDraft> write) {
    }

    static final List<Row> ROWS = List.of(
            new Row(AiSettingsPanel.PODCAST_LABEL, "Plant den Podcast und schreibt die Sprechertexte; Standard: "
                    + "das Text-Modell", AiModel.CHAT, draft -> draft.podcast().model(),
                    (draft, model) -> draft.withUses(draft.summary(), draft.summaryPlan(), withModel(draft.podcast(),
                            model))),
            new Row(AiSettingsPanel.IMAGE_PLAN_LABEL, "Plant die Schaubilder des Podcasts; Standard: das "
                    + "Text-Modell", AiModel.CHAT, draft -> draft.imagePlan().model(),
                    (draft, model) -> draft.withImagePlan(withModel(draft.imagePlan(), model))),
            new Row(AiSettingsPanel.IMAGE_LABEL, "Zeichnet die Bilder und die Teile der Schaubilder; Standard: das "
                    + "Text-Modell", AiModel.CHAT, AiDraft::imageModel,
                    (draft, model) -> draft.withImageGeneration(model, draft.imageMaxTokens())),
            new Row(AiSettingsPanel.PODCAST_SPEECH_LABEL, "Spricht den Podcast; Standard: das bevorzugte "
                    + "Sprachmodell des Dienstes", AiModel.TTS, draft -> speech(draft).podcastModel(),
                    (draft, model) -> {
                        AiServiceSettings.Speech speech = speech(draft);
                        return draft.withSpeech(speech.withPodcastModel(model));
                    }));

    private PodcastModels() {
    }

    /**
     * Je Zeile ein Untermenü „Modell …“; leer ohne gelesene Einstellungen.
     *
     * @param status bekommt, was nach einer Wahl geschah
     */
    public static List<JMenu> menus(Component owner, AppContext context, ModelChoice.Catalog catalog,
                                    Consumer<String> status) {
        AppSettings settings = context.settings();
        if (settings == null) {
            return List.of();
        }
        AiDraft current = AiDraft.of(settings.ai());
        List<JMenu> menus = new ArrayList<>();
        for (Row row : ROWS) {
            menus.add(ModelChoice.menu(owner, "Modell " + row.label(), row.tip(), row.category(), row.read().apply(current),
                    catalog, model -> context.changeAi(read -> row.write().apply(AiDraft.of(read.ai()), model),
                            () -> status.accept("Modell „" + row.label() + "“: " + (model == null
                                    ? ModelChoice.STANDARD : model)),
                            error -> status.accept("Modell „" + row.label() + "“ nicht übernommen: "
                                    + error.getMessage()))));
        }
        return menus;
    }

    private static AiDraft.Use withModel(AiDraft.Use use, String model) {
        AiDraft.Use own = use == null ? AiDraft.Use.EMPTY : use;
        return new AiDraft.Use(model, own.maxTokens(), own.thinking());
    }

    private static AiServiceSettings.Speech speech(AiDraft draft) {
        return draft.speech() == null ? AiServiceSettings.Speech.AUTOMATIC : draft.speech();
    }
}
