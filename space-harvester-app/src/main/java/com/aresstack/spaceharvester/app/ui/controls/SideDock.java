package com.aresstack.spaceharvester.app.ui.controls;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.dnd.DragSource;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import javax.swing.AbstractButton;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * Die Werkzeuge einer Seitenleiste als Knopfleiste und einzelne Kugeln (Angelo 28.11.2026, nach dem Vorbild der
 * Werkzeugfenster in IntelliJ): Jedes Symbol der Knopfleiste lässt sich mit der Maus herausziehen und steht dann als
 * eigene Kugel darunter; zurück auf die Leiste geschoben, gehört es wieder zu ihr. Kugeln lassen sich untereinander
 * umsortieren. Die Symbole einer Knopfleiste schalten nur um: Von ihnen ist höchstens eines offen. Jede Kugel öffnet
 * dagegen ihren eigenen Bereich, unabhängig von der Leiste und den anderen Kugeln; die offenen Bereiche teilen sich
 * die Höhe der Seitenleiste ({@link AreaStack}). Kugeln sind wie die Leiste leicht durchscheinend und nur unter der
 * Maus voll deckend.
 *
 * <p>Diese Klasse kennt nur Namen, Symbole und Plätze. Was ein Werkzeug zeigt, entscheidet der Aufrufer und hört
 * dafür auf {@link #onChange}.
 */
public final class SideDock {

    /** Abstand zwischen Knopfleiste und Kugeln und zwischen zwei Kugeln. */
    public static final int GAP = 4;

    private final SegmentedToggle rail;
    private final Column column = new Column();
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final List<String> balls = new ArrayList<>();
    private final Map<String, SegmentedToggle> views = new HashMap<>();
    private final Set<String> open = new LinkedHashSet<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private BiConsumer<String, Boolean> attached = (name, wasOpen) -> { };
    private EdgeOverlay overlay;
    private BooleanSupplier sideways = () -> false;
    private BooleanSupplier railBusy = () -> false;
    private boolean carrying;

    /** Ein Werkzeug: sein Name, sein Symbol und sein Tooltip, gleich wo es gerade steht. */
    private static final class Tool {
        private Icon icon;
        private String tip;

        private Tool(Icon icon, String tip) {
            this.icon = icon;
            this.tip = tip;
        }
    }

    /** Die Werkzeuge stehen zunächst alle in {@code rail}. */
    public SideDock(SegmentedToggle rail) {
        this.rail = rail;
        column.rebuild();
    }

    /** Meldet ein Werkzeug an; es steht am Ende der Knopfleiste. */
    public SideDock tool(String name, Icon icon, String tip) {
        tools.put(name, new Tool(icon, tip));
        rail.segment(name, icon);
        dress(rail, name);
        return this;
    }

    private void dress(SegmentedToggle holder, String name) {
        AbstractButton button = holder.button(name);
        button.setToolTipText(tools.get(name).tip);
        Carry carry = new Carry(name);
        button.addMouseListener(carry);
        button.addMouseMotionListener(carry);
    }

    /** Die Knopfleiste mit den Kugeln darunter, als ein Teil für den Platz am Trenner oder am Fensterrand. */
    public JComponent column() {
        return column;
    }

    /** Die Fläche, über der ein Symbol beim Ziehen getragen wird. */
    public void carrier(EdgeOverlay overlay) {
        this.overlay = overlay;
    }

    /**
     * Wem eine Bewegung zur Seite gehört: Solange {@code sideways} gilt (die Leiste sitzt auf einem Trenner und zieht
     * ihn), löst nur eine Bewegung nach oben oder unten ein Symbol aus der Leiste. Solange {@code railBusy} gilt (der
     * Trenner wird gerade gezogen), wird nichts herausgelöst.
     */
    public void sideways(BooleanSupplier sideways, BooleanSupplier railBusy) {
        this.sideways = sideways;
        this.railBusy = railBusy;
    }

    /** Ob gerade ein Symbol getragen wird. */
    public boolean carrying() {
        return carrying;
    }

    /** Jede Änderung: ein Werkzeug wurde Kugel oder kam zurück, wurde umsortiert, eine Kugel ging auf oder zu. */
    public void onChange(Runnable listener) {
        listeners.add(listener);
    }

    /** Eine Kugel kam zurück in die Leiste; {@code wasOpen} sagt, ob ihr Bereich dabei offen war. */
    public void onAttach(BiConsumer<String, Boolean> listener) {
        this.attached = listener;
    }

    private void changed() {
        column.rebuild();
        for (Runnable listener : List.copyOf(listeners)) {
            listener.run();
        }
    }

    /** Ob das Werkzeug als einzelne Kugel steht. */
    public boolean ball(String name) {
        return balls.contains(name);
    }

    /** Die Kugeln von oben nach unten. */
    public List<String> balls() {
        return List.copyOf(balls);
    }

    /** Die Kugeln, deren Bereich offen ist, von oben nach unten. */
    public List<String> openBalls() {
        List<String> result = new ArrayList<>(balls);
        result.retainAll(open);
        return result;
    }

    /** Ob der Bereich dieser Kugel offen ist. */
    public boolean open(String name) {
        return open.contains(name);
    }

    /** Öffnet oder schließt den Bereich einer Kugel; für ein Werkzeug in der Leiste geschieht nichts. */
    public void open(String name, boolean shown) {
        if (!ball(name) || open.contains(name) == shown) {
            return;
        }
        if (shown) {
            open.add(name);
            views.get(name).show(name);
        } else {
            open.remove(name);
            views.get(name).clear();
        }
        changed();
    }

    /** Schließt die Bereiche aller Kugeln. */
    public void closeBalls() {
        if (open.isEmpty()) {
            return;
        }
        for (String name : List.copyOf(open)) {
            views.get(name).clear();
        }
        open.clear();
        changed();
    }

    /**
     * Löst ein Werkzeug aus der Knopfleiste und stellt es als Kugel an Platz {@code index}. War es das gewählte der
     * Leiste, bleibt sein Bereich offen, nun als der der Kugel; die Leiste zeigt dann keine Wahl mehr.
     */
    public void detach(String name, int index) {
        if (!tools.containsKey(name) || ball(name)) {
            return;
        }
        boolean wasOpen = name.equals(rail.selected());
        rail.remove(name);
        place(name, index, wasOpen);
        changed();
    }

    private void place(String name, int index, boolean shown) {
        SegmentedToggle view = new SegmentedToggle();
        view.segment(name, tools.get(name).icon);
        view.vertical(true);
        if (!shown) {
            view.clear();
        }
        view.onSelect(chosen -> open(chosen, true));
        view.onAgain(chosen -> open(chosen, false));
        dress(view, name);
        views.put(name, view);
        balls.add(Math.max(0, Math.min(balls.size(), index)), name);
        if (shown) {
            open.add(name);
        }
    }

    /**
     * Schiebt ein Werkzeug an Platz {@code index} der Knopfleiste: eine Kugel kommt zurück, ein Symbol der Leiste
     * wechselt nur seinen Platz.
     */
    public void attach(String name, int index) {
        if (!tools.containsKey(name)) {
            return;
        }
        if (!ball(name)) {
            boolean chosen = name.equals(rail.selected());
            rail.remove(name);
            rail.segment(name, tools.get(name).icon, index);
            dress(rail, name);
            if (chosen) {
                rail.show(name);
            }
            changed();
            return;
        }
        boolean wasOpen = open.remove(name);
        balls.remove(name);
        views.remove(name);
        rail.segment(name, tools.get(name).icon, index);
        dress(rail, name);
        changed();
        attached.accept(name, wasOpen);
    }

    /** Stellt eine Kugel an Platz {@code index} unter den Kugeln. */
    public void order(String name, int index) {
        if (!ball(name)) {
            return;
        }
        balls.remove(name);
        balls.add(Math.max(0, Math.min(balls.size(), index)), name);
        changed();
    }

    /** Der Knopf des Werkzeugs, in der Leiste oder als Kugel. */
    public AbstractButton button(String name) {
        return holder(name).button(name);
    }

    private SegmentedToggle holder(String name) {
        return views.getOrDefault(name, rail);
    }

    /** Tauscht das Symbol eines Werkzeugs, gleich wo es steht. */
    public void icon(String name, Icon icon) {
        Tool tool = tools.get(name);
        if (tool != null) {
            tool.icon = icon;
            holder(name).icon(name, icon);
        }
    }

    /** Setzt den Tooltip eines Werkzeugs, gleich wo es steht. */
    public void tip(String name, String tip) {
        Tool tool = tools.get(name);
        if (tool != null) {
            tool.tip = tip;
            holder(name).button(name).setToolTipText(tip);
        }
    }

    /**
     * Die Plätze zum Merken: je Kugel von oben nach unten die Nummer des Werkzeugs (in der Reihenfolge der Anmeldung)
     * und ob ihr Bereich offen ist, etwa {@code "2:1,0:0"}; leer ohne Kugeln.
     */
    public String placement() {
        List<String> names = new ArrayList<>(tools.keySet());
        StringBuilder text = new StringBuilder();
        for (String name : balls) {
            if (text.length() > 0) {
                text.append(',');
            }
            text.append(names.indexOf(name)).append(':').append(open.contains(name) ? 1 : 0);
        }
        return text.toString();
    }

    /** Stellt gemerkte Plätze ({@link #placement}) wieder her; Unlesbares wird übergangen. Meldet eine Änderung. */
    public void restore(String placement) {
        if (placement == null || placement.isBlank()) {
            return;
        }
        List<String> names = new ArrayList<>(tools.keySet());
        for (String part : placement.split(",")) {
            String[] fields = part.trim().split(":");
            try {
                int number = Integer.parseInt(fields[0]);
                if (number < 0 || number >= names.size() || ball(names.get(number))) {
                    continue;
                }
                rail.remove(names.get(number));
                place(names.get(number), balls.size(), fields.length > 1 && "1".equals(fields[1]));
            } catch (NumberFormatException unreadable) {
                // Ein unlesbarer Eintrag bleibt in der Leiste.
            }
        }
        changed();
    }

    /**
     * Wohin ein an {@code point} (in der Spalte) losgelassenes Symbol gehört: Nahe der Knopfleiste oder über ihr
     * schnappt es in die Leiste, sonst zwischen die Kugeln nach seiner Höhe.
     */
    void drop(String name, Point point) {
        Rectangle bar = rail.getBounds();
        boolean near = point.x >= -column.getWidth() && point.x <= 2 * column.getWidth();
        if (near && point.y < bar.y + bar.height + GAP / 2) {
            int height = bar.height / Math.max(1, rail.getComponentCount());
            // Die nächste Fuge zwischen zwei Symbolen der Leiste.
            int index = height <= 0 ? 0 : Math.round((point.y - bar.y) / (float) height);
            attach(name, Math.max(0, index));
            return;
        }
        int index = 0;
        for (String other : balls) {
            SegmentedToggle view = views.get(other);
            if (!other.equals(name) && view.getY() + view.getHeight() / 2 < point.y) {
                index++;
            }
        }
        if (ball(name)) {
            order(name, index);
        } else {
            detach(name, index);
        }
    }

    /** Ziehen an einem Symbol: Es wird als Abbild getragen und schnappt beim Loslassen an seinen Platz. */
    private final class Carry extends MouseAdapter {
        private final String name;
        private Point start;
        private SegmentedToggle ghost;

        private Carry(String name) {
            this.name = name;
        }

        @Override
        public void mousePressed(MouseEvent event) {
            start = SwingUtilities.isLeftMouseButton(event) ? event.getPoint() : null;
        }

        @Override
        public void mouseDragged(MouseEvent event) {
            if (start == null) {
                return;
            }
            if (ghost == null) {
                int dx = Math.abs(event.getX() - start.x);
                int dy = Math.abs(event.getY() - start.y);
                if (Math.max(dx, dy) <= DragSource.getDragThreshold() || railBusy.getAsBoolean()
                        || !ball(name) && sideways.getAsBoolean() && dx >= dy) {
                    return;
                }
                carrying = true;
                ghost = new SegmentedToggle();
                ghost.segment(name, tools.get(name).icon);
                ghost.vertical(true);
                if (event.getComponent() instanceof AbstractButton button) {
                    button.getModel().setArmed(false);
                    button.getModel().setPressed(false);
                }
            }
            if (overlay != null) {
                overlay.carry(ghost, SwingUtilities.convertPoint(event.getComponent(), event.getPoint(), overlay));
            }
        }

        @Override
        public void mouseReleased(MouseEvent event) {
            boolean carried = ghost != null;
            start = null;
            ghost = null;
            if (!carried) {
                return;
            }
            carrying = false;
            if (overlay != null) {
                overlay.drop();
            }
            drop(name, SwingUtilities.convertPoint(event.getComponent(), event.getPoint(), column));
        }
    }

    /** Knopfleiste und Kugeln untereinander, waagerecht mittig; eine leere Leiste nimmt keinen Platz. */
    private final class Column extends JPanel {

        private Column() {
            super(null);
            setOpaque(false);
        }

        private void rebuild() {
            removeAll();
            add(rail);
            for (String name : balls) {
                add(views.get(name));
            }
            revalidate();
            repaint();
        }

        private List<Component> parts() {
            List<Component> parts = new ArrayList<>();
            for (Component part : getComponents()) {
                if (part != rail || rail.getComponentCount() > 0) {
                    parts.add(part);
                }
            }
            return parts;
        }

        @Override
        public Dimension getPreferredSize() {
            int width = 0;
            int height = 0;
            List<Component> parts = parts();
            for (Component part : parts) {
                Dimension size = part.getPreferredSize();
                width = Math.max(width, size.width);
                height += size.height;
            }
            return new Dimension(width, height + GAP * Math.max(0, parts.size() - 1));
        }

        @Override
        public void doLayout() {
            rail.setBounds(0, 0, 0, 0);
            int y = 0;
            for (Component part : parts()) {
                Dimension size = part.getPreferredSize();
                part.setBounds((getWidth() - size.width) / 2, y, size.width, size.height);
                y += size.height + GAP;
            }
        }

        /** Zwischen den Teilen fängt die Spalte keine Maus: Dort liegt der Trenner. */
        @Override
        public boolean contains(int x, int y) {
            for (Component part : getComponents()) {
                if (part.getBounds().contains(x, y)) {
                    return true;
                }
            }
            return false;
        }
    }
}
