package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;

/**
 * Abschnitt „KeePass“ unter Einstellungen › Sicherheit: die eine Kopplung der App mit KeePass (KeePassRPC), die für
 * den KI-Dienst und alle Wissensquellen gilt. Sie ist keine KI-Einstellung, sondern regelt, woher die App Zugangsdaten
 * bezieht; darum steht sie hier und nicht unter „KI-Dienst“. Eine Zeile „Kopplung“ mit dem Stand und den Knöpfen IM
 * Feld: „Koppeln“ (nicht gekoppelt) bzw. „Neu koppeln“ und „Entkoppeln“ (gekoppelt). Entkoppeln löscht nur die
 * lokale Datei {@code pairing.key}; der Eintrag in KeePass bleibt. Der Kopplungscode erscheint nie hier, nur in KeePass.
 */
final class KeePassPairingPanel {

    static final String HEADING = "KeePass";
    static final String PAIR = "Koppeln";
    static final String REPAIR = "Neu koppeln";
    static final String UNPAIR = "Entkoppeln";
    static final String NOT_PAIRED = "Nicht gekoppelt";
    static final String PAIRED = "Gekoppelt";

    private final AppContext context;
    private final JPanel root = new JPanel(new BorderLayout(0, 8));
    /** Nur lesbar; die Knöpfe stehen rechts im Feld. */
    final JTextField status = Comic.field(24);
    final ComposerButton pair = Comic.button(PAIR, "Mit KeePass koppeln (KeePassRPC); KeePass zeigt dabei ein "
            + "Einmal-Passwort, das du dort abliest");
    final ComposerButton unpair = Comic.button(UNPAIR, "Kopplung aufheben: löscht die lokale Datei pairing.key; "
            + "der Eintrag in KeePass bleibt");
    final SettingDialogs.Rows rows = new SettingDialogs.Rows();
    final Feedback feedback = new Feedback();
    /** Wird nach jeder Änderung des Kopplungsstands gerufen (Koppeln, Entkoppeln), etwa für den Reiter „KI-Dienst“. */
    private Runnable changed = () -> { };

    KeePassPairingPanel(AppContext context) {
        this.context = context;
        root.setOpaque(false);
        status.setEditable(false);
        status.setFocusable(false);
        JPanel field = Comic.fieldWithButton(status, pair);
        if (field instanceof com.aresstack.spaceharvester.app.ui.controls.InputFrame frame) {
            com.aresstack.spaceharvester.app.ui.controls.InputFrame.styleFlat(unpair);
            frame.addButton(unpair);
        }
        rows.add("Kopplung", field, "Eine Kopplung für den KI-Dienst und alle Wissensquellen; KeePass muss laufen, "
                + "mit dem Plugin KeePassRPC");
        root.add(Comic.wrapped("Zugangsdaten, die „über KeePass“ bezogen werden, liest die App aus KeePass über diese "
                + "eine Kopplung. Der Kopplungsschlüssel liegt im Ordner „keepass“ neben der Konfigurationsdatei, nur "
                + "für dieses Konto lesbar; Passwörter und Tokens bleiben in KeePass.", Comic.TEXT, 12.5f), BorderLayout.NORTH);
        root.add(rows, BorderLayout.CENTER);
        root.add(feedback, BorderLayout.SOUTH);
        pair.addActionListener(e -> pair());
        unpair.addActionListener(e -> unpair());
        render();
    }

    JComponent component() {
        return root;
    }

    void onChange(Runnable changed) {
        this.changed = changed;
    }

    /** Zeigt den gespeicherten Stand; ob KeePass gerade läuft, zeigt erst „Koppeln“ bzw. „Neu koppeln“. */
    void render() {
        boolean paired = context.keePass().paired();
        status.setText(paired ? PAIRED : NOT_PAIRED);
        pair.setText(paired ? REPAIR : PAIR);
        unpair.setVisible(paired);
        root.revalidate();
        root.repaint();
    }

    /** Koppelt (KeePass zeigt ein Einmal-Passwort) und zählt zur Probe die lesbaren Einträge. */
    void pair() {
        pair.setEnabled(false);
        unpair.setEnabled(false);
        status.setText("Verbinde mit KeePass …");
        feedback.clear();
        Background.run(() -> context.keePass().titles(), titles -> {
            pair.setEnabled(true);
            unpair.setEnabled(true);
            render();
            status.setText(PAIRED + " · " + Texts.count(titles.size(), "Eintrag", "Einträge") + " lesbar");
            feedback.success("Mit KeePass gekoppelt");
            changed.run();
        }, error -> {
            pair.setEnabled(true);
            unpair.setEnabled(true);
            render();
            feedback.warning("KeePass nicht erreichbar", AiSettingsPanel.keePassReason(error));
            changed.run();
        });
    }

    /** Hebt die Kopplung nach Rückfrage auf; danach fragt der nächste Zugriff auf KeePass neu nach dem Code. */
    void unpair() {
        if (!Dialogs.confirm(root, "KeePass entkoppeln?", "Die lokale Datei pairing.key wird gelöscht. Der KI-Dienst "
                + "und Quellen, die ihre Zugangsdaten über KeePass beziehen, brauchen danach eine neue Kopplung; der "
                + "Eintrag in KeePass bleibt.", UNPAIR)) {
            return;
        }
        unpairWithoutAsking();
    }

    /** Entkoppeln ohne Rückfrage; für Tests und nach bestätigter Rückfrage. */
    void unpairWithoutAsking() {
        context.keePass().unpair();
        render();
        feedback.success("KeePass entkoppelt");
        changed.run();
    }
}
