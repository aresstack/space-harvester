package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.settings.VideoSettings;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bereich „Video“ der Einstellungen: je Wert für Videos ({@code space-harvester-app.video.*}) ein Feld mit Hinweis,
 * Vorgabe und Grenzen, in der Reihenfolge von {@link VideoSettings.Value}. Eine Änderung (Enter oder Verlassen des
 * Feldes) wird geprüft und sofort gespeichert, ohne Neustart; Player, Standbilder und Marken lesen sie beim nächsten
 * Gebrauch. „Vorgaben“ setzt alle Werte zurück. Nur auf dem EDT.
 */
final class VideoSettingsPanel {

    static final String HEADING = "Videos und Folien";
    static final String RESET = "Vorgaben";

    private final AppContext context;
    private final SettingDialogs.Rows rows = new SettingDialogs.Rows();
    private final Map<VideoSettings.Value, JTextField> fields = new EnumMap<>(VideoSettings.Value.class);
    private final Feedback feedback = new Feedback();
    private final ComposerButton reset = Comic.button(RESET, "Alle Werte für Videos auf ihre Vorgabe zurücksetzen");
    private final JPanel component = new JPanel(new BorderLayout(0, 8));
    /** Was die Felder zeigen; dieselben Werte noch einmal schreiben nichts. */
    private VideoSettings shown = VideoSettings.DEFAULT;
    /** Während {@link #render} die Felder füllt, ist das keine Eingabe des Benutzers. */
    private boolean showing;

    VideoSettingsPanel(AppContext context) {
        this.context = Objects.requireNonNull(context, "context");
        for (VideoSettings.Value value : VideoSettings.Value.values()) {
            JTextField field = Comic.field(8);
            field.setToolTipText(value.hint());
            field.addActionListener(e -> save());
            field.addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusLost(java.awt.event.FocusEvent e) {
                    save();
                }
            });
            fields.put(value, field);
            rows.add(value.label(), field, value.hint() + ". Vorgabe " + value.defaultValue() + ", von "
                    + value.min() + " bis " + value.max() + ".");
        }
        reset.addActionListener(e -> {
            render(VideoSettings.DEFAULT);
            save();
        });
        component.setOpaque(false);
        component.add(rows, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout(0, 6));
        south.setOpaque(false);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        buttons.setOpaque(false);
        buttons.add(reset);
        south.add(buttons, BorderLayout.NORTH);
        south.add(feedback, BorderLayout.CENTER);
        component.add(south, BorderLayout.SOUTH);
        render();
    }

    JComponent component() {
        return component;
    }

    /** Zeigt die gespeicherten Werte. */
    void render() {
        shown = context.video();
        render(shown);
    }

    private void render(VideoSettings settings) {
        showing = true;
        try {
            fields.forEach((value, field) -> field.setText(String.valueOf(settings.get(value))));
        } finally {
            showing = false;
        }
    }

    /** Prüft die Felder und speichert sie, wenn sich etwas geändert hat; ein ungültiger Wert wird gemeldet. */
    private void save() {
        if (showing || context.settings() == null) {
            return;
        }
        Map<VideoSettings.Value, Long> values = new EnumMap<>(VideoSettings.Value.class);
        for (Map.Entry<VideoSettings.Value, JTextField> entry : fields.entrySet()) {
            VideoSettings.Value value = entry.getKey();
            String text = entry.getValue().getText().strip();
            long chosen;
            try {
                chosen = text.isEmpty() ? value.defaultValue() : Long.parseLong(text);
            } catch (NumberFormatException e) {
                feedback.problem(new IllegalArgumentException(value.label() + ": „" + text + "“ ist keine ganze "
                        + "Zahl; nicht gespeichert"));
                return;
            }
            if (!value.accepts(chosen)) {
                feedback.problem(new IllegalArgumentException(value.label() + ": " + chosen + " liegt nicht zwischen "
                        + value.min() + " und " + value.max() + "; nicht gespeichert"));
                return;
            }
            values.put(value, chosen);
        }
        VideoSettings chosen = new VideoSettings(values);
        if (chosen.equals(shown)) {
            return;
        }
        shown = chosen;
        context.video(chosen, () -> feedback.success(HEADING + (chosen.changed().isEmpty() ? ": Vorgaben"
                : " gespeichert")), feedback::problem);
    }

    /** Das Feld eines Werts (für Tests). */
    JTextField field(VideoSettings.Value value) {
        return fields.get(value);
    }
}
