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
 * the library one inside the test method. Values in {@code {{...}}} are never written: they come from environment variables
 * ({@code {{start}}} from {@code START}, {@code {{secret.Passwort}}} from {@code SECRET_PASSWORT}); no system
 * properties, because names like {@code user.name} are taken by the JVM. One-time codes ({@code {{otp()}}}) and screenshot comparisons
 * need Proving Ground; such steps are written as comments and the export says so.</p>
 */
public final class JUnitExport {

    /** How the exported test starts the browser: Playwright type, channel and executable. */
    public record Launch(boolean firefox, String channel, String executable) {
    }

    /** The exported class and what it could not take over. */
    public record Result(String className, String source, List<String> notExported) {
    }

    static final String NOT_EXPORTED = "// nicht exportiert (braucht Proving Ground): ";
    static final String EXECUTABLE_PROPERTY = "playwright.executable";

    private JUnitExport() {
    }

    public static Result export(TestCase testCase, Launch launch) {
        String className = className(testCase.name());
        List<String> notExported = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < testCase.size(); i++) {
            TestStep step = testCase.step(i);
            String code = step.code();
            String why = code == null || code.isBlank() ? "kein Playwright-Code"
                    : code.contains("screenshots.") ? "Bildvergleich"
                    : code.matches("(?s).*\\{\\{[^}]*\\(.*") ? "Ausdruck mit Funktion (z. B. Einmalcode)" : null;
            if (why != null) {
                String note = (i + 1) + ". " + step.describe() + " – " + why;
                notExported.add(note);
                body.append(NOT_EXPORTED).append(note.replace('\n', ' ')).append('\n');
                continue;
            }
            body.append(PlaywrightCode.withVariables(code.strip(), "v")).append('\n');
        }
        String steps = body.toString();
        StringBuilder pages = new StringBuilder();
        for (String alias : PlaywrightCode.otherPages(steps, PlaywrightCode.declaredPages(steps))) {
            pages.append("Page ").append(alias).append(" = context.newPage();\n");
        }
        String source = """
                import com.microsoft.playwright.*;
                import com.microsoft.playwright.options.*;
                import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
                import java.nio.file.Paths;
                import java.util.*;
                import org.junit.jupiter.api.Test;

                /** Aus Proving Ground exportiert: %s */
                public class %s {

                  @Test
                  void test() {
                    try (Playwright playwright = Playwright.create()) {
                      Browser browser = playwright.%s().launch(%s);
                      BrowserContext context = browser.newContext();
                %s    }
                  }

                  /** {{name}} aus der Umgebungsvariablen NAME (Punkte und andere Zeichen als _). */
                  private static String v(String text) {
                    StringBuilder result = new StringBuilder();
                    int at = 0;
                    while (true) {
                      int open = text.indexOf("{{", at);
                      int close = open < 0 ? -1 : text.indexOf("}}", open + 2);
                      if (close < 0) {
                        return result.append(text.substring(at)).toString();
                      }
                      String name = text.substring(open + 2, close).strip();
                      String environment = name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
                      String value = System.getenv(environment);
                      if (value == null) {
                        throw new IllegalStateException("Umgebungsvariable " + environment + " fehlt (für {{" + name + "}})");
                      }
                      result.append(text, at, open).append(value);
                      at = close + 2;
                    }
                  }
                }
                """.formatted(testCase.name().replace("*/", "* /"), className, launch.firefox() ? "firefox" : "chromium",
                launchOptions(launch), indent(pages + steps, "      "));
        return new Result(className, source, notExported);
    }

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
