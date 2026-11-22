package com.aresstack.spaceharvester.deck.ftp;

import java.util.List;
import java.util.Locale;

/**
 * Pfade auf MVS-Hosts, übernommen aus MainframeMate ({@code MvsPathDialect}, dort im Einsatz erprobt): absolute Namen
 * stehen in Hochkommas ({@code 'HLQ.DATA.SET'}), Members in Klammern ({@code 'HLQ.PDS(MEMBER)'}).
 */
public final class MvsPath {

    /** Die Wurzel; sie lässt sich nicht auflisten, man braucht einen HLQ. */
    public static final String ROOT = "''";

    private MvsPath() {
    }

    /** Macht einen Namen absolut: Hochkommas drum, {@code /} wird zu {@code .}. */
    public static String absolute(String path) {
        if (path == null) {
            return ROOT;
        }
        String trimmed = path.trim();
        if (trimmed.isEmpty() || "/".equals(trimmed)) {
            return ROOT;
        }
        if (trimmed.startsWith("'") && trimmed.endsWith("'") && trimmed.length() >= 2) {
            return trimmed;
        }
        String normalized = trimmed.replace('/', '.');
        if (normalized.startsWith("'")) {
            return normalized + "'";
        }
        if (normalized.endsWith("'")) {
            return "'" + normalized;
        }
        return "'" + normalized + "'";
    }

    /** Ohne Hochkommas. */
    public static String unquote(String path) {
        if (path == null) {
            return "";
        }
        String trimmed = path.trim();
        if (trimmed.startsWith("'") && trimmed.endsWith("'") && trimmed.length() >= 2) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    /** Ob der Pfad ein Member bezeichnet: {@code DATA.SET(MEMBER)}. */
    public static boolean isMember(String path) {
        String unquoted = unquote(path);
        int open = unquoted.indexOf('(');
        int close = unquoted.indexOf(')');
        return open > 0 && close == unquoted.length() - 1 && open < close;
    }

    /** Dataset und Member eines Member-Pfads. */
    public static String[] splitMember(String path) {
        if (!isMember(path)) {
            throw new IllegalArgumentException("Kein Member-Pfad: " + path);
        }
        String unquoted = unquote(path);
        int open = unquoted.indexOf('(');
        return new String[] {unquoted.substring(0, open), unquoted.substring(open + 1, unquoted.length() - 1)};
    }

    /** {@code DATASET(MEMBER)} ohne Hochkommas. */
    public static String memberSpec(String dataset, String member) {
        String ds = dataset == null ? "" : dataset.trim();
        String mem = member == null ? "" : member.trim();
        if (ds.isEmpty()) {
            return mem;
        }
        return mem.isEmpty() ? ds : ds + "(" + mem + ")";
    }

    /**
     * Mögliche absolute Pfade zum Lesen und Schreiben: {@code DATASET.MEMBER} wird zuerst als
     * {@code DATASET(MEMBER)} versucht, dann wörtlich.
     */
    public static List<String> candidates(String path) {
        String trimmed = path == null ? "" : path.trim();
        if (trimmed.isEmpty() || "/".equals(trimmed)) {
            return List.of(ROOT);
        }
        if (trimmed.startsWith("'") || isMember(trimmed)) {
            return List.of(absolute(trimmed));
        }
        if (trimmed.contains(".")) {
            int last = trimmed.lastIndexOf('.');
            return List.of(absolute(memberSpec(trimmed.substring(0, last), trimmed.substring(last + 1))),
                    absolute(trimmed));
        }
        return List.of(absolute(trimmed));
    }

    /**
     * Pfad eines Kindes in einer Liste: unter einem HLQ mit Punkt, unter einem Dataset als Member, wenn der Name wie
     * ein Member aussieht, sonst mit Punkt. Schon qualifizierte Namen bleiben, wie sie sind.
     */
    public static String child(String parent, String name) {
        String rawParent = unquote(parent);
        String child = name == null ? "" : name.trim();
        if (rawParent.isEmpty()) {
            return absolute(child);
        }
        if (child.isEmpty()) {
            return absolute(rawParent);
        }
        if (child.contains(".") || child.contains("(") || child.startsWith("'")) {
            String unquotedChild = unquote(child);
            if (unquotedChild.toUpperCase(Locale.ROOT).startsWith(rawParent.toUpperCase(Locale.ROOT) + ".")
                    || unquotedChild.contains(".")) {
                return absolute(unquotedChild);
            }
        }
        boolean parentIsHlq = !rawParent.contains(".") && !rawParent.contains("(");
        if (parentIsHlq) {
            return absolute(rawParent + "." + child);
        }
        if (isMemberName(child) && !rawParent.contains("(")) {
            return absolute(memberSpec(rawParent, child));
        }
        return absolute(rawParent + "." + child);
    }

    /** Der übergeordnete Pfad: Member → Dataset, {@code A.B.C} → {@code A.B}, HLQ → Wurzel. */
    public static String parent(String path) {
        String unquoted = unquote(path);
        if (unquoted.isEmpty()) {
            return ROOT;
        }
        if (isMember(unquoted)) {
            return absolute(splitMember(unquoted)[0]);
        }
        int dot = unquoted.lastIndexOf('.');
        return dot < 0 ? ROOT : absolute(unquoted.substring(0, dot));
    }

    /** Member-Namen: 1 bis 8 Zeichen, Buchstaben, Ziffern und {@code @ # $}. */
    public static boolean isMemberName(String name) {
        if (name == null || name.isEmpty() || name.length() > 8) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '@' && c != '#' && c != '$') {
                return false;
            }
        }
        return true;
    }
}
