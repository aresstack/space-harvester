package com.aresstack.spaceharvester.provingground;

import com.aresstack.spaceharvester.browser.runtime.BrowserChannel;
import com.aresstack.spaceharvester.browser.runtime.BrowserExecutables;
import com.aresstack.spaceharvester.provingground.model.TestCase;
import com.aresstack.spaceharvester.provingground.model.TestStep;
import com.aresstack.spaceharvester.provingground.player.JUnitExport;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The exported JUnit test runs on its own in a real browser: compiled from the export and its {@code @Test} method
 * called, with nothing of Proving Ground; a wrong expectation fails it. Browser: PROVING_GROUND_TEST_CHANNEL (default
 * firefox) and PROVING_GROUND_TEST_EXECUTABLE. Only a local test server is visited.
 */
@Tag("live")
class JUnitExportLiveTest {

    private static final String PAGE = """
            <!doctype html><html lang="de"><head><meta charset="utf-8"><title>Login</title></head><body>
            <input id="user"><button onclick="document.getElementById('s').textContent=
            'Angemeldet als '+document.getElementById('user').value">Anmelden</button><p id="s"></p></body></html>
            """;

    @Test
    void theExportedTestPassesAndAWrongExpectationFailsIt() throws Throwable {
        BrowserChannel channel = BrowserChannel.of(System.getenv().getOrDefault("PROVING_GROUND_TEST_CHANNEL",
                BrowserChannel.FIREFOX.id()));
        String executable = BrowserExecutables.effective(channel.id(),
                System.getenv().getOrDefault("PROVING_GROUND_TEST_EXECUTABLE", ""));
        assumeTrue(channel.firefoxFamily() ? executable != null && !executable.isBlank() : true,
                "SKIPPED (environment-gated live test): kein Firefox");
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = PAGE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String start = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            JUnitExport.Launch launch = new JUnitExport.Launch(channel.firefoxFamily(), channel.launchChannel(),
                    executable);
            run(JUnitExport.export(recorded(start, "Angemeldet als sb"), launch));
            AssertionError wrong = assertThrows(AssertionError.class,
                    () -> run(JUnitExport.export(recorded(start, "Angemeldet als pr"), launch)));
            assertTrue(wrong.getMessage().contains("Angemeldet als"), wrong.getMessage());
        } finally {
            server.stop(0);
        }
    }

    private static TestCase recorded(String start, String expected) {
        return new TestCase("Login", List.of(
                step("openPage", "Page page = context.newPage();\npage.navigate(\"" + start + "\");"),
                step("fill", "page.locator(\"#user\").fill(\"sb\");"),
                step("click", "page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions()"
                        + ".setName(\"Anmelden\")).click();"),
                step("assertText", "assertThat(page.locator(\"#s\")).hasText(\"" + expected + "\");")));
    }

    private static TestStep step(String name, String code) {
        JsonObject action = new JsonObject();
        action.addProperty("name", name);
        return TestStep.action("p", action).withCode(code);
    }

    /** Compiles the export and calls its test method as JUnit would. */
    private static void run(JUnitExport.Result result) throws Throwable {
        Map<String, byte[]> classes = new HashMap<>();
        String problems = com.aresstack.spaceharvester.provingground.player.JUnitExportTestAccess.compile(result,
                classes);
        assertEquals("", problems, result.source());
        ClassLoader loader = new ClassLoader(JUnitExportLiveTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) {
                    throw new ClassNotFoundException(name);
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        Class<?> type = loader.loadClass(result.className());
        Constructor<?> constructor = type.getDeclaredConstructor();
        Method test = type.getDeclaredMethod("test");
        test.setAccessible(true);
        try {
            test.invoke(constructor.newInstance());
        } catch (InvocationTargetException failed) {
            throw failed.getCause();
        }
    }
}
