package com.aresstack.spaceharvester.app.ui;

import com.aresstack.knowledge4j.scope.Fence;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import java.awt.Component;
import java.awt.Dimension;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Die Eingabe zu „Korb füllen“ (Buchfunktion Phase 3, Arbeitszweig): sichtbar nur der Auftrag, die Entscheidung des
 * Benutzers; Rand des Themas, was ausdrücklich dazugehört und was ausgeschlossen ist, stehen unter „Erweitert“. Daraus
 * wird der Weidezaun der Recherche. Die Eingabe ist ein Overlay wie die übrigen Formulare ({@link Dialogs#formOverlay}).
 */
final class BasketResearchForm {

    static final String TITLE = "Korb füllen";
    static final String ACTION = "Recherche starten";

    final JTextArea mission = area(4);
    final JTextArea edge = area(2);
    final JTextArea included = area(3);
    final JTextArea excluded = area(3);
    private final JButton advanced = Comic.disclosure("Erweitert",
            "Rand des Themas, was ausdrücklich dazugehört und was ausgeschlossen ist");
    private final JPanel more = new JPanel();

    /** Fragt nach Auftrag und Weidezaun; leer, wenn der Benutzer abbricht oder keinen Auftrag nennt. */
    Optional<Fence> ask(Component parent, String folderName) {
        boolean start = Dialogs.formOverlay(parent, TITLE + " – " + folderName, body(folderName), ACTION);
        return start ? fence() : Optional.empty();
    }

    JComponent body(String folderName) {
        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.add(left(Comic.note("Die Recherche sucht im Wissensbestand Seiten zum Auftrag, prüft jede am "
                + "Weidezaun und legt passende als Favoriten in den Ordner „" + folderName + "“. Seiten, die schon im "
                + "Ordner liegen, liest sie nicht noch einmal. Fortschritt unter Aufträge.")));
        body.add(left(new JLabel("Auftrag")));
        body.add(left(Comic.scroll(mission)));
        body.add(left(advanced));
        more.setOpaque(false);
        more.setLayout(new BoxLayout(more, BoxLayout.Y_AXIS));
        more.add(left(new JLabel("Rand des Themas")));
        more.add(left(Comic.scroll(edge)));
        more.add(left(new JLabel("Dazu gehört ausdrücklich (eine Angabe je Zeile)")));
        more.add(left(Comic.scroll(included)));
        more.add(left(new JLabel("Ausgeschlossen (eine Angabe je Zeile)")));
        more.add(left(Comic.scroll(excluded)));
        more.setVisible(false);
        advanced.addActionListener(e -> {
            boolean open = !Comic.disclosed(advanced);
            Comic.disclose(advanced, open);
            more.setVisible(open);
            body.revalidate();
        });
        body.add(more);
        return body;
    }

    /** Der Weidezaun aus den Feldern; leer ohne Auftrag. */
    Optional<Fence> fence() {
        String text = mission.getText().strip();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Fence(text, edge.getText().strip(), lines(included), lines(excluded), "", ""));
    }

    private static List<String> lines(JTextArea area) {
        return Arrays.stream(area.getText().split("\\R")).map(String::strip).filter(line -> !line.isEmpty())
                .toList();
    }

    private static JTextArea area(int rows) {
        JTextArea area = new JTextArea(rows, 40);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(Comic.regular(12.5f));
        return area;
    }

    private static <T extends JComponent> T left(T component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (component instanceof javax.swing.JScrollPane) {
            component.setMaximumSize(new Dimension(Integer.MAX_VALUE, component.getPreferredSize().height));
        }
        return component;
    }
}
