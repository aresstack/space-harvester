package com.aresstack.enterpriseai.browser.api.engine;

/**
 * One control of a rendered page as the engine read it. {@code ref} is the engine's handle of the element within the
 * current document; the other fields mean the same as in {@code BrowserControl}.
 */
public final class RawControl {

    private final String ref;
    private final String kind;
    private final String label;
    private final String value;
    private final String options;
    private final String target;
    private final String form;
    private final boolean required;
    private final String locator;
    private final String position;

    public RawControl(String ref, String kind, String label, String value, String options, String target) {
        this(ref, kind, label, value, options, target, "", false, "", "");
    }

    /**
     * With the control's context: {@code form} the name of the form or group it belongs to, {@code required} whether
     * the page marks it as mandatory, {@code locator} how a person would name it ({@code role=button name="Speichern"},
     * {@code label="Betrag"}), {@code position} where it sits in the viewport ({@code oben rechts}); empty when unknown.
     */
    public RawControl(String ref, String kind, String label, String value, String options, String target, String form,
                      boolean required, String locator, String position) {
        this.ref = ref == null ? "" : ref;
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

    public String ref() {
        return ref;
    }

    public String kind() {
        return kind;
    }

    public String label() {
        return label;
    }

    public String value() {
        return value;
    }

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
