package com.aresstack.spaceharvester.deck.terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Eine Maus-Aktion (mit Zusatztaste) löst eine F-Taste aus, wie {@code MouseFkeyBinding} in MainframeMate: Scrollrad
 * hoch/runter blättert mit F7/F8, Shift+Scrollrad mit F10/F11, die Seitentaste „Zurück“ ist F3. Die Liste ist in den
 * Einstellungen änderbar; abgeschaltete Zeilen bleiben stehen, lösen aber nichts aus.
 *
 * @param action   Maustaste oder Scrollrichtung
 * @param modifier Zusatztaste, die dabei gedrückt sein muss (genau diese, keine weitere)
 * @param fkey     F-Taste 1 bis 24
 * @param enabled  ob die Zeile gilt
 */
public record MouseFkeyBinding(MouseAction action, Modifier modifier, int fkey, boolean enabled) {

    /** Maus-Aktionen; „Zurück“ und „Vorwärts“ sind die Seitentasten (4 und 5) der Maus. */
    public enum MouseAction {
        BACK("Maus Zurück"),
        FORWARD("Maus Vorwärts"),
        SCROLL_UP("Scroll hoch"),
        SCROLL_DOWN("Scroll runter");

        private final String label;

        MouseAction(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Zusatztasten wie in MainframeMate. */
    public enum Modifier {
        NONE("Keine"),
        SHIFT("Shift"),
        CTRL("Strg"),
        ALT("Alt"),
        CTRL_SHIFT("Strg+Shift"),
        CTRL_ALT("Strg+Alt"),
        SHIFT_ALT("Shift+Alt");

        private final String label;

        Modifier(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }

        /** Ob genau diese Zusatztasten gedrückt sind (modifierMatches in MainframeMate). */
        public boolean matches(boolean shift, boolean ctrl, boolean alt) {
            return switch (this) {
                case NONE -> !shift && !ctrl && !alt;
                case SHIFT -> shift && !ctrl && !alt;
                case CTRL -> !shift && ctrl && !alt;
                case ALT -> !shift && !ctrl && alt;
                case CTRL_SHIFT -> shift && ctrl && !alt;
                case CTRL_ALT -> !shift && ctrl && alt;
                case SHIFT_ALT -> shift && !ctrl && alt;
            };
        }
    }

    public MouseFkeyBinding {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(modifier, "modifier");
        if (fkey < 1 || fkey > 24) {
            throw new IllegalArgumentException("F-Taste gibt es nur von 1 bis 24: " + fkey);
        }
    }

    public MouseFkeyBinding(MouseAction action, Modifier modifier, int fkey) {
        this(action, modifier, fkey, true);
    }

    /** Die Vorgaben aus MainframeMate (getDefaults). */
    public static List<MouseFkeyBinding> defaults() {
        return List.of(
                new MouseFkeyBinding(MouseAction.BACK, Modifier.NONE, 3),
                new MouseFkeyBinding(MouseAction.SCROLL_DOWN, Modifier.NONE, 8),
                new MouseFkeyBinding(MouseAction.SCROLL_UP, Modifier.NONE, 7),
                new MouseFkeyBinding(MouseAction.SCROLL_DOWN, Modifier.SHIFT, 11),
                new MouseFkeyBinding(MouseAction.SCROLL_UP, Modifier.SHIFT, 10));
    }

    /** Die erste gültige Zeile für diese Aktion und Zusatztasten, sonst 0. */
    public static int fkeyFor(List<MouseFkeyBinding> bindings, MouseAction action, boolean shift, boolean ctrl,
                              boolean alt) {
        for (MouseFkeyBinding binding : bindings) {
            if (binding.enabled() && binding.action() == action && binding.modifier().matches(shift, ctrl, alt)) {
                return binding.fkey();
            }
        }
        return 0;
    }

    /** Als Text zum Merken: Zeilen mit „;“ getrennt, je Zeile {@code AKTION:ZUSATZ:F:an}. */
    public static String encode(List<MouseFkeyBinding> bindings) {
        StringBuilder text = new StringBuilder();
        for (MouseFkeyBinding binding : bindings) {
            if (!text.isEmpty()) {
                text.append(';');
            }
            text.append(binding.action().name()).append(':').append(binding.modifier().name()).append(':')
                    .append(binding.fkey()).append(':').append(binding.enabled());
        }
        return text.toString();
    }

    /** Liest {@link #encode}; ohne Text die Vorgaben, unlesbare Zeilen fallen weg. */
    public static List<MouseFkeyBinding> decode(String text) {
        if (text == null) {
            return defaults();
        }
        List<MouseFkeyBinding> bindings = new ArrayList<>();
        for (String line : text.split(";")) {
            String[] parts = line.trim().split(":");
            if (parts.length < 3) {
                continue;
            }
            try {
                bindings.add(new MouseFkeyBinding(MouseAction.valueOf(parts[0]), Modifier.valueOf(parts[1]),
                        Integer.parseInt(parts[2]), parts.length < 4 || Boolean.parseBoolean(parts[3])));
            } catch (IllegalArgumentException unreadable) {
                // alte oder kaputte Zeile: überspringen
            }
        }
        return List.copyOf(bindings);
    }
}
