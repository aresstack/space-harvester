package com.aresstack.enterpriseai.ui.comic.theme;

import java.awt.Color;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * THE semantic color token table of the comic controls — the one place every surface takes its colors
 * from (mechanism moved from askai's {@code AskAiTheme}). Every token has a key of the form
 * {@code group.role} ({@code comic.surface}, {@code research.DARK_BASE}, {@code bubble.userBackground}, …)
 * and is readable via {@link #color(String)}. The typed accessors are views of the same table.
 *
 * <p>One built-in theme exists: {@link #classic()}, the historic look, every value identical to the
 * former hardcoded colors. Further themes are pure color tables registered by name through
 * {@link #registerTheme(String, Map)}. Hosts add tokens of their own surfaces with
 * {@link #registerTokens(String, Map)} (the library must not know them); a theme without a host token
 * falls back to the classic value. The active theme can be switched at runtime
 * ({@link #setActive(AskAiTheme)}); consumers holding {@link ThemeColor}s follow automatically.</p>
 */
public final class AskAiTheme {

    /** JVM start property selecting the theme. */
    public static final String PROPERTY = "askai.theme";

    public static final String CLASSIC = "classic";

    /** The theme used when the property is missing or unknown. */
    static final String DEFAULT_THEME = CLASSIC;

    /** Key prefix of the research group; the rest of the key is the ResearchUiPalette constant name. */
    static final String RESEARCH_PREFIX = "research.";

    private static volatile AskAiTheme active;
    private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<Runnable>();
    /** Host tokens and further themes: theme name → (key → color). */
    private static final ConcurrentMap<String, Map<String, Color>> EXTRA_TOKENS =
            new ConcurrentHashMap<String, Map<String, Color>>();
    private static final ConcurrentMap<String, Map<String, Color>> REGISTERED_THEMES =
            new ConcurrentHashMap<String, Map<String, Color>>();

    private final String name;
    private final Map<String, Color> tokens;
    private final ComicPalette comicPalette;

    private AskAiTheme(String name, Map<String, Color> tokens) {
        this.name = name;
        this.tokens = Collections.unmodifiableMap(new LinkedHashMap<String, Color>(tokens));
        this.comicPalette = new ComicPalette(color("comic.surface"), color("comic.ink"),
                color("comic.navigationBlue"), color("comic.agentPetrol"),
                color("comic.accentYellow"), color("comic.accentOrange"), color("comic.accentRed"));
    }

    /** The historic look — every value identical to the pre-theme hardcoded colors. */
    public static AskAiTheme classic() {
        return build(CLASSIC, classicTable());
    }

    private static Map<String, Color> classicTable() {
        Map<String, Color> t = new LinkedHashMap<String, Color>();
        put(t, "comic.surface", 0xF4F4F4);
        put(t, "comic.ink", 0x252525);
        put(t, "comic.navigationBlue", 0x1676D2);
        put(t, "comic.agentPetrol", 0x15827A);
        put(t, "comic.accentYellow", 0xF2C94C);
        put(t, "comic.accentOrange", 0xE39A18);
        put(t, "comic.accentRed", 0xC94C4C);
        put(t, "comic.buttonPlate", 0xFFFFFF);
        put(t, "comic.warmFocus", 0xFFF8E1);
        put(t, "comic.fieldBg", 0xFFFFFF);
        put(t, "comic.fieldBgQuiet", 0xFAFAFA);
        put(t, "comic.fieldBorder", 0xC6C6C6);
        put(t, "comic.hintText", 0x888888);
        put(t, "comic.hintTextSoft", 0xAAAAAA);
        t.put("comic.overlayDim", new Color(0, 0, 0, 110));
        put(t, "comic.placeholderText", 0x6B6F76);
        // the find bar contour; a theme sets it together with comic.fieldBorder (askai folds both into one value)
        put(t, "comic.searchBarBorder", 0xBBBBBB);
        put(t, "chat.composerButtonDisabledFill", 0xB7BBC1);
        put(t, "chat.composerButtonDisabledText", 0xA5A9AE);
        put(t, "chat.composerButtonHoverShade", 0x000000);
        put(t, "comic.checkBorderShade", 0x000000);
        put(t, "comic.folderPaper", 0xFFF1C2);
        put(t, "bubble.transcriptBackground", 0xF4F4F4);
        put(t, "bubble.userBackground", 0x1676D2);
        put(t, "bubble.userForeground", 0xFFFFFF);
        put(t, "bubble.assistantBackground", 0x15827A);
        put(t, "bubble.assistantForeground", 0xFFFFFF);
        put(t, "bubble.activityBackground", 0xF2C94C);
        put(t, "bubble.activityForeground", 0x252525);
        put(t, "bubble.activityAccent", 0xE39A18);
        put(t, "bubble.failureAccent", 0xC94C4C);
        put(t, "bubble.infoForeground", 0x6B6F76);

        put(t, "research.ACCENT_BLUE", 0x2979FF);
        put(t, "research.BORDER_DARK", 0x5E6370);
        put(t, "research.BORDER_WINDOW", 0x38404D);
        put(t, "research.CHIP_CLOSE_HOVER", 0x2A3039);
        put(t, "research.CHIP_HOVER_BORDER", 0x767D89);
        put(t, "research.CHIP_HOVER_SURFACE", 0x151B23);
        put(t, "research.CHIP_SURFACE", 0x0E1218);
        put(t, "research.CLOUD_BORDER", 0xAFCDE8);
        put(t, "research.CLOUD_CLOSE", 0x72879A);
        put(t, "research.CLOUD_HOVER_BORDER", 0x8FB8DE);
        put(t, "research.CLOUD_HOVER_SURFACE", 0xEAF4FC);
        put(t, "research.CLOUD_SURFACE", 0xF7FBFF);
        put(t, "research.CLOUD_TEXT", 0x40556B);
        put(t, "research.COMPASS_RED", 0xF03A3A);
        put(t, "research.DANGER_RED", 0xD32F2F);
        put(t, "research.DARK_BASE", 0x0C1117);
        put(t, "research.DARK_SURFACE", 0x12181F);
        put(t, "research.FAVORITE_YELLOW", 0xFFC800);
        put(t, "research.FAVORITE_YELLOW_EDGE", 0xC99A00);
        put(t, "research.FIND_AMBER", 0xF57C00);
        put(t, "research.LIGHT_CONTROL_BORDER", 0xC9CED6);
        put(t, "research.LIGHT_CONTROL_TEXT", 0x44484D);
        put(t, "research.LIGHT_TEXT_MUTED", 0x8A909B);
        put(t, "research.RECORDING_ORANGE", 0xF57C00);
        put(t, "research.RECORDING_SURFACE", 0xFFF8E1);
        put(t, "research.SECONDARY_HOVER", 0x202832);
        put(t, "research.SECONDARY_SURFACE", 0x141A21);
        put(t, "research.SKY_BAR_SURFACE", 0xBFDCF2);
        put(t, "research.SKY_CAPTION", 0x6E87A2);
        put(t, "research.SKY_TOP", 0xF5FAFE);
        put(t, "research.TAG_SUGGESTION_SURFACE", 0xF2C94C);
        put(t, "research.TAG_SUGGESTION_TEXT", 0x252525);
        put(t, "research.TEXT_DARK", 0x171C29);
        put(t, "research.TEXT_MUTED", 0xADB5C7);
        put(t, "research.TEXT_PRIMARY", 0xE8EBF2);
        put(t, "research.WAVE_GREEN", 0x2E7D32);
        put(t, "research.WORKSPACE_DIVIDER", 0x282E38);
        put(t, "research.WORKSPACE_SURFACE", 0x1B2028);
        put(t, "research.SCROLL_PAPER", 0xFFFFFF);
        put(t, "research.LIGHT_CONTROL_BG", 0xFFFFFF);
        t.put("research.ACRONYM_MARK", new Color(0xF4, 0x8F, 0xB1, 72));
        return t;
    }

    /** A registered theme (name → pure color table), completed by the classic values for missing tokens. */
    private static AskAiTheme build(String name, Map<String, Color> own) {
        Map<String, Color> merged = new LinkedHashMap<String, Color>(CLASSIC_BASE);
        merged.putAll(own);
        return new AskAiTheme(name, merged);
    }

    private static final Map<String, Color> CLASSIC_BASE = Collections.unmodifiableMap(classicTable());

    /**
     * Registers a further theme as a pure color table (token key → color). Missing tokens fall back to
     * the classic values. Names are case-insensitive. The name {@code classic} is reserved.
     * @throws IllegalArgumentException for the reserved name
     */
    public static void registerTheme(String themeName, Map<String, Color> table) {
        if (CLASSIC.equals(key(themeName))) {
            throw new IllegalArgumentException("the default theme \"" + CLASSIC + "\" cannot be replaced");
        }
        REGISTERED_THEMES.put(key(themeName), new LinkedHashMap<String, Color>(table));
    }

    /**
     * Adds the tokens of a host's own surfaces for one theme ({@code classic} is the fallback of every
     * other theme). Call at startup before the first {@link ThemeColor#of(String)} of those keys.
     */
    public static void registerTokens(String themeName, Map<String, Color> table) {
        Map<String, Color> existing = EXTRA_TOKENS.get(key(themeName));
        Map<String, Color> merged = existing == null
                ? new LinkedHashMap<String, Color>() : new LinkedHashMap<String, Color>(existing);
        merged.putAll(table);
        EXTRA_TOKENS.put(key(themeName), merged);
    }

    private static String key(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** The names of all known themes, classic first. */
    public static java.util.List<String> themeNames() {
        java.util.List<String> names = new java.util.ArrayList<String>();
        names.add(CLASSIC);
        for (String registered : REGISTERED_THEMES.keySet()) {
            if (!names.contains(registered)) {
                names.add(registered);
            }
        }
        return names;
    }

    /**
     * Pure theme resolution: trims and lower-cases the value; a known theme name selects that theme,
     * anything else (missing, empty, unknown) yields the default theme.
     */
    public static AskAiTheme resolve(String value) {
        String key = key(value);
        Map<String, Color> own = REGISTERED_THEMES.get(key);
        if (own != null) {
            return build(key, own);
        }
        return classic();
    }

    /**
     * The active theme: the one last set via {@link #setActive(AskAiTheme)}; before that, resolved once
     * from {@value #PROPERTY} (missing: the default).
     */
    public static AskAiTheme active() {
        AskAiTheme theme = active;
        if (theme == null) {
            synchronized (AskAiTheme.class) {
                theme = active;
                if (theme == null) {
                    theme = resolve(System.getProperty(PROPERTY));
                    active = theme;
                }
            }
        }
        return theme;
    }

    /**
     * Makes {@code theme} the active one (any number of times, on the EDT). Every {@link ThemeColor}
     * reads the new values from its next paint on. Deliberately SILENT: the host's switch gate
     * (re-install the UIManager defaults, refresh the windows) calls {@link #fireChanged()} afterwards.
     */
    public static void setActive(AskAiTheme theme) {
        if (theme == null) {
            throw new IllegalArgumentException("theme must not be null");
        }
        synchronized (AskAiTheme.class) {
            active = theme;
        }
    }

    /** Registers a listener for theme switches (see {@link #fireChanged()}); duplicates are ignored. */
    public static void addChangeListener(Runnable listener) {
        if (listener != null) {
            LISTENERS.addIfAbsent(listener);
        }
    }

    public static void removeChangeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    /** Notifies every change listener once; called by the host after a switch is fully applied. */
    public static void fireChanged() {
        for (Runnable listener : LISTENERS) {
            listener.run();
        }
    }

    public String name() {
        return name;
    }

    /** Any token by its full key ({@code group.role}); unknown keys are rejected. */
    public Color color(String key) {
        Color color = lookup(key);
        if (color == null) {
            throw new IllegalArgumentException("unknown theme token: " + key);
        }
        return color;
    }

    /** Whether the table has a token with this key. */
    public boolean hasToken(String key) {
        return lookup(key) != null;
    }

    /** Own table, then the host tokens registered for this theme, then the host tokens of classic. */
    private Color lookup(String key) {
        Color color = tokens.get(key);
        if (color == null) {
            Map<String, Color> host = EXTRA_TOKENS.get(name);
            color = host == null ? null : host.get(key);
        }
        if (color == null) {
            Map<String, Color> classicHost = EXTRA_TOKENS.get(CLASSIC);
            color = classicHost == null ? null : classicHost.get(key);
        }
        return color;
    }

    /** The library tokens of this theme (key → color), in table order; host tokens are not listed. */
    public Map<String, Color> tokens() {
        return tokens;
    }

    public ComicPalette comicPalette() {
        return comicPalette;
    }

    /** Resting fill of comic button plates (and other lifted light chips). */
    public Color buttonPlate() {
        return color("comic.buttonPlate");
    }

    /** The warm cream focus surface of the amber family (find bar, search tag). */
    public Color warmFocus() {
        return color("comic.warmFocus");
    }

    /** Light input field background. */
    public Color fieldBg() {
        return color("comic.fieldBg");
    }

    /** The quieter field background of the find bar at rest. */
    public Color fieldBgQuiet() {
        return color("comic.fieldBgQuiet");
    }

    /** The one neutral field contour. */
    public Color fieldBorder() {
        return color("comic.fieldBorder");
    }

    /** Icon / hint grey. */
    public Color hintText() {
        return color("comic.hintText");
    }

    /** Softer placeholder / inactive grey. */
    public Color hintTextSoft() {
        return color("comic.hintTextSoft");
    }

    /** Translucent backdrop behind overlays. */
    public Color overlayDim() {
        return color("comic.overlayDim");
    }

    /** A bubble token by role ({@code transcriptBackground}, {@code userBackground}, ...). */
    public Color bubble(String role) {
        return color("bubble." + role);
    }

    /** Muted but readable placeholder text on light plates (about 5:1). */
    public Color placeholderText() {
        return color("comic.placeholderText");
    }

    /** A research-side token, keyed by its {@link ResearchUiPalette} constant name. */
    public Color research(String constantName) {
        Color color = tokens.get(RESEARCH_PREFIX + constantName);
        if (color == null) {
            throw new IllegalArgumentException("unknown research token: " + constantName);
        }
        return color;
    }

    /**
     * All {@link ResearchUiPalette} tokens (constant name → color), in table order: the research keys whose
     * role is an UPPER_SNAKE constant name. Role tokens of the research plugin ({@code research.someRole},
     * lowerCamel) are regular tokens without a palette constant.
     */
    public Map<String, Color> researchTokens() {
        Map<String, Color> research = new LinkedHashMap<String, Color>();
        for (Map.Entry<String, Color> e : tokens.entrySet()) {
            if (e.getKey().startsWith(RESEARCH_PREFIX)) {
                String name = e.getKey().substring(RESEARCH_PREFIX.length());
                if (name.matches("[A-Z][A-Z0-9_]*")) {
                    research.put(name, e.getValue());
                }
            }
        }
        return Collections.unmodifiableMap(research);
    }

    private static void put(Map<String, Color> tokens, String key, int rgb) {
        tokens.put(key, new Color(rgb));
    }
}
