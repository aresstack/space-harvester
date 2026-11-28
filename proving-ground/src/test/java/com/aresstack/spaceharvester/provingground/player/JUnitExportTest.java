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
        assertTrue(source.contains(".fill(v(\"{{otp()}}\"));"), source);
        assertEquals(1, result.notExported().size(), result.notExported().toString());
        assertTrue(result.notExported().get(0).contains("Bildvergleich"));

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

    @Test
    void aPreconditionAndTheCaseBothDeclaringPageCompileAsOneTest() throws Exception {
        TestCase joined = new TestCase("Mit Vorbedingung", List.of(
                step("openPage", "Page page = context.newPage();\npage.navigate(\"{{start}}login\");"),
                step("click", "page.locator(\"#ok\").click();"),
                step("openPage", "Page page = context.newPage();\npage.navigate(\"{{start}}page\");"),
                step("click", "Page page1 = page.waitForPopup(() -> {\n  page.locator(\"#page\").click();\n});"),
                step("assertText", "assertThat(page1.locator(\"h1\")).hasText(\"page\");")));
        JUnitExport.Result result = JUnitExport.export(joined, new JUnitExport.Launch(false, "chrome", ""));
        String source = result.source();
        assertTrue(source.contains("Page page_2 = context.newPage();\n      page_2.navigate(v(\"{{start}}page\"));"),
                source);
        assertTrue(source.contains("Page page1 = page_2.waitForPopup(() -> {\n        page_2.locator(\"#page\").click();"),
                source);
        assertTrue(source.contains("assertThat(page1.locator(\"h1\")).hasText(\"page\");"), "strings stay: " + source);
        assertEquals("", compile(result), source);
    }

    @Test
    void stepsOfAnotherUserRunInTheirOwnContextAndTheCodeStaysPlaywrights() throws Exception {
        TestCase twoUsers = new TestCase("Zwei Benutzer", List.of(
                step("openPage", "Page page = context.newPage();\npage.navigate(\"{{start}}\");"),
                step("fill", "page.locator(\"#user\").fill(\"{{user.login}}\");"),
                step("openPage", "Page page1 = context.newPage();\npage1.navigate(\"{{start}}\");"),
                step("fill", "page1.locator(\"#code\").fill(\"{{otp()}}\");")));
        JUnitExport.Result result = JUnitExport.export(twoUsers, java.util.Arrays.asList("sb", "sb", "pr", "pr"),
                new JUnitExport.Launch(false, "chrome", ""));
        String source = result.source();
        assertTrue(result.notExported().isEmpty(), result.notExported().toString());
        assertTrue(source.contains("user = \"sb\";\n      context = contexts.computeIfAbsent(String.valueOf(user),"
                + " name -> browser.newContext());\n      Page page = context.newPage();"), source);
        assertTrue(source.contains("user = \"pr\";\n      context = contexts.computeIfAbsent(String.valueOf(user),"
                + " name -> browser.newContext());\n      Page page1 = context.newPage();"), source);
        assertTrue(source.contains("page1.locator(\"#code\").fill(v(\"{{otp()}}\"));"), source);
        Map<String, byte[]> classes = new HashMap<>();
        assertEquals("", compile(result, classes), source);

        // the exported one-time code is the run's (run.Totp) for the same secret
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) {
                    throw new ClassNotFoundException(name);
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        java.lang.reflect.Method totp = loader.loadClass(result.className()).getDeclaredMethod("totp", String.class);
        totp.setAccessible(true);
        for (String secret : List.of("JBSWY3DPEHPK3PXP",
                "otpauth://totp/Test:sb?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&digits=8&period=60&algorithm=SHA256")) {
            java.time.Instant before = java.time.Instant.now();
            String exported = (String) totp.invoke(null, secret);
            java.time.Instant after = java.time.Instant.now();
            assertTrue(exported.equals(com.aresstack.spaceharvester.provingground.run.Totp.code(secret, before))
                    || exported.equals(com.aresstack.spaceharvester.provingground.run.Totp.code(secret, after)),
                    secret + " → " + exported);
        }
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
