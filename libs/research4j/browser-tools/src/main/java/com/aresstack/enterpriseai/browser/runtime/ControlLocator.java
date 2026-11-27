package com.aresstack.enterpriseai.browser.runtime;

import com.aresstack.enterpriseai.browser.api.BrowserException;
import com.aresstack.enterpriseai.browser.api.engine.RawControl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a control by the way a person names it: {@code role=button name="Speichern"}, {@code label="Betrag"} or a
 * plain visible text. Matches against the controls of the rendered page, so a control found this way passes the same
 * checks as one picked from a list; never a CSS or XPath selector.
 */
final class ControlLocator {

    private static final Pattern ROLE = Pattern.compile("role\\s*=\\s*(\\S+)\\s+name\\s*=\\s*\"(.*)\"\\s*");
    private static final Pattern LABEL = Pattern.compile("(?:label|text|placeholder)\\s*=\\s*\"(.*)\"\\s*");

    private ControlLocator() {
    }

    /**
     * The control {@code locator} names among {@code controls}: an equal locator, else an equal label (with a fitting
     * role when one is given), else the only label containing the name.
     */
    static RawControl find(List<RawControl> controls, String locator) throws BrowserException {
        String wanted = locator == null ? "" : locator.trim();
        if (wanted.isEmpty()) {
            throw new BrowserException(BrowserException.Kind.INVALID_REQUEST, "locator fehlt");
        }
        String role = null;
        String name;
        Matcher m = ROLE.matcher(wanted);
        Matcher l = LABEL.matcher(wanted);
        if (m.matches()) {
            role = m.group(1).toLowerCase(Locale.ROOT);
            name = m.group(2);
        } else if (l.matches()) {
            name = l.group(1);
        } else {
            name = wanted;
        }
        String needle = normalize(name);
        List<RawControl> exact = new ArrayList<RawControl>();
        List<RawControl> partial = new ArrayList<RawControl>();
        for (RawControl control : controls) {
            if (normalize(control.locator()).equals(normalize(wanted))) {
                return control;
            }
            if (role != null && !fits(role, control)) {
                continue;
            }
            String label = normalize(control.label());
            if (label.equals(needle)) {
                exact.add(control);
            } else if (!needle.isEmpty() && label.contains(needle)) {
                partial.add(control);
            }
        }
        List<RawControl> best = exact.isEmpty() ? partial : exact;
        if (best.size() == 1) {
            return best.get(0);
        }
        if (best.isEmpty()) {
            throw new BrowserException(BrowserException.Kind.INVALID_REQUEST, "Kein Bedienelement passt zu "
                    + wanted + "; browser_controls zeigt, was die Seite anbietet");
        }
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < best.size() && i < 3; i++) {
            names.append(i == 0 ? "" : ", ").append('„').append(best.get(i).label()).append('“');
        }
        throw new BrowserException(BrowserException.Kind.INVALID_REQUEST, "Mehrere Bedienelemente passen zu "
                + wanted + " (" + names + "); genauer benennen oder die Id aus browser_controls nehmen");
    }

    /** Whether {@code control}'s kind fits the ARIA {@code role} a locator names. */
    private static boolean fits(String role, RawControl control) {
        String kind = control.kind();
        if (control.locator().toLowerCase(Locale.ROOT).startsWith("role=" + role + " ")) {
            return true;
        }
        switch (role) {
            case "button":
                return "button".equals(kind) || "toggle".equals(kind);
            case "textbox":
            case "searchbox":
            case "combobox":
                return "input".equals(kind) || "search".equals(kind) || "select".equals(kind);
            case "menuitem":
                return "menu".equals(kind);
            default:
                return role.equals(kind);
        }
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
