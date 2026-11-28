package com.aresstack.spaceharvester.provingground.player;

import com.aresstack.spaceharvester.provingground.model.TestCase;
import com.aresstack.spaceharvester.provingground.model.TestStep;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Playwright's generated Java code: framed per step, compiled with the JDK, variables resolved at run time. */
class PlaywrightCodeTest {

    @Test
    void aStepWhoseCodeOpensItsPageNeedsNoPageHandedIn() {
        assertTrue(PlaywrightCode.opensOwnPage("Page page = context.newPage();\npage.navigate(\"{{start}}\");"));
        assertFalse(PlaywrightCode.opensOwnPage("page.navigate(\"{{start}}\");"));
        assertFalse(PlaywrightCode.opensOwnPage("Page page1 = page.waitForPopup(() -> {\n  page.click(\"a\");\n});"));
        assertFalse(PlaywrightCode.opensOwnPage(null));
    }

    @Test
    void literalsWithExpressionsAreResolvedAtRunTimeOthersStay() {
        assertEquals("page.getByLabel(\"Name\").fill(v.apply(\"{{user.login}}\"));",
                PlaywrightCode.withVariables("page.getByLabel(\"Name\").fill(\"{{user.login}}\");"));
        assertEquals("page.navigate(\"https://example.org/\");",
                PlaywrightCode.withVariables("page.navigate(\"https://example.org/\");"));
    }

    @Test
    void pagesAreBoundByTheStepNotByPlaywrightsVariableNames() {
        String popup = "Page page1 = page.waitForPopup(() -> {\n  page.getByText(\"Hilfe\").click();\n});";
        assertEquals(List.of("page1"), PlaywrightCode.declaredPages(popup));
        String method = PlaywrightCode.method(3, popup, List.of("page1"));
        assertTrue(method.contains("Page page = subject;"), method);
        assertTrue(method.contains("opened.put(\"page1\", page1);"), method);
        String open = PlaywrightCode.method(0, "Page page = context.newPage();", List.of("page"));
        assertTrue(!open.contains("= subject"), open);
    }

    @Test
    void compilesWhenPlaywrightIsOnlyReachableThroughTheClassLoaderAsInThePackagedApp() {
        // Angelo 28.11.: in the app JAR (Spring Boot, Playwright nested in BOOT-INF/lib) step 1 "Page page =
        // context.newPage();" failed with "Symbol nicht gefunden": the compiler only saw the outer JAR. Here the
        // compiler gets no file class path at all; the classes come through the class loader (jar: URLs).
        TestCase test = new TestCase("t", List.of(
                TestStep.navigate("p", "about:blank").withCode("Page page = context.newPage();"),
                TestStep.assertText("p", "h1", "Titel", false).withCode(
                        "assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName(\"T\")))"
                                + ".hasText(\"Titel\");")));
        PlaywrightCode code = PlaywrightCode.compile(test, PlaywrightCodeTest.class.getClassLoader(), "");
        assertNull(code.problem(0), code.problem(0));
        assertNull(code.problem(1), code.problem(1));
    }

    @Test
    void playwrightsCodeCompilesWithTheJdkAndAnUnfitStepSaysWhy() {
        TestCase test = new TestCase("t", List.of(
                TestStep.navigate("p", "{{start}}").withCode("Page page = context.newPage();\npage.navigate(\"{{start}}\");"),
                TestStep.assertText("p", "h1", "Titel", false)
                        .withCode("assertThat(page.locator(\"h1\")).hasText(\"Titel\");"),
                TestStep.assertText("p", "h1", "Titel", false).withCode("page.gibtEsNicht();"),
                TestStep.navigate("p", "alt"),
                TestStep.assertScreenshot("p", null, "start"),
                TestStep.assertScreenshot("p", "internal:role=button[name=\"Senden\"i]", "knopf")));
        PlaywrightCode code = PlaywrightCode.compile(test);
        assertNull(code.problem(0), code.problem(0));
        assertNull(code.problem(1), code.problem(1));
        assertTrue(code.problem(2).startsWith("Playwright-Code nicht übersetzbar"), code.problem(2));
        assertEquals(PlaywrightCode.NO_CODE, code.problem(3));
        assertNull(code.problem(4), code.problem(4));
        assertNull(code.problem(5), code.problem(5));
    }
}
