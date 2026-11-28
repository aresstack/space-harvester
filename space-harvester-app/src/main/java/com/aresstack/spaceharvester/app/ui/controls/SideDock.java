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
    /** Kennung der Knopfleiste in der Reihenfolge der Gruppen ({@link #units}) und in {@link #placement}. */
    public static final String RAIL = "R";

    /** Die Gruppen von oben nach unten: die Knopfleiste ({@link #RAIL}) und jede Kugel mit ihrem Namen. */
    private final List<String> units = new ArrayList<>(List.of(RAIL));
    private final Map<String, SegmentedToggle> views = new HashMap<>();
    private final Set<String> open = new LinkedHashSet<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private BiConsumer<String, Boolean> attached = (name, wasOpen) -> { };
    private EdgeOverlay overlay;
    private BooleanSupplier sideways = () -> false;
    private BooleanSupplier railBusy = () -> false;
    private boolean carrying;
    private java.util.function.Function<String, Integer> centre = name -> null;
    private Runnable laidOut = () -> { };

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

    /**
     * Auf welcher Höhe der Spalte der Bereich eines Werkzeugs seine Mitte hat, oder {@code null}, wenn er nicht zu
     * sehen ist. Ist die Spalte über einen Trenner gestreckt, sitzt die Knopfleiste auf Höhe des Bereichs ihres
     * gewählten Symbols und jede offene Kugel auf Höhe ihres eigenen Bereichs: Man sieht, was wozu gehört. Was
     * keinen offenen Bereich hat, steht oben, wo die Leiste auch zugeklappt steht.
     */
    public void centres(java.util.function.Function<String, Integer> centre) {
        this.centre = centre;
    }

    /** Meldet, wenn Leiste und Kugeln ihren Platz in der Spalte bekommen haben. */
    public void onLayout(Runnable laidOut) {
        this.laidOut = laidOut;
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
        return views.containsKey(name);
    }

    /**
     * Die Gruppen von oben nach unten: {@link #RAIL} für die Knopfleiste, sonst der Name der Kugel. In dieser
     * Reihenfolge stehen auch ihre Bereiche untereinander (Angelo 28.11.2026: die oberste Kugel hat den oberen
     * Bereich); eine Kugel umzusortieren tauscht also die Plätze der Bereiche.
     */
    public List<String> units() {
        return List.copyOf(units);
    }

    /** Die Kugeln von oben nach unten. */
    public List<String> balls() {
        List<String> result = new ArrayList<>(units);
        result.remove(RAIL);
        return result;
    }

    /** Die Kugeln, deren Bereich offen ist, von oben nach unten. */
    public List<String> openBalls() {
        List<String> result = balls();
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
     * Löst ein Werkzeug aus der Knopfleiste und stellt es als Kugel an Platz {@code index} der Gruppen
     * ({@link #units}; die Leiste zählt mit). War es das gewählte der Leiste, bleibt sein Bereich offen, nun als der
     * der Kugel; die Leiste zeigt dann keine Wahl mehr. Das letzte Symbol der Leiste bleibt in ihr: Allein ist sie
     * selbst schon eine Kugel und wechselt nur ihren Platz ({@link #order}).
     */
    public void detach(String name, int index) {
        if (!tools.containsKey(name) || ball(name)) {
            return;
        }
        if (rail.names().size() == 1) {
            order(RAIL, index);
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
        units.add(Math.max(0, Math.min(units.size(), index)), name);
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
        units.remove(name);
        views.remove(name);
        rail.segment(name, tools.get(name).icon, index);
        dress(rail, name);
        changed();
        attached.accept(name, wasOpen);
    }

    /**
     * Stellt eine Kugel oder die Knopfleiste ({@link #RAIL}) an Platz {@code index} der Gruppen ({@link #units}), gezählt
     * ohne sie selbst.
     */
    public void order(String unit, int index) {
        if (!units.contains(unit)) {
            return;
        }
        int before = units.indexOf(unit);
        units.remove(unit);
        units.add(Math.max(0, Math.min(units.size(), index)), unit);
        if (units.indexOf(unit) != before) {
            changed();
        }
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
     * Die Plätze zum Merken: die Gruppen von oben nach unten, {@link #RAIL} für die Knopfleiste und je Kugel die
     * Nummer des Werkzeugs (in der Reihenfolge der Anmeldung) und ob ihr Bereich offen ist, etwa {@code "2:1,R,0:0"};
     * leer ohne Kugeln.
     */
    public String placement() {
        if (views.isEmpty()) {
            return "";
        }
        List<String> names = new ArrayList<>(tools.keySet());
        StringBuilder text = new StringBuilder();
        for (String unit : units) {
            if (text.length() > 0) {
                text.append(',');
            }
            if (RAIL.equals(unit)) {
                text.append(RAIL);
            } else {
                text.append(names.indexOf(unit)).append(':').append(open.contains(unit) ? 1 : 0);
            }
        }
        return text.toString();
    }

    /** Stellt gemerkte Plätze ({@link #placement}) wieder her; Unlesbares wird übergangen. Meldet eine Änderung. */
    public void restore(String placement) {
        if (placement == null || placement.isBlank()) {
            return;
        }
        List<String> names = new ArrayList<>(tools.keySet());
        boolean railSeen = false;
        for (String part : placement.split(",")) {
            String[] fields = part.trim().split(":");
            if (RAIL.equals(fields[0])) {
                // Die Leiste steht hinter allem, was bis hierher Kugel wurde.
                units.remove(RAIL);
                units.add(RAIL);
                railSeen = true;
                continue;
            }
            try {
                int number = Integer.parseInt(fields[0]);
                // Das letzte Symbol bleibt in der Leiste.
                if (number < 0 || number >= names.size() || ball(names.get(number)) || rail.names().size() == 1) {
                    continue;
                }
                rail.remove(names.get(number));
                place(names.get(number), units.size(), fields.length > 1 && "1".equals(fields[1]));
            } catch (NumberFormatException unreadable) {
                // Ein unlesbarer Eintrag bleibt in der Leiste.
            }
        }
        if (!railSeen) {
            units.remove(RAIL);
            units.add(0, RAIL);
        }
        changed();
    }

    /**
     * Wohin ein an {@code point} (in der Spalte) losgelassenes Symbol gehört: Auf der Knopfleiste schnappt es in die
     * Leiste. Sonst wird es eine Kugel und steht nach seiner Höhe zwischen den anderen Gruppen: Über dem Bereich einer
     * anderen Gruppe losgelassen, tauscht es mit ihr den Platz.
     */
    void drop(String name, Point point) {
        Rectangle bar = rail.getBounds();
        boolean alone = !ball(name) && rail.names().size() == 1;
        boolean near = point.x >= -column.getWidth() && point.x <= 2 * column.getWidth();
        if (!alone && near && point.y >= bar.y - GAP / 2 && point.y < bar.y + bar.height + GAP / 2) {
            int height = bar.height / Math.max(1, rail.getComponentCount());
            // Die nächste Fuge zwischen zwei Symbolen der Leiste.
            int index = height <= 0 ? 0 : Math.round((point.y - bar.y) / (float) height);
            attach(name, Math.max(0, index));
            return;
        }
        Component own = ball(name) ? views.get(name) : rail;
        int from = own.getY() + own.getHeight() / 2;
        String self = ball(name) ? name : alone ? RAIL : null;
        int index = 0;
        for (String unit : units) {
            if (unit.equals(self)) {
                continue;
            }
            Component other = RAIL.equals(unit) ? rail : views.get(unit);
            // Die Grenze zu einer anderen Gruppe liegt mittig zwischen ihr und dem alten Platz: bei gleich hohen
            // Bereichen genau auf der Linie zwischen beiden.
            int middle = other.getY() + other.getHeight() / 2;
            // Ein Symbol, das gerade erst aus der Leiste kommt, hat noch keinen eigenen Platz: Es ordnet sich nach
            // den Mitten der anderen ein.
            int border = self == null ? middle : (middle + from) / 2;
            if (border < point.y) {
                index++;
            }
        }
        if (self != null) {
            order(self, index);
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
    private final class Column extends JPanel implements DividerRail.Beads {

        @Override
        public List<int[]> rests() {
            List<int[]> rests = new ArrayList<>();
            for (Component part : parts()) {
                rests.add(new int[] {part.getY(), part.getY() + part.getHeight()});
            }
            rests.sort(java.util.Comparator.comparingInt(rest -> rest[0]));
            return rests;
        }

        /** Das Werkzeug, dessen Bereich zu diesem Teil gehört: das gewählte der Leiste oder das der Kugel. */
        private String toolOf(Component part) {
            if (part == rail) {
                return rail.selected();
            }
            for (String name : views.keySet()) {
                if (views.get(name) == part) {
                    return open.contains(name) ? name : null;
                }
            }
            return null;
        }

        private Column() {
            super(null);
            setOpaque(false);
        }

        private void rebuild() {
            removeAll();
            for (String unit : units) {
                add(RAIL.equals(unit) ? rail : views.get(unit));
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
            // Gestreckt über einen Trenner: Was offen ist, sitzt auf Höhe seines Bereichs; der Rest steht oben.
            boolean stretched = getHeight() > getPreferredSize().height;
            int y = stretched ? HeadBar.HEIGHT + EdgeOverlay.HEAD_GAP : 0;
            for (Component part : parts()) {
                Dimension size = part.getPreferredSize();
                String tool = stretched ? toolOf(part) : null;
                Integer middle = tool == null ? null : centre.apply(tool);
                if (middle != null) {
                    part.setBounds((getWidth() - size.width) / 2, middle - size.height / 2, size.width, size.height);
                } else {
                    part.setBounds((getWidth() - size.width) / 2, y, size.width, size.height);
                    y += size.height + GAP;
                }
            }
            laidOut.run();
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
