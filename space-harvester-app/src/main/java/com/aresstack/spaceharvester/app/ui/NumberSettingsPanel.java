package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.settings.NumberSetting;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Ein Bereich der Einstellungen für eine Liste einstellbarer Zahlen ({@link NumberSetting}): je Wert ein Feld mit
 * Hinweis, Vorgabe und Grenzen, in der Reihenfolge der Aufzählung. Eine Änderung (Enter oder Verlassen des Feldes)
 * wird geprüft und sofort gespeichert, ohne Neustart. „Vorgaben“ setzt alle Werte zurück. Nur auf dem EDT.
 *
 * @param <V> die Aufzählung der Werte
 */
public final class NumberSettingsPanel<V extends Enum<V> & NumberSetting> {

    static final String RESET = "Vorgaben";

    /** Speichert die Werte; {@code false}, wenn gerade nichts gespeichert werden kann. */
    @FunctionalInterface
    public interface Store<V> {
        boolean save(Map<V, Long> values, Runnable saved, Consumer<Throwable> failed);

        /** Speichert sofort im EDT (etwa in {@code app-state.json}); ein Fehler wird gemeldet. */
        static <V> Store<V> immediately(Consumer<Map<V, Long>> save) {
            Objects.requireNonNull(save, "save");
            return (values, saved, failed) -> {
                try {
                    save.accept(values);
                    saved.run();
                } catch (RuntimeException e) {
                    failed.accept(e);
                }
                return true;
            };
        }
    }

    private final String heading;
    private final Class<V> type;
    private final Supplier<Map<V, Long>> stored;
    private final Store<V> store;
    private final SettingDialogs.Rows rows = new SettingDialogs.Rows();
    private final Map<V, JTextField> fields;
    private final Feedback feedback = new Feedback();
    private final JPanel component = new JPanel(new BorderLayout(0, 8));
    /** Was die Felder zeigen; dieselben Werte noch einmal schreiben nichts. */
    private Map<V, Long> shown;
    /** Während {@link #render} die Felder füllt, ist das keine Eingabe des Benutzers. */
    private boolean showing;

    /**
     * @param heading   Überschrift des Bereichs, auch in den Rückmeldungen
     * @param resetTip  Tooltip des Knopfs „Vorgaben“
     * @param type      die Aufzählung der Werte
     * @param stored    die gespeicherten Werte (alle, mit Vorgaben aufgefüllt)
     * @param store     speichert geänderte Werte
     */
    public NumberSettingsPanel(String heading, String resetTip, Class<V> type, Supplier<Map<V, Long>> stored,
                               Store<V> store) {
        this.heading = Objects.requireNonNull(heading, "heading");
        this.type = Objects.requireNonNull(type, "type");
        this.stored = Objects.requireNonNull(stored, "stored");
        this.store = Objects.requireNonNull(store, "store");
        this.fields = new EnumMap<>(type);
        for (V value : type.getEnumConstants()) {
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
        ComposerButton reset = Comic.button(RESET, resetTip);
        reset.addActionListener(e -> {
            render(defaults());
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

    public JComponent component() {
        return component;
    }

    /** Zeigt die gespeicherten Werte. */
    public void render() {
        shown = Map.copyOf(stored.get());
        render(shown);
    }

    private void render(Map<V, Long> values) {
        showing = true;
        try {
            fields.forEach((value, field) -> field.setText(String.valueOf(
                    values.getOrDefault(value, value.defaultValue()))));
        } finally {
            showing = false;
        }
    }

    private Map<V, Long> defaults() {
        Map<V, Long> values = new EnumMap<>(type);
        for (V value : type.getEnumConstants()) {
            values.put(value, value.defaultValue());
        }
        return values;
    }

    /** Prüft die Felder und speichert sie, wenn sich etwas geändert hat; ein ungültiger Wert wird gemeldet. */
    private void save() {
        if (showing) {
            return;
        }
        Map<V, Long> values = new EnumMap<>(type);
        for (Map.Entry<V, JTextField> entry : fields.entrySet()) {
            V value = entry.getKey();
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
        if (values.equals(shown)) {
            return;
        }
        boolean atDefaults = values.equals(defaults());
        if (store.save(Map.copyOf(values), () -> feedback.success(heading + (atDefaults ? ": Vorgaben"
                : " gespeichert")), feedback::problem)) {
            shown = Map.copyOf(values);
        }
    }

    /** Das Feld eines Werts (für Tests). */
    public JTextField field(V value) {
        return fields.get(value);
    }
}
