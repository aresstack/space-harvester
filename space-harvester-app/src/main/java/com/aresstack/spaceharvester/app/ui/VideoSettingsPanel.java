package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.settings.VideoSettings;

import javax.swing.JComponent;
import javax.swing.JTextField;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bereich „Video“ der Einstellungen: je Wert für Videos ({@code space-harvester-app.video.*}) ein Feld mit Hinweis,
 * Vorgabe und Grenzen ({@link NumberSettingsPanel}). Player, Standbilder und Marken lesen eine Änderung beim nächsten
 * Gebrauch. Nur auf dem EDT.
 */
final class VideoSettingsPanel {

    static final String HEADING = "Videos und Folien";
    static final String RESET = NumberSettingsPanel.RESET;

    private final NumberSettingsPanel<VideoSettings.Value> panel;

    VideoSettingsPanel(AppContext context) {
        Objects.requireNonNull(context, "context");
        panel = new NumberSettingsPanel<>(HEADING, "Alle Werte für Videos auf ihre Vorgabe zurücksetzen",
                VideoSettings.Value.class, () -> values(context.video()), (values, saved, failed) -> {
                    if (context.settings() == null) {
                        return false;
                    }
                    context.video(new VideoSettings(values), saved, failed);
                    return true;
                });
    }

    private static Map<VideoSettings.Value, Long> values(VideoSettings settings) {
        Map<VideoSettings.Value, Long> values = new EnumMap<>(VideoSettings.Value.class);
        for (VideoSettings.Value value : VideoSettings.Value.values()) {
            values.put(value, settings.get(value));
        }
        return values;
    }

    JComponent component() {
        return panel.component();
    }

    /** Zeigt die gespeicherten Werte. */
    void render() {
        panel.render();
    }

    /** Das Feld eines Werts (für Tests). */
    JTextField field(VideoSettings.Value value) {
        return panel.field(value);
    }
}
