package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.event.ApplicationEventBus;
import com.aresstack.spaceharvester.app.event.JobProgressEvent;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;

/**
 * Mitte der Statuszeile: zeigt nur, was als {@link JobProgressEvent} auf dem Ereignisbus erscheint – links Schritt bzw.
 * Auftrag, mittig „Abbrechen“ (für den laufenden Auftrag) oder der Endzustand. Das Modell {@link #model()} treibt die
 * Füllung der {@link StatusLine}.
 */
final class JobStatusLine extends JPanel {

    private final JProgressBar model = new JProgressBar();
    final JLabel text = Comic.muted(" ");
    final JLabel endState = new JLabel(" ");
    final ComposerButton cancel = Comic.danger("Abbrechen", "Auftrag abbrechen");
    private JobProgressEvent last = JobProgressEvent.none();

    JobStatusLine(ApplicationEventBus events, Runnable onCancel) {
        super(new BorderLayout());
        setOpaque(false);
        endState.setFont(Comic.semiBold(13f));
        endState.setForeground(Comic.INK);
        cancel.addActionListener(e -> onCancel.run());
        JPanel center = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        center.setOpaque(false);
        center.add(cancel);
        center.add(endState);
        setBorder(BorderFactory.createEmptyBorder());
        add(text, BorderLayout.WEST);
        add(center, BorderLayout.CENTER);
        render(last);
        events.subscribe(JobProgressEvent.class, event -> {
            if (SwingUtilities.isEventDispatchThread()) {
                render(event);
            } else {
                SwingUtilities.invokeLater(() -> render(event));
            }
        });
    }

    JProgressBar model() {
        return model;
    }

    /** Das zuletzt gezeigte Ereignis. */
    JobProgressEvent last() {
        return last;
    }

    private void render(JobProgressEvent event) {
        last = event;
        JobProgressEvent.Outcome outcome = event.outcome();
        boolean running = outcome == JobProgressEvent.Outcome.RUNNING;
        text.setText(event.text() == null || event.text().isEmpty() ? " " : event.text());
        text.setToolTipText(event.text());
        if (running && event.total() > 0) {
            model.setIndeterminate(false);
            model.setMaximum((int) Math.min(Integer.MAX_VALUE, event.total()));
            model.setValue((int) Math.min(Integer.MAX_VALUE, event.done()));
        } else {
            model.setIndeterminate(running && event.indeterminate());
            model.setMaximum(1);
            // Fertig heißt 100 %: die Statuszeile zeigt den vollen Balken kurz und blendet ihn dann aus.
            model.setValue(outcome == JobProgressEvent.Outcome.COMPLETED ? 1 : 0);
        }
        cancel.setVisible(running);
        cancel.setEnabled(running && !event.cancelRequested());
        cancel.setText(event.cancelRequested() ? "Abbruch angefordert" : "Abbrechen");
        endState.setVisible(outcome.terminal());
        endState.setText(switch (outcome) {
            case COMPLETED -> "Fertig";
            case CANCELLED -> "Abgebrochen";
            case FAILED -> "Fehlgeschlagen";
            default -> " ";
        });
        endState.setForeground(outcome == JobProgressEvent.Outcome.COMPLETED ? Comic.GREEN
                : outcome == JobProgressEvent.Outcome.FAILED ? Comic.RED : Comic.ORANGE);
    }
}
