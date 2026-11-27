package com.aresstack.askai.browser.playwright.camoufox;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What Camoufox is started with: fingerprint environment ({@code CAMOU_CONFIG_1..n}, {@code CAMOU_PREFS_1..n},
 * {@code FONTCONFIG_FILE}), Firefox prefs and arguments. Either from a launch.json the operator names explicitly
 * ({@code BROWSER_LAUNCH_CONFIG}), or from Argos' built-in profile {@code camoufox/default-launch.json}.
 *
 * @param environment only the Camoufox keys; the caller adds them to the inherited process environment
 * @param fingerprint the joined {@code CAMOU_CONFIG} object (for the window size real input needs)
 * @param proxy the launch.json {@code proxy} element, {@link JsonNull} when absent and for the built-in profile
 */
public record CamoufoxLaunch(Map<String, String> environment, JsonObject fingerprint, Map<String, Object> prefs,
                             List<String> args, JsonElement proxy) {

    static final String DEFAULT_PROFILE = "/camoufox/default-launch.json";
    /** Compact and without Gson's HTML escaping, so the environment carries the JSON as Argos wrote it. */
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** An explicitly configured launch.json; a missing or invalid file is an error, never a silent fallback. */
    public static CamoufoxLaunch fromFile(Path file) throws IOException {
        JsonElement launch;
        try {
            launch = parse(Files.readString(file));
        } catch (NoSuchFileException missing) {
            throw new IllegalStateException("BROWSER_LAUNCH_CONFIG / browser.launch-config names " + file
                    + ", which does not exist. Remove the setting to use the built-in Camoufox profile.");
        } catch (IOException | JsonParseException invalid) {
            throw new IllegalArgumentException("Camoufox launch options " + file + " are not valid JSON: "
                    + invalid.getClass().getSimpleName());
        }
        if (launch == null || !launch.isJsonObject()) {
            throw new IllegalArgumentException("Camoufox launch options " + file + " must be a JSON object.");
        }
        JsonObject options = launch.getAsJsonObject();
        JsonElement variables = options.get("env");
        if (variables == null || !variables.isJsonObject() || !variables.getAsJsonObject().has("CAMOU_CONFIG_1")) {
            throw new IllegalArgumentException("Official Camoufox fingerprint file requires env.CAMOU_CONFIG_1");
        }
        Map<String, String> environment = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : variables.getAsJsonObject().entrySet()) {
            if (!entry.getKey().startsWith("CAMOU_") && !entry.getKey().startsWith("FONTCONFIG")) {
                throw new IllegalArgumentException("Unexpected fingerprint environment key: " + entry.getKey());
            }
            if (!isString(entry.getValue())) {
                throw new IllegalArgumentException("Invalid fingerprint environment value: " + entry.getKey());
            }
            environment.put(entry.getKey(), entry.getValue().getAsString());
        }
        StringBuilder json = new StringBuilder();
        for (int part = 1; environment.containsKey("CAMOU_CONFIG_" + part); part++) {
            json.append(environment.get("CAMOU_CONFIG_" + part));
        }
        JsonObject fingerprint;
        try {
            JsonElement joined = parse(json.toString());
            fingerprint = joined.isJsonObject() ? joined.getAsJsonObject() : new JsonObject();
        } catch (IOException | JsonParseException unreadable) {
            fingerprint = new JsonObject();
        }
        JsonObject preferences = options.has("firefoxUserPrefs") ? object(options, "firefoxUserPrefs")
                : object(options, "firefox_user_prefs");
        JsonElement proxy = options.has("proxy") ? options.get("proxy") : JsonNull.INSTANCE;
        return new CamoufoxLaunch(environment, fingerprint, prefs(preferences), args(array(options, "args")), proxy);
    }

    /**
     * Argos' built-in profile for the Camoufox at {@code executable}. Add-ons it names are taken from
     * {@code <executable dir>/addons/<name>} (shipped in the Argos distribution). On Linux the fonts of the
     * fingerprint's OS come from the bundle's fontconfig; the generated fonts.conf is written to {@code runtimeDir}.
     */
    public static CamoufoxLaunch builtIn(Path executable, Path runtimeDir) throws IOException {
        return builtIn(defaultProfile(), executable, runtimeDir, System.getProperty("os.name"));
    }

    /**
     * AskAI: the built-in profile in the user's language instead of its fixed en-US. Search engines answer in
     * the browser's language, and the SERP analysis was tuned on the pages Chrome got in the system
     * language (an English Bing page reads "About 1,230,000 results", which contains the no-results
     * marker "0 results"). Sets the requested locale, Accept-Language and Camoufox's locale fingerprint.
     */
    public static CamoufoxLaunch builtIn(Path executable, Path runtimeDir, Locale language) throws IOException {
        return builtIn(localized(defaultProfile(), language), executable, runtimeDir, System.getProperty("os.name"));
    }

    static JsonObject localized(JsonObject profile, Locale language) {
        String tag = language.toLanguageTag();
        String primary = language.getLanguage();
        if (primary.isEmpty() || "und".equals(tag)) return profile;
        String accept = tag.equals(primary) ? primary + ",en-US;q=0.7,en;q=0.3"
                : tag + "," + primary + ";q=0.8,en-US;q=0.5,en;q=0.3";
        JsonObject prefs = putObject(profile, "firefoxUserPrefs");
        prefs.addProperty("intl.locale.requested", tag);
        prefs.addProperty("intl.accept_languages", accept);
        JsonObject config = putObject(profile, "config");
        config.addProperty("locale:language", primary);
        if (!language.getCountry().isEmpty()) config.addProperty("locale:region", language.getCountry());
        config.addProperty("locale:all", accept.replaceAll(";q=[0-9.]+", ""));
        config.addProperty("headers.Accept-Language", accept);
        return profile;
    }

    static JsonObject defaultProfile() throws IOException {
        try (InputStream in = CamoufoxLaunch.class.getResourceAsStream(DEFAULT_PROFILE)) {
            if (in == null) throw new IllegalStateException(DEFAULT_PROFILE + " missing from the browser-playwright jar");
            return parse(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    static CamoufoxLaunch builtIn(JsonObject profile, Path executable, Path runtimeDir, String hostOs)
            throws IOException {
        Path browserDir = executable.toAbsolutePath().getParent();
        JsonObject config = object(profile, "config").deepCopy();
        JsonArray addons = new JsonArray();
        config.add("addons", addons);
        for (JsonElement entry : array(profile, "bundledAddons")) {
            String name = text(entry);
            Path addon = browserDir.resolve("addons").resolve(name);
            if (!Files.isDirectory(addon)) {
                throw new IllegalStateException("The built-in Camoufox profile loads the add-on " + name
                        + ", which this Camoufox does not ship in " + addon.getParent() + ". Use the Argos Camoufox "
                        + "distribution, or name your own launch.json in BROWSER_LAUNCH_CONFIG.");
            }
            addons.add(addon.toString());
        }
        boolean windows = hostOs.toLowerCase(Locale.ROOT).startsWith("windows");
        Map<String, Object> prefs = prefs(object(profile, "firefoxUserPrefs"));
        Map<String, String> environment = new LinkedHashMap<>();
        // Same chunking as the camoufox launcher (utils.get_env_vars): Windows caps one variable at 2047 chars.
        int chunk = windows ? 2047 : 32767;
        environment.putAll(chunks("CAMOU_CONFIG_", GSON.toJson(config), chunk));
        // Prefs Gecko reads during startup only apply through settings/camoufox.cfg (utils.get_pref_env_vars),
        // ASCII-escaped because autoconfig reads the environment through the ANSI code page on Windows.
        if (!prefs.isEmpty()) {
            String json = escapeNonAscii(GSON.toJson(prefs));
            environment.putAll(chunks("CAMOU_PREFS_", json, chunk));
        }
        if (!windows) {
            environment.put("FONTCONFIG_FILE", fontconfig(browserDir, fontOs(config), runtimeDir).toString());
        }
        return new CamoufoxLaunch(environment, config, prefs, args(array(profile, "args")), JsonNull.INSTANCE);
    }

    /**
     * AskAI: this launch with one more unpacked add-on directory in {@code CAMOU_CONFIG.addons}, which Camoufox loads
     * at startup like the bundled ones (the AskAI Research add-on, docs/research-browser-addon.md). The
     * {@code CAMOU_CONFIG_1..n} chunks are rebuilt from the fingerprint; every other variable stays as it is.
     */
    public CamoufoxLaunch withAddon(Path addon) {
        return withAddon(addon, System.getProperty("os.name"));
    }

    CamoufoxLaunch withAddon(Path addon, String hostOs) {
        JsonObject config = fingerprint.deepCopy();
        JsonArray addons = new JsonArray();
        JsonElement existing = config.get("addons");
        if (existing != null && existing.isJsonArray()) addons.addAll(existing.getAsJsonArray());
        addons.add(addon.toAbsolutePath().toString());
        config.add("addons", addons);
        Map<String, String> next = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            if (!entry.getKey().startsWith("CAMOU_CONFIG_")) next.put(entry.getKey(), entry.getValue());
        }
        int chunk = hostOs.toLowerCase(Locale.ROOT).startsWith("windows") ? 2047 : 32767;
        next.putAll(chunks("CAMOU_CONFIG_", GSON.toJson(config), chunk));
        return new CamoufoxLaunch(next, config, prefs, args, proxy);
    }

    /**
     * Every character above US-ASCII as a JSON unicode escape (uppercase hex, as Jackson's ESCAPE_NON_ASCII wrote
     * it). Outside string literals JSON is pure ASCII, so escaping the serialized text is the same as escaping the
     * strings in it.
     */
    static String escapeNonAscii(String json) {
        StringBuilder escaped = new StringBuilder(json.length());
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c < 0x80) escaped.append(c);
            else escaped.append(String.format(Locale.ROOT, "\\u%04X", (int) c));
        }
        return escaped.toString();
    }

    /** {@code CAMOU_CONFIG_1..n}: the JSON split at {@code size} characters, never inside a surrogate pair. */
    static Map<String, String> chunks(String prefix, String json, int size) {
        Map<String, String> parts = new LinkedHashMap<>();
        int part = 1;
        for (int start = 0; start < json.length(); part++) {
            int end = Math.min(json.length(), start + size);
            if (end < json.length() && Character.isHighSurrogate(json.charAt(end - 1))) end--;
            parts.put(prefix + part, json.substring(start, end));
            start = end;
        }
        return parts;
    }

    private static String fontOs(JsonObject config) {
        String platform = text(config.get("navigator.platform")).toLowerCase(Locale.ROOT);
        if (platform.startsWith("win")) return "windows";
        if (platform.startsWith("mac")) return "macos";
        return "linux";
    }

    /**
     * The camoufox launcher's {@code _generate_fontconfig}: the bundle's {@code fontconfig/<os>/fonts.conf} with
     * its relative font directory replaced by the absolute font groups that OS reads ({@code fonts/groups.json}).
     */
    static Path fontconfig(Path browserDir, String os, Path runtimeDir) throws IOException {
        Path source = browserDir.resolve("fontconfig").resolve(os).resolve("fonts.conf");
        if (!Files.isRegularFile(source)) source = browserDir.resolve("fontconfigs").resolve(os).resolve("fonts.conf");
        if (!Files.isRegularFile(source)) {
            throw new IllegalStateException("fonts.conf for " + os + " missing in the Camoufox bundle " + browserDir);
        }
        Path fonts = browserDir.resolve("fonts");
        List<String> dirs = new ArrayList<>();
        Path groups = fonts.resolve("groups.json");
        String key = switch (os) { case "windows" -> "win"; case "macos" -> "mac"; default -> "lin"; };
        if (Files.isRegularFile(groups)) {
            JsonElement groupsJson = parse(Files.readString(groups));
            JsonObject readBy = groupsJson.isJsonObject() ? object(groupsJson.getAsJsonObject(), "readBy") : new JsonObject();
            for (JsonElement entry : array(readBy, key)) {
                String group = text(entry);
                if (Files.isDirectory(fonts.resolve(group))) dirs.add(fonts.resolve(group).toString());
            }
        }
        if (dirs.isEmpty()) dirs.add(Files.isDirectory(fonts.resolve(os)) ? fonts.resolve(os).toString() : fonts.toString());
        StringBuilder replacement = new StringBuilder();
        for (String dir : dirs) {
            if (!replacement.isEmpty()) replacement.append("\n\t");
            replacement.append("<dir>").append(dir).append("</dir>");
        }
        String conf = Files.readString(source).replace("<dir prefix=\"cwd\">fonts</dir>", replacement);
        byte[] bytes = conf.getBytes(StandardCharsets.UTF_8);
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 12);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        Path target = runtimeDir.resolve("fontconfig").resolve("fonts-" + hash + ".conf");
        if (!Files.isRegularFile(target)) {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), "fonts-", ".tmp");
            Files.write(temporary, bytes);
            Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }
        return target;
    }

    private static Map<String, Object> prefs(JsonObject preferences) {
        Map<String, Object> prefs = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : preferences.entrySet()) {
            JsonElement value = entry.getValue();
            JsonPrimitive primitive = value.isJsonPrimitive() ? value.getAsJsonPrimitive() : null;
            if (primitive != null && primitive.isBoolean()) prefs.put(entry.getKey(), primitive.getAsBoolean());
            else if (primitive != null && primitive.isNumber() && primitive.getAsString().matches("-?[0-9]+")) {
                prefs.put(entry.getKey(), primitive.getAsInt());
            } else if (primitive != null && primitive.isString()) prefs.put(entry.getKey(), primitive.getAsString());
            else throw new IllegalArgumentException("Unsupported Firefox pref: " + entry.getKey());
        }
        return prefs;
    }

    private static List<String> args(JsonArray values) {
        List<String> args = new ArrayList<>();
        values.forEach(value -> args.add(text(value)));
        return args;
    }

    /**
     * Strict JSON (no comments, no single quotes, no trailing content), as the Jackson reader this code had
     * before; Gson's own entry points parse leniently.
     */
    static JsonElement parse(String json) throws IOException {
        return parse(new StringReader(json));
    }

    static JsonElement parse(Reader json) throws IOException {
        JsonReader reader = new JsonReader(json);
        reader.setStrictness(Strictness.STRICT);
        JsonElement element = JsonParser.parseReader(reader);
        if (reader.peek() != JsonToken.END_DOCUMENT) {
            throw new JsonParseException("trailing content after the JSON document");
        }
        return element;
    }

    /** The object member {@code key}, or an empty object when it is missing or not an object. */
    static JsonObject object(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    /** The array member {@code key}, or an empty array when it is missing or not an array. */
    static JsonArray array(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    /** A primitive's text, otherwise {@code ""} (missing, null, object, array). */
    static String text(JsonElement value) {
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static boolean isString(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }

    /** The object member {@code key}; a missing or non-object member is replaced by a new object. */
    private static JsonObject putObject(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        if (value != null && value.isJsonObject()) return value.getAsJsonObject();
        JsonObject created = new JsonObject();
        parent.add(key, created);
        return created;
    }
}
