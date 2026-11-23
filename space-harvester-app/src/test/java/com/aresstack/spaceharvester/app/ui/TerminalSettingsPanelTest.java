package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.deck.terminal.MouseFkeyBinding;
import com.aresstack.spaceharvester.deck.terminal.MouseFkeyBinding.Modifier;
import com.aresstack.spaceharvester.deck.terminal.MouseFkeyBinding.MouseAction;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Die Tabelle der Maus-Aktionen in Einstellungen › Terminal (aus MainframeMate). */
class TerminalSettingsPanelTest {

    @Test
    void tableEditsChangeTheBindings() {
        TerminalSettingsPanel.BindingModel model = new TerminalSettingsPanel.BindingModel();
        model.reset(MouseFkeyBinding.defaults());
        assertThat(model.getRowCount()).isEqualTo(5);
        assertThat(model.getValueAt(1, 3)).isEqualTo("F8");

        model.setValueAt(MouseAction.FORWARD, 0, 1);
        model.setValueAt(Modifier.CTRL, 0, 2);
        model.setValueAt("F12", 0, 3);
        model.setValueAt(false, 1, 0);
        model.remove(4);
        model.add(new MouseFkeyBinding(MouseAction.BACK, Modifier.ALT, 1));

        List<MouseFkeyBinding> rows = model.rows();
        assertThat(rows.get(0)).isEqualTo(new MouseFkeyBinding(MouseAction.FORWARD, Modifier.CTRL, 12));
        assertThat(rows.get(1).enabled()).isFalse();
        assertThat(rows).hasSize(5).last().isEqualTo(new MouseFkeyBinding(MouseAction.BACK, Modifier.ALT, 1));
    }
}
