package com.aresstack.askai.browser.playwright;

import com.microsoft.playwright.Page;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Issue #22: script-free mode. With {@code -Daskai.browser.scriptFree=true} a Camoufox driver refuses every
 * main-world script before it reaches the browser; the switch defaults to off and never applies to Chrome/Edge. A
 * source scan pins that no browser code calls {@code evaluate} past the gate, and that no page script clicks.
 */
public class PageScriptGateTest {

    @After
    public void clearProperty() {
        System.clearProperty(PageScriptGate.PROPERTY);
    }

    @Test
    public void scriptFreeModeIsOffByDefault() {
        System.clearProperty(PageScriptGate.PROPERTY);
        assertFalse(PageScriptGate.forBrowser(true).scriptFree());
        assertFalse(PageScriptGate.forBrowser(false).scriptFree());
    }

    @Test
    public void scriptFreeModeAppliesToCamoufoxOnly() {
        System.setProperty(PageScriptGate.PROPERTY, "true");
        assertTrue(PageScriptGate.forBrowser(true).scriptFree());
        assertFalse("Chrome/Edge have no extension yet, the overlay and reads stay there",
                PageScriptGate.forBrowser(false).scriptFree());
    }

    @Test
    public void everyEvaluateFailsInScriptFreeModeWithoutReachingThePage() {
        AtomicInteger evaluated = new AtomicInteger();
        Page page = recordingPage(evaluated);
        PageScriptGate gate = new PageScriptGate(true);
        for (String purpose : new String[]{"page-read", "hud-overlay"}) {
            try {
                gate.evaluate(page, "() => document.title", purpose);
                fail("script-free mode must refuse '" + purpose + "'");
            } catch (PageScriptGate.PageScriptForbiddenException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().startsWith("SCRIPT_FREE"));
                assertTrue(expected.getMessage(), expected.getMessage().contains(purpose));
            }
        }
        assertEquals("nothing may reach the page", 0, evaluated.get());
    }

    @Test
    public void outsideScriptFreeModeTheScriptRuns() {
        AtomicInteger evaluated = new AtomicInteger();
        assertEquals("ran", new PageScriptGate(false).evaluate(recordingPage(evaluated), "() => 1", "page-read"));
        assertEquals(1, evaluated.get());
    }

    /**
     * Every {@code evaluate(} call in the browser code goes through the gate: the only raw {@code page.evaluate} is
     * the gate's own, and the structured capture calls its runner, which the driver routes through the gate.
     */
    @Test
    public void noBrowserCodeEvaluatesPastTheGate() throws IOException {
        Pattern call = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*\\.\\s*(evaluate|evaluateHandle|evaluateAll"
                + "|addInitScript|exposeFunction|dispatchEvent|addScriptTag)\\s*\\(");
        List<String> violations = new ArrayList<String>();
        for (Path source : mainSources()) {
            String file = source.getFileName().toString();
            List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) {
                    continue;
                }
                Matcher matcher = call.matcher(line);
                while (matcher.find()) {
                    String receiver = matcher.group(1);
                    boolean gate = "pageScripts".equals(receiver)
                            || ("PageScriptGate.java".equals(file) && "page".equals(receiver))
                            || ("RenderedPageDocumentCapture.java".equals(file) && "page".equals(receiver)
                                    && "evaluate".equals(matcher.group(2)));
                    if (!gate) {
                        violations.add(file + ":" + (i + 1) + ": " + line);
                    }
                }
            }
        }
        assertTrue("page scripts must pass PageScriptGate: " + violations, violations.isEmpty());
    }

    /** No page script clicks, focuses or types: interactions are real mouse/keyboard input only. */
    @Test
    public void noPageScriptInteracts() throws IOException {
        Pattern interaction = Pattern.compile("\\.(click|focus|submit|dispatchEvent)\\(\\s*\\)?");
        List<String> violations = new ArrayList<String>();
        for (Path source : mainSources()) {
            for (String literal : stringLiterals(new String(Files.readAllBytes(source), StandardCharsets.UTF_8))) {
                if (interaction.matcher(literal).find()) {
                    violations.add(source.getFileName() + ": " + literal);
                }
            }
        }
        assertTrue("page scripts must not click/focus/submit: " + violations, violations.isEmpty());
    }

    /**
     * The engine of the chat tools (browser-tools) is not the research browser: it carries the contract of the
     * Enterprise AI Client's chat browser, whose page scripts read and, when real input is not possible, act as a
     * fallback. Script-free mode and the no-interaction rule belong to the research browser only.
     */
    static final List<String> CHAT_ENGINE_SOURCES = java.util.Arrays.asList("ChatPageScripts.java",
            "PlaywrightChatConnection.java", "PageOperations.java");

    private static List<Path> mainSources() throws IOException {
        Path root = Paths.get("src/main/java/com/aresstack/askai/browser/playwright");
        assertTrue("run from the browser-playwright module: " + root.toAbsolutePath(), Files.isDirectory(root));
        List<Path> sources = new ArrayList<Path>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !CHAT_ENGINE_SOURCES.contains(path.getFileName().toString()))
                    .forEach(sources::add);
        }
        return sources;
    }

    private static List<String> stringLiterals(String source) {
        List<String> literals = new ArrayList<String>();
        Matcher matcher = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"").matcher(source);
        while (matcher.find()) {
            literals.add(matcher.group(1));
        }
        return literals;
    }

    private static Page recordingPage(final AtomicInteger evaluated) {
        return (Page) Proxy.newProxyInstance(Page.class.getClassLoader(), new Class<?>[]{Page.class},
                (proxy, method, args) -> {
                    if ("evaluate".equals(method.getName())) {
                        evaluated.incrementAndGet();
                        return "ran";
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
