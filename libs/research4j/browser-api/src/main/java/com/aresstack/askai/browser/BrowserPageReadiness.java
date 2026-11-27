package com.aresstack.askai.browser;

/**
 * A cheap readability PROBE of a page BEFORE it is read as a source: the signals a caller (or an LLM judge)
 * needs to decide whether the page can be read now, or whether a cookie/consent banner or a manual challenge
 * (CAPTCHA) is in the way. This is deliberately NOT the full page text — only the final URL/title, the body
 * text length, a short excerpt, and the two obstruction signals. It is rendered to a typed line block for the
 * {@code web_probe} MCP tool and parsed back on the host with {@link #parse}.
 */
public final class BrowserPageReadiness {

    /** Upper bound of the excerpt carried in a probe (never the full body). */
    public static final int EXCERPT_LIMIT = 400;

    public final String url;
    public final String title;
    public final int textLength;
    public final String excerpt;
    /**
     * A challenge ARTIFACT (CAPTCHA / "one last step" / recaptcha widget) exists somewhere in the DOM — visible
     * OR hidden. On its own this does NOT mean the user must solve anything: many readable pages embed a hidden
     * recaptcha (contact-form spam protection). Use {@link #challengeVisible} to decide whether it blocks.
     */
    public final boolean challengePresent;
    /**
     * The artifact is actually VISIBLE and blocking (laid out, not display:none/visibility:hidden/opacity:0, has
     * a real box intersecting the viewport). Only a visible challenge yields INTERACTIVE_CHALLENGE; a present-but-
     * hidden one leaves a readable page READABLE (the reactree false-positive fix).
     */
    public final boolean challengeVisible;
    /** The marker that matched the challenge ("visible:&lt;sel&gt;" / "visible:text:&lt;text&gt;" block; "embedded:&lt;sel&gt;" / "hidden:&lt;sel&gt;" / "hidden:text:&lt;text&gt;" do not), or "" when none. */
    public final String challengeMarker;
    /** A consent/cookie banner with a dismissible positive control is present. */
    public final boolean consentPresent;
    /** A human-readable hint at the dismiss control (its text or selector), or "" when none. */
    public final String consentCandidate;

    /** Backward-compatible ctor: an unspecified visibility defaults to {@code challengePresent} (old behavior). */
    public BrowserPageReadiness(String url, String title, int textLength, String excerpt,
                                boolean challengePresent, String challengeMarker,
                                boolean consentPresent, String consentCandidate) {
        this(url, title, textLength, excerpt, challengePresent, challengePresent, challengeMarker,
                consentPresent, consentCandidate);
    }

    public BrowserPageReadiness(String url, String title, int textLength, String excerpt,
                                boolean challengePresent, boolean challengeVisible, String challengeMarker,
                                boolean consentPresent, String consentCandidate) {
        this.url = url == null ? "" : url;
        this.title = title == null ? "" : title;
        this.textLength = textLength;
        this.excerpt = excerpt == null ? "" : excerpt;
        this.challengePresent = challengePresent;
        this.challengeVisible = challengeVisible;
        this.challengeMarker = challengeMarker == null ? "" : challengeMarker;
        this.consentPresent = consentPresent;
        this.consentCandidate = consentCandidate == null ? "" : consentCandidate;
    }

    /** Bound a body text to a probe-sized single-line excerpt. */
    public static String excerptOf(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ').replace('\r', ' ').trim();
        return flat.length() <= EXCERPT_LIMIT ? flat : flat.substring(0, EXCERPT_LIMIT);
    }

    /** A typed, single-value-per-line rendering for the {@code web_probe} tool result. */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("url: ").append(url).append('\n');
        sb.append("title: ").append(title).append('\n');
        sb.append("text_length: ").append(textLength).append('\n');
        sb.append("challenge: ").append(challengePresent).append('\n');
        sb.append("challenge_visible: ").append(challengeVisible).append('\n');
        sb.append("challenge_marker: ").append(challengeMarker).append('\n');
        sb.append("consent: ").append(consentPresent).append('\n');
        sb.append("consent_candidate: ").append(consentCandidate).append('\n');
        sb.append("excerpt: ").append(excerptOf(excerpt));
        return sb.toString();
    }

    /** Parse a {@link #render()} block back into a value (missing keys default to empty/false/0). */
    public static BrowserPageReadiness parse(String rendered) {
        String url = "";
        String title = "";
        int textLength = 0;
        String excerpt = "";
        boolean challenge = false;
        Boolean challengeVisible = null; // absent → defaults to `challenge` (old renders had no visibility axis)
        String challengeMarker = "";
        boolean consent = false;
        String consentCandidate = "";
        if (rendered != null) {
            for (String line : rendered.split("\n")) {
                int colon = line.indexOf(": ");
                if (colon < 0) {
                    if (line.endsWith(":")) {
                        colon = line.length() - 2; // an empty value ("key:")
                    } else {
                        continue;
                    }
                }
                String key = line.substring(0, colon).trim();
                String value = colon + 2 <= line.length() ? line.substring(colon + 2) : "";
                if ("url".equals(key)) {
                    url = value;
                } else if ("title".equals(key)) {
                    title = value;
                } else if ("text_length".equals(key)) {
                    textLength = parseIntSafe(value);
                } else if ("challenge".equals(key)) {
                    challenge = Boolean.parseBoolean(value.trim());
                } else if ("challenge_visible".equals(key)) {
                    challengeVisible = Boolean.valueOf(Boolean.parseBoolean(value.trim()));
                } else if ("challenge_marker".equals(key)) {
                    challengeMarker = value;
                } else if ("consent".equals(key)) {
                    consent = Boolean.parseBoolean(value.trim());
                } else if ("consent_candidate".equals(key)) {
                    consentCandidate = value;
                } else if ("excerpt".equals(key)) {
                    excerpt = value;
                }
            }
        }
        return new BrowserPageReadiness(url, title, textLength, excerpt, challenge,
                challengeVisible == null ? challenge : challengeVisible.booleanValue(), challengeMarker,
                consent, consentCandidate);
    }

    private static int parseIntSafe(String v) {
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
