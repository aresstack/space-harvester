package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.application.sources.ConnectionStep;
import com.aresstack.spaceharvester.application.sources.SourceDiagnosis;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;

/**
 * Die Verbindungsdiagnose des Core als Stufenliste (#189): je Stufe Zustand, Meldung und bei Warnung oder Fehler der
 * Hinweis, was zu tun ist. Die Texte kommen vom Core und enthalten keine Geheimnisse; die App wertet nichts selbst aus.
 */
final class DiagnosisPanel extends JPanel {

    DiagnosisPanel(SourceDiagnosis diagnosis) {
        super(new BorderLayout(0, 10));
        setOpaque(false);
        ConnectionStep failure = diagnosis.failure().orElse(null);
        JLabel summary = Comic.text(failure == null ? "Die Verbindung steht."
                : "Gescheitert bei „" + failure.stage().label() + "“.");
        summary.setFont(Comic.semiBold(13.5f));
        summary.setForeground(failure == null ? Comic.GREEN : Comic.RED);
        add(summary, BorderLayout.NORTH);
        JPanel steps = new JPanel();
        steps.setOpaque(false);
        steps.setLayout(new BoxLayout(steps, BoxLayout.Y_AXIS));
        for (ConnectionStep step : diagnosis.steps()) {
            steps.add(row(step));
        }
        add(steps, BorderLayout.CENTER);
    }

    private static JPanel row(ConnectionStep step) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        JLabel status = new JLabel(status(step.status()));
        status.setFont(Comic.semiBold(12.5f));
        status.setForeground(color(step.status()));
        status.setPreferredSize(new java.awt.Dimension(96, status.getPreferredSize().height));
        status.setVerticalAlignment(JLabel.TOP);
        row.add(status, BorderLayout.WEST);
        StringBuilder html = new StringBuilder("<html><div style='width:380px'><b>")
                .append(escape(step.stage().label())).append("</b>");
        if (step.message() != null && !step.message().isBlank()) {
            html.append("<br>").append(escape(step.message()));
        }
        if (step.hint() != null && !step.hint().isBlank()) {
            html.append("<br><i>").append(escape(step.hint())).append("</i>");
        }
        JLabel text = new JLabel(html.append("</div></html>").toString());
        text.setFont(Comic.regular(12.5f));
        text.setForeground(step.status() == ConnectionStep.Status.SKIPPED ? Comic.MUTED : Comic.TEXT);
        row.add(text, BorderLayout.CENTER);
        return row;
    }

    static String status(ConnectionStep.Status status) {
        return switch (status) {
            case OK -> "✓ in Ordnung";
            case INFO -> "ℹ Hinweis";
            case WARNING -> "⚠ Warnung";
            case FAILED -> "✗ Fehler";
            case SKIPPED -> "– übersprungen";
        };
    }

    private static Color color(ConnectionStep.Status status) {
        return switch (status) {
            case OK -> Comic.GREEN;
            case INFO -> Comic.BLUE;
            case WARNING -> Comic.ORANGE;
            case FAILED -> Comic.RED;
            case SKIPPED -> Comic.MUTED;
        };
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
