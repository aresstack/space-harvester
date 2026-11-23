package com.aresstack.enterpriseai.browser.api;

/**
 * One operable element of a page snapshot that is not a plain link: a search or text field, a button, a select box,
 * a menu entry, a tab, a toggle. {@code kind} is one of {@code search}, {@code input}, {@code button}, {@code select},
 * {@code checkbox}, {@code radio}, {@code menu}, {@code tab} and {@code toggle}. {@code target} is the address a form
 * submit goes to when the page declares one, else empty.
 */
public final class BrowserControl {

    private final String id;
    private final String kind;
    private final String label;
    private final String value;
    private final String options;
    private final String target;
    private final String form;
    private final boolean required;
    private final String locator;
    private final String position;

    public BrowserControl(String id, String kind, String label, String value, String options, String target) {
        this(id, kind, label, value, options, target, "", false, "", "");
    }

    /**
     * With the control's context: {@code form} the name of the form or group it belongs to, {@code required} whether
     * the page marks it as mandatory, {@code locator} how a person would name it ({@code role=button name="Speichern"},
     * {@code label="Betrag"}), {@code position} where it sits in the viewport ({@code oben rechts}); empty when unknown.
     */
    public BrowserControl(String id, String kind, String label, String value, String options, String target, String form,
                          boolean required, String locator, String position) {
        this.id = id == null ? "" : id;
        this.kind = kind == null ? "" : kind;
        this.label = label == null ? "" : label;
        this.value = value == null ? "" : value;
        this.options = options == null ? "" : options;
        this.target = target == null ? "" : target;
        this.form = form == null ? "" : form;
        this.required = required;
        this.locator = locator == null ? "" : locator;
        this.position = position == null ? "" : position;
    }

    public String id() {
        return id;
    }

    public String kind() {
        return kind;
    }

    public String label() {
        return label;
    }

    /** The current value of a field or the state of a toggle ({@code an}/{@code aus}), else empty. */
    public String value() {
        return value;
    }

    /** The visible options of a select box, separated by {@code " | "}, else empty. */
    public String options() {
        return options;
    }

    public String target() {
        return target;
    }

    /** The name of the form or group the control belongs to, else empty. */
    public String form() {
        return form;
    }

    /** Whether the page marks the field as mandatory. */
    public boolean required() {
        return required;
    }

    /** How a person would name the control: role and accessible name, or label, placeholder, text; else empty. */
    public String locator() {
        return locator;
    }

    /** Where the control sits in the viewport ({@code oben rechts}, {@code Mitte}), else empty. */
    public String position() {
        return position;
    }
}
