package com.aresstack.spaceharvester.app.chat.artifact;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Änderungen der Zeichenfläche am SVG (ADR 0045 §6): verschieben, Größe ändern, Form anlegen, Farbe setzen. Reine
 * DOM-Logik ohne Oberfläche, damit sie ohne Bildschirm prüfbar ist; Koordinaten kommen schon im Raum des
 * Elternelements an. Verschieben und Größe wirken über {@code transform}, damit jede Art Element gleich behandelt wird.
 */
public final class SvgEditing {

    public static final String SVG_NS = "http://www.w3.org/2000/svg";
    private static final Pattern LEADING_TRANSLATE = Pattern.compile(
            "^\\s*translate\\(\\s*(-?[\\d.eE+-]+)[\\s,]+(-?[\\d.eE+-]+)\\s*\\)\\s*");

    private SvgEditing() {
    }

    /**
     * Verschiebt um {@code dx}, {@code dy} gegenüber {@code original} (dem {@code transform} vor dem Ziehen); ein
     * führendes {@code translate} wird zusammengefasst, damit sich beim Ziehen nichts anhäuft.
     */
    public static void translate(Element element, String original, double dx, double dy) {
        String rest = original == null ? "" : original.strip();
        double x = dx;
        double y = dy;
        Matcher match = LEADING_TRANSLATE.matcher(rest);
        if (match.find()) {
            x += Double.parseDouble(match.group(1));
            y += Double.parseDouble(match.group(2));
            rest = rest.substring(match.end());
        }
        set(element, "transform", (x == 0 && y == 0 ? "" : "translate(" + number(x) + " " + number(y) + ") ") + rest);
    }

    /** Skaliert um {@code sx}, {@code sy} mit festem Punkt {@code ax}, {@code ay}, gegenüber {@code original}. */
    public static void scale(Element element, String original, double ax, double ay, double sx, double sy) {
        String rest = original == null ? "" : original.strip();
        String scale = "scale(" + number(sx) + " " + number(sy) + ")";
        boolean origin = number(ax).equals(number(0)) && number(ay).equals(number(0));
        set(element, "transform", (origin ? scale : "translate(" + number(ax) + " " + number(ay) + ") " + scale
                + " translate(" + number(-ax) + " " + number(-ay) + ")") + (rest.isEmpty() ? "" : " " + rest));
    }

    /** Legt eine Form als letztes Kind von {@code parent} an (zuoberst gezeichnet). */
    public static Element add(Document document, Element parent, String tag) {
        Element element = document.createElementNS(SVG_NS, tag);
        parent.appendChild(element);
        return element;
    }

    /**
     * Setzt die Lage einer neuen Form aus zwei Ecken: Rechteck und Ellipse füllen den Kasten, eine Linie verbindet die
     * Punkte.
     */
    public static void span(Element element, double x1, double y1, double x2, double y2) {
        double left = Math.min(x1, x2);
        double top = Math.min(y1, y2);
        double width = Math.abs(x2 - x1);
        double height = Math.abs(y2 - y1);
        switch (element.getLocalName() == null ? element.getTagName() : element.getLocalName()) {
            case "rect" -> {
                set(element, "x", number(left));
                set(element, "y", number(top));
                set(element, "width", number(width));
                set(element, "height", number(height));
            }
            case "ellipse" -> {
                set(element, "cx", number(left + width / 2));
                set(element, "cy", number(top + height / 2));
                set(element, "rx", number(width / 2));
                set(element, "ry", number(height / 2));
            }
            case "line" -> {
                set(element, "x1", number(x1));
                set(element, "y1", number(y1));
                set(element, "x2", number(x2));
                set(element, "y2", number(y2));
            }
            default -> {
                set(element, "x", number(x1));
                set(element, "y", number(y1));
            }
        }
    }

    /** Setzt ein Merkmal; leer entfernt es. */
    public static void set(Element element, String attribute, String value) {
        if (value == null || value.isBlank()) {
            element.removeAttribute(attribute);
        } else {
            element.setAttribute(attribute, value.strip());
        }
    }

    /** Eine Farbe als {@code #rrggbb}. */
    public static String color(java.awt.Color color) {
        return String.format(Locale.ROOT, "#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    /** Eine Zahl kurz geschrieben: ganze ohne Nachkommastellen, sonst höchstens zwei. */
    static String number(double value) {
        double rounded = Math.round(value * 100) / 100.0;
        if (rounded == Math.rint(rounded)) {
            return Long.toString((long) rounded);
        }
        return String.format(Locale.ROOT, "%s", rounded);
    }
}
