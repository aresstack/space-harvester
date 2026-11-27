package com.aresstack.askai.browser.search.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Search language codes as the engine configuration stores them: a BCP-47 language subtag, optionally followed by
 * a region ({@code zh-CN}) or script ({@code zh-Hant}) subtag. Codes are normalised (language lower case, region
 * upper case, script title case, {@code _} read as {@code -}), duplicates removed, and a code that is not of this
 * form is reported instead of being stored.
 * <p>
 * The language of a search is a property of ONE engine configuration (one row of Search → Engines), not of the
 * research mission: the same research may search Google in German and Yandex in Russian. Region/country stays a
 * separate setting.
 */
public final class SearchLanguages {

    private static final Pattern CODE = Pattern.compile("[a-z]{2,3}(-([A-Z]{2}|[0-9]{3}|[A-Z][a-z]{3}))?");

    /**
     * The languages offered as check boxes in the engine table, with their English names. Any other well-formed
     * code is entered as a "further language".
     */
    private static final Map<String, String> PICKER;

    static {
        Map<String, String> picker = new LinkedHashMap<String, String>();
        picker.put("de", "German");
        picker.put("en", "English");
        picker.put("fr", "French");
        picker.put("es", "Spanish");
        picker.put("it", "Italian");
        picker.put("ru", "Russian");
        picker.put("zh-CN", "Chinese (simplified)");
        picker.put("zh-TW", "Chinese (traditional)");
        picker.put("ja", "Japanese");
        picker.put("ko", "Korean");
        picker.put("cs", "Czech");
        picker.put("sk", "Slovak");
        picker.put("vi", "Vietnamese");
        picker.put("tr", "Turkish");
        PICKER = Collections.unmodifiableMap(picker);
    }

    private SearchLanguages() {
    }

    /** The codes of the picker languages, in display order. */
    public static List<String> pickerCodes() {
        return Collections.unmodifiableList(new ArrayList<String>(PICKER.keySet()));
    }

    /** Whether the code is one of the picker languages (after normalisation). */
    public static boolean isPickerLanguage(String code) {
        String normalized = normalize(code);
        return normalized != null && PICKER.containsKey(normalized);
    }

    /** The English name of a picker language, else the code itself. */
    public static String displayName(String code) {
        String normalized = normalize(code);
        String name = normalized == null ? null : PICKER.get(normalized);
        return name != null ? name : String.valueOf(code).trim();
    }

    /**
     * The normalised form of one code, or {@code null} when it is not a well-formed language code
     * ({@code " ZH_cn "} becomes {@code "zh-CN"}, {@code "zh-hant"} becomes {@code "zh-Hant"}).
     */
    public static String normalize(String code) {
        if (code == null) {
            return null;
        }
        String text = code.trim().replace('_', '-');
        if (text.isEmpty()) {
            return null;
        }
        String[] parts = text.split("-", -1);
        if (parts.length > 2) {
            return null;
        }
        StringBuilder sb = new StringBuilder(parts[0].toLowerCase(Locale.ROOT));
        if (parts.length == 2) {
            String sub = parts[1];
            sb.append('-');
            if (sub.length() == 4) {
                sb.append(sub.substring(0, 1).toUpperCase(Locale.ROOT)).append(sub.substring(1).toLowerCase(Locale.ROOT));
            } else {
                sb.append(sub.toUpperCase(Locale.ROOT));
            }
        }
        String normalized = sb.toString();
        return CODE.matcher(normalized).matches() ? normalized : null;
    }

    /** The outcome of reading a list of codes: the clean, de-duplicated codes and the pieces that were rejected. */
    public static final class Parsed {
        private final List<String> codes;
        private final List<String> rejected;

        Parsed(List<String> codes, List<String> rejected) {
            this.codes = Collections.unmodifiableList(new ArrayList<String>(codes));
            this.rejected = Collections.unmodifiableList(new ArrayList<String>(rejected));
        }

        public List<String> getCodes() {
            return codes;
        }

        /** The input pieces that are not well-formed codes, as typed (trimmed). */
        public List<String> getRejected() {
            return rejected;
        }
    }

    /** Read codes separated by commas, semicolons, spaces or {@code |}: {@code "pl, uk; id"}. */
    public static Parsed parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new Parsed(Collections.<String>emptyList(), Collections.<String>emptyList());
        }
        return parse(Arrays.asList(text.trim().split("[,;|\\s]+")));
    }

    /** Normalise each code, drop duplicates (first occurrence wins) and collect what is not a code. */
    public static Parsed parse(Collection<String> pieces) {
        Set<String> codes = new LinkedHashSet<String>();
        List<String> rejected = new ArrayList<String>();
        if (pieces != null) {
            for (String piece : pieces) {
                if (piece == null || piece.trim().isEmpty()) {
                    continue;
                }
                String normalized = normalize(piece);
                if (normalized == null) {
                    rejected.add(piece.trim());
                } else {
                    codes.add(normalized);
                }
            }
        }
        return new Parsed(new ArrayList<String>(codes), rejected);
    }

    /** {@code [de, en]} as {@code "de, en"} — how the table shows a language list. */
    public static String join(Collection<String> codes) {
        StringBuilder sb = new StringBuilder();
        if (codes != null) {
            for (String code : codes) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(code);
            }
        }
        return sb.toString();
    }
}
