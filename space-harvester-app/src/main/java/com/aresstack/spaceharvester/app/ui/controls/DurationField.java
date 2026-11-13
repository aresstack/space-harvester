package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;

import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Eine Dauer als Zahl (Spinner) mit Einheit (Auswahlliste: Sekunden, Minuten, Stunden, Tage) statt eines Textfelds,
 * in das man „1d“ oder „30m“ auswendig tippen müsste. Optional steht vor den Einheiten ein Sonderwert wie „Wie global“
 * oder „Aus“; ist er gewählt, ist die Zahl ausgegraut und {@link #value()} liefert {@code null}.
 * <p>
 * Eine gesetzte Dauer erscheint in der größten Einheit, die sie ganzzahlig teilt (6h statt 360m); Bruchteile einer
 * Sekunde werden auf ganze Sekunden gerundet. Gleiches Vorbild wie das Dauer-Feld in askai, im Stil dieser App.
 */
public class DurationField extends JPanel {

    /** Einheiten in Reihenfolge der Auswahlliste. */
    private static final String[] UNITS = {"Sekunden", "Minuten", "Stunden", "Tage"};
    private static final long[] SECONDS = {1L, 60L, 3_600L, 86_400L};

    private final JSpinner amount;
    private final JComboBox<String> unit;
    private final String none;
    private final List<Runnable> listeners = new ArrayList<>();

    /** Ein Feld von 0 bis {@code maximum} der gewählten Einheit, ohne Sonderwert. */
    public DurationField(int maximum) {
        this(null, maximum);
    }

    /**
     * @param none    Sonderwert vor den Einheiten, etwa „Wie global“ oder „Aus“; {@code null} für keinen
     * @param maximum höchste Zahl des Spinners je Einheit
     */
    public DurationField(String none, int maximum) {
        super(new GridBagLayout());
        setOpaque(false);
        this.none = none;
        amount = new JSpinner(new SpinnerNumberModel(0, 0, maximum, 1));
        amount.setFont(Comic.regular(13f));
        if (amount.getEditor() instanceof JSpinner.DefaultEditor editor) {
            Comic.style(editor.getTextField());
            editor.getTextField().setColumns(5);
            editor.getTextField().setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 6, 2, 4));
        }
        amount.setBorder(Comic.fieldBorder());
        unit = Comic.combo();
        if (none != null) {
            unit.addItem(none);
        }
        for (String name : UNITS) {
            unit.addItem(name);
        }
        unit.setSelectedIndex(none != null ? 0 : 1);
        GridBagConstraints left = new GridBagConstraints();
        left.gridx = 0;
        left.gridy = 0;
        left.anchor = GridBagConstraints.WEST;
        left.fill = GridBagConstraints.VERTICAL;
        left.insets = new Insets(0, 0, 0, 6);
        add(amount, left);
        GridBagConstraints right = new GridBagConstraints();
        right.gridx = 1;
        right.gridy = 0;
        right.weightx = 1;
        right.fill = GridBagConstraints.HORIZONTAL;
        add(unit, right);
        unit.addActionListener(e -> {
            amount.setEnabled(isEnabled() && !isNone());
            changed();
        });
        amount.addChangeListener(e -> changed());
        amount.setEnabled(!isNone());
    }

    private void changed() {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }

    /** Ob der Sonderwert („Wie global“, „Aus“) gewählt ist. */
    public boolean isNone() {
        return none != null && unit.getSelectedIndex() == 0;
    }

    /** Die Dauer; {@code null} beim Sonderwert. */
    public Duration value() {
        if (isNone()) {
            return null;
        }
        return Duration.ofSeconds(((Number) amount.getValue()).longValue() * SECONDS[unitIndex()]);
    }

    /** Zeigt die Dauer in der größten Einheit, die sie ganzzahlig teilt; {@code null} wählt den Sonderwert. */
    public void value(Duration duration) {
        if (duration == null) {
            if (none != null) {
                unit.setSelectedIndex(0);
            }
            return;
        }
        long seconds = Math.max(0, (duration.toMillis() + 500L) / 1_000L);
        int index = 0;
        for (int i = SECONDS.length - 1; i > 0; i--) {
            if (seconds > 0 && seconds % SECONDS[i] == 0) {
                index = i;
                break;
            }
        }
        long count = seconds / SECONDS[index];
        // Eine gesetzte Dauer bleibt erhalten: Braucht sie mehr als das Maximum, wächst das Maximum mit, statt dass
        // der Wert still gekappt und beim Speichern verkürzt würde.
        SpinnerNumberModel model = (SpinnerNumberModel) amount.getModel();
        int value = (int) Math.min(Integer.MAX_VALUE, count);
        if (((Number) model.getMaximum()).intValue() < value) {
            model.setMaximum(value);
        }
        amount.setValue(value);
        unit.setSelectedIndex(index + (none != null ? 1 : 0));
    }

    private int unitIndex() {
        return unit.getSelectedIndex() - (none != null ? 1 : 0);
    }

    /** Wird bei jeder Änderung von Zahl oder Einheit aufgerufen. */
    public void onChange(Runnable listener) {
        listeners.add(listener);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        amount.setEnabled(enabled && !isNone());
        unit.setEnabled(enabled);
    }

    /** Der Spinner, für Tests. */
    public JSpinner spinner() {
        return amount;
    }

    /** Die Einheiten-Auswahl, für Tests. */
    public JComboBox<String> units() {
        return unit;
    }

    /** Die Einheit zu {@code index} ab 0, etwa für Tests: Sekunden, Minuten, Stunden, Tage. */
    public static String unitName(int index) {
        return UNITS[index];
    }
}
