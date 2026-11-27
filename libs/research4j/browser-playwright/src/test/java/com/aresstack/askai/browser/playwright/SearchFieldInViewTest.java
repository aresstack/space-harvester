package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.input.HumanInteractionPolicy;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Mouse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.ViewportSize;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Live acceptance of #28: DuckDuckGo's HTML start page was refused with "the search field input[name=q] is not visible
 * in the window" although the field was visible. Camoufox reports no window height, so the driver guesses a safe line
 * at 360 px; the field sits at y=372 on a page that does not scroll, and {@link Playwright4jDriver#inView} gave up when
 * the wheel moved nothing. Stand-in page and field, so the positions are exact.
 */
public class SearchFieldInViewTest {

    /** A page whose content scrolls up to {@code scrollable} pixels under the wheel; {@code windowHeight} 0 = unknown. */
    private static final class Window {
        final int windowHeight;
        final int scrollable;
        int scrolled;
        int wheels;

        Window(int windowHeight, int scrollable) {
            this.windowHeight = windowHeight;
            this.scrollable = scrollable;
        }

        Page page() {
            Mouse mouse = (Mouse) Proxy.newProxyInstance(Mouse.class.getClassLoader(), new Class<?>[]{Mouse.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("wheel")) {
                            wheels++;
                            double delta = ((Number) args[1]).doubleValue();
                            scrolled = (int) Math.max(0, Math.min(scrollable, scrolled + delta));
                        }
                        return null;
                    });
            return (Page) Proxy.newProxyInstance(Page.class.getClassLoader(), new Class<?>[]{Page.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "viewportSize":
                                return windowHeight > 0 ? new ViewportSize(1280, windowHeight) : null;
                            case "mouse":
                                return mouse;
                            case "waitForTimeout":
                                return null;
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "equals":
                                return proxy == args[0];
                            default:
                                throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }

        /** A field laid out at document position {@code y}; its box moves with the scrolled content. */
        ElementHandle field(double y, double width, double height) {
            return (ElementHandle) Proxy.newProxyInstance(ElementHandle.class.getClassLoader(),
                    new Class<?>[]{ElementHandle.class}, (proxy, method, args) -> {
                        if (method.getName().equals("boundingBox")) {
                            BoundingBox box = new BoundingBox();
                            box.x = 265;
                            box.y = y - scrolled;
                            box.width = width;
                            box.height = height;
                            return box;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    private static HumanInteractionPolicy policy() {
        return new HumanInteractionPolicy(
                HumanInteractionPolicy.Config.fromEnvironment(Collections.<String, String>emptyMap()), new Random(7));
    }

    private static BoundingBox inView(Window window, ElementHandle field) {
        return Playwright4jDriver.inView(window.page(), field, policy(), new int[]{20, 20});
    }

    @Test
    public void aFieldBelowTheGuessedLineOnAPageThatDoesNotScrollIsTakenWhereItIs() {
        Window ddgHtml = new Window(0, 0); // Camoufox: no window height; the start page fits its window
        BoundingBox box = inView(ddgHtml, ddgHtml.field(372.33, 460.5, 40.33));

        assertNotNull("the visible field must not be refused", box);
        assertEquals(372.33, box.y, 0.01);
    }

    @Test
    public void aFieldFarDownAPageThatScrollsIsWheeledAboveTheGuessedLine() {
        Window longPage = new Window(0, 2000);
        BoundingBox box = inView(longPage, longPage.field(1200, 400, 40));

        assertNotNull(box);
        assertTrue("wheeled with real input", longPage.wheels > 0);
        assertTrue("its center lies above the guessed line now, was " + box.y, box.y + box.height / 2 < 360);
    }

    @Test
    public void withAKnownWindowAFieldThatStaysOutsideIsStillRefused() {
        Window small = new Window(300, 0);

        assertNull(inView(small, small.field(372, 400, 40)));
        assertTrue("it tried the wheel first", small.wheels > 0);
    }

    @Test
    public void aFieldInsideAKnownWindowIsTakenWithoutTouchingTheWheel() {
        Window window = new Window(900, 2000);
        BoundingBox box = inView(window, window.field(372, 400, 40));

        assertNotNull(box);
        assertEquals(0, window.wheels);
    }

    @Test
    public void aFieldWithoutSizeIsRefused() {
        Window window = new Window(0, 0);

        assertNull(inView(window, window.field(100, 0, 0)));
        assertNull(inView(window, window.field(500, 0, 40)));
    }
}
