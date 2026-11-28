package com.aresstack.spaceharvester.provingground.player;

import com.aresstack.spaceharvester.provingground.model.TestCase;
import com.aresstack.spaceharvester.provingground.model.TestStep;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JUnitExportTest {

    static TestCase recorded() {
        return new TestCase("Anmelden & Prüfen", List.of(
                step("openPage", "Page page = context.newPage();\npage.navigate(\"{{start}}login\");"),
                step("fill", "page.locator(\"#pw\").fill(\"{{secret.Passwort}}\");"),
                step("fill", "page.locator(\"#code\").fill(\"{{otp()}}\");"),
                step("click", "Page page1 = page.waitForPopup(() -> {\n  page.getByRole(AriaRole.LINK,"
                        + " new Page.GetByRoleOptions().setName(\"Hilfe\")).click();\n});"),
                TestStep.assertScreenshot("p", null, "start"),
                step("assertText", "assertThat(page1.locator(\"h1\")).hasText(\"Hilfe\");")));
    }

    private static TestStep step(String name, String code) {
        JsonObject action = new JsonObject();
        action.addProperty("name", name);
        return TestStep.action("p", action).withCode(code);
    }

    @Test
    void theExportIsAJUnitTestOverPlaywrightJavaThatCompilesWithoutProvingGround() throws Exception {
        JUnitExport.Result result = JUnitExport.export(recorded(),
                new JUnitExport.Launch(true, "moz-firefox", "C:\\Programme\\Mozilla Firefox\\firefox.exe"));

        assertEquals("AnmeldenPruefenTest", result.className());
        String source = result.source();
        assertTrue(source.contains("public class AnmeldenPruefenTest"), source);
        assertTrue(source.contains("try (Playwright playwright = Playwright.create())"), source);
        assertTrue(source.contains("playwright.firefox().launch(new BrowserType.LaunchOptions()"), source);
        assertTrue(source.contains(".setChannel(\"moz-firefox\")"), source);
        assertTrue(source.contains("\"C:\\\\Programme\\\\Mozilla Firefox\\\\firefox.exe\""), source);
        assertTrue(source.contains("page.navigate(v(\"{{start}}login\"));"), source);
        assertTrue(source.contains(".fill(v(\"{{secret.Passwort}}\"));"), source);
        assertTrue(source.contains("Page page1 = page.waitForPopup(() -> {"), source);
        assertFalse(source.contains("screenshots."), source);
        assertFalse(source.contains("com.aresstack"), "ohne Proving Ground lauffähig: " + source);
        assertEquals(2, result.notExported().size(), result.notExported().toString());
        assertTrue(result.notExported().get(0).contains("Einmalcode"));
        assertTrue(result.notExported().get(1).contains("Bildvergleich"));

        assertEquals("", compile(result), source);
    }

    @Test
    void aCaseThatStartsWithoutOpeningAPageGetsOne() throws Exception {
        TestCase edited = new TestCase("2 Seiten", List.of(step("click", "page.locator(\"#a\").click();")));
        JUnitExport.Result result = JUnitExport.export(edited, new JUnitExport.Launch(false, "chrome", ""));
        assertEquals("Recorded2SeitenTest", result.className());
        assertTrue(result.source().contains("Page page = context.newPage();\n      page.locator(\"#a\").click();"),
                result.source());
        assertFalse(result.source().contains("setExecutablePath"), result.source());
        assertEquals("", compile(result), result.source());
    }

    /** Compiler messages for the exported class, empty when it compiles (Playwright and JUnit on the class path). */
    static String compile(JUnitExport.Result result) throws Exception {
        Map<String, byte[]> classes = new HashMap<>();
        return compile(result, classes);
    }

    static String compile(JUnitExport.Result result, Map<String, byte[]> classes) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager standard = compiler.getStandardFileManager(diagnostics, null, null)) {
            JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///" + result.className() + ".java"),
                    JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignore) {
                    return result.source();
                }
            };
            var memory = new ForwardingJavaFileManager<>(standard) {
                @Override
                public JavaFileObject getJavaFileForOutput(Location location, String name, JavaFileObject.Kind kind,
                                                           FileObject sibling) {
                    return new SimpleJavaFileObject(URI.create("mem:///" + name + ".class"), kind) {
                        @Override
                        public OutputStream openOutputStream() {
                            return new ByteArrayOutputStream() {
                                @Override
                                public void close() {
                                    classes.put(name, toByteArray());
                                }
                            };
                        }
                    };
                }
            };
            boolean ok = compiler.getTask(null, memory, diagnostics,
                    List.of("-classpath", System.getProperty("java.class.path")), null, List.of(file)).call();
            StringBuilder messages = new StringBuilder();
            diagnostics.getDiagnostics().forEach(d -> messages.append(d.getLineNumber()).append(": ")
                    .append(d.getMessage(null)).append('\n'));
            return ok ? "" : messages.toString();
        }
    }
}
