package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.ui.brand.ScenePanel;

import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;

/** „Über Space Harvester“: die Weltraumszene mit Name, Leitsatz und Version. */
final class AboutDialog {

    static final String TAGLINE = "Entdecke. Sammle. Bewahre.";

    private AboutDialog() {
    }

    static void open(Component parent, AppContext context) {
        JDialog dialog = Dialogs.dialog(parent, "Über Space Harvester");
        ComposerButton close = Comic.primary("Schließen", null);
        close.addActionListener(e -> dialog.dispose());
        Dialogs.show(dialog, "Über Space Harvester", body(context), close);
    }

    static JComponent body(AppContext context) {
        Facts facts = new Facts()
                .add("Version", context.version())
                .add("Java", System.getProperty("java.version"))
                .add("Hersteller", "© 2026 AresStack");
        JPanel stack = new JPanel(new BorderLayout(0, 12));
        stack.setOpaque(false);
        stack.add(heading(TAGLINE), BorderLayout.NORTH);
        // Feste Breite als HTML: Ein umbrechender Textbereich hätte vor dem ersten Layout nur eine Zeile Höhe.
        JLabel about = Comic.text("<html><div style='width:260px'>Space Harvester erschließt Wissensquellen wie "
                + "Confluence und Dateiablagen, hält sie als lokalen Bestand vor und macht sie durchsuchbar und "
                + "exportierbar.</div></html>");
        stack.add(about, BorderLayout.CENTER);
        stack.add(facts, BorderLayout.SOUTH);
        JPanel text = new JPanel(new BorderLayout());
        text.setOpaque(false);
        text.add(stack, BorderLayout.NORTH);
        JPanel body = new JPanel(new BorderLayout(18, 0));
        body.setOpaque(false);
        body.add(new ScenePanel(300, 300, Comic.INK), BorderLayout.WEST);
        body.add(text, BorderLayout.CENTER);
        return body;
    }

    static JLabel heading(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Comic.semiBold(15f));
        label.setForeground(Comic.MUTED);
        return label;
    }
}
