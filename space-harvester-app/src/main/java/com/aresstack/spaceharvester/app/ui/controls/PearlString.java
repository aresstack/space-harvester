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
 * Die „Perlenkette“ (Angelo 28.11.2026): die Werkzeuge einer Seitenleiste als Knopfleiste und einzelne Kugeln
 * („Perlen“, im Code {@code pearl}), aufgereiht auf dem Trenner der Leiste; nach dem Vorbild der
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
public final class PearlString {

    /** Abstand zwischen Knopfleiste und Kugeln und zwischen zwei Kugeln. */
    public static final int GAP = 4;

    private final SegmentedToggle rail;
    private final Column column = new Column();
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    /** Kennung der Knopfleiste in der Reihenfolge der Gruppen ({@link #units}) und in {@link #placement}. */
    public static final String RAIL = "R";
    /** Vor der Nummer einer Kugel in {@link #placement}: Das Werkzeug gehört der Kette des anderen Rands. */
    public static final String GUEST = "*";

    /** Die Gruppen von oben nach unten: die Knopfleiste ({@link #RAIL}) und jede Kugel mit ihrem Namen. */
    private final List<String> units = new ArrayList<>(List.of(RAIL));
    private final Map<String, SegmentedToggle> views = new HashMap<>();
    private final Set<String> open = new LinkedHashSet<>();
    private final Set<String> kept = new java.util.HashSet<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private BiConsumer<String, Boolean> attached = (name, wasOpen) -> { };
    private EdgeOverlay overlay;
    private BooleanSupplier sideways = () -> false;
    private BooleanSupplier railBusy = () -> false;
    /** Eigene Werkzeuge, die gerade als Kugel auf einer anderen Kette sitzen, und auf welcher. */
    private final Map<String, PearlString> away = new HashMap<>();
    /** Fremde Kugeln auf dieser Kette und die Kette, der ihr Werkzeug gehört. */
    private final Map<String, PearlString> guests = new HashMap<>();
    /** Welche Kette an einem Punkt der Tragfläche zuständig ist (links oder rechts); ohne Angabe diese. */
    private java.util.function.Function<Point, PearlString> sideAt = point -> this;
    private boolean railShown = true;
    /**
     * Je Gruppe die Höhe, auf der sie mit offenem Bereich zuletzt saß, als Anteil der Länge: Dort bleibt sie, wenn
     * ihr Bereich zu ist oder die Leiste zugeklappt am Fensterrand steht.
     */
    private final Map<String, Integer> seats = new LinkedHashMap<>();
    /** Ein Anteil der Länge in {@link #seats}: so fein, dass die Höhe auf das Pixel genau wiederkommt. */
    private static final int SEAT_SCALE = 1_000_000;
    private Runnable seatsChanged = () -> { };

    /** Meldet, wenn eine Gruppe auf einer neuen Höhe sitzt (zum Merken, {@link #seats()}). */
    public void onSeats(Runnable listener) {
        this.seatsChanged = listener;
    }

    /**
     * Die gemerkten Höhen zum Ablegen: je Gruppe ihre Kennung wie in {@link #placement} und ihr Anteil der Länge,
     * etwa {@code "R=250000,*0=750000"}.
     */
    public String seats() {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, Integer> seat : seats.entrySet()) {
            String code = code(seat.getKey());
            if (code != null) {
                text.append(text.length() > 0 ? "," : "").append(code).append('=').append(seat.getValue());
            }
        }
        return text.toString();
    }

    /**
     * Setzt die Höhen aus {@link #seats()}: So sitzen Leiste und Kugeln schon beim Start zugeklappt dort, wo sie
     * aufgeklappt saßen. {@code other} ist die Kette des anderen Rands (für ihre Gäste hier). Unlesbares entfällt.
     */
    public void seats(String text, PearlString other) {
        seats.clear();
        if (text != null) {
            for (String part : text.split(",")) {
                String[] fields = part.trim().split("=");
                String unit = fields.length == 2 ? unit(fields[0], other) : null;
                try {
                    if (unit != null) {
                        seats.put(unit, Math.max(0, Math.min(SEAT_SCALE, Integer.parseInt(fields[1]))));
                    }
                } catch (NumberFormatException unreadable) {
                    // Ohne lesbare Höhe gilt die Verteilung nach der Reihenfolge.
                }
            }
        }
        column.revalidate();
    }

    /** Die Kennung einer Gruppe zum Merken: die Leiste, sonst die Nummer des Werkzeugs in seiner Kette. */
    private String code(String unit) {
        if (RAIL.equals(unit)) {
            return RAIL;
        }
        int number = guests.getOrDefault(unit, this).tools().indexOf(unit);
        return number < 0 ? null : (guests.containsKey(unit) ? GUEST : "") + number;
    }

    private String unit(String code, PearlString other) {
        if (RAIL.equals(code)) {
            return RAIL;
        }
        boolean guest = code.startsWith(GUEST);
        PearlString origin = guest ? other : this;
        try {
            int number = Integer.parseInt(guest ? code.substring(GUEST.length()) : code);
            return origin == null || number < 0 || number >= origin.tools().size() ? null
                    : origin.tools().get(number);
        } catch (NumberFormatException unreadable) {
            return null;
        }
    }
    private boolean carrying;
    private java.util.function.Function<String, Integer> centre = name -> null;
    private Runnable laidOut = () -> { };

    /** Ein Werkzeug: sein Name, sein Symbol und sein Tooltip, gleich wo es gerade steht. */
    private static final class Tool {
        private Icon icon;
        private String tip;
        private int count;
        private java.awt.Color tone;
        private String countTip;

        private Tool(Icon icon, String tip) {
            this.icon = icon;
            this.tip = tip;
        }
    }

    /** Die Werkzeuge stehen zunächst alle in {@code rail}. */
    public PearlString(SegmentedToggle rail) {
        this.rail = rail;
        column.rebuild();
    }

    /** Meldet ein Werkzeug an; es steht am Ende der Knopfleiste. */
    public PearlString tool(String name, Icon icon, String tip) {
        tools.put(name, new Tool(icon, tip));
        rail.segment(name, icon);
        dress(rail, name);
        return this;
    }

    /**
     * Meldet ein Symbol als Werkzeug an, das schon in der Knopfleiste steht (etwa die Hälften eines Umschalters, den
     * sein Besitzer selbst gebaut hat): Es lässt sich danach wie jedes andere herausziehen.
     */
    public PearlString adopt(String name, Icon icon, String tip) {
        if (!rail.names().contains(name)) {
            return tool(name, icon, tip);
        }
        tools.put(name, new Tool(icon, tip));
        dress(rail, name);
        return this;
    }

    /**
     * Dieses Werkzeug bleibt in der Knopfleiste, weil sein Bereich der der Leiste selbst ist: Es zu ziehen verschiebt
     * die ganze Leiste, wie beim letzten Symbol.
     */
    public PearlString keep(String name) {
        kept.add(name);
        return this;
    }

    /** Ob dieses Symbol der Leiste sie nicht verlassen kann: das letzte oder ein festes ({@link #keep}). */
    private boolean stays(String name) {
        return rail.names().size() == 1 || kept.contains(name);
    }

    /** Symbol und Tooltip eines Werkzeugs, gleich ob es dieser Kette gehört oder hier zu Gast ist. */
    private Tool spec(String name) {
        return guests.getOrDefault(name, this).tools.get(name);
    }

    /** Die Kette, auf der dieses eigene Werkzeug gerade steht: diese oder die, auf die es verschoben wurde. */
    public PearlString host(String name) {
        return away.getOrDefault(name, this);
    }

    /**
     * Die Ketten der beiden Fensterränder: {@code sideAt} sagt für einen Punkt der Tragfläche ({@link #carrier}),
     * welche dort zuständig ist. Eine Kugel, die auf der anderen Seite losgelassen wird, schnappt in deren Kette.
     */
    public void sides(java.util.function.Function<Point, PearlString> sideAt) {
        this.sideAt = sideAt;
    }

    /** Ob die Knopfleiste dieser Kette zu sehen ist (etwa nur in der Perspektive, zu der sie gehört). */
    public void rail(boolean shown) {
        if (railShown != shown) {
            railShown = shown;
            column.rebuild();
        }
    }

    private void dress(SegmentedToggle holder, String name) {
        AbstractButton button = holder.button(name);
        Tool tool = spec(name);
        button.setToolTipText(tool.tip);
        if (tool.count > 0) {
            holder.count(name, tool.count, tool.tone, tool.countTip, tool.tip);
        }
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
     * keinen offenen Bereich hat, bleibt auf der Höhe, auf der es offen zuletzt saß.
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
    public boolean pearl(String name) {
        return views.containsKey(name) || away.containsKey(name);
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
    public List<String> pearls() {
        List<String> result = new ArrayList<>(units);
        result.remove(RAIL);
        return result;
    }

    /** Die Kugeln, deren Bereich offen ist, von oben nach unten. */
    public List<String> openPearls() {
        List<String> result = pearls();
        result.retainAll(open);
        return result;
    }

    /** Ob der Bereich dieser Kugel offen ist, gleich auf welcher Kette sie sitzt. */
    public boolean open(String name) {
        return host(name).open.contains(name);
    }

    /** Öffnet oder schließt den Bereich einer Kugel; für ein Werkzeug in der Leiste geschieht nichts. */
    public void open(String name, boolean shown) {
        if (host(name) != this) {
            host(name).open(name, shown);
            return;
        }
        if (!views.containsKey(name) || open.contains(name) == shown) {
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
    public void closePearls() {
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
        if (!tools.containsKey(name) || pearl(name)) {
            return;
        }
        if (stays(name)) {
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
        view.segment(name, spec(name).icon);
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
        if (!tools.containsKey(name) || away.containsKey(name)) {
            return;
        }
        if (!pearl(name)) {
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

    /** Der Knopf des Werkzeugs, in der Leiste oder als Kugel, gleich auf welcher Kette. */
    public AbstractButton button(String name) {
        return holder(name).button(name);
    }

    private SegmentedToggle holder(String name) {
        PearlString host = host(name);
        return host.views.getOrDefault(name, rail);
    }

    /**
     * Verschiebt ein Werkzeug von dieser Kette als Kugel an Platz {@code index} der Gruppen von {@code target}
     * (Angelo 28.11.2026: eine Kugel wandert vom linken an den rechten Rand und umgekehrt). Ein offener Bereich
     * bleibt offen und zieht mit um. Das letzte Symbol einer Knopfleiste bleibt in ihr.
     */
    public void move(String name, PearlString target, int index) {
        if (target == this) {
            if (views.containsKey(name)) {
                order(name, index);
            } else {
                detach(name, index);
            }
            return;
        }
        PearlString origin = guests.getOrDefault(name, this);
        boolean wasOpen;
        if (views.containsKey(name)) {
            wasOpen = open.remove(name);
            units.remove(name);
            views.remove(name);
            guests.remove(name);
        } else if (tools.containsKey(name) && rail.names().contains(name) && !stays(name)) {
            wasOpen = name.equals(rail.selected());
            rail.remove(name);
        } else {
            return;
        }
        if (target == origin) {
            origin.away.remove(name);
        } else {
            origin.away.put(name, target);
            target.guests.put(name, origin);
        }
        target.place(name, index, wasOpen);
        changed();
        target.changed();
    }

    /** Holt ein eigenes Werkzeug zurück in die Knopfleiste, von welcher Kette auch immer, an seinen alten Platz. */
    public void home(String name) {
        if (!tools.containsKey(name) || !pearl(name)) {
            return;
        }
        if (away.containsKey(name)) {
            away.get(name).move(name, this, units.size());
        }
        int index = 0;
        for (String tool : tools.keySet()) {
            if (tool.equals(name)) {
                break;
            }
            if (rail.names().contains(tool)) {
                index++;
            }
        }
        attach(name, index);
    }

    /**
     * Eine Zahl am Symbol eines eigenen Werkzeugs ({@link SegmentedToggle#count}); sie wandert mit, wenn es Kugel
     * wird oder in die Leiste zurückkommt.
     */
    public void count(String name, int count, java.awt.Color tone, String tip, String idleTip) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return;
        }
        tool.count = Math.max(0, count);
        tool.tone = tone;
        tool.countTip = tip;
        tool.tip = idleTip;
        holder(name).count(name, count, tone, tip, idleTip);
    }

    /** Die eigenen Werkzeuge in der Reihenfolge ihrer Anmeldung. */
    public List<String> tools() {
        return List.copyOf(tools.keySet());
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
            button(name).setToolTipText(tip);
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
        StringBuilder text = new StringBuilder();
        for (String unit : units) {
            if (text.length() > 0) {
                text.append(',');
            }
            if (RAIL.equals(unit)) {
                text.append(RAIL);
            } else {
                // Gezählt wird in der Kette, der das Werkzeug gehört; ein Gast vom anderen Rand trägt das Zeichen
                // davor, damit er nicht mit einem eigenen Werkzeug derselben Nummer verwechselt wird.
                text.append(guests.containsKey(unit) ? GUEST : "")
                        .append(guests.getOrDefault(unit, this).tools().indexOf(unit)).append(':')
                        .append(open.contains(unit) ? 1 : 0);
            }
        }
        return text.toString();
    }

    /** Stellt gemerkte Plätze ({@link #placement}) wieder her; Unlesbares wird übergangen. Meldet eine Änderung. */
    public void restore(String placement) {
        restore(placement, null);
    }

    /**
     * Wie {@link #restore(String)}; Einträge mit {@link #GUEST} davor meinen Werkzeuge von {@code other}, der Kette
     * des anderen Rands: Was dort noch in der Leiste steht, kommt als Kugel auf diese Kette.
     */
    public void restore(String placement, PearlString other) {
        if (placement == null || placement.isBlank()) {
            return;
        }
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
            boolean guest = fields[0].startsWith(GUEST);
            PearlString origin = guest ? other : this;
            if (origin == null) {
                continue;
            }
            List<String> names = origin.tools();
            try {
                int number = Integer.parseInt(guest ? fields[0].substring(GUEST.length()) : fields[0]);
                // Das letzte Symbol bleibt in der Leiste.
                if (number < 0 || number >= names.size() || origin.pearl(names.get(number))
                        || origin.stays(names.get(number))) {
                    continue;
                }
                origin.move(names.get(number), this, units.size());
                // Gemerkt ist, ob der Bereich offen war; die Auswahl in der Leiste zählt hier nicht.
                open(names.get(number), fields.length > 1 && "1".equals(fields[1]));
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
        settle(this, name, point);
    }

    /**
     * Wie {@link #drop}, für ein Symbol, das von der Kette {@code from} kommt (dieser oder der des anderen Rands):
     * In die Knopfleiste schnappt nur ein Werkzeug, das dieser Kette gehört; alles andere wird hier eine Kugel.
     */
    void settle(PearlString from, String name, Point point) {
        Rectangle bar = rail.getBounds();
        boolean here = from == this;
        boolean inRail = here && !views.containsKey(name);
        boolean alone = inRail && rail.names().size() == 1;
        // Ein festes Symbol sortiert sich in seiner Leiste um; daneben losgelassen, verschiebt es die ganze Leiste.
        boolean fixed = inRail && stays(name);
        boolean near = point.x >= -column.getWidth() && point.x <= 2 * column.getWidth();
        if (tools.containsKey(name) && railShown && !alone && near && point.y >= bar.y - GAP / 2
                && point.y < bar.y + bar.height + GAP / 2) {
            int height = bar.height / Math.max(1, rail.getComponentCount());
            // Die nächste Fuge zwischen zwei Symbolen der Leiste.
            int index = height <= 0 ? 0 : Math.round((point.y - bar.y) / (float) height);
            if (!here) {
                from.move(name, this, units.size());
            }
            attach(name, Math.max(0, index));
            return;
        }
        String self = !here ? null : views.containsKey(name) ? name : fixed ? RAIL : null;
        Component own = self == null ? null : RAIL.equals(self) ? rail : views.get(name);
        int from0 = own == null ? 0 : own.getY() + own.getHeight() / 2;
        int index = 0;
        for (String unit : units) {
            if (unit.equals(self)) {
                continue;
            }
            Component other = RAIL.equals(unit) ? rail : views.get(unit);
            int middle = other.getY() + other.getHeight() / 2;
            // Die Grenze zu einer anderen Gruppe liegt mittig zwischen ihr und dem alten Platz: bei gleich hohen
            // Bereichen genau auf der Linie zwischen beiden. Ein Symbol, das gerade erst aus einer Leiste oder vom
            // anderen Rand kommt, hat hier noch keinen Platz: Es ordnet sich nach den Mitten der anderen ein.
            int border = own == null ? middle : (middle + from0) / 2;
            if (border < point.y) {
                index++;
            }
        }
        if (!here) {
            from.move(name, this, index);
        } else if (self != null) {
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
                        || !pearl(name) && sideways.getAsBoolean() && dx >= dy) {
                    return;
                }
                carrying = true;
                ghost = new SegmentedToggle();
                ghost.segment(name, spec(name).icon);
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
            // Wo losgelassen wurde, entscheidet über den Rand: Die Kette dort nimmt das Symbol auf.
            PearlString target = overlay == null ? PearlString.this
                    : sideAt.apply(SwingUtilities.convertPoint(event.getComponent(), event.getPoint(), overlay));
            target.settle(PearlString.this, name,
                    SwingUtilities.convertPoint(event.getComponent(), event.getPoint(), target.column));
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

        /** Die Gruppe dieses Teils: die Knopfleiste oder der Name der Kugel. */
        private String unitOf(Component part) {
            for (String name : views.keySet()) {
                if (views.get(name) == part) {
                    return name;
                }
            }
            return RAIL;
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
                if (part != rail || railShown && rail.getComponentCount() > 0) {
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
            // Gestreckt über die Länge eines Trenners (oder zugeklappt über dieselbe Länge am Fensterrand).
            boolean stretched = getHeight() > getPreferredSize().height;
            List<Component> parts = parts();
            if (!stretched) {
                int y = 0;
                for (Component part : parts) {
                    Dimension size = part.getPreferredSize();
                    part.setBounds((getWidth() - size.width + 1) / 2, y, size.width, size.height);
                    y += size.height + GAP;
                }
                laidOut.run();
                return;
            }
            // Jedes Teil sitzt auf Höhe seines Bereichs. Ist der zu (oder die ganze Leiste zugeklappt, die Kette steht
            // dann am Fensterrand), bleibt es auf der Höhe, auf der es offen zuletzt saß (Angelo 28.11.2026: zugeklappt
            // genau dort, wo aufgeklappt). Was noch nie offen war, teilt sich die Länge in der Reihenfolge der Kette.
            Map<Component, Integer> middles = new LinkedHashMap<>();
            boolean moved = false;
            for (int index = 0; index < parts.size(); index++) {
                Component part = parts.get(index);
                String unit = unitOf(part);
                String tool = toolOf(part);
                Integer middle = tool == null ? null : centre.apply(tool);
                if (middle != null) {
                    int seat = (int) Math.round(middle * (double) SEAT_SCALE / getHeight());
                    Integer before = seats.put(unit, seat);
                    moved |= before == null || before != seat;
                } else if (seats.containsKey(unit)) {
                    middle = (int) Math.round(seats.get(unit) * (double) getHeight() / SEAT_SCALE);
                } else {
                    middle = (2 * index + 1) * getHeight() / (2 * parts.size());
                }
                middles.put(part, middle);
            }
            // Von oben nach unten: Zwei Teile auf fast derselben Höhe rücken untereinander statt aufeinander.
            List<Component> down = new ArrayList<>(parts);
            down.sort(java.util.Comparator.comparingInt(middles::get));
            int free = 0;
            for (Component part : down) {
                Dimension size = part.getPreferredSize();
                int y = Math.max(free, middles.get(part) - size.height / 2);
                part.setBounds((getWidth() - size.width + 1) / 2, y, size.width, size.height);
                free = y + size.height + GAP;
            }
            if (moved) {
                seatsChanged.run();
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
