package com.aresstack.askai.browser.playwright;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Live acceptance of #28: a pager click counts as the next result page only when the page then has another address.
 * Bing showed page 2 ({@code first=11}) twice because the click result was never checked.
 */
public class NextPageAddressTest {

    private static final String PAGE_2 = "https://www.bing.com/search?q=Java+21+LTS+release&first=11&FORM=PORE";

    @Test
    public void theSameAddressIsNoFurtherPage() {
        assertFalse(Playwright4jDriver.isAnotherPage(PAGE_2, PAGE_2));
        assertFalse("only the fragment differs", Playwright4jDriver.isAnotherPage(PAGE_2, PAGE_2 + "#b_results"));
        assertFalse("a blank tab is no result page", Playwright4jDriver.isAnotherPage(PAGE_2, "about:blank"));
        assertFalse(Playwright4jDriver.isAnotherPage(PAGE_2, ""));
    }

    @Test
    public void anotherOffsetIsTheNextPage() {
        assertTrue(Playwright4jDriver.isAnotherPage(PAGE_2,
                "https://www.bing.com/search?q=Java+21+LTS+release&first=21&FORM=PERE1"));
    }
}
