package com.aresstack.spaceharvester.deck.terminal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Anwendungsfall „KI-Maskenassistent“ einer Terminal-Sitzung (#1151): Maske lesen, ungeschützte Felder lokal
 * ausfüllen, die Schreibmarke lokal bewegen, KI-Eingaben zurücknehmen. Er spricht nur mit dem Port
 * {@link TerminalMask}, der keine Host-Taste kennt; eine Methode zum Abschicken gibt es hier nicht und darf es nie
 * geben. Abgeschickt wird ausschließlich durch eine bewusste Taste des Menschen.
 *
 * <p>Sicherheitsgrenze, an der Ausführung erzwungen (nicht nur im Prompt):
 * <ul>
 *   <li>nur bei verbundener Sitzung und freier Tastatur;</li>
 *   <li>nur auf der Maske, die zuletzt gelesen wurde: Jede Änderung nennt deren {@link MaskSnapshot#revision()};
 *   schickt der Host inzwischen ein neues Bild, wird abgelehnt statt auf das falsche Bild zu schreiben;</li>
 *   <li>nur ungeschützte, nicht verdeckte, nicht als Kennwort erkannte Felder, nie länger als das Feld, in numerische
 *   Felder nur Ziffern;</li>
 *   <li>hat der Mensch ein Feld seit dem Lesen geändert, gewinnt er: abgelehnt;</li>
 *   <li>alles oder nichts: erst wird jede Änderung geprüft, dann geschrieben.</li>
 * </ul>
 */
public final class MaskAssistant {

    /** Beschriftungen, deren Felder als Kennwort gelten, auch wenn der Host sie nicht verdeckt. */
    static final Pattern SENSITIVE = Pattern.compile(
            "(?i)(passw|kennw|password|passphrase|\\bpin\\b|geheim|secret|token|new pass|neues kennw)");
    /** Was ein numerisches 3270-Feld annimmt. */
    private static final Pattern NUMERIC = Pattern.compile("[0-9 .,+-]*");

    /** Eine Eingabe der KI, zum Markieren und Zurücknehmen. */
    public record Edit(int field, int start, int length, String before, String after) {
    }

    /** Ein Wunsch der KI: Feld {@code field} (Nummer aus der Maske) bekommt {@code value}. */
    public record FieldValue(int field, String value) {
    }

    private final TerminalSession session;
    private final Supplier<Optional<TerminalMask>> port;
    private final ReentrantLock lock = new ReentrantLock();
    private final List<Runnable> observers = new CopyOnWriteArrayList<>();
    /** Die Maske, die die KI zuletzt gelesen hat, und die Werte ihrer Felder (nach Start), wie danach geschrieben. */
    private String readRevision;
    private final Map<Integer, String> readValues = new HashMap<>();
    /** Die KI-Eingaben auf der aktuellen Maske. */
    private final List<Edit> edits = new ArrayList<>();
    private String editsRevision;
    /** Der zuletzt gemeldete Stand der KI-Eingaben, für Leser, die nicht warten dürfen. */
    private volatile List<Edit> published = List.of();

    MaskAssistant(TerminalSession session, Supplier<Optional<TerminalMask>> port) {
        this.session = Objects.requireNonNull(session, "session");
        this.port = Objects.requireNonNull(port, "port");
    }

    public String sessionId() {
        return session.id();
    }

    /** Meldet jede Änderung an den KI-Eingaben (gesetzt, zurückgenommen, durch neues Bild verfallen). */
    public void observe(Runnable observer) {
        observers.add(Objects.requireNonNull(observer, "observer"));
    }

    public void unobserve(Runnable observer) {
        observers.remove(observer);
    }

    // ------------------------------------------------------------------ Lesen

    /** Liest die aktuelle Maske, geschwärzt, und merkt sie als Grundlage für die nächsten Änderungen. */
    public MaskSnapshot read() {
        TerminalMask mask = connectedMask();
        TerminalMask.ScreenImage image = mask.capture();
        String revision = revision(image);
        MaskSnapshot snapshot;
        boolean expired;
        lock.lock();
        try {
            expired = expireEdits(revision);
            readRevision = revision;
            readValues.clear();
            for (TerminalMask.Field field : inputFields(image)) {
                readValues.put(field.start(), trimEnd(image.text(field)));
            }
            snapshot = snapshot(image, revision);
        } finally {
            lock.unlock();
        }
        if (expired) {
            notifyObservers();
        }
        return snapshot;
    }

    // ------------------------------------------------------------------ Ausfüllen

    /**
     * Füllt Felder lokal aus, alles oder nichts.
     *
     * @param revision die Maske, auf die sich die KI bezieht ({@link MaskSnapshot#revision()})
     * @return die ausgeführten Eingaben
     * @throws MaskRefused mit lesbarem Grund, ohne etwas geschrieben zu haben
     */
    public List<Edit> fill(String revision, List<FieldValue> values) {
        if (values == null || values.isEmpty()) {
            throw new MaskRefused("Keine Felder angegeben.");
        }
        TerminalMask mask = connectedMask();
        List<Edit> done = new ArrayList<>();
        lock.lock();
        try {
            TerminalMask.ScreenImage image = currentMask(mask, revision);
            List<TerminalMask.Field> inputs = inputFields(image);
            Map<Integer, String> labels = labels(image, inputs);
            List<Edit> planned = new ArrayList<>();
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            for (FieldValue wanted : values) {
                int number = wanted.field();
                if (number < 1 || number > inputs.size()) {
                    throw new MaskRefused("Feld " + number + " gibt es auf dieser Maske nicht (Eingabefelder 1 bis "
                            + inputs.size() + ").");
                }
                if (!seen.add(number)) {
                    throw new MaskRefused("Feld " + number + " ist doppelt angegeben.");
                }
                TerminalMask.Field field = inputs.get(number - 1);
                if (sensitive(field, labels.get(number))) {
                    throw new MaskRefused("Feld " + number + " ist ein verdecktes oder Kennwortfeld; das füllt nur "
                            + "der Benutzer selbst.");
                }
                String value = wanted.value() == null ? "" : wanted.value();
                if (value.chars().anyMatch(c -> c < 0x20 || c == 0x7F)) {
                    throw new MaskRefused("Feld " + number + ": Steuerzeichen (auch Zeilenumbrüche) sind nicht "
                            + "erlaubt; für den Feldwechsel cursor_bewegen nutzen.");
                }
                value = trimEnd(value);
                if (value.length() > field.length()) {
                    throw new MaskRefused("Feld " + number + " ist nur " + field.length() + " Zeichen lang, der Wert "
                            + "hat " + value.length() + ".");
                }
                if (field.numeric() && !NUMERIC.matcher(value).matches()) {
                    throw new MaskRefused("Feld " + number + " nimmt nur Ziffern an.");
                }
                String now = trimEnd(image.text(field));
                String known = readValues.get(field.start());
                if (known != null && !known.equals(now)) {
                    throw new MaskRefused("Feld " + number + " wurde inzwischen von Hand geändert; die Eingabe des "
                            + "Benutzers bleibt. Maske neu lesen.");
                }
                planned.add(new Edit(number, field.start(), field.length(), now, value));
            }
            for (Edit edit : planned) {
                if (edit.before().equals(edit.after())) {
                    continue;
                }
                mask.write(edit.start(), edit.length(), edit.after());
                readValues.put(edit.start(), edit.after());
                record(edit, revision);
                done.add(edit);
            }
        } finally {
            lock.unlock();
        }
        if (!done.isEmpty()) {
            notifyObservers();
        }
        return done;
    }

    /** Bewegt die Schreibmarke lokal; gibt die neue Position als {@code {zeile, spalte}} (ab 1). */
    public int[] moveCursor(String revision, CursorMove move, int fieldNumber) {
        Objects.requireNonNull(move, "move");
        TerminalMask mask = connectedMask();
        lock.lock();
        try {
            TerminalMask.ScreenImage image = currentMask(mask, revision);
            List<TerminalMask.Field> inputs = inputFields(image);
            if (inputs.isEmpty()) {
                throw new MaskRefused("Diese Maske hat keine Eingabefelder.");
            }
            int target = target(image, inputs, move, fieldNumber);
            mask.moveCursor(target);
            return new int[] {target / image.columns() + 1, target % image.columns() + 1};
        } finally {
            lock.unlock();
        }
    }

    static int target(TerminalMask.ScreenImage image, List<TerminalMask.Field> inputs, CursorMove move,
                      int fieldNumber) {
        int cursor = image.cursor();
        int columns = image.columns();
        return switch (move) {
            case HOME -> inputs.get(0).start();
            case FIELD -> {
                if (fieldNumber < 1 || fieldNumber > inputs.size()) {
                    throw new MaskRefused("Feld " + fieldNumber + " gibt es auf dieser Maske nicht (Eingabefelder 1 "
                            + "bis " + inputs.size() + ").");
                }
                yield inputs.get(fieldNumber - 1).start();
            }
            case TAB -> inputs.stream().mapToInt(TerminalMask.Field::start).filter(start -> start > cursor)
                    .findFirst().orElse(inputs.get(0).start());
            case BACKTAB -> {
                int result = -1;
                for (TerminalMask.Field field : inputs) {
                    if (field.start() < cursor) {
                        result = field.start();
                    }
                }
                yield result >= 0 ? result : inputs.get(inputs.size() - 1).start();
            }
            case NEW_LINE -> {
                int row = cursor / columns;
                yield inputs.stream().mapToInt(TerminalMask.Field::start).filter(start -> start / columns > row)
                        .findFirst().orElse(inputs.get(0).start());
            }
        };
    }

    // ------------------------------------------------------------------ KI-Eingaben

    /**
     * Die KI-Eingaben auf der aktuellen Maske; nach einem neuen Bild vom Host leer. Blockiert nie: Schreibt die KI
     * gerade (die Oberfläche fragt auf dem EDT, der Adapter schreibt dort), kommt der zuletzt gemeldete Stand.
     */
    public List<Edit> edits() {
        if (!lock.tryLock()) {
            return published;
        }
        boolean expired = false;
        try {
            Optional<TerminalMask> mask = port.get();
            if (!edits.isEmpty()) {
                if (session.state() != SessionState.CONNECTED || mask.isEmpty()) {
                    edits.clear();
                    editsRevision = null;
                    expired = true;
                } else {
                    expired = expireEdits(revision(mask.get().capture()));
                }
            }
            published = List.copyOf(edits);
        } finally {
            lock.unlock();
        }
        if (expired) {
            notifyObservers();
        }
        return published;
    }

    /**
     * Nimmt die KI-Eingaben zurück (der Mensch, nie die KI): Jedes Feld, das noch den Wert der KI hat, bekommt seinen
     * Wert von vorher. Was der Mensch danach selbst geändert hat, bleibt.
     *
     * @return wie viele Felder zurückgesetzt wurden
     */
    public int undo() {
        TerminalMask mask = connectedMask();
        int restored = 0;
        if (!lock.tryLock()) {
            throw new MaskRefused("Die KI schreibt gerade; gleich noch einmal versuchen.");
        }
        try {
            TerminalMask.ScreenImage image = mask.capture();
            String revision = revision(image);
            if (revision.equals(editsRevision) && !image.keyboardLocked()) {
                java.util.Set<Integer> done = new java.util.HashSet<>();
                for (int i = edits.size() - 1; i >= 0; i--) {
                    Edit edit = edits.get(i);
                    if (!done.add(edit.start())) {
                        continue;
                    }
                    TerminalMask.Field field = new TerminalMask.Field(edit.start(), edit.length(), false, false,
                            false, false);
                    String now = trimEnd(image.text(field));
                    String first = firstBefore(edit.start());
                    if (now.equals(edit.after())) {
                        mask.write(edit.start(), edit.length(), first);
                        readValues.put(edit.start(), first);
                        restored++;
                    }
                }
            }
            edits.clear();
            editsRevision = null;
            published = List.of();
        } finally {
            lock.unlock();
        }
        notifyObservers();
        return restored;
    }

    private String firstBefore(int start) {
        for (Edit edit : edits) {
            if (edit.start() == start) {
                return edit.before();
            }
        }
        return "";
    }

    private void record(Edit edit, String revision) {
        if (!revision.equals(editsRevision)) {
            edits.clear();
            editsRevision = revision;
        }
        edits.add(edit);
        published = List.copyOf(edits);
    }

    /** Verwirft die Eingaben einer alten Maske; {@code true}, wenn welche verworfen wurden. */
    private boolean expireEdits(String revision) {
        if (editsRevision != null && !editsRevision.equals(revision)) {
            boolean had = !edits.isEmpty();
            edits.clear();
            editsRevision = null;
            published = List.of();
            return had;
        }
        return false;
    }

    private void notifyObservers() {
        observers.forEach(Runnable::run);
    }

    // ------------------------------------------------------------------ Prüfungen

    private TerminalMask connectedMask() {
        if (session.state() != SessionState.CONNECTED) {
            throw new MaskRefused("Die Terminal-Sitzung ist nicht verbunden (" + session.state().label() + ").");
        }
        return port.get().orElseThrow(() -> new MaskRefused("Diese Sitzung hat keine lesbare Maske."));
    }

    /** Das aktuelle Bild, wenn es noch die Maske {@code revision} ist, die die KI zuletzt gelesen hat. */
    private TerminalMask.ScreenImage currentMask(TerminalMask mask, String revision) {
        if (readRevision == null) {
            throw new MaskRefused("Erst die Maske lesen.");
        }
        TerminalMask.ScreenImage image = mask.capture();
        String now = revision(image);
        if (revision == null || !revision.equals(readRevision) || !now.equals(readRevision)) {
            throw new MaskRefused("Die Maske hat sich geändert (neues Bild vom Host oder andere Revision); nichts "
                    + "geändert. Erst die Maske neu lesen.");
        }
        if (image.keyboardLocked()) {
            throw new MaskRefused("Der Host nimmt gerade keine Eingabe an (X-Wait); nichts geändert.");
        }
        return image;
    }

    // ------------------------------------------------------------------ Abbild

    static List<TerminalMask.Field> inputFields(TerminalMask.ScreenImage image) {
        return image.fields().stream().filter(field -> !field.protectedField() && field.length() > 0).toList();
    }

    private MaskSnapshot snapshot(TerminalMask.ScreenImage image, String revision) {
        int columns = image.columns();
        List<TerminalMask.Field> inputs = inputFields(image);
        Map<Integer, String> labels = labels(image, inputs);
        char[] shown = image.cells().clone();
        List<MaskSnapshot.InputField> fields = new ArrayList<>();
        java.util.Set<Integer> byAssistant = new java.util.HashSet<>();
        if (revision.equals(editsRevision)) {
            edits.forEach(edit -> byAssistant.add(edit.start()));
        }
        // Verdeckte Felder auch außerhalb der Eingabefelder schwärzen (geschützt und verdeckt).
        for (TerminalMask.Field field : image.fields()) {
            if (field.hidden()) {
                blank(shown, field);
            }
        }
        for (int i = 0; i < inputs.size(); i++) {
            TerminalMask.Field field = inputs.get(i);
            String label = labels.getOrDefault(i + 1, "");
            boolean sensitive = sensitive(field, label);
            if (sensitive) {
                blank(shown, field);
            }
            fields.add(new MaskSnapshot.InputField(i + 1, field.start() / columns + 1, field.start() % columns + 1,
                    field.length(), field.numeric(), sensitive, field.modified(), byAssistant.contains(field.start()),
                    sensitive ? MaskSnapshot.MASKED : trimEnd(image.text(field)), label));
        }
        List<String> lines = new ArrayList<>(image.rows());
        for (int row = 0; row < image.rows(); row++) {
            lines.add(trimEnd(new String(shown, row * columns, columns)));
        }
        return new MaskSnapshot(session.id(), revision, image.rows(), columns, image.cursor() / columns + 1,
                image.cursor() % columns + 1, image.keyboardLocked(), lines, fields);
    }

    private static void blank(char[] cells, TerminalMask.Field field) {
        for (int i = 0; i < field.length(); i++) {
            cells[(field.start() + i) % cells.length] = ' ';
        }
    }

    static boolean sensitive(TerminalMask.Field field, String label) {
        return field.hidden() || label != null && SENSITIVE.matcher(label).find();
    }

    /**
     * Vermutete Beschriftungen: der geschützte Text links vor dem Feld in derselben Zeile (bis zum vorigen
     * Eingabefeld), ohne Füllpunkte und Pfeile wie {@code ===>}; ohne Text links der Text direkt darüber.
     */
    static Map<Integer, String> labels(TerminalMask.ScreenImage image, List<TerminalMask.Field> inputs) {
        int columns = image.columns();
        boolean[] input = new boolean[image.cells().length];
        for (TerminalMask.Field field : inputs) {
            for (int i = -1; i < field.length(); i++) {
                // -1: auch die Attributzelle gehört nicht zur Beschriftung
                input[Math.floorMod(field.start() + i, input.length)] = true;
            }
        }
        Map<Integer, String> labels = new HashMap<>();
        for (int i = 0; i < inputs.size(); i++) {
            TerminalMask.Field field = inputs.get(i);
            int row = field.start() / columns;
            int column = field.start() % columns - 1;
            StringBuilder left = new StringBuilder();
            for (int c = column - 1; c >= 0 && !input[row * columns + c]; c--) {
                left.insert(0, image.cells()[row * columns + c]);
            }
            String label = clean(left.toString());
            if (label.isEmpty() && row > 0) {
                StringBuilder above = new StringBuilder();
                int from = Math.max(0, column);
                for (int c = from; c < Math.min(columns, from + field.length() + 1); c++) {
                    above.append(input[(row - 1) * columns + c] ? ' ' : image.cells()[(row - 1) * columns + c]);
                }
                label = clean(above.toString());
            }
            labels.put(i + 1, label);
        }
        return labels;
    }

    private static String clean(String text) {
        String cleaned = text.replaceAll("[.]{2,}", " ").replaceAll("=+>", " ").replaceAll("[ ]+", " ").strip();
        cleaned = cleaned.replaceAll("^[:. ]+|[:. ]+$", "").strip();
        return cleaned;
    }

    /** Kennung der Maske: Aufbau der Felder und alle geschützten Texte, nicht die Werte der Eingabefelder. */
    static String revision(TerminalMask.ScreenImage image) {
        StringBuilder b = new StringBuilder();
        b.append(image.rows()).append('x').append(image.columns()).append('|');
        boolean[] input = new boolean[image.cells().length];
        for (TerminalMask.Field field : image.fields()) {
            b.append(field.start()).append(',').append(field.length()).append(',')
                    .append(field.protectedField() ? 'p' : 'u').append(field.numeric() ? 'n' : 'a')
                    .append(field.hidden() ? 'h' : 'v').append(';');
            if (!field.protectedField()) {
                for (int i = 0; i < field.length(); i++) {
                    input[(field.start() + i) % input.length] = true;
                }
            }
        }
        b.append('|');
        char[] cells = image.cells();
        for (int i = 0; i < cells.length; i++) {
            b.append(input[i] ? ' ' : cells[i]);
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 6).toUpperCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String trimEnd(String text) {
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == ' ' || text.charAt(end - 1) == 0)) {
            end--;
        }
        return text.substring(0, end);
    }
}
