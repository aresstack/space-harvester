package com.aresstack.spaceharvester.provingground.player;

import com.aresstack.spaceharvester.provingground.model.TestCase;
import com.aresstack.spaceharvester.provingground.model.TestStep;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A test case as an ordinary JUnit 5 test with the Playwright Java API, runnable without Proving Ground: Playwright's
 * own frame of a recording in Java ({@code Playwright.create()}, launch, {@code browser.newContext()}, as its
 * JavaLanguageGenerator writes it) around the recorded code of every step, in a {@code @Test} method.
 *
 * <p>Playwright's own JUnit target uses {@code @UsePlaywright}, which Playwright4j does not provide; so the frame is
 * the library one inside the test method. The recorded code stays as Playwright wrote it; only string literals with
 * {@code {{...}}} are wrapped in {@code v(...)}, which takes the values from environment variables, never from the
 * file. Users work as in a run: each user gets an own browser context, {@code {{user.login}}},
 * {@code {{secret.<field>}}} and {@code {{otp()}}} (one-time code, RFC 6238 as {@code run.Totp}) are those of the
 * step's user. Screenshot comparisons and page functions ({@code js}, {@code text}, {@code url}, {@code title}) need
 * Proving Ground; such steps are written as comments and the export says so.</p>
 */
public final class JUnitExport {

    /**
     * How the exported test starts the browser: Playwright type, channel and executable; {@code timeoutMillis} is each
     * context's default timeout for actions (0 = Playwright's default).
     */
    public record Launch(boolean firefox, String channel, String executable, long timeoutMillis) {

        public Launch(boolean firefox, String channel, String executable) {
            this(firefox, channel, executable, 0);
        }
    }

    /**
     * What the one running the exported test hands in: {@code values} ({{...}}-text and user → value), {@code parts}
     * (told which test case the following steps come from: a precondition or the case itself; may be null) and
     * {@code started} (gets the test's Playwright as soon as it runs, to close it when the run is stopped; may be null).
     */
    public record Hooks(java.util.function.BiFunction<String, String, String> values,
                        java.util.function.Consumer<String> parts,
                        java.util.function.Consumer<AutoCloseable> started) {
    }

    /** The exported class and what it could not take over. */
    public record Result(String className, String source, List<String> notExported) {
    }

    static final String NOT_EXPORTED = "// nicht exportiert (braucht Proving Ground): ";
    static final String EXECUTABLE_PROPERTY = "playwright.executable";

    private JUnitExport() {
    }

    public static Result export(TestCase testCase, Launch launch) {
        return export(testCase, java.util.Collections.nCopies(testCase.size(), null), launch);
    }

    /**
     * @param stepUsers the user each step runs as (by index; null = without user), as the run decides it
     */
    public static Result export(TestCase testCase, List<String> stepUsers, Launch launch) {
        return export(testCase, stepUsers, null, launch);
    }

    /**
     * @param stepUsers the user each step runs as (by index; null = without user), as the run decides it
     * @param stepParts the test case each step comes from (by index; a precondition or the case), null = one case
     */
    public static Result export(TestCase testCase, List<String> stepUsers, List<String> stepParts, Launch launch) {
        String className = className(testCase.name());
        List<String> notExported = new ArrayList<>();
        java.util.Set<String> users = new java.util.LinkedHashSet<>();
        java.util.Set<String> parts = new java.util.LinkedHashSet<>();
        for (int i = 0; i < testCase.size(); i++) {
            users.add(userAt(stepUsers, i));
            parts.add(stepParts == null ? null : userAt(stepParts, i));
        }
        boolean switching = users.size() > 1;
        boolean joined = parts.size() > 1;
        String timeout = launch.timeoutMillis() > 0 ? "context.setDefaultTimeout(" + launch.timeoutMillis() + ");\n" : "";
        String firstUser = users.isEmpty() ? null : users.iterator().next();
        StringBuilder body = new StringBuilder();
        java.util.Set<String> declared = new java.util.HashSet<>();
        java.util.Map<String, String> renames = new java.util.HashMap<>();
        // which user's context a page variable belongs to, and each user's variable for a recorded page
        java.util.Map<String, String> owners = new java.util.HashMap<>();
        java.util.Map<String, String> ofUser = new java.util.HashMap<>();
        String current = null;
        String part = null;
        boolean first = true;
        for (int i = 0; i < testCase.size(); i++) {
            TestStep step = testCase.step(i);
            String user = userAt(stepUsers, i);
            String stepPart = stepParts == null ? null : userAt(stepParts, i);
            if (joined && (first || !java.util.Objects.equals(stepPart, part))) {
                body.append("part(").append(stepPart == null ? "null" : TestStep.javaString(stepPart)).append(");\n");
            }
            part = stepPart;
            if (switching && (first || !java.util.Objects.equals(user, current))) {
                // each user in an own browser context, as Proving Ground runs it
                body.append("user = ").append(user == null ? "null" : TestStep.javaString(user)).append(";\n")
                        .append("context = contexts.computeIfAbsent(String.valueOf(user), name -> browser.newContext());\n")
                        .append(timeout);
            }
            current = user;
            first = false;
            String code = step.code();
            String why = code == null || code.isBlank() ? "kein Playwright-Code"
                    : code.contains("screenshots.") ? "Bildvergleich"
                    : OTHER_FUNCTION.matcher(code).find() ? "Ausdruck mit Seitenfunktion (js, text, url, title)" : null;
            if (why != null) {
                String note = (i + 1) + ". " + step.describe() + " – " + why;
                notExported.add(note);
                body.append(NOT_EXPORTED).append(note.replace('\n', ' ')).append('\n');
                continue;
            }
            List<String> own = PlaywrightCode.declaredPages(code);
            // steps of a precondition and of the case both declare "page": the later one gets a fresh name
            for (String alias : own) {
                if (declared.contains(alias)) {
                    renames.put(alias, fresh(alias, declared, renames));
                }
                String variable = renames.getOrDefault(alias, alias);
                declared.add(variable);
                owners.put(variable, String.valueOf(user));
                ofUser.put(alias + "\n" + user, variable);
            }
            if (switching) {
                // a step of another user on a page recorded before: that user's page, as Proving Ground plays it
                for (String alias : PlaywrightCode.otherPages(code, own)) {
                    String variable = renames.getOrDefault(alias, alias);
                    if (!declared.contains(variable)) {
                        // a page the test opens at the start (case without openPage): the first user's
                        owners.put(variable, String.valueOf(firstUser));
                        ofUser.putIfAbsent(alias + "\n" + firstUser, variable);
                    }
                    String owner = owners.get(variable);
                    if (java.util.Objects.equals(owner, String.valueOf(user))) {
                        continue;
                    }
                    String mine = ofUser.get(alias + "\n" + user);
                    if (mine == null) {
                        mine = fresh(alias, declared, renames);
                        body.append("Page ").append(mine)
                                .append(" = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);\n");
                        declared.add(mine);
                        owners.put(mine, String.valueOf(user));
                        ofUser.put(alias + "\n" + user, mine);
                    }
                    renames.put(alias, mine);
                }
            }
            body.append(PlaywrightCode.withVariables(renamed(code.strip(), renames), "v")).append('\n');
        }
        String steps = body.toString();
        StringBuilder pages = new StringBuilder();
        for (String alias : PlaywrightCode.otherPages(steps, PlaywrightCode.declaredPages(steps))) {
            pages.append("Page ").append(alias).append(" = context.newPage();\n");
        }
        String start;
        if (switching) {
            // the first user's context comes first, then the pages a case without openPage needs
            int afterFirst = steps.indexOf('\n', steps.indexOf("context = ")) + 1;
            afterFirst = timeout.isEmpty() ? afterFirst : steps.indexOf('\n', afterFirst) + 1;
            steps = steps.substring(0, afterFirst) + pages + steps.substring(afterFirst);
            start = "Map<String, BrowserContext> contexts = new HashMap<>();\nBrowserContext context;\n";
        } else {
            start = (firstUser == null ? "" : "user = " + TestStep.javaString(firstUser) + ";\n")
                    + "BrowserContext context = browser.newContext();\n" + timeout + pages;
        }
        String source = TEMPLATE.formatted(testCase.name().replace("*/", "* /"), className,
                launch.firefox() ? "firefox" : "chromium", launchOptions(launch), indent(start + steps, "      "));
        return new Result(className, source, notExported);
    }

    /** A page variable name not used yet ({@code page_2}, {@code page_3}, …; never matched as a recorded page). */
    private static String fresh(String alias, java.util.Set<String> declared, java.util.Map<String, String> renames) {
        String fresh = alias;
        for (int n = 2; declared.contains(fresh) || renames.containsValue(fresh); n++) {
            fresh = alias + "_" + n;
        }
        return fresh;
    }

    /** What the exported code uses directly; their JARs make the compiler's class path. */
    private static final List<Class<?>> ANCHORS = List.of(com.microsoft.playwright.Page.class,
            com.microsoft.playwright.assertions.PlaywrightAssertions.class,
            com.microsoft.playwright.options.AriaRole.class, org.junit.jupiter.api.Test.class);

    /**
     * Runs the exported test as JUnit would call it, with the values from {@code values} ({{...}}-text and user →
     * value): Space Harvester hands in what it reads from KeePass, the test itself never reads it. A step that could not
     * be exported fails the run before it starts.
     */
    public static void run(Result result, java.util.function.BiFunction<String, String, String> values)
            throws Throwable {
        run(result, new Hooks(values, null, null));
    }

    /** Runs the exported test as JUnit would call it, with what {@code hooks} hands in (see {@link Hooks}). */
    public static void run(Result result, Hooks hooks) throws Throwable {
        if (!result.notExported().isEmpty()) {
            throw new IllegalStateException("nicht als JUnit-Test ausführbar: " + String.join(" · ", result.notExported()));
        }
        InMemoryCompiler.Compiled compiled = InMemoryCompiler.compile(result.className(), result.source(),
                JUnitExport.class.getClassLoader(), PlaywrightCode.classpath(), ANCHORS);
        if (compiled.type() == null) {
            throw new IllegalStateException("JUnit-Test nicht übersetzbar: " + (compiled.failure() != null
                    ? compiled.failure() : compiled.errors().stream().map(e -> "Zeile " + e.line() + ": " + e.message())
                    .collect(java.util.stream.Collectors.joining(" · "))));
        }
        Class<?> type = compiled.type();
        type.getField("values").set(null, hooks.values());
        type.getField("parts").set(null, hooks.parts());
        type.getField("started").set(null, hooks.started());
        java.lang.reflect.Method test = type.getDeclaredMethod("test");
        test.setAccessible(true);
        try {
            test.invoke(type.getDeclaredConstructor().newInstance());
        } catch (java.lang.reflect.InvocationTargetException failed) {
            throw failed.getCause();
        }
    }

    private static final java.util.regex.Pattern LITERAL = java.util.regex.Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");

    /** Renames page variables in {@code code}, never inside string literals. */
    static String renamed(String code, java.util.Map<String, String> renames) {
        if (renames.isEmpty()) {
            return code;
        }
        StringBuilder result = new StringBuilder();
        java.util.regex.Matcher literal = LITERAL.matcher(code);
        int at = 0;
        while (literal.find()) {
            result.append(renamedOutside(code.substring(at, literal.start()), renames)).append(literal.group());
            at = literal.end();
        }
        return result.append(renamedOutside(code.substring(at), renames)).toString();
    }

    private static String renamedOutside(String text, java.util.Map<String, String> renames) {
        String result = text;
        for (java.util.Map.Entry<String, String> rename : renames.entrySet()) {
            result = result.replaceAll("\\b" + java.util.regex.Pattern.quote(rename.getKey()) + "\\b",
                    java.util.regex.Matcher.quoteReplacement(rename.getValue()));
        }
        return result;
    }

    private static String userAt(List<String> stepUsers, int index) {
        String user = index < stepUsers.size() ? stepUsers.get(index) : null;
        return user == null || user.isBlank() ? null : user;
    }

    /** Functions of a run that need Proving Ground's page access ({@code otp} is exported). */
    private static final java.util.regex.Pattern OTHER_FUNCTION =
            java.util.regex.Pattern.compile("\\{\\{\\s*(js|text|url|title)\\s*\\(");

    private static final String TEMPLATE = """
            import com.microsoft.playwright.*;
            import com.microsoft.playwright.options.*;
            import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
            import java.nio.ByteBuffer;
            import java.nio.file.Paths;
            import java.util.*;
            import java.util.regex.*;
            import javax.crypto.Mac;
            import javax.crypto.spec.SecretKeySpec;
            import org.junit.jupiter.api.Test;

            /**
             * Aus Proving Ground exportiert: %s
             *
             * <p>Werte stehen nie in dieser Datei. Führt Space Harvester den Test aus, übergibt es sie (values: Benutzer,
             * Passwörter und Einmalcodes aus seiner KeePass-Anbindung). Sonst kommen sie aus Umgebungsvariablen: ohne
             * Benutzer {{name}} aus NAME; für den Benutzer der folgenden Schritte (user, z. B. sb): {{user.login}} aus SB_LOGIN, {{secret.Passwort}} aus
             * SB_SECRET_PASSWORT, {{otp()}} als Einmalcode aus SB_OTP (Base32 oder otpauth://-Adresse),
             * {{otp('pr')}} aus PR_OTP; {{user}} ist der Name selbst.</p>
             */
            public class %s {

              /** Der Benutzer der folgenden Schritte, null ohne. */
              private String user;

              @Test
              void test() {
                try (Playwright playwright = Playwright.create()) {
                  if (started != null) {
                    started.accept(playwright);
                  }
                  Browser browser = playwright.%s().launch(%s);
            %s    }
              }

              private static final Pattern OTP = Pattern.compile("otp\\\\(\\\\s*(?:'([^']*)'|\\"([^\\"]*)\\")?\\\\s*\\\\)");

              /**
               * Wer den Test startet, kann die Werte selbst liefern: {{...}}-Text und Benutzer → Wert. Space Harvester
               * setzt das, wenn es den Test ausführt (Benutzer, Passwörter und Einmalcodes aus seiner KeePass-Anbindung);
               * sonst gelten die Umgebungsvariablen.
               */
              public static java.util.function.BiFunction<String, String, String> values;

              /** Erfährt, aus welchem Testfall die folgenden Schritte stammen (Vorbedingung oder der Fall); darf fehlen. */
              public static java.util.function.Consumer<String> parts;

              /** Erhält Playwright beim Start, um den Lauf abbrechen zu können; darf fehlen. */
              public static java.util.function.Consumer<AutoCloseable> started;

              private static void part(String name) {
                if (parts != null) {
                  parts.accept(name);
                }
              }

              /** Ersetzt jedes {{...}} in text (siehe Klassenkommentar). */
              private String v(String text) {
                if (values != null) {
                  return values.apply(text, user);
                }
                StringBuilder result = new StringBuilder();
                int at = 0;
                while (true) {
                  int open = text.indexOf("{{", at);
                  int close = open < 0 ? -1 : text.indexOf("}}", open + 2);
                  if (close < 0) {
                    return result.append(text.substring(at)).toString();
                  }
                  result.append(text, at, open).append(value(text.substring(open + 2, close).strip()));
                  at = close + 2;
                }
              }

              private String value(String name) {
                Matcher otp = OTP.matcher(name);
                if (otp.matches()) {
                  String other = otp.group(1) != null ? otp.group(1) : otp.group(2);
                  return totp(environment((other != null && !other.isBlank() ? other : user) + "_OTP", name));
                }
                if (name.equals("user")) {
                  return Objects.requireNonNull(user, "{{user}}: Schritt ohne Benutzer");
                }
                if (name.equals("user.login")) {
                  return environment(user + "_LOGIN", name);
                }
                return environment(user != null && name.startsWith("secret.") ? user + "_" + name : name, name);
              }

              private static String environment(String key, String name) {
                String variable = key.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
                String value = System.getenv(variable);
                if (value == null) {
                  throw new IllegalStateException("Umgebungsvariable " + variable + " fehlt (für {{" + name + "}})");
                }
                return value;
              }

              /** Einmalcode nach RFC 6238 zur jetzigen Zeit; otpauth://-Adressen mit digits, period, algorithm. */
              private static String totp(String secretOrUri) {
                String secret = secretOrUri.trim();
                int digits = 6;
                int period = 30;
                String algorithm = "SHA1";
                if (secret.toLowerCase(Locale.ROOT).startsWith("otpauth://")) {
                  String query = java.net.URI.create(secret).getRawQuery();
                  secret = "";
                  for (String pair : query == null ? new String[0] : query.split("&")) {
                    int equals = pair.indexOf('=');
                    if (equals < 0) {
                      continue;
                    }
                    String key = pair.substring(0, equals).toLowerCase(Locale.ROOT);
                    String value = java.net.URLDecoder.decode(pair.substring(equals + 1), java.nio.charset.StandardCharsets.UTF_8);
                    switch (key) {
                      case "secret" -> secret = value;
                      case "digits" -> digits = Integer.parseInt(value);
                      case "period" -> period = Integer.parseInt(value);
                      case "algorithm" -> algorithm = value.toUpperCase(Locale.ROOT);
                      default -> { }
                    }
                  }
                }
                String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
                String clean = secret.replace(" ", "").replace("-", "").replace("=", "").toUpperCase(Locale.ROOT);
                ByteBuffer key = ByteBuffer.allocate(clean.length() * 5 / 8);
                int buffer = 0;
                int bits = 0;
                for (char c : clean.toCharArray()) {
                  int index = alphabet.indexOf(c);
                  if (index < 0) {
                    throw new IllegalArgumentException("OTP-Geheimnis ist kein Base32");
                  }
                  buffer = (buffer << 5) | index;
                  bits += 5;
                  if (bits >= 8) {
                    key.put((byte) (buffer >> (bits - 8)));
                    bits -= 8;
                  }
                }
                try {
                  Mac hmac = Mac.getInstance("Hmac" + algorithm);
                  hmac.init(new SecretKeySpec(key.array(), "Hmac" + algorithm));
                  byte[] hash = hmac.doFinal(ByteBuffer.allocate(Long.BYTES)
                      .putLong(System.currentTimeMillis() / 1000 / period).array());
                  int offset = hash[hash.length - 1] & 0x0f;
                  int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                      | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
                  return String.format("%%0" + digits + "d", binary %% (long) Math.pow(10, digits));
                } catch (java.security.GeneralSecurityException unsupported) {
                  throw new IllegalArgumentException("OTP-Verfahren nicht unterstützt: " + algorithm, unsupported);
                }
              }
            }
            """;

    private static String launchOptions(Launch launch) {
        StringBuilder options = new StringBuilder("new BrowserType.LaunchOptions()");
        if (launch.channel() != null && !launch.channel().isBlank()) {
            options.append("\n          .setChannel(").append(TestStep.javaString(launch.channel())).append(')');
        }
        String executable = launch.executable() == null ? "" : launch.executable();
        if (!executable.isBlank()) {
            options.append("\n          .setExecutablePath(Paths.get(System.getProperty(")
                    .append(TestStep.javaString(EXECUTABLE_PROPERTY)).append(", ")
                    .append(TestStep.javaString(executable)).append(")))");
        }
        return options.append("\n          .setHeadless(false)").toString();
    }

    private static String indent(String text, String prefix) {
        StringBuilder result = new StringBuilder();
        for (String line : text.split("\\R")) {
            result.append(line.isEmpty() ? "" : prefix + line).append('\n');
        }
        return result.toString();
    }

    /** A Java class name from the test case's name ({@code Anmelden & Prüfen} → {@code AnmeldenPruefenTest}). */
    public static String className(String name) {
        String plain = Normalizer.normalize((name == null ? "" : name).replace("ä", "ae").replace("ö", "oe")
                .replace("ü", "ue").replace("Ä", "Ae").replace("Ö", "Oe").replace("Ü", "Ue").replace("ß", "ss"),
                Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        StringBuilder result = new StringBuilder();
        for (String part : plain.split("[^A-Za-z0-9]+")) {
            if (!part.isEmpty()) {
                result.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append(part.substring(1));
            }
        }
        if (result.isEmpty() || !Character.isJavaIdentifierStart(result.charAt(0))) {
            result.insert(0, "Recorded");
        }
        return result.append("Test").toString();
    }
}
