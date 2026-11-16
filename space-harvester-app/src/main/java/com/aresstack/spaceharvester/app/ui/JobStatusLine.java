package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.event.ApplicationEventBus;
import com.aresstack.spaceharvester.app.event.JobProgressEvent;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;

/**
 * Mitte der Statuszeile: zeigt nur, was als {@link JobProgressEvent} auf dem Ereignisbus erscheint, den Gesamtstand
 * aller Aufträge (Angelo 11.11.2026) – links in Worten, mittig „Angehalten“ oder wie der Durchgang endete. Anhalten
 * und Abbrechen stehen nicht mehr hier, sondern für alle Aufträge zugleich in der Kopfzeile der Aufträge. Das Modell
 * {@link #model()} treibt die Füllung der {@link StatusLine}; angehalten bleibt sie stehen, wo sie war.
 */
final class JobStatusLine extends JPanel {

    private final JProgressBar model = new JProgressBar();
    final JLabel text = Comic.muted(" ");
    final JLabel endState = new JLabel(" ");
    private JobProgressEvent last = JobProgressEvent.none();
    private final JPanel center = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
    private final javax.swing.Timer done = new javax.swing.Timer(StatusLine.FADE_DELAY_MS,
            e -> endState.setVisible(false));
    private JComponent navigation;

    JobStatusLine(ApplicationEventBus events) {
        super(new BorderLayout());
        setOpaque(false);
        endState.setFont(Comic.semiBold(13f));
        endState.setForeground(Comic.INK);
        center.setOpaque(false);
        center.add(endState);
        // „Fertig“ ist nach kurzer Zeit keine Nachricht mehr und verschwindet mit dem vollen Balken (Angelo,
        // 16.11.2026); Angehalten, Abgebrochen und Fehlgeschlagen bleiben stehen, bis sich etwas ändert.
        done.setRepeats(false);
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

    /**
     * Setzt Zurück und Vor der Seitenansicht in die Mitte der Statuszeile, vor den Endzustand; neben der Suchleiste
     * ist bei schmalem Fenster kein Platz (Angelo, 16.11.2026).
     */
    void navigation(JComponent buttons) {
        if (navigation != null) {
            center.remove(navigation);
        }
        navigation = buttons;
        center.add(buttons, 0);
        center.revalidate();
    }

    /**
     * Die Mitte (Zurück, Vor, Endzustand) behält ihren Platz; wird es eng, kürzt sich der Auftragstext mit „…“
     * (der volle Text steht im Tooltip), statt die Knöpfe aus der Zeile zu drängen.
     */
    @Override
    public void doLayout() {
        super.doLayout();
        int width = getWidth();
        int textWidth = Math.max(0, width - center.getPreferredSize().width);
        if (text.getWidth() > textWidth) {
            text.setBounds(text.getX(), text.getY(), textWidth, text.getHeight());
            center.setBounds(text.getX() + textWidth, center.getY(), width - textWidth, center.getHeight());
        }
    }

    /** Zeigt Zurück und Vor nur, solange die Mitte des Fensters die Seitenansicht zeigt. */
    void navigationVisible(boolean visible) {
        if (navigation != null) {
            navigation.setVisible(visible);
        }
    }

    /** Für Tests: blendet „Fertig“ nach {@link StatusLine#FADE_DELAY_MS} aus. */
    javax.swing.Timer doneTimer() {
        return done;
    }

    JProgressBar model() {
        return model;
    }

    /** Das zuletzt gezeigte Ereignis. */
    JobProgressEvent last() {
        return last;
    }

    private void render(JobProgressEvent event) {
        boolean wasCompleted = last.outcome() == JobProgressEvent.Outcome.COMPLETED;
        last = event;
        JobProgressEvent.Outcome outcome = event.outcome();
        boolean running = outcome == JobProgressEvent.Outcome.RUNNING;
        boolean paused = outcome == JobProgressEvent.Outcome.PAUSED;
        text.setText(event.text() == null || event.text().isEmpty() ? " " : event.text());
        text.setToolTipText(event.text());
        if ((running || paused) && event.total() > 0 && !event.indeterminate()) {
            model.setIndeterminate(false);
            model.setMaximum((int) Math.min(Integer.MAX_VALUE, event.total()));
            model.setValue((int) Math.min(Integer.MAX_VALUE, event.done()));
        } else {
            model.setIndeterminate(running && event.indeterminate());
            model.setMaximum(1);
            // Fertig heißt 100 %: die Statuszeile zeigt den vollen Balken kurz und blendet ihn dann aus.
            model.setValue(outcome == JobProgressEvent.Outcome.COMPLETED ? 1 : 0);
        }
        if (outcome == JobProgressEvent.Outcome.COMPLETED) {
            // Nur der Wechsel auf „Fertig“ zeigt es; eine Wiederholung desselben Stands holt es nicht zurück.
            if (!wasCompleted) {
                endState.setVisible(true);
                done.restart();
            }
        } else {
            done.stop();
            endState.setVisible(outcome.terminal());
        }
        endState.setText(switch (outcome) {
            case COMPLETED -> "Fertig";
            case CANCELLED -> "Abgebrochen";
            case FAILED -> "Fehlgeschlagen";
            case PAUSED -> "Angehalten";
            default -> " ";
        });
        endState.setForeground(outcome == JobProgressEvent.Outcome.COMPLETED ? Comic.GREEN
                : outcome == JobProgressEvent.Outcome.FAILED ? Comic.RED
                : outcome == JobProgressEvent.Outcome.PAUSED ? Comic.INK : Comic.ORANGE);
    }
}
