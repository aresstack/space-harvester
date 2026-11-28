package com.aresstack.spaceharvester.provingground.player;

import com.aresstack.spaceharvester.provingground.model.TestCase;
import com.aresstack.spaceharvester.provingground.model.TestStep;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Java code Playwright's own generator wrote for the steps of a test case (recorder language {@code java}),
 * compiled with the JDK's compiler and run over Playwright Java. Only Java: nothing is generated or interpreted here
 * except the frame around Playwright's code.
 *
 * <p>Each step becomes one method around its code, so the run keeps a result per step and the pages are bound by the
 * recording's page guid, not by the variable names Playwright chose ({@code page}, {@code page1} ...): the page the
 * step acts on is passed in, pages the code opens ({@code Page page1 = page.waitForPopup(...)},
 * {@code Page page = context.newPage()}) are handed back. String literals with {@code {{...}}} are resolved at run
 * time (variables, users, secrets), never written into the code.</p>
 */
final class PlaywrightCode {

    static final String NO_CODE = "Schritt ohne Playwright-Code (vor dem Umstieg auf Playwrights Java-Code aufgenommen)"
            + " – bitte neu aufnehmen";
    static final String NO_COMPILER = InMemoryCompiler.NO_COMPILER;

    private static final Pattern DECLARED_PAGE = Pattern.compile("\\bPage\\s+(page\\d*)\\s*=");
    private static final Pattern USED_PAGE = Pattern.compile("\\b(page\\d*)\\s*\\.");
    private static final Pattern STRING = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final String CLASS_NAME = "RecordedTest";
    /** Classes the generated code uses directly; their JARs make the compiler's class path. */
    private static final List<Class<?>> ANCHORS = List.of(Page.class,
            com.microsoft.playwright.assertions.PlaywrightAssertions.class,
            com.microsoft.playwright.options.AriaRole.class, Screenshots.class);
    private static final String[] HEADER = {
            "import com.microsoft.playwright.*;",
            "import com.microsoft.playwright.options.*;",
            "import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;",
            "import java.util.*;",
            "import java.util.function.UnaryOperator;",
            "import " + Screenshots.class.getName() + ";",
            "",
            "public class " + CLASS_NAME + " {",
    };

    private final Object instance;
    private final Map<Integer, Method> methods;
    private final Map<Integer, String> problems;
    private final Map<Integer, List<String>> declared;

    private PlaywrightCode(Object instance, Map<Integer, Method> methods, Map<Integer, String> problems,
                           Map<Integer, List<String>> declared) {
        this.instance = instance;
        this.methods = methods;
        this.problems = problems;
        this.declared = declared;
    }

    /**
     * Compiles the code of every step of {@code testCase}; a step that cannot run says why in {@link #problem}. A step
     * whose code does not compile is left out and the rest compiled again, so it fails alone.
     */
    static PlaywrightCode compile(TestCase testCase) {
        return compile(testCase, PlaywrightCode.class.getClassLoader(), classpath());
    }

    /**
     * As {@link #compile(TestCase)}, with the classes the code uses from {@code loader} and only {@code fileClasspath}
     * as files for the compiler (in the packaged app that is nothing: Playwright lies in nested JARs).
     */
    static PlaywrightCode compile(TestCase testCase, ClassLoader loader, String fileClasspath) {
        Map<Integer, String> problems = new HashMap<>();
        while (true) {
            int known = problems.size();
            PlaywrightCode attempt = compile(testCase, problems, loader, fileClasspath);
            if (attempt.instance != null || problems.size() == known || problems.size() >= testCase.size()) {
                return attempt;
            }
        }
    }

    private static PlaywrightCode compile(TestCase testCase, Map<Integer, String> problems, ClassLoader runtime,
                                          String fileClasspath) {
        Map<Integer, List<String>> declared = new HashMap<>();
        StringBuilder source = new StringBuilder();
        for (String line : HEADER) {
            source.append(line).append('\n');
        }
        List<int[]> lines = new ArrayList<>(); // step index, first line, last line
        for (int i = 0; i < testCase.size(); i++) {
            String code = testCase.step(i).code();
            if (problems.containsKey(i)) {
                continue;
            }
            if (code == null || code.isBlank()) {
                problems.put(i, NO_CODE);
                continue;
            }
            List<String> pages = declaredPages(code);
            declared.put(i, pages);
            int first = count(source) + 1;
            source.append(method(i, code, pages));
            lines.add(new int[]{i, first, count(source)});
        }
        source.append("}\n");
        if (lines.isEmpty()) {
            return new PlaywrightCode(null, Map.of(), problems, declared);
        }
        InMemoryCompiler.Compiled compiled = InMemoryCompiler.compile(CLASS_NAME, source, runtime, fileClasspath,
                ANCHORS);
        if (compiled.type() == null) {
            for (InMemoryCompiler.Error error : compiled.errors()) {
                for (int[] range : lines) {
                    if (error.line() >= range[1] && error.line() <= range[2]) {
                        problems.putIfAbsent(range[0], "Playwright-Code nicht übersetzbar: " + error.message());
                    }
                }
            }
            boolean attributed = lines.stream().anyMatch(range -> problems.containsKey(range[0]));
            if (!attributed) {
                String why = InMemoryCompiler.NO_COMPILER.equals(compiled.failure()) ? NO_COMPILER
                        : "Playwright-Code " + (compiled.failure() == null ? "nicht übersetzbar" : compiled.failure());
                lines.forEach(range -> problems.put(range[0], why));
            }
            return new PlaywrightCode(null, Map.of(), problems, declared);
        }
        try {
            Class<?> type = compiled.type();
            Map<Integer, Method> methods = new HashMap<>();
            for (int[] range : lines) {
                methods.put(range[0], type.getMethod("step" + range[0], BrowserContext.class, Page.class,
                        UnaryOperator.class, Screenshots.class));
            }
            return new PlaywrightCode(type.getConstructor().newInstance(), methods, problems, declared);
        } catch (ReflectiveOperationException | LinkageError broken) {
            lines.forEach(range -> problems.put(range[0], "Playwright-Code nicht ladbar: " + broken));
            return new PlaywrightCode(null, Map.of(), problems, declared);
        }
    }

    /**
     * Whether {@code code} opens the page it acts on itself ({@code Page page = context.newPage();}) and needs no page
     * handed in.
     */
    static boolean opensOwnPage(String code) {
        if (code == null) {
            return false;
        }
        List<String> declaredPages = declaredPages(code);
        return !declaredPages.isEmpty() && otherPages(code, declaredPages).isEmpty();
    }

    /** Why step {@code index} cannot run, or null. */
    String problem(int index) {
        return problems.get(index);
    }

    /**
     * Runs Playwright's code of step {@code index}.
     *
     * @param page     the page the step acts on (null for opening a page)
     * @param resolve  resolves a string literal with {@code {{...}}}
     * @param screenshots the screenshot comparison of the run ({@code screenshots.expect(...)} in the code)
     * @return the pages the code opened, in the order it declares them
     */
    @SuppressWarnings("unchecked")
    List<Page> run(int index, BrowserContext context, Page page, UnaryOperator<String> resolve,
                   Screenshots screenshots) throws Throwable {
        Method method = methods.get(index);
        if (method == null) {
            throw new IllegalStateException(problems.getOrDefault(index, NO_CODE));
        }
        try {
            Map<String, Object> opened = (Map<String, Object>) method.invoke(instance, context, page, resolve,
                    screenshots);
            List<Page> pages = new ArrayList<>();
            for (String alias : declared.getOrDefault(index, List.of())) {
                if (opened.get(alias) instanceof Page created) {
                    pages.add(created);
                }
            }
            return pages;
        } catch (InvocationTargetException failed) {
            throw failed.getCause();
        }
    }

    /** One method around Playwright's code of one step. */
    static String method(int index, String code, List<String> declaredPages) {
        Set<String> used = otherPages(code, declaredPages);
        StringBuilder method = new StringBuilder();
        method.append("  public Map<String, Object> step").append(index)
                .append("(BrowserContext context, Page subject, UnaryOperator<String> v, Screenshots screenshots)")
                .append(" throws Exception {\n");
        for (String alias : used) {
            method.append("    Page ").append(alias).append(" = subject;\n");
        }
        method.append("    Map<String, Object> opened = new HashMap<>();\n");
        for (String line : withVariables(code).split("\\R")) {
            method.append("    ").append(line).append('\n');
        }
        for (String alias : declaredPages) {
            method.append("    opened.put(\"").append(alias).append("\", ").append(alias).append(");\n");
        }
        method.append("    return opened;\n  }\n");
        return method.toString();
    }

    /** String literals with {@code {{...}}} become {@code v.apply("...")}: resolved when the step runs. */
    static String withVariables(String code) {
        return withVariables(code, "v.apply");
    }

    /** String literals with {@code {{...}}} become {@code <call>("...")}. */
    static String withVariables(String code, String call) {
        Matcher literal = STRING.matcher(code);
        StringBuilder result = new StringBuilder();
        while (literal.find()) {
            String text = literal.group();
            literal.appendReplacement(result, Matcher.quoteReplacement(text.contains("{{") ? call + "(" + text + ")"
                    : text));
        }
        literal.appendTail(result);
        return result.toString();
    }

    /** The pages {@code code} acts on without declaring them: they are the step's page. */
    static Set<String> otherPages(String code, List<String> declaredPages) {
        Set<String> used = new LinkedHashSet<>();
        Matcher uses = USED_PAGE.matcher(code);
        while (uses.find()) {
            if (!declaredPages.contains(uses.group(1))) {
                used.add(uses.group(1));
            }
        }
        return used;
    }

    static List<String> declaredPages(String code) {
        List<String> pages = new ArrayList<>();
        Matcher declaration = DECLARED_PAGE.matcher(code);
        while (declaration.find()) {
            pages.add(declaration.group(1));
        }
        return pages;
    }

    /** Where Playwright Java is (and the rest of the class path), for the compiler. */
    static String classpath() {
        Set<String> entries = new LinkedHashSet<>();
        for (Class<?> type : ANCHORS) {
            try {
                entries.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
            } catch (Exception | LinkageError unknown) {
                // nested JAR of the packaged app: LoaderClassPath reads it through the class loader
            }
        }
        String own = System.getProperty("java.class.path");
        if (own != null) {
            for (String entry : own.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (LoaderClassPath.openable(entry)) {
                    entries.add(entry);
                }
            }
        }
        return String.join(File.pathSeparator, entries);
    }

    private static int count(CharSequence text) {
        int lines = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }
}
